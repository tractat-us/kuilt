@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.checkNotUnderTestDispatcher
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.crdt.piece
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("us.tractat.kuilt.crdt.Quilter")

/**
 * Configuration for [Quilter].
 *
 * @param evictionAfter how long a peer can be absent from [Seam.peers] before it
 *   is evicted from [knownPeers]. Absent-and-silent peers pin the pending-delta
 *   buffer; eviction releases that pin. A peer that reappears after eviction
 *   will receive a fresh [QuiltMessage.FullState].
 * @param antiEntropyInterval how often the background eviction check runs.
 * @param resendRetryInterval how long to wait before re-emitting a [QuiltMessage.Resend]
 *   when the first Resend is itself dropped and no further inbound traffic triggers
 *   re-detection. The timer is cancelled when the gap closes. In a low-traffic system
 *   this is the only mechanism that heals a gap whose first Resend was lost.
 * @param fullStateRetryInterval how long to wait before re-sending a [QuiltMessage.FullState]
 *   to a peer when the initial snapshot may have been dropped. The timer is cancelled
 *   when any message from that peer is received, confirming it is alive and reachable.
 * @param fullStateRetryLimit maximum number of FullState retry attempts per peer before giving up.
 *   A value of 10 means up to 10 retries (11 total send attempts) before the timer is abandoned.
 * @param strictTestGuard When `true`, throw [IllegalStateException] at construction
 *   time if the owning [kotlinx.coroutines.CoroutineScope] contains a
 *   `kotlinx.coroutines.test.TestDispatcher`. When `false` (the default), emit a
 *   warning to stdout instead. Set to `true` in tests that want to assert the guard
 *   fires. Leave `false` in production — the guard is informational there.
 */
public data class QuilterConfig(
    val evictionAfter: Duration = 5.minutes,
    val antiEntropyInterval: Duration = 1.minutes,
    val resendRetryInterval: Duration = 30.seconds,
    val fullStateRetryInterval: Duration = 30.seconds,
    val fullStateRetryLimit: Int = 10,
    val strictTestGuard: Boolean = false,
    /**
     * Suppresses the TestDispatcher warning for tests that intentionally run a real
     * [Quilter] under `UnconfinedTestDispatcher`. Has no effect in production.
     * Default `false`: warn as usual. See [strictTestGuard].
     */
    val expectVirtualTime: Boolean = false,
)

/**
 * A provider of monotonic time in milliseconds. The default reads from
 * `kotlin.time.TimeSource.Monotonic`; tests pass a controlled counter.
 */
public fun interface MonotonicMillis {
    public fun now(): Long
}

/**
 * Production-default [MonotonicMillis] using the platform's monotonic clock.
 * Returns elapsed milliseconds from an arbitrary fixed origin.
 */
private object SystemMonotonicMillis : MonotonicMillis {
    private val origin = kotlin.time.TimeSource.Monotonic.markNow()
    override fun now(): Long = origin.elapsedNow().inWholeMilliseconds
}

/**
 * Runs any [Quilted] CRDT live over a [Seam], providing eventually-consistent
 * multi-peer replication via a simple delta-propagation protocol.
 *
 * **Precondition — one instance per `(replica, CRDT type)` pair.** Running two
 * `Quilter<S>` instances with the same [replica] concurrently in the
 * same process breaks the delta GC protocol: both will mint deltas starting at
 * `seq = 1`, colliding on sequence numbers. The recipient cannot distinguish
 * them and will silently drop or misorder deltas, leaving replicas permanently
 * diverged. This is the same class of collision that the `BoundedCounter`
 * single-dimension fix addressed in a prior release. Create exactly **one**
 * `Quilter<S>` per `(replica, CRDT type)` per process.
 *
 * ## Protocol
 * - **[apply]** applies a local mutation, updates [state], and broadcasts a [QuiltMessage.Delta]
 *   to all current peers. Each delta is tagged with a monotonic [seq].
 * - On receiving a [QuiltMessage.Delta], the state is joined and an [QuiltMessage.Ack]
 *   is sent back to the original sender.
 * - On receiving an [QuiltMessage.Ack], the acker's progress is recorded; once every
 *   known peer has acked through a seq, all deltas at or below that seq are GC'd.
 * - On first contact with a new peer, a [QuiltMessage.FullState] is sent so the
 *   late joiner converges immediately without waiting for a delta replay, plus a
 *   [QuiltMessage.RootDigest] announcing that this side speaks the digest exchange.
 * - On the anti-entropy tick, a [QuiltMessage.RootDigest] — a hash of the state, not the
 *   state — goes to one random peer, which replies with a [QuiltMessage.FullStateRequest]
 *   only when its own root differs; that request is answered with a [QuiltMessage.FullState].
 *   A peer that has never sent a digest of its own may be running a build that cannot read
 *   one, so it is sent the state alongside the digest until it proves otherwise (#2006).
 * - A [QuiltMessage.Delivered] gossips this replica's contiguous delivered version vector,
 *   the matrix-clock row that drives causal-stability GC for dot-carrying CRDTs.
 *
 * ## Gap detection (Rung 12b)
 * Per-sender receive-sequence tracking detects dropped or reordered deltas:
 * - Out-of-order deltas are buffered and applied in order once the gap is filled.
 * - Missing ranges trigger a [QuiltMessage.Resend] to the original sender.
 * - Duplicate or stale deltas are re-acked and silently dropped.
 * - [QuiltMessage.Resend] causes this replica to re-broadcast buffered pending
 *   deltas for the requested range (if they haven't been GC'd yet).
 *
 * ## Peer eviction
 * Peers absent from [Seam.peers] beyond [QuilterConfig.evictionAfter] are
 * evicted from the known-peer set, releasing their buffer pin. They receive a fresh
 * [QuiltMessage.FullState] if they rejoin.
 *
 * @param replica this peer's [ReplicaId].
 * @param seam the [Seam] to ride. Collect [Seam.incoming] exactly once — this class
 *   takes sole ownership of the incoming stream.
 * @param initial the starting state (typically the CRDT's zero/empty value).
 * @param messageSerializer a [KSerializer] for [QuiltMessage]`<S>`, obtained via
 *   `QuiltMessage.serializer(stateSerializer)`.
 * @param scope the [CoroutineScope] whose [Job] becomes the parent of the replicator's
 *   owned child job. In tests, pass `backgroundScope` from [kotlinx.coroutines.test.TestScope]
 *   so infinite-running collectors are cancelled cleanly at test end without raising
 *   [kotlinx.coroutines.test.UncompletedCoroutinesError].
 * @param config replication behaviour tuning (eviction TTL, anti-entropy interval).
 * @param clock monotonic time source; override in tests to inject a fake clock.
 *
 * **Test-dispatcher guard.** If the scope contains a `kotlinx.coroutines.test.TestDispatcher`,
 * a diagnostic is emitted because [runAntiEntropy] uses real-clock [kotlinx.coroutines.delay] —
 * under virtual time those delays never advance automatically, causing tests to deadlock silently.
 * Either use `UnconfinedTestDispatcher` (delays execute eagerly) or advance virtual time via
 * `testScheduler.advanceTimeBy(…)` if you must use `StandardTestDispatcher`. Set
 * [QuilterConfig.strictTestGuard] to `true` to throw rather than warn.
 */
