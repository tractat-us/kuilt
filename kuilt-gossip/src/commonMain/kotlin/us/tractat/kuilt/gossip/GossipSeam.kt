package us.tractat.kuilt.gossip

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A partial-mesh [Seam] over a [base] full-membership seam, exposing **two views
 * of the endpoints** (`docs/gossip-mesh-design.md`):
 *
 * - **active-neighbour view** ([activePeers]) — the ~k peers this node pushes
 *   deltas to and GCs against. Maintained by an internal [GossipView].
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
 * frame, delivers the payload to the application **once** — keyed by the
 * `(origin, seq)` [GossipMessageId] in a [seen] set — and, while the TTL permits,
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
 * **Lifecycle.** Call [start] once with a scope you own; it launches the inbound
 * loop and the [GossipView]. All timing/scheduling runs on that scope, all
 * randomness on the injected seeded [random], time via the injected [clock].
 *
 * @param base the underlying full-membership seam.
 * @param random seeded RNG, seeded per-peer by the caller (drives view selection + jitter).
 * @param clock injected time source for the per-neighbour detectors; never the wall clock.
 * @param jitter per-peer view-recompute jitter window (see [GossipView]); a zero range
 *   makes recompute synchronous, which deterministic tests rely on.
 * @param initialTtl hop budget stamped on a locally-originated broadcast. Dedup is
 *   what terminates the flood; this is only a generous hard cap, comfortably above
 *   the overlay diameter at the tens–low-hundreds target scale.
 */
public class GossipSeam(
    private val base: Seam,
    random: Random,
    clock: () -> Instant,
    config: HeartbeatConfig = HeartbeatConfig(),
    spareCount: Int = GossipView.DEFAULT_SPARE_COUNT,
    jitter: ClosedRange<Duration> = GossipView.DEFAULT_JITTER,
    private val initialTtl: Int = DEFAULT_TTL,
) : Seam {
    // Broadcast bus for raw inbound frames; per-neighbour detectors subscribe here
    // so they never contend for the single-consumer base.incoming channel.
    private val rawIncoming = MutableSharedFlow<Swatch>(extraBufferCapacity = RAW_BUFFER)

    // Application frames delivered to the single [incoming] collector. A buffered
    // channel (not a SharedFlow): it never drops a frame for a collector that
    // subscribes after a send, and — closed when the inbound loop ends (base seam
    // Torn) — its receiveAsFlow **completes**, honouring the Seam termination
    // contract that consumers like Quilter rely on to self-clean.
    private val _incoming = Channel<Swatch>(Channel.UNLIMITED)

    // Per-origin broadcast sequence counter. Guarded by a lock (not dispatcher
    // confinement) so concurrent broadcast() callers get distinct sequence numbers
    // even under a multi-threaded dispatcher.
    private val seqLock = reentrantLock()
    private var seqCounter = 0L

    // Dedup set of every relay frame already delivered + re-flooded. Mutated only
    // inside the single base.incoming collector (ADR-034 single-collection), so it
    // needs no lock. Grows with distinct broadcasts seen; bounding it (per-origin
    // high-water mark) is a documented v1 follow-up — at the target scale the set
    // stays small relative to live CRDT state.
    private val seen = mutableSetOf<GossipMessageId>()

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
        )

    /** The active-neighbour view — deltas/GC target set. Strict subset of [peers]. */
    public val activePeers: StateFlow<Set<PeerId>> get() = view.active

    /** Ordered standby neighbours promoted on active-neighbour loss. */
    public val spares: StateFlow<List<PeerId>> get() = view.spares

    override val selfId: PeerId get() = base.selfId

    /** Full-membership view (includes [selfId]); the anti-entropy sampling pool. */
    override val peers: StateFlow<Set<PeerId>> get() = base.peers

    override val state: StateFlow<SeamState> get() = base.state

    /** Application frames only — heartbeat ping/pong frames are filtered out. */
    override val incoming: Flow<Swatch> = _incoming.receiveAsFlow()

    /**
     * Starts the inbound loop (sole collector of `base.incoming`) and the
     * [GossipView]. Idempotent only if called once per scope; call exactly once.
     */
    public fun start(scope: CoroutineScope) {
        view.start(scope)
        scope.launch {
            // Sole collector of base.incoming (ADR-034). When the base seam tears its
            // incoming completes, the collect returns, and we close [_incoming] so this
            // seam's own [incoming] completes too — propagating Torn to our consumers.
            try {
                base.incoming.collect { swatch -> dispatchInbound(swatch) }
            } finally {
                _incoming.close()
            }
        }
    }

    /**
     * Routes one inbound frame: fan it to the per-neighbour detectors, drop
     * heartbeats, pass non-gossip frames straight through, and dedup + relay
     * gossip frames. Runs only on the single `base.incoming` collector, so the
     * [seen] set is accessed without a lock.
     */
    private suspend fun dispatchInbound(swatch: Swatch) {
        rawIncoming.emit(swatch)
        if (swatch.isHeartbeat()) return

        val frame = GossipFrame.tryDecode(swatch)
        if (frame == null) {
            // A raw point-to-point sendTo frame (or any non-gossip frame): deliver as-is.
            _incoming.trySend(swatch)
            return
        }
        // Our own broadcast looped back along the overlay — we already have it.
        if (frame.origin == selfId) return
        // Already delivered + relayed this broadcast; dedup terminates the flood.
        if (!seen.add(frame.id)) return

        _incoming.trySend(Swatch(payload = frame.payload, sender = frame.origin, sequence = frame.seq))
        // Re-flood to our own active neighbours minus the peer it arrived from,
        // until the hop budget runs out.
        if (frame.ttl > 1) flood(frame.decremented(), except = swatch.sender)
    }

    /**
     * Eager-flood to the active neighbours only. A defined no-op when the active
     * view is empty (alone in the session), matching the [Seam] broadcast contract.
     *
     * The payload is wrapped in a fresh origin-stamped [GossipFrame] so receivers
     * can dedup and relay it across the overlay (see the class KDoc).
     */
    override suspend fun broadcast(payload: ByteArray) {
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
        }
    }

    private fun nextSeq(): Long = seqLock.withLock { ++seqCounter }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ): Unit = base.sendTo(peer, payload)

    override suspend fun close(reason: CloseReason): Unit = base.close(reason)

    private fun Swatch.isHeartbeat(): Boolean {
        val decoded = decodeToString()
        return decoded.startsWith(HeartbeatPartitionDetector.PING_PREFIX) ||
            decoded.startsWith(HeartbeatPartitionDetector.PONG_PREFIX)
    }

    private companion object {
        const val RAW_BUFFER = 256

        // Generous default hop budget. Dedup terminates the flood; this only caps
        // pathological loops. Comfortably above the diameter of a k-regular overlay
        // at tens–low-hundreds peers (k ≈ 4–7 ⇒ diameter ≲ 4).
        const val DEFAULT_TTL = 16
    }
}
