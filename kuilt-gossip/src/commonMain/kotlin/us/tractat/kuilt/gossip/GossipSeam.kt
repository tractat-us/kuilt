package us.tractat.kuilt.gossip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.gossip.GossipSeam")

/**
 * A partial-mesh [Seam] over a [base] full-membership seam, exposing **two views
 * of the endpoints** (`docs/gossip-mesh-design.md`):
 *
 * - **active-neighbour view** ([activePeers]) — the ~k peers this node pushes
 *   deltas to and GCs against. Shaped by the injected [TopologyPolicy] and
 *   maintained by an internal [GossipView].
 * - **full-membership view** ([peers]) — everyone in the room, the pool
 *   anti-entropy samples. Delegated straight from [base].
 *
 * For a full-mesh base seam the active view is a strict subset, so [broadcast]
 * floods only to the ~k active neighbours rather than the whole room — the
 * O(N)-fan-out win.
 *
 * **Relayed dissemination (Phase 3).** [broadcast] wraps the payload in a
 * [GossipFrame] (origin id + per-origin sequence + a hop-budget TTL) and
 * eager-floods it to the active neighbours. On receive, [incoming] decodes the
 * frame, delivers the payload to the application **once and in per-origin send
 * order** — keyed by the `(origin, seq)` pair in a bounded [GossipDedup], which
 * holds reordered frames for contiguous release — and, while the TTL permits,
 * decrements the budget and re-floods to *this* node's active neighbours minus
 * the peer the frame arrived from. So a broadcast reaches the whole overlay
 * device-to-device along ~k-regular edges, dedup terminates the flood (a node
 * relays each message at most once), and the TTL is only a hard cap against
 * pathological loops. Anything a flood drops is backstopped by anti-entropy
 * (Phase 1), so the overlay need only be *usually* connected. [sendTo] is
 * delegated straight to [base] (point-to-point, unwrapped), which on a full-mesh
 * transport can reach any connected peer directly.
 *
 * **Single-collection [incoming] (ADR-034).** [GossipSeam] is the *single*
 * collector of `base.incoming`. Its [start] loop fans every inbound [Swatch] to
 * an internal `rawIncoming` bus that the per-neighbour detectors subscribe to,
 * and re-publishes only **non-heartbeat** frames to [incoming] — ping/pong frames
 * are consumed by the detectors and never surface to the application. Collect
 * [incoming] exactly once; wrap with `shareIn` for fan-out.
 *
 * **Reverse-edge liveness.** The active view is directed (an independent per-peer
 * k-out sample), so a peer may watch this node without being watched back. An
 * inbound ping from such a peer is answered with a pong directly (see
 * [answerUnwatchedPing]) — otherwise the watcher's detector would starve and tear
 * every asymmetric edge down, collapsing the overlay to mutual-only edges.
 *
 * **Lifecycle.** Call [start] once with a scope you own; it launches the inbound
 * loop and the [GossipView]. All timing/scheduling runs on that scope, all
 * randomness on the injected seeded [random], time via the injected [clock].
 *
 * @param base the underlying full-membership seam.
 * @param random seeded RNG, seeded per-peer by the caller (drives view-recompute jitter;
 *   with the default [topology] it also seeds neighbour selection).
 * @param clock injected time source for the per-neighbour detectors; never the wall clock.
 * @param topology the overlay shape — which peers this node eager-floods to (see
 *   [TopologyPolicy]). Defaults to the [RandomKRegular] partial mesh seeded from
 *   [random]; pass [FullFanout] for a hub star. Only broadcast dissemination is
 *   shaped; [sendTo] always passes through unwrapped.
 * @param jitter per-peer view-recompute jitter window (see [GossipView]); a zero range
 *   makes recompute synchronous, which deterministic tests rely on.
 * @param initialTtl hop budget stamped on a locally-originated broadcast. Dedup is
 *   what terminates the flood; this is only a generous hard cap, comfortably above
 *   the overlay diameter at the tens–low-hundreds target scale.
 * @param reorderGrace how long a relayed frame held for an earlier same-origin gap
 *   waits before the gap is abandoned and the held run released in order (see
 *   [GossipDedup]). Multi-path relay reordering resolves within a few hops'
 *   latency, so a gap older than this is a genuine flood drop (anti-entropy
 *   backstops it) or a pre-join seq (a late joiner first sights an origin
 *   mid-stream) — either way the held frames must not wait forever. Measured on
 *   the seam's own sweep ticker (dispatcher time — virtual under a test
 *   dispatcher), never on [clock], which is the liveness time source and may be
 *   frozen (#1309).
 */