public class Quilter<S : Quilted<S>>(
    public val replica: ReplicaId,
    private val seam: Seam,
    initial: S,
    private val messageSerializer: KSerializer<QuiltMessage<S>>,
    scope: CoroutineScope,
    private val config: QuilterConfig = QuilterConfig(),
    private val clock: MonotonicMillis = SystemMonotonicMillis,
    /**
     * Binary format used to encode and decode [QuiltMessage] frames.
     * Defaults to plain [Cbor]. Override in tests or for CRDTs whose serializer
     * requires a custom [kotlinx.serialization.modules.SerializersModule]
     * (e.g. [us.tractat.kuilt.crdt.Rga] with a generic value type).
     */
    private val binaryFormat: BinaryFormat = Cbor,
    /**
     * Narrows the peers this replica pushes deltas *to* and GCs *against*, out of the
     * full membership. The default — identity, i.e. all of `knownPeers` — preserves
     * existing behaviour for a `MeshSeam` or `LinkSeam`.
     *
     * Supply a sparse selector (e.g. the ~k active neighbours from a `GossipSeam`) to
     * reduce the GC watermark from `min over N` to `min over k`. Peers excluded from this
     * set never pin the pending-delta buffer; they still converge because the anti-entropy
     * backstop ([reconcileWithRandomPeer]) sends a [QuiltMessage.RootDigest] to a peer drawn
     * from the **full** membership every round, and a peer that is behind necessarily has a
     * different root, so it asks for and merges the full state. State ships only when it is
     * needed, but the backstop still reaches every peer in full membership. Convergence no
     * longer depends on every peer acking every delta.
     */
    private val deltaTargets: (Set<PeerId>) -> Set<PeerId> = { it },
    /**
     * RNG for anti-entropy peer selection. Each reconcile round picks one random peer from
     * the full membership and sends it a [QuiltMessage.RootDigest]; the current post-merge full
     * state follows only if that peer's root differs. The merge is idempotent
     * (join-semilattice), so the order or frequency of reconcile rounds does not affect the
     * final converged value.
     *
     * Defaults to [kotlin.random.Random.Default] in production. Inject a **seeded**
     * [kotlin.random.Random] in tests so the peer-selection sequence is reproducible under
     * virtual time.
     */
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) : ScopedCloseable(scope) {
    /**
     * Guards every mutation of the plain replicator state (`nextSeq`, `pendingDeltas`,
     * `knownPeers`, `ackedThrough`, `expectedReceiveSeq`, `pendingInbound`, `frontiers`,
     * `retainedFrontier`, `monotonicStableCut`, `lastSeenAt`, the retry-job maps).
     *
     * Four contexts read-modify-write that state — public [apply] (any caller thread) plus the
     * three `launchIn(scope)` collectors ([dispatch] over `seam.incoming`, [onPeersChanged] over
     * `seam.peers`, and [runAntiEntropy]) — and a consumer may run them under a multithreaded
     * dispatcher. This coarse **reentrant** lock serialises them (ADR-003 §4.6 W2). Critical
     * sections are pure synchronous map updates (µs); all I/O (`seam.broadcast`/`sendTo`) and
     * every `delay` already run in separate `scope.launch {}` children, so the lock is never held
     * across a suspension point and one lock cannot deadlock. Reentrant ⇒ the rule is uniformly
     * "touch state only under `lock`", composable as helpers call one another.
     */
    private val lock = reentrantLock()

    private val _state = MutableStateFlow(initial)
    public val state: StateFlow<S> = _state.asStateFlow()

    private val _universalAckFlow = MutableStateFlow(0L)

    /**
     * The causal-stability watermark: the highest sequence number that every currently
     * known peer has acknowledged. Advances monotonically — it never decreases.
     *
     * A newly-joined peer receives a [QuiltMessage.FullState] that already reflects
     * any compacted history, so it does not need to acknowledge old deltas before the
     * watermark can advance. Consequently a late-joiner's absence from [ackedThrough]
     * does not drag the watermark backward: the flow stays at its last value until the
     * peer actually acks (or is evicted). Eviction of a lagging peer may legitimately
     * raise the watermark.
     *
     * Emits `0L` until at least one delta has been universally acknowledged.
     */
    public val universalAckFlow: StateFlow<Long> = _universalAckFlow.asStateFlow()

    private val _deliveredLocal = MutableStateFlow(VersionVector.EMPTY)

    /**
     * This replica's **delivered** version vector: `author → highest contiguous
     * (gap-free) seq this replica has applied`, derived from the current merged
     * [state]'s [Quilted.causalDots]. Recomputed on every state change (local apply
     * and inbound delta), so it never carries an incremental-contiguity bug — a gap
     * in an author's dots truncates that author's high-water at the gap.
     *
     * This is the per-replica matrix-clock row of the causal-stability barrier
     * (ADR-003 addendum v3, #262): it is gossiped via [QuiltMessage.Delivered]
     * and folded into peers' matrices. Empty for CRDTs that expose no dots (the whole
     * delta-state zoo); populated for [us.tractat.kuilt.crdt.Rga].
     */
    public val deliveredLocal: StateFlow<VersionVector> = _deliveredLocal.asStateFlow()

    /**
     * The matrix clock: `peer → that peer's last-gossiped delivered VV`. Populated by
     * inbound [QuiltMessage.Delivered]; consumed by [recomputeCut] to derive the
     * stable cut and frontier. Mutated only under [lock] (ADR §4.6 W2 — see [recomputeCut] /
     * [evictStalePeers]).
     */
    private val frontiers: MutableMap<PeerId, VersionVector> = mutableMapOf()

    /** Exposed internally so tests can observe the matrix clock. */
    internal val frontiersForTest: Map<PeerId, VersionVector> get() = frontiers

    /**
     * The eviction-proof floor on known-to-exist dots (ADR §4.2). On eviction, a
     * departing peer's last-gossiped frontier is folded in by elementwise max
     * ([evictStalePeers], retain rule §4.3) so `F` never falls below a dot the peer
     * witnessed; entries are released (§4.4 release rule) once self delivers them (R1)
     * or a live peer dominates them (R2). Mutated only under [lock] (W2).
     */
    private var retainedFrontier: VersionVector = VersionVector.EMPTY

    /** The monotonic stable cut `S` — never decreases (ADR §4.2; a FullState-synced joiner must not lower it). */
    private var monotonicStableCut: VersionVector = VersionVector.EMPTY

    private val _cutFrontier = MutableStateFlow(CutFrontier.EMPTY)

    /**
     * The causal-stability cut + frontier, recomputed on every matrix change (local
     * apply, inbound delta, inbound [QuiltMessage.Delivered], join, eviction) and
     * published **atomically** as a single [CutFrontier] (W1 of ADR §4.6). A
     * [us.tractat.kuilt.crdt.Rga] GC coordinator (#270) consumes this together with
     * [deliveredLocal] and feeds them to `Rga.compact(stableCut, frontierMax, delivered)`.
     * For CRDTs that expose no dots (the delta-state zoo) it stays at [CutFrontier.EMPTY].
     */
    public val cutFrontier: StateFlow<CutFrontier> = _cutFrontier.asStateFlow()

    /** Exposed internally so tests can observe the retained frontier. */
    internal val retainedFrontierForTest: VersionVector get() = retainedFrontier

    private var nextSeq: Long = 0L
    private val pendingDeltas: MutableMap<Long, S> = mutableMapOf()
    private val knownPeers: MutableSet<PeerId> = mutableSetOf()

    /** Per-peer acked-through-seq for MY deltas: ackedThrough[peer] = highest seq B has acked. */
    private val ackedThrough: MutableMap<PeerId, Long> = mutableMapOf()

    /** Per-sender expected receive seq: expectedReceiveSeq[sender] = next seq we expect. */
    private val expectedReceiveSeq: MutableMap<ReplicaId, Long> = mutableMapOf()

    /** Buffered out-of-order inbound deltas: pendingInbound[sender][seq] = delta. */
    private val pendingInbound: MutableMap<ReplicaId, MutableMap<Long, S>> = mutableMapOf()

    /** Last-seen time (ms from clock) per peer for eviction tracking. */
    private val lastSeenAt: MutableMap<PeerId, Long> = mutableMapOf()

    /**
     * Pending retry jobs per sender: when a Resend is emitted, a [Job] is scheduled
     * to re-fire after [QuilterConfig.resendRetryInterval]. The job is cancelled
     * when the gap closes or a new Resend supersedes it for the same sender.
     */
    private val pendingResendJobs: MutableMap<ReplicaId, Job> = mutableMapOf()

    /**
     * Pending FullState retry jobs per peer: when a [QuiltMessage.FullState] is sent
     * to a new peer, a [Job] is scheduled to re-send it after
     * [QuilterConfig.fullStateRetryInterval] in case the initial snapshot was dropped.
     * The job is cancelled when any message from that peer arrives, confirming reachability.
     */
    private val pendingFullStateJobs: MutableMap<PeerId, Job> = mutableMapOf()

    /**
     * Peers we have sent a [QuiltMessage.RootDigest] to and not yet answered a
     * [QuiltMessage.FullStateRequest] for. Gates the amplification lever: an unsolicited request
     * finds no entry and is dropped.
     */
    private val digestOutstanding: MutableSet<PeerId> = mutableSetOf()

    /**
     * Peers that have **sent us** a [QuiltMessage.RootDigest], and are therefore running a build
     * that can read one (#2006). A peer absent from this set is *unproven*, not *old*: the tick
     * ships it the whole state as well as the digest, which is exactly what the tick did before
     * #1955.
     *
     * The discriminator is one-directional, and that is the whole point. *Silence in reply* to a
     * digest is ambiguous — a converged current peer answers a matched root with nothing at all,
     * which is what #1955 bought — but *having sent one* is not, because
     * [reconcileWithRandomPeer] emits digests unconditionally and a build predating #1955 emits
     * none. So there is no false positive that costs anything: an unproven peer gets the
     * pre-#1955 behaviour, never worse than it.
     *
     * Monotonic within a peer's membership; dropped on eviction ([evictStalePeers]) because the
     * proof is about the software behind a [PeerId], and a rejoining id may be attached to
     * different software.
     *
     * **Limit, deliberately not engineered around:** a peer that *downgrades* mid-session keeps
     * its latch and stops being healed by this side's tick, exactly as it would have before this
     * mechanism existed. A version handshake on [QuiltMessage] — of which there is none today —
     * subsumes all of this and remains the better long-term shape; this is the cheap correct-now
     * option, not the last word on it.
     */
    private val everSentUsDigest: MutableSet<PeerId> = mutableSetOf()

    /** Counts anti-entropy iterations; logged each tick so virtual-time cycling is visible. */
    private var antiEntropyCount = 0L

    /** Exposed internally so tests can observe GC behaviour. */
    internal val pendingDeltasForTest: Map<Long, S> get() = pendingDeltas

    /** Exposed internally so tests can observe known-peer state. */
    internal val knownPeersForTest: Set<PeerId> get() = knownPeers

    // ownJob, scope, and closed are inherited from ScopedCloseable.

    private val backgroundJobs: List<Job>

    /** Exposed internally so tests can verify [close] cancels every background job. */
    internal val backgroundJobsForTest: List<Job> get() = backgroundJobs

    /** Sends one [QuiltMessage.RootDigest] to [peer], arming the solicited-request flag. Test-only. */
    internal fun sendRootDigestForTest(peer: PeerId): Unit = lock.withLock { sendRootDigestTo(peer) }

    /**
     * The root an emitted [QuiltMessage.RootDigest] would carry right now, taken under [lock].
     *
     * Exposed so a test compares against the production framing rather than re-deriving it: three
     * independent hand-written mirrors of [stateRoot]'s expression existed before, and a mirror that
     * drifts pins itself, not this function. `QuilterStateRootGoldenVectorTest` keeps one
     * deliberately — that is its whole job — and asserts it against *this* value.
     */
    internal fun stateRootForTest(): Long = lock.withLock { stateRoot() }

    init {
        // `scope` here is the constructor parameter (the original parent scope).
        // `this.scope` is the owned child scope inherited from ScopedCloseable.
        checkNotUnderTestDispatcher(
            scope = scope,
            typeName = "Quilter",
            substitute = "a Quilter under UnconfinedTestDispatcher or with manual testScheduler.advanceTimeBy(…)",
            strict = config.strictTestGuard,
            expectVirtualTime = config.expectVirtualTime,
        )

        val incomingJob = seam.incoming
            .onEach { swatch -> swatch.sender?.let { touch(it); dispatch(it, swatch) } }
            .onCompletion { close() }   // seam torn ⇒ incoming completes ⇒ replicator closes itself
            .launchIn(this.scope)

        val peersJob = seam.peers
            .onEach { currentPeers -> onPeersChanged(currentPeers) }
            .launchIn(this.scope)

        val antiEntropyJob = this.scope.launch { runAntiEntropy() }

        backgroundJobs = listOf(incomingJob, peersJob, antiEntropyJob)
    }

    /**
     * Clears pending retry-job maps before [ownJob] is cancelled by [ScopedCloseable.close].
     * Called at most once, always before the coroutines stop.
     */
    override fun onClose() {
        logger.debug { "[Quilter/$replica] close() — anti-entropy ran $antiEntropyCount iteration(s)" }
        lock.withLock {
            pendingResendJobs.values.forEach { it.cancel() }
            pendingResendJobs.clear()
            pendingFullStateJobs.values.forEach { it.cancel() }
            pendingFullStateJobs.clear()
        }
    }

    /**
     * Apply a local mutation. Updates [state] synchronously; broadcasts a [QuiltMessage.Delta]
     * to all current peers asynchronously (fire-and-forget within [scope]).
     *
     * **Thread-safe.** Safe to call from any thread or coroutine context — the state mutation is
     * serialised against the inbound/peers/anti-entropy collectors by an internal reentrant
     * [lock] — and remains **synchronous** (non-suspending): it returns once [state] reflects the
     * mutation, the broadcast having been handed off to a child coroutine.
     *
     * @throws IllegalStateException if this replicator has been [close]d.
     */
    public fun apply(patch: Patch<S>): Unit = lock.withLock {
        check(!closed) { "Quilter($replica) is closed" }
        _state.update { it.piece(patch) }
        val seq = ++nextSeq
        pendingDeltas[seq] = patch.delta
        recomputeDeliveredLocal()
        broadcastDelta(seq, patch.delta)
    }

    /**
     * Apply a local mutation expressed as a transform on the current state — the **atomic
     * read-modify-write** entry point.
     *
     * The read of [state].value and the [transform] run inside the same internal reentrant
     * [lock] as the [apply], so the state the transform sees cannot change before its patch
     * lands. Two concurrent `mutate` calls therefore serialise instead of both reading the
     * same snapshot and max-joining each other's update away — the lost-update class that
     * bit same-replica counter increments. [transform] must be pure, fast, and
     * non-suspending: it runs inside the locked section (I/O stays outside, as everywhere).
     *
     * ```kotlin
     * tally.mutate { it.increment(replica, 3L) }
     * ```
     *
     * @sample us.tractat.kuilt.quilter.sampleQuilterConvenience
     * @throws IllegalStateException if this replicator has been [close]d.
     */
    public fun mutate(transform: (S) -> Patch<S>): Unit = lock.withLock {
        apply(state.value.let(transform))
    }

    // ---- private helpers ----

    /**
     * Recomputes [deliveredLocal] from the current [state]'s [Quilted.causalDots] as the
     * **contiguous frontier**: per author, the highest `seq` such that every seq in
     * `1..seq` is present. A gap truncates that author at the gap (dots `{1,2,4}` →
     * frontier `2`). Called after every state mutation; the value only changes for
     * dot-carrying CRDTs ([us.tractat.kuilt.crdt.Rga]).
     *
     * When the vector **advances** — on local [apply] *and* on every inbound delivery
     * ([applyAndDrain], [drainPendingInbound], [onFullState]) — this replica [gossipDelivered]s
     * the fresh row so peers' matrix clocks (and hence the [cutFrontier] that drives RGA GC)
     * converge without waiting on the slow anti-entropy tick. A receiver that just delivered an
     * author's op is the timeliest witness of that delivery; gossiping here is what lets the
     * stable cut rise as deltas land rather than only once per [QuilterConfig.antiEntropyInterval].
     */
    private fun recomputeDeliveredLocal() {
        val previous = _deliveredLocal.value
        _deliveredLocal.value = contiguousFrontier(_state.value.causalDots())
        recomputeCut()
        if (_deliveredLocal.value != previous) gossipDelivered()
    }

    /**
     * Recomputes the stable cut `S`, the retained-frontier release (§4.4), and the
     * frontier `F = max(F_live, retainedFrontier)`, then publishes both as one atomic
     * [CutFrontier] (W1 of ADR §4.6 — no observable half-update). Called from every
     * site that mutates the matrix-clock state ([recomputeDeliveredLocal], [onDelivered],
     * [onPeersChanged], [evictStalePeers]); all hold [lock] (W2) — this method's effects must
     * stay inside the critical section, never moved into a separately-launched coroutine.
     *
     * `S = min over live peers ∪ self` (a known-but-not-yet-gossiped peer contributes
     * [VersionVector.EMPTY], conservatively flooring `S` to 0 until it gossips), kept
     * monotonic. `F_live = max over live peers ∪ self`.
     */
    private fun recomputeCut() {
        val self = _deliveredLocal.value
        val rows = knownPeers.map { frontiers[it] ?: VersionVector.EMPTY } + self
        val fLive = rows.fold(VersionVector.EMPTY) { acc, vv -> acc.ceilWith(vv) }
        val sMin = rows.reduce { acc, vv -> acc.floorWith(vv) } // rows always non-empty (self)
        monotonicStableCut = monotonicStableCut.ceilWith(sMin)
        // Release rule §4.4: a retained entry survives only as the EXCESS over what self
        // has delivered (R1) or any live peer witnesses (R2).
        val selfOrLive = self.ceilWith(fLive)
        retainedFrontier = VersionVector.of(
            retainedFrontier.entries.filter { (author, seq) -> seq > selfOrLive[author] },
        )
        val fMax = fLive.ceilWith(retainedFrontier)
        _cutFrontier.value = CutFrontier(stableCut = monotonicStableCut, frontierMax = fMax)
    }

    /**
     * Gossips this replica's whole-room [deliveredLocal] as a [QuiltMessage.Delivered]
     * broadcast. Fired on local [apply] and on the anti-entropy tick — its own cadence,
     * separate from the delta/ack path. Skipped while the vector is empty (nothing yet
     * delivered, so no peer's matrix row gains information).
     */
    private fun gossipDelivered() {
        val vector = _deliveredLocal.value
        if (vector.entries.isEmpty()) return
        val msg = QuiltMessage.Delivered<S>(sender = replica, vector = vector)
        val bytes = encode(msg)
        scope.launch {
            runCatchingCancellable { seam.broadcast(bytes) }
                .onFailure { logger.debug { "gossipDelivered broadcast failed: ${it.message}" } }
        }
    }

    private fun touch(peer: PeerId): Unit = lock.withLock {
        lastSeenAt[peer] = clock.now()
    }

    private suspend fun runAntiEntropy() {
        while (true) {
            delay(config.antiEntropyInterval)
            val n = ++antiEntropyCount
            // Logged at DEBUG so virtual-time cycling is visible in the test/CI artifact:
            // normal production = one line per antiEntropyInterval; cycling = rapid-fire lines
            // with ascending iteration numbers, immediately distinguishing the #329 signature.
            logger.debug { "[Quilter/$replica] anti-entropy iteration=$n peers=${seam.peers.value.size}" }
            // Lock the state work, NOT the delay (which must stay a suspension point outside it).
            lock.withLock {
                evictStalePeers()
                gossipDelivered()
                reconcileWithRandomPeer()
            }
        }
    }

    /**
     * Picks one peer at random from the full membership and reconciles with it. This is the
     * convergence backstop: a peer that missed a delta — it sat outside [deltaTargets], or a
     * relayed delivery was dropped — requests and merges the full state on the next round that
     * *selects* it, and converges without replaying the missing deltas. One peer is drawn per
     * tick, so that is a coupon-collector wait rather than the very next tick.
     *
     * Sends a [QuiltMessage.RootDigest] — a hash of the state, not the state (#1955) — to a peer
     * proven to be able to read one ([everSentUsDigest]; an unproven peer is also sent the state,
     * which is the pre-#1955 cost, never worse). The peer
     * replies with a [QuiltMessage.FullStateRequest] only if its own root differs, so a converged
     * round costs two small frames instead of the whole CRDT: the digest out (~54–57 b) and the
     * matched peer's [QuiltMessage.Ack] of `upThrough` back (~40–46 b), ~94–103 b in total.
     * Measured: a converged 100k-entry `GSet` node drops from ~58 KB/s of steady-state egress to
     * roughly 1.7 B/s — a ~34,000× reduction. Both frames are flat in state size, which is the
     * claim that matters; the constant is not exact, because CBOR encodes `root`, `seq` and
     * `upThrough` at minimal width, so a few bytes move with the values and with the replica id's
     * length. The published pair is the **conservative** end of the measured range (94–103 b per
     * round), so the real saving is this or better. These are encoded-frame bytes and exclude
     * whatever the transport adds on top — WebSocket framing, TLS records, IP headers — but so is
     * the "before" side, so the ratio holds.
     *
     * The merge at the receiver is idempotent and order-independent (every delta-state CRDT
     * is a join-semilattice), so the same full state can be sent any number of times safely.
     * This is what makes GC against a sparse [deltaTargets] set correct: convergence does
     * not depend on every peer acking every delta.
     *
     * [QuiltMessage.FullState] remains the always-correct fallback and every convergence
     * guarantee still traces to it — a root collision or a digest a peer cannot parse costs a
     * missed heal, never divergence that survives a [QuiltMessage.FullState].
     *
     * That missed heal is **not** bounded by the next round, though. Between two *quiescent* peers
     * neither state changes, so the same pair of colliding roots recurs identically on every
     * subsequent round in both directions; recovery waits on a local mutation or a
     * [QuiltMessage.FullState] from a third peer. At 2⁻⁶⁴ per pair that is a risk to record, not to
     * engineer around — but the bound is "until something changes", not "one round".
     *
     * The digest also carries `upThrough`, because on a *matched* round no state ships and nothing
     * else would resync the recipient's receive cursor (#1266). On a mismatch the requested
     * [QuiltMessage.FullState] carries its own, so the digest handler deliberately leaves the
     * cursor alone there rather than acking history it has not yet received.
     *
     * The send is fire-and-forget — a later round that draws the same peer is the natural retry.
     * Must be called under [lock]; the actual `seam.sendTo` is launched on [scope] outside it.
     */
    private fun reconcileWithRandomPeer() {
        if (knownPeers.isEmpty()) return
        val peer = knownPeers.elementAt(random.nextInt(knownPeers.size))
        // The digest goes to every peer, proven or not. It is the only probe that can ever set
        // [everSentUsDigest] from this side's point of view, so withholding it from unproven peers
        // — the shape #2006's discussion first reached for — would wedge every peer unproven
        // forever and revert #1955 outright, in both directions at once.
        sendRootDigestTo(peer)
        if (peer !in everSentUsDigest) sendUnprovenPeerFullState(peer)
    }

    /**
     * The #2006 fallback: ship the whole state to a peer that has never sent us a
     * [QuiltMessage.RootDigest] and may therefore be unable to read one.
     *
     * A build predating #1955 hits the unknown-variant path and drops the digest with no error, no
     * log and no negotiation, so anti-entropy from this side towards it stops dead. Convergence
     * survives — the older peer's own full-state ticks continue and `onFullState`'s push-back heals
     * both directions — but initiation becomes one-sided and the rate halves. This restores it.
     *
     * Ships directly rather than through [sendFullStateTo], for the same reason
     * [onFullStateRequest] does: that helper arms [scheduleFullStateRetry], which exists for the
     * first-contact path, and re-arming it on a recurring tick would stack a second retry machine
     * on a peer that already has one. No retry is needed here — the next round that draws this peer
     * is the retry.
     *
     * Must be called under [lock]; the suspending send is launched on [scope] outside it.
     */
    private fun sendUnprovenPeerFullState(peer: PeerId) {
        val bytes = encode(QuiltMessage.FullState(sender = replica, state = _state.value, upThrough = nextSeq))
        scope.launch {
            runCatchingCancellable { seam.sendTo(peer, bytes) }
                .onFailure { logger.debug { "unproven-peer fullState to $peer failed: ${it.message}" } }
        }
    }

    /**
     * FNV-1a 64 over the state as it would appear on the wire. Must be called under [lock].
     *
     * There is no `KSerializer<S>` on this class — the primary constructor takes only
     * [messageSerializer], and the top-level factory's `valueSerializer` is not retained — so the
     * state is encoded inside a fixed synthetic [QuiltMessage.FullState]. [ReplicaId.Bottom] and
     * `upThrough = 0L` are constants, so the *envelope* is identical on every peer.
     *
     * Equal states then yield equal roots **provided `S`'s serializer is encoding-canonical** —
     * equal values must encode to identical bytes, which requires a deterministic order for any
     * set- or map-valued field. Every CRDT in the in-tree zoo satisfies this (they serialize
     * through sorted, canonical forms), but [Quilter] is generic over consumer state types: an `S`
     * carrying a plain unordered `Set`/`Map` can encode one value two ways on two peers. The
     * failure mode is benign and self-limiting — the roots never match, so every round falls back
     * to the [QuiltMessage.FullState] path that predates #1955 and the optimization simply never
     * engages for that pair. It is never divergence.
     */
    private fun stateRoot(): Long = fnv1a64(
        binaryFormat.encodeToByteArray(
            messageSerializer,
            QuiltMessage.FullState(sender = ReplicaId.Bottom, state = _state.value, upThrough = 0L),
        ),
    )

    /**
     * Ships a [QuiltMessage.RootDigest] to [peer]. Under [lock].
     *
     * @param armGrant when `true` (the anti-entropy tick), arms the one-shot solicited-request
     *   flag so the peer's [QuiltMessage.FullStateRequest] is answered. The first-contact
     *   announcement passes `false`: see [announceDigestTo].
     */
    private fun sendRootDigestTo(peer: PeerId, armGrant: Boolean = true) {
        val bytes = encode(QuiltMessage.RootDigest(sender = replica, root = stateRoot(), upThrough = nextSeq))
        if (armGrant) digestOutstanding.add(peer)
        scope.launch {
            runCatchingCancellable { seam.sendTo(peer, bytes) }
                .onFailure { logger.debug { "rootDigest to $peer failed: ${it.message}" } }
        }
    }

    private fun evictStalePeers() {
        val currentPeers = seam.peers.value
        val now = clock.now()
        val toEvict = knownPeers
            .filter { peer -> peer !in currentPeers && isStale(peer, now) }
            .toSet()
        if (toEvict.isEmpty()) return

        // W1 (ADR §4.6) — retain-capture-BEFORE-drop atomicity. Fold every evicting peer's
        // last-gossiped row into `retainedFrontier` (retain rule §4.3) FIRST, then drop the
        // live rows. Both halves run synchronously under the held [lock] (W2)
        // and `cutFrontier` is republished exactly once, at the end — so a compactor can
        // never observe an intermediate where `F_live` has fallen but `retainedFrontier`
        // has not yet floored (that intermediate is precisely the #275 hole).
        toEvict.forEach { peer ->
            frontiers[peer]?.let { retainedFrontier = retainedFrontier.ceilWith(it) }
        }
        toEvict.forEach { peer ->
            knownPeers.remove(peer)
            frontiers.remove(peer)
            ackedThrough.remove(peer)
            lastSeenAt.remove(peer)
            cancelFullStateRetry(peer)
            digestOutstanding.remove(peer)
            everSentUsDigest.remove(peer)
        }
        recomputeUniversalAck()
        recomputeCut()
    }

    private fun isStale(peer: PeerId, nowMs: Long): Boolean {
        val seenAt = lastSeenAt[peer] ?: return true
        return (nowMs - seenAt) >= config.evictionAfter.inWholeMilliseconds
    }

    private fun broadcastDelta(seq: Long, delta: S) {
        val msg = QuiltMessage.Delta(sender = replica, seq = seq, delta = delta)
        val bytes = encode(msg)
        scope.launch {
            runCatchingCancellable { seam.broadcast(bytes) }
                .onFailure { logger.debug { "broadcastDelta failed: ${it.message}" } }
        }
    }

    private fun onPeersChanged(currentPeers: Set<PeerId>): Unit = lock.withLock {
        val newPeers = currentPeers - seam.selfId - knownPeers
        knownPeers += currentPeers - seam.selfId
        newPeers.forEach { peer ->
            sendFullStateTo(peer)
            announceDigestTo(peer)
        }
        // A new peer that has not gossiped contributes EMPTY to `min over live` — but the
        // cut is monotonic, so it cannot lower `S`. Safe: the joiner is FullState-synced and
        // has no concurrent history to orphan (ADR §4.5). Recompute so membership is reflected.
        if (newPeers.isNotEmpty()) recomputeCut()
    }

    /**
     * A first-contact "I speak the digest exchange" beacon (#2006), sent alongside the
     * first-contact [QuiltMessage.FullState].
     *
     * Without it, [everSentUsDigest] could only be set by the *peer's own* tick happening to draw
     * us, which is a coupon-collector wait of `O(N log N)` rounds — about 70 minutes at N = 20 with
     * the default one-minute interval. Throughout that window a mesh of entirely current peers
     * would take the unproven-peer fallback on nearly every round, i.e. #1955's saving would be off
     * for an hour after every join. One 50-odd-byte frame per new peer collapses that to a single
     * round; the tick's own digest remains the retry if this one is lost.
     *
     * **Grant-free, deliberately.** Arming [digestOutstanding] here would hand every peer a
     * redeemable one-shot full-state coupon from the moment it joined, retiring the
     * unsolicited-[QuiltMessage.FullStateRequest] guard that is the amplification lever #1955
     * closed. The cost of not arming it is that a peer whose root legitimately differs may answer
     * with a request that is dropped and debug-logged; the state it wanted is already on its way in
     * the first-contact [QuiltMessage.FullState] beside this frame, and the next tick re-drives the
     * exchange with a grant.
     *
     * Must be called under [lock].
     */
    private fun announceDigestTo(peer: PeerId) = sendRootDigestTo(peer, armGrant = false)

    private fun sendFullStateTo(peer: PeerId) {
        val msg = QuiltMessage.FullState(sender = replica, state = _state.value, upThrough = nextSeq)
        val bytes = encode(msg)
        scope.launch {
            runCatchingCancellable { seam.sendTo(peer, bytes) }
                .onFailure { logger.debug { "sendFullStateTo $peer failed: ${it.message}" } }
        }
        scheduleFullStateRetry(peer, config.fullStateRetryLimit)
    }

    private fun scheduleFullStateRetry(peer: PeerId, attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
            pendingFullStateJobs.remove(peer)
            return
        }
        pendingFullStateJobs[peer]?.cancel()
        pendingFullStateJobs[peer] = scope.launch {
            delay(config.fullStateRetryInterval)
            // Snapshot the frame under the lock; perform the suspending send OUTSIDE it; then
            // reschedule under the lock again — the lock is never held across `seam.sendTo`.
            val bytes = lock.withLock {
                if (peer !in knownPeers) return@launch
                encode(QuiltMessage.FullState(sender = replica, state = _state.value, upThrough = nextSeq))
            }
            runCatchingCancellable { seam.sendTo(peer, bytes) }
                .onFailure { logger.debug { "fullStateRetry sendTo $peer failed: ${it.message}" } }
            lock.withLock { scheduleFullStateRetry(peer, attemptsLeft - 1) }
        }
    }

    private fun cancelFullStateRetry(peer: PeerId) {
        pendingFullStateJobs.remove(peer)?.cancel()
    }

    private fun dispatch(sender: PeerId, swatch: Swatch): Unit = lock.withLock {
        cancelFullStateRetry(sender)
        // Log the drop. A peer running a build that predates #1955 cannot decode `rootDigest` or
        // `fullStateRequest`, and silence is then the *only* symptom of a mixed-version rollout
        // (#2006) — every send site in this file already logs its failure; so does this one.
        val msg = runCatchingCancellable { swatch.decode(binaryFormat, messageSerializer) }
            .onFailure { failure -> logger.debug { "undecodable frame from $sender dropped: $failure" } }
            .getOrNull() ?: return@withLock
        when (msg) {
            is QuiltMessage.Delta -> onDelta(sender, msg)
            is QuiltMessage.Ack -> onAck(sender, msg)
            is QuiltMessage.FullState -> onFullState(sender, msg)
            is QuiltMessage.Resend -> onResend(sender, msg)
            is QuiltMessage.Delivered -> onDelivered(sender, msg)
            is QuiltMessage.RootDigest -> onRootDigest(sender, msg)
            is QuiltMessage.FullStateRequest -> onFullStateRequest(sender, msg)
        }
    }

    private fun onDelta(sender: PeerId, msg: QuiltMessage.Delta<S>) {
        val senderReplica = msg.sender
        val expected = expectedReceiveSeq.getOrPut(senderReplica) { 1L }

        when {
            msg.seq == expected -> applyAndDrain(senderReplica, msg.seq, msg.delta, sender)
            msg.seq > expected -> {
                bufferInbound(senderReplica, msg.seq, msg.delta)
                requestResend(sender, senderReplica, fromSeq = expected, toSeq = msg.seq - 1)
            }
            else -> {
                // Duplicate or stale — re-ack for sender GC, don't re-apply
                sendAck(to = sender, originalSender = senderReplica, seq = msg.seq)
            }
        }
    }

    private fun applyAndDrain(senderReplica: ReplicaId, seq: Long, delta: S, ackTarget: PeerId) {
        _state.update { it.piece(delta) }
        expectedReceiveSeq[senderReplica] = seq + 1
        recomputeDeliveredLocal()
        sendAck(to = ackTarget, originalSender = senderReplica, seq = seq)
        drainPendingInbound(senderReplica, ackTarget)
        // Cancel the Resend retry only when the gap has fully closed — the
        // [QuilterConfig.resendRetryInterval] contract. After a *partial* drain (the drain
        // stopped at a still-missing seq with later deltas still buffered) the retry must
        // stay armed for the remaining range: in a low-traffic system it is the only
        // mechanism that heals a gap whose retransmission was itself dropped.
        val remaining = pendingInbound[senderReplica]
        if (remaining == null) {
            cancelResendRetry(senderReplica)
        } else {
            val stillExpected = expectedReceiveSeq[senderReplica] ?: 1L
            scheduleResendRetry(ackTarget, senderReplica, stillExpected, remaining.keys.max())
        }
    }

    private fun drainPendingInbound(senderReplica: ReplicaId, ackTarget: PeerId) {
        val buffer = pendingInbound[senderReplica] ?: return
        var next = expectedReceiveSeq[senderReplica] ?: 1L
        while (true) {
            val delta = buffer.remove(next) ?: break
            _state.update { it.piece(delta) }
            expectedReceiveSeq[senderReplica] = next + 1
            recomputeDeliveredLocal()
            sendAck(to = ackTarget, originalSender = senderReplica, seq = next)
            next++
        }
        if (buffer.isEmpty()) pendingInbound.remove(senderReplica)
    }

    private fun bufferInbound(senderReplica: ReplicaId, seq: Long, delta: S) {
        pendingInbound.getOrPut(senderReplica) { mutableMapOf() }[seq] = delta
    }

    private fun requestResend(to: PeerId, sender: ReplicaId, fromSeq: Long, toSeq: Long) {
        sendResend(to, sender, fromSeq, toSeq)
        scheduleResendRetry(to, sender, fromSeq, toSeq)
    }

    private fun sendResend(to: PeerId, sender: ReplicaId, fromSeq: Long, toSeq: Long) {
        val msg = QuiltMessage.Resend<S>(
            requester = replica,
            sender = sender,
            fromSeq = fromSeq,
            toSeq = toSeq,
        )
        val bytes = encode(msg)
        scope.launch {
            runCatchingCancellable { seam.sendTo(to, bytes) }
                .onFailure { logger.debug { "sendResend to $to failed: ${it.message}" } }
        }
    }

    private fun scheduleResendRetry(to: PeerId, sender: ReplicaId, fromSeq: Long, toSeq: Long) {
        pendingResendJobs[sender]?.cancel()
        pendingResendJobs[sender] = scope.launch {
            delay(config.resendRetryInterval)
            // Lock the state re-check + reschedule, NOT the preceding delay. `sendResend` only
            // launches a child coroutine for the actual send, so it stays safe under the lock.
            lock.withLock {
                // Re-check that the gap is still open before retrying.
                val stillExpecting = expectedReceiveSeq[sender] ?: 1L
                if (stillExpecting <= toSeq) {
                    sendResend(to, sender, stillExpecting, toSeq)
                    scheduleResendRetry(to, sender, stillExpecting, toSeq)
                }
            }
        }
    }

    private fun cancelResendRetry(sender: ReplicaId) {
        pendingResendJobs.remove(sender)?.cancel()
    }

    private fun sendAck(to: PeerId, originalSender: ReplicaId, seq: Long) {
        val msg = QuiltMessage.Ack<S>(acker = replica, sender = originalSender, seq = seq)
        val bytes = encode(msg)
        scope.launch {
            runCatchingCancellable { seam.sendTo(to, bytes) }
                .onFailure { logger.debug { "sendAck to $to failed: ${it.message}" } }
        }
    }

    /**
     * Records the acker's progress on MY deltas, keyed by [acker] — the transport-level
     * [PeerId] the ack arrived from. It must never be a PeerId fabricated from the wire
     * message's [ReplicaId]: the two identity domains are decoupled (the convenience
     * factory explicitly supports a custom [replica] id), so a fabricated key would never
     * match [knownPeers] and [recomputeUniversalAck]'s watermark would be frozen at 0
     * forever, pinning [pendingDeltas] unboundedly.
     */
    private fun onAck(acker: PeerId, msg: QuiltMessage.Ack<S>) {
        if (msg.sender != replica) return
        val current = ackedThrough[acker] ?: 0L
        if (msg.seq > current) ackedThrough[acker] = msg.seq
        recomputeUniversalAck()
    }

    /**
     * Advances the GC watermark and prunes the pending-delta buffer.
     *
     * Computes `min(ackedThrough)` over [deltaTargets]`(knownPeers)` — the peers this
     * replica actually pushes deltas to — and updates [universalAckFlow] monotonically.
     * Any pending delta at or below the new watermark is removed.
     *
     * Peers outside the delta-target set cannot pin the watermark; they converge via the
     * anti-entropy backstop ([reconcileWithRandomPeer]) rather than acks. When
     * [deltaTargets] is the identity (the default), this is `min over knownPeers` — the
     * same behaviour as before Phase 1. No-op when the delta-target set is empty.
     */
    private fun recomputeUniversalAck() {
        val targets = deltaTargets(knownPeers)
        if (targets.isEmpty()) return
        val candidate = targets.minOfOrNull { peer -> ackedThrough[peer] ?: 0L } ?: return
        val next = maxOf(_universalAckFlow.value, candidate)
        _universalAckFlow.value = next
        gcPendingDeltas(next)
    }

    private fun gcPendingDeltas(universalAck: Long) {
        pendingDeltas.keys.removeAll { it <= universalAck }
    }

    /**
     * Processes an inbound [QuiltMessage.FullState] from [sender].
     *
     * Three cases:
     * - **Incoming advances our state** (`merged != current`): merge, recompute delivered.
     * - **Incoming is strictly behind** (`merged == current && msg.state != current`): the
     *   sender is lagging. Push our full state back to it immediately so it heals without
     *   waiting for the anti-entropy backstop. This is the fix for the reconnect timing
     *   gap (#828): the reconnecting Quilter sends its own empty first-contact FullState
     *   (from `onPeersChanged → sendFullStateTo(hub)`) *after* its collector has subscribed,
     *   so that FullState is guaranteed to reach the hub; the hub sees an empty/lagging state
     *   and pushes its full history back in response. Prompt, not anti-entropy-cadence.
     * - **Incoming equals our state exactly** (`msg.state == current`): steady-state tick,
     *   no-op. No push-back here — avoids a FullState storm when all peers are already equal.
     *
     * `sendFullStateTo` is called under [lock] but internally schedules the suspending send
     * via `scope.launch` (outside the lock), so this call is safe and non-suspending.
     *
     * Whatever the merge outcome, the heal also resynchronises the receive watermark
     * ([resyncReceiveCursor]) — a FullState is a statement that the sender's history through
     * [QuiltMessage.FullState.upThrough] is absorbed, so the receive cursor, inbound buffer,
     * and the sender's ack bookkeeping must all reflect it.
     */
    private fun onFullState(sender: PeerId, msg: QuiltMessage.FullState<S>) {
        val current = _state.value
        val merged = current.piece(msg.state)
        if (merged != current) {
            _state.value = merged
            recomputeDeliveredLocal()
        } else if (msg.state != current) {
            // Sender is strictly behind: push our full state back so it heals promptly.
            // When msg.state == current the sender is already up to date — no push-back,
            // avoiding a FullState storm when all peers are already equal.
            sendFullStateTo(sender)
        }
        resyncReceiveCursor(sender, msg.sender, msg.upThrough)
    }

    /**
     * An inbound anti-entropy digest.
     *
     * On a **match** the roots agree, so the states agree, so resyncing the receive cursor and
     * acking [QuiltMessage.RootDigest.upThrough] is honest — and it is the #1266 obligation this
     * frame exists to carry, since no state ships.
     *
     * On a **mismatch** it deliberately does **not** resync. `resyncReceiveCursor` acks, and today
     * that ack is only ever issued after the state was merged ([onFullState] merges, then resyncs).
     * Acking here would claim absorption of history we have not received and drop buffered deltas
     * covering it; if the request or its reply were then lost we would be stale *and* cut off from
     * that history via the delta path. The requested [QuiltMessage.FullState] carries its own
     * `upThrough` and resyncs exactly as it does today.
     *
     * Whichever branch runs, the arrival itself proves [sender] runs a build that can read a digest
     * — the [everSentUsDigest] latch (#2006). It is latched on the frame being *sent to us*, not on
     * the peer *answering* one of ours: an answer is ambiguous (a converged peer answers a matched
     * root with silence) and a mismatch reply would only ever prove capability for peers that
     * happen to disagree with us.
     */
    private fun onRootDigest(sender: PeerId, msg: QuiltMessage.RootDigest<S>) {
        everSentUsDigest.add(sender)
        if (msg.root == stateRoot()) {
            resyncReceiveCursor(sender, msg.sender, msg.upThrough)
            return
        }
        val bytes = encode(QuiltMessage.FullStateRequest<S>(requester = replica, sender = msg.sender))
        scope.launch {
            runCatchingCancellable { seam.sendTo(sender, bytes) }
                .onFailure { logger.debug { "fullStateRequest to $sender failed: ${it.message}" } }
        }
    }

    /**
     * A peer's reply to our digest, asking for the state. Ships it directly rather than via
     * [sendFullStateTo]: that helper arms [scheduleFullStateRetry], which exists for the
     * first-contact path, and running it here would put two independent retry machines on one
     * peer.
     *
     * No retry is armed instead, because the **requester** re-drives the exchange: it asks again
     * on the next digest whose root disagrees with its own. Note this is not "anti-entropy retries
     * every interval" — [reconcileWithRandomPeer] draws **one** peer uniformly per tick, so any
     * given peer is re-digested at rate 1/N, a coupon-collector wait rather than the next round.
     * That is the same tail every heal on this path already has, so it costs nothing extra here.
     */
    private fun onFullStateRequest(sender: PeerId, msg: QuiltMessage.FullStateRequest<S>) {
        if (msg.sender != replica) return // mirrors onResend's guard — not our state being asked for
        if (!digestOutstanding.remove(sender)) {
            logger.debug { "unsolicited fullStateRequest from $sender — ignored" }
            return
        }
        val bytes = encode(QuiltMessage.FullState(sender = replica, state = _state.value, upThrough = nextSeq))
        scope.launch {
            runCatchingCancellable { seam.sendTo(sender, bytes) }
                .onFailure { logger.debug { "fullState reply to $sender failed: ${it.message}" } }
        }
    }

    /**
     * Fast-forwards the per-sender receive cursor past the history [upThrough] already covers
     * (#1266). Called from both [onFullState] and [onRootDigest] — an anti-entropy round must
     * resync the cursor whether or not it ships state. Without this, a receiver whose gap
     * range outlives the sender's GC — the late-joiner case — livelocks: every subsequent
     * delta is buffered against a cursor that can never advance, each one costs a
     * Resend → FullState round-trip, the receiver never acks via the delta path, and the
     * sender's [pendingDeltas] (plus this side's [pendingInbound]) grow without bound.
     *
     * Drops buffered inbound deltas at or below the sender's high-water (their effects
     * are already merged), acks the high-water so the sender's watermark can advance,
     * drains anything now contiguous, and cancels the Resend retry once no gap remains.
     *
     * Returns immediately at `upThrough <= 0`, which is the floor for a sender that has never
     * applied a local mutation ([nextSeq] starts at `0`): there is no history to fast-forward
     * past and nothing to ack.
     */
    private fun resyncReceiveCursor(sender: PeerId, senderReplica: ReplicaId, upThrough: Long) {
        if (upThrough <= 0L) return
        val expected = expectedReceiveSeq[senderReplica] ?: 1L
        if (upThrough >= expected) {
            expectedReceiveSeq[senderReplica] = upThrough + 1
            pendingInbound[senderReplica]?.let { buffer ->
                buffer.keys.removeAll { it <= upThrough }
                if (buffer.isEmpty()) pendingInbound.remove(senderReplica)
            }
        }
        // Ack the snapshot's high-water even when no fast-forward was needed: the ack is
        // idempotent at the sender (it keeps the max) and heals a previously-lost ack.
        sendAck(to = sender, originalSender = senderReplica, seq = upThrough)
        drainPendingInbound(senderReplica, sender)
        if (senderReplica !in pendingInbound) cancelResendRetry(senderReplica)
    }

    /**
     * Absorbs a peer's gossiped delivered VV into the [frontiers] matrix clock and
     * recomputes the cut/frontier (the inbound knowledge can raise `F_live` and, via the
     * §4.4 release rule, discharge retained entries this peer now witnesses).
     */
    private fun onDelivered(sender: PeerId, msg: QuiltMessage.Delivered<S>) {
        frontiers[sender] = msg.vector
        recomputeCut()
    }

    /**
     * Re-broadcasts the requested delta range, or — when part of the range is already
     * GC'd — heals the requester with a [QuiltMessage.FullState] instead. The fallback is
     * addressed to [requester], the transport-level [PeerId] the Resend arrived from,
     * never a PeerId fabricated from the wire message's [ReplicaId]: with a custom
     * replica id the fabricated peer is unknown to the seam and the resulting
     * PeerNotConnected is swallowed, silently disabling the gap-heal path.
     */
    private fun onResend(requester: PeerId, msg: QuiltMessage.Resend<S>) {
        if (msg.sender != replica) return
        val allPresent = (msg.fromSeq..msg.toSeq).all { seq -> seq in pendingDeltas }
        if (!allPresent) {
            sendFullStateTo(requester)
            return
        }
        for (seq in msg.fromSeq..msg.toSeq) {
            val delta = pendingDeltas[seq] ?: continue
            broadcastDelta(seq, delta)
        }
    }

    private fun encode(msg: QuiltMessage<S>): ByteArray =
        binaryFormat.encodeToByteArray(messageSerializer, msg)

}