public class GossipSeam(
    private val base: Seam,
    random: Random,
    private val clock: () -> Instant,
    config: HeartbeatConfig = HeartbeatConfig(),
    spareCount: Int = GossipView.DEFAULT_SPARE_COUNT,
    jitter: ClosedRange<Duration> = GossipView.DEFAULT_JITTER,
    private val initialTtl: Int = DEFAULT_TTL,
    topology: TopologyPolicy = RandomKRegular(random),
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val reorderGrace: Duration = DEFAULT_REORDER_GRACE,
) : Seam, PrincipalRoster {
    init {
        require(reorderGrace > Duration.ZERO) { "reorderGrace must be positive (was $reorderGrace)" }
    }

    // Broadcast bus for raw inbound frames; per-neighbour detectors subscribe here
    // so they never contend for the single-consumer base.incoming channel.
    private val rawIncoming = MutableSharedFlow<Swatch>(extraBufferCapacity = RAW_BUFFER)

    // Application frames delivered to the single [incoming] collector. A bounded [Spool]
    // (not a SharedFlow): it never drops a frame for a collector that subscribes after a
    // send, and — closed when the inbound loop ends (base seam Torn) — its [Spool.incoming]
    // **completes**, honouring the Seam termination contract that consumers like Quilter
    // rely on to self-clean. Delivery is bounded/backpressured (no UNLIMITED inbound queue);
    // `deliver` runs on the single base.incoming collector, so it holds no lock.
    private val spool = Spool<Swatch>(policy)

    // Per-origin broadcast sequence counter. Guarded by a lock (not dispatcher
    // confinement) so concurrent broadcast() callers get distinct sequence numbers
    // even under a multi-threaded dispatcher.
    private val seqLock = reentrantLock()
    private var seqCounter = 0L

    // Dedup + per-origin reorder buffer for relay frames — terminates the flood (a node
    // relays each message at most once) and releases same-origin frames to the app in
    // send order (#1272). Bounded to O(origins) steady-state via a per-origin contiguous
    // high-water mark plus a small reorder window (#675). Mutated only inside the single
    // inbound event loop (ADR-034 single-collection), so it needs no lock.
    private val dedup = GossipDedup()

    // The dedup/reorder time source: a monotonic ms counter advanced by the sweep ticker
    // on the single inbound event loop — i.e. **dispatcher time** (virtual under a test
    // dispatcher), quantized to reorderGrace/2. NEVER the injected [clock]: that is the
    // liveness time source and may legitimately be frozen (harnesses freeze it to keep the
    // heartbeat detectors quiescent), and a held frame's release must not depend on it —
    // an un-replicated one-shot broadcast has no anti-entropy backstop, so an unbounded
    // hold is a silent drop (#1309). Quantization means a blocked gap releases within
    // [reorderGrace/2, reorderGrace] of the frame being held — the grace is a straggler
    // heuristic, not a precise deadline.
    private var dedupNowMs = 0L

    private val view =
        GossipView(
            selfId = base.selfId,
            seam = base,
            roster = base.peers,
            rawIncoming = rawIncoming.asSharedFlow(),
            random = random,
            clock = clock,
            config = config,
            spareCount = spareCount,
            jitter = jitter,
            topology = topology,
        )

    /** The active-neighbour view — deltas/GC target set. Strict subset of [peers]. */
    public val activePeers: StateFlow<Set<PeerId>> get() = view.active

    /** Ordered standby neighbours promoted on active-neighbour loss. */
    public val spares: StateFlow<List<PeerId>> get() = view.spares

    override val selfId: PeerId get() = base.selfId

    /** Full-membership view (includes [selfId]); the anti-entropy sampling pool. */
    override val peers: StateFlow<Set<PeerId>> get() = base.peers

    /**
     * Host-verified principals of the base seam's linked peers, delegated to [base] when it is a
     * [PrincipalRoster] (a hub mesh with attested links); a constant empty map otherwise.
     */
    override val attestedPrincipals: StateFlow<Map<PeerId, Principal>>
        get() = (base as? PrincipalRoster)?.attestedPrincipals ?: EMPTY_ROSTER

    override val state: StateFlow<SeamState> get() = base.state

    /** Application frames only — heartbeat ping/pong frames are filtered out. */
    override val incoming: Flow<Swatch> = spool.incoming

    private sealed interface InboundEvent {
        /** A frame from the single `base.incoming` collection. */
        class Frame(val swatch: Swatch) : InboundEvent

        /** Periodic tick releasing reorder-held frames whose gap outlived [reorderGrace]. */
        data object Sweep : InboundEvent

        /** Sentinel: `base.incoming` completed (the base seam tore). */
        data object BaseCompleted : InboundEvent
    }

    /**
     * Starts the inbound event loop and the [GossipView]. Idempotent only if
     * called once per scope; call exactly once.
     *
     * The loop is the sole collector of `base.incoming` (ADR-034), merged with a
     * periodic reorder-grace sweep tick so **all** dedup/reorder mutation and all
     * [spool] delivery happen on one coroutine — no second mutator, no lock, and
     * released frames can never interleave out of order. When the base seam tears
     * its `incoming` completes, the loop ends (cancelling the sweep ticker), and
     * [spool] is closed so this seam's own [incoming] completes too — propagating
     * Torn to our consumers.
     */
    public fun start(scope: CoroutineScope) {
        view.start(scope)
        scope.launch {
            val frames =
                flow {
                    base.incoming.collect { swatch -> emit(InboundEvent.Frame(swatch)) }
                    emit(InboundEvent.BaseCompleted)
                }
            val sweeps =
                flow {
                    while (true) {
                        delay(reorderGrace / 2)
                        emit(InboundEvent.Sweep)
                    }
                }
            try {
                merge(frames, sweeps)
                    .takeWhile { it !is InboundEvent.BaseCompleted }
                    .collect { event ->
                        when (event) {
                            is InboundEvent.Frame -> dispatchInbound(event.swatch)
                            is InboundEvent.Sweep -> {
                                dedupNowMs += (reorderGrace / 2).inWholeMilliseconds
                                deliver(dedup.releaseExpired(dedupNowMs, reorderGrace.inWholeMilliseconds))
                            }
                            is InboundEvent.BaseCompleted -> Unit
                        }
                    }
            } finally {
                spool.close()
            }
        }
    }

    /**
     * Routes one inbound frame: fan it to the per-neighbour detectors, answer + drop
     * heartbeats, pass non-gossip frames straight through, and dedup/reorder + relay
     * gossip frames. Runs only on the single inbound event loop, so [dedup] is
     * accessed without a lock.
     */
    private suspend fun dispatchInbound(swatch: Swatch) {
        rawIncoming.emit(swatch)
        if (swatch.isHeartbeat()) {
            answerUnwatchedPing(swatch)
            return
        }

        val frame = GossipFrame.tryDecode(swatch)
        if (frame == null) {
            // A raw point-to-point sendTo frame (or any non-gossip frame): deliver as-is.
            spool.deliver(swatch)
            return
        }
        // Our own broadcast looped back along the overlay — we already have it.
        if (frame.origin == selfId) return
        // Already seen this broadcast; dedup terminates the flood. A fresh frame may
        // still be *held* for delivery (an earlier same-origin frame is outstanding):
        // Seam.incoming promises per-sender send order and we re-stamp sender = origin,
        // so same-origin frames are released contiguously (#1272). Relay is never held —
        // gap-fill latency must not compound per hop.
        val admission = dedup.admit(frame, dedupNowMs)
        if (!admission.isNew) return

        deliver(admission.deliverable)
        // Re-flood to our own active neighbours minus the peer it arrived from,
        // until the hop budget runs out.
        if (frame.ttl > 1) flood(frame.decremented(), except = swatch.sender)
    }

    /** Surfaces released relay frames to the app, re-stamped with the origin as sender. */
    private suspend fun deliver(released: List<GossipFrame>) {
        for (frame in released) {
            spool.deliver(Swatch(payload = frame.payload, sender = frame.origin, sequence = frame.seq))
        }
    }

    /**
     * Answers an inbound heartbeat ping from a peer this node does not itself watch
     * — the reverse-edge-liveness half of the directed overlay.
     *
     * The active view is an independent per-peer k-out sample, so edges are
     * **directed**: a peer may watch us without us watching it back. Its detector's
     * pings would otherwise go unanswered (no local detector matches that sender),
     * so the watcher would tear the edge down after its timeout and blacklist us —
     * collapsing the overlay to mutual-only edges. Answering here is stateless, so
     * it needs no reconciliation on roster or view churn, and it leaves the k-out
     * view and detector set untouched — k-regularity is preserved; the pong merely
     * makes an existing directed edge observable from the watcher's side.
     *
     * Pings from an **active** neighbour are answered by that neighbour's own
     * detector (ping → pong inside [HeartbeatPartitionDetector]), so this covers
     * only the asymmetric case. The active-view check races benignly with view
     * churn: a double pong is harmless, and a missed pong is retried by the
     * watcher's next ping one interval later.
     */
    private suspend fun answerUnwatchedPing(swatch: Swatch) {
        if (!swatch.startsWithBytes(PING_PREFIX_BYTES)) return
        val watcher = swatch.sender ?: return
        if (watcher in view.active.value) return
        runCatchingCancellable { base.sendTo(watcher, PONG_PAYLOAD) }
            .onFailure { logger.debug { "gossip pong: dropping reply to $watcher — ${it.message}" } }
    }

    /**
     * Eager-flood to the active neighbours only. A defined no-op when the active
     * view is empty (alone in the session, or the view has not reconciled yet),
     * matching the [Seam] broadcast contract.
     *
     * The no-op **must not consume a per-origin seq**: the flood reaches nobody, so
     * a burned seq would be a permanent phantom gap — every future receiver would
     * first-sight this origin mid-stream and reorder-hold (up to a full
     * [reorderGrace]) everything sent after it (#1309). The check races benignly
     * with view reconciliation: a view that empties between the check and the flood
     * burns one seq into a gap the grace bounds, and a view that fills gains
     * receivers for the already-stamped frame.
     *
     * The payload is wrapped in a fresh origin-stamped [GossipFrame] so receivers
     * can dedup and relay it across the overlay (see the class KDoc).
     *
     * A `Torn` overlay throws [IllegalStateException] (`state` delegates to [base]),
     * honouring the shared [Seam] send contract — a torn transport cannot deliver, so
     * swallowing the send would hide the failure. This is distinct from the empty-view
     * no-op below: an empty active view is a *live* seam with nobody to flood to yet
     * (recoverable), whereas `Torn` is terminal death (#1390).
     */
    override suspend fun broadcast(payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "broadcast on a Torn seam" }
        if (view.active.value.isEmpty()) return
        flood(GossipFrame.origin(selfId, nextSeq(), initialTtl, payload), except = null)
    }

    /**
     * Send [frame] to every active neighbour except [except] (the peer it arrived
     * from, or `null` for a locally-originated broadcast).
     *
     * Best-effort per neighbour: a send that fails (e.g. a neighbour that just left
     * the base roster) is swallowed so one stale edge can't drop the broadcast to
     * the rest — anti-entropy (Phase 1) re-delivers anything missed. Cancellation
     * still propagates ([runCatchingCancellable]).
     */
    private suspend fun flood(
        frame: GossipFrame,
        except: PeerId?,
    ) {
        val encoded = frame.encode()
        for (peer in view.active.value) {
            if (peer == except) continue
            runCatchingCancellable { base.sendTo(peer, encoded) }
                .onFailure { logger.debug { "gossip flood: dropping frame to $peer — ${it.message}" } }
        }
    }

    private fun nextSeq(): Long = seqLock.withLock { ++seqCounter }

    /** Tracked relay-dedup entries — O(origins). Test-only window onto the bound (#675). */
    internal val trackedDedupEntries: Int get() = dedup.trackedEntryCount

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ): Unit = base.sendTo(peer, payload)

    override suspend fun close(reason: CloseReason): Unit = base.close(reason)

    private fun Swatch.isHeartbeat(): Boolean =
        startsWithBytes(PING_PREFIX_BYTES) || startsWithBytes(PONG_PREFIX_BYTES)

    private fun Swatch.startsWithBytes(prefix: ByteArray): Boolean {
        if (payloadSize < prefix.size) return false
        for (i in prefix.indices) if (byteAt(i) != prefix[i]) return false
        return true
    }

    public companion object {
        private const val RAW_BUFFER = 256

        // Constant roster for a base seam with no attestation concept — one shared instance,
        // never mutated.
        private val EMPTY_ROSTER: StateFlow<Map<PeerId, Principal>> =
            MutableStateFlow<Map<PeerId, Principal>>(emptyMap())

        // Generous default hop budget. Dedup terminates the flood; this only caps
        // pathological loops. Comfortably above the diameter of a k-regular overlay
        // at tens–low-hundreds peers (k ≈ 4–7 ⇒ diameter ≲ 4).
        private const val DEFAULT_TTL = 16

        /**
         * Default reorder-grace: generous against multi-path relay latency (≤ diameter
         * hops), small against the anti-entropy round that backstops abandoned gaps.
         */
        public val DEFAULT_REORDER_GRACE: Duration = 2.seconds

        // ASCII prefixes ⇒ a UTF-8 byte-prefix match is equivalent to the old decoded-String startsWith.
        private val PING_PREFIX_BYTES = HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray()
        private val PONG_PREFIX_BYTES = HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray()

        // A bare pong frame, as HeartbeatPartitionDetector sends (prefix-matched by receivers).
        private val PONG_PAYLOAD = HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray()
    }
}