/**
 * The contiguous (gap-free) frontier of a set of causal [Dot]s: for each author, the
 * highest `seq` such that every seq in `1..seq` is present. A gap stops the frontier at
 * the gap — dots `{1, 2, 4}` for one author yield high-water `2`. Authors with no dot at
 * `seq == 1` contribute nothing (omitted, reading as `0`). This is exactly the
 * **delivered** quantity the causal-stability barrier requires (ADR-003 addendum v3).
 */
internal fun contiguousFrontier(dots: Set<Dot>): VersionVector {
    val seqsByAuthor: Map<ReplicaId, Set<Long>> = dots
        .groupBy(keySelector = { it.replica }, valueTransform = { it.seq })
        .mapValues { (_, seqs) -> seqs.toSet() }
    val highWaters = seqsByAuthor.mapValues { (_, seqs) -> contiguousHighWater(seqs) }
    return VersionVector.of(highWaters)
}

/** The highest `n` such that `1..n` are all in [seqs`; `0` if `1` is absent. */
private fun contiguousHighWater(seqs: Set<Long>): Long {
    var n = 0L
    while ((n + 1L) in seqs) n++
    return n
}

/**
 * Convenience factory for [Quilter] that derives the message serializer internally.
 *
 * The full constructor requires callers to wrap the value serializer manually via
 * `QuiltMessage.serializer(valueSerializer)`. This factory does that wrapping for you,
 * and defaults [replica] to `ReplicaId(seam.selfId.value)` — a safe default because each
 * `Seam` has a unique [us.tractat.kuilt.core.Seam.selfId], satisfying the one-instance-per-
 * `(replica, CRDT-type)` precondition as long as each peer creates exactly one replicator per
 * CRDT type. Override [replica] when you need a custom id (e.g. a stable persistent id that
 * survives reconnects with a different peer identity).
 *
 * ```kotlin
 * val tally = Quilter(seam, PNCounter.ZERO, PNCounter.serializer(), backgroundScope)
 * tally.mutate { it.increment(tally.replica, 3L) }
 * ```
 *
 * @param seam the [us.tractat.kuilt.core.Seam] to replicate over.
 * @param initial the starting CRDT state (typically the zero/empty value).
 * @param valueSerializer the [KSerializer] for [S]. The message serializer is derived automatically.
 * @param scope the [CoroutineScope] whose [Job] parents the replicator's owned child job.
 * @param replica this peer's [ReplicaId]; defaults to `ReplicaId(seam.selfId.value)`.
 * @param config replication behaviour tuning.
 * @param clock monotonic time source; override in tests.
 * @param binaryFormat binary codec for wire frames; defaults to [kotlinx.serialization.cbor.Cbor].
 * @param deltaTargets selects the delta-target set (peers GC'd against) from full membership;
 *   defaults to the identity (full membership). A sparse-mesh `GossipSeam` supplies the ~k
 *   active neighbours — the partial-mesh GC scaling unlock (#654).
 * @param random RNG for anti-entropy peer selection; defaults to [kotlin.random.Random.Default].
 *   Inject a seeded instance in tests for a reproducible reconcile-peer sequence.
 *
 * @sample us.tractat.kuilt.quilter.sampleQuilterConvenience
 * @sample us.tractat.kuilt.quilter.sampleQuilterSparseDeltaTargets
 */
@Suppress("LongParameterList")
public fun <S : Quilted<S>> Quilter(
    seam: Seam,
    initial: S,
    valueSerializer: KSerializer<S>,
    scope: CoroutineScope,
    replica: ReplicaId = ReplicaId(seam.selfId.value),
    config: QuilterConfig = QuilterConfig(),
    clock: MonotonicMillis = SystemMonotonicMillis,
    binaryFormat: BinaryFormat = Cbor,
    deltaTargets: (Set<PeerId>) -> Set<PeerId> = { it },
    random: Random = Random.Default,
): Quilter<S> = Quilter(
    replica = replica,
    seam = seam,
    initial = initial,
    messageSerializer = QuiltMessage.serializer(valueSerializer),
    scope = scope,
    config = config,
    clock = clock,
    binaryFormat = binaryFormat,
    deltaTargets = deltaTargets,
    random = random,
)

