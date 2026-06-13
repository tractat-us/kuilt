@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.crdt.replicator

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
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.crdt.piece
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("us.tractat.kuilt.crdt.SeamReplicator")

/**
 * Configuration for [SeamReplicator].
 *
 * @param evictionAfter how long a peer can be absent from [Seam.peers] before it
 *   is evicted from [knownPeers]. Absent-and-silent peers pin the pending-delta
 *   buffer; eviction releases that pin. A peer that reappears after eviction
 *   will receive a fresh [ReplicatorMessage.FullState].
 * @param antiEntropyInterval how often the background eviction check runs.
 * @param resendRetryInterval how long to wait before re-emitting a [ReplicatorMessage.Resend]
 *   when the first Resend is itself dropped and no further inbound traffic triggers
 *   re-detection. The timer is cancelled when the gap closes. In a low-traffic system
 *   this is the only mechanism that heals a gap whose first Resend was lost.
 * @param fullStateRetryInterval how long to wait before re-sending a [ReplicatorMessage.FullState]
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
public data class SeamReplicatorConfig(
    val evictionAfter: Duration = 5.minutes,
    val antiEntropyInterval: Duration = 1.minutes,
    val resendRetryInterval: Duration = 30.seconds,
    val fullStateRetryInterval: Duration = 30.seconds,
    val fullStateRetryLimit: Int = 10,
    val strictTestGuard: Boolean = false,
    /**
     * Suppresses the TestDispatcher warning for tests that intentionally run a real
     * [SeamReplicator] under `UnconfinedTestDispatcher`. Has no effect in production.
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
 * `SeamReplicator<S>` instances with the same [replica] concurrently in the
 * same process breaks the delta GC protocol: both will mint deltas starting at
 * `seq = 1`, colliding on sequence numbers. The recipient cannot distinguish
 * them and will silently drop or misorder deltas, leaving replicas permanently
 * diverged. This is the same class of collision that the `BoundedCounter`
 * single-dimension fix addressed in a prior release. Create exactly **one**
 * `SeamReplicator<S>` per `(replica, CRDT type)` per process.
 *
 * ## Protocol
 * - **[apply]** applies a local mutation, updates [state], and broadcasts a [ReplicatorMessage.Delta]
 *   to all current peers. Each delta is tagged with a monotonic [seq].
 * - On receiving a [ReplicatorMessage.Delta], the state is joined and an [ReplicatorMessage.Ack]
 *   is sent back to the original sender.
 * - On receiving an [ReplicatorMessage.Ack], the acker's progress is recorded; once every
 *   known peer has acked through a seq, all deltas at or below that seq are GC'd.
 * - On first contact with a new peer, a [ReplicatorMessage.FullState] is sent so the
 *   late joiner converges immediately without waiting for a delta replay.
 *
 * ## Gap detection (Rung 12b)
 * Per-sender receive-sequence tracking detects dropped or reordered deltas:
 * - Out-of-order deltas are buffered and applied in order once the gap is filled.
 * - Missing ranges trigger a [ReplicatorMessage.Resend] to the original sender.
 * - Duplicate or stale deltas are re-acked and silently dropped.
 * - [ReplicatorMessage.Resend] causes this replica to re-broadcast buffered pending
 *   deltas for the requested range (if they haven't been GC'd yet).
 *
 * ## Peer eviction
 * Peers absent from [Seam.peers] beyond [SeamReplicatorConfig.evictionAfter] are
 * evicted from the known-peer set, releasing their buffer pin. They receive a fresh
 * [ReplicatorMessage.FullState] if they rejoin.
 *
 * @param replica this peer's [ReplicaId].
 * @param seam the [Seam] to ride. Collect [Seam.incoming] exactly once — this class
 *   takes sole ownership of the incoming stream.
 * @param initial the starting state (typically the CRDT's zero/empty value).
 * @param messageSerializer a [KSerializer] for [ReplicatorMessage]`<S>`, obtained via
 *   `ReplicatorMessage.serializer(stateSerializer)`.
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
 * [SeamReplicatorConfig.strictTestGuard] to `true` to throw rather than warn.
 */
public class SeamReplicator<S : Quilted<S>>(
    public val replica: ReplicaId,
    private val seam: Seam,
    initial: S,
    private val messageSerializer: KSerializer<ReplicatorMessage<S>>,
    scope: CoroutineScope,
    private val config: SeamReplicatorConfig = SeamReplicatorConfig(),
    private val clock: MonotonicMillis = SystemMonotonicMillis,
    /**
     * Binary format used to encode and decode [ReplicatorMessage] frames.
     * Defaults to plain [Cbor]. Override in tests or for CRDTs whose serializer
     * requires a custom [kotlinx.serialization.modules.SerializersModule]
     * (e.g. [us.tractat.kuilt.crdt.Rga] with a generic value type).
     */
    private val binaryFormat: BinaryFormat = Cbor,
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
     * A newly-joined peer receives a [ReplicatorMessage.FullState] that already reflects
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
     * (ADR-003 addendum v3, #262): it is gossiped via [ReplicatorMessage.Delivered]
     * and folded into peers' matrices. Empty for CRDTs that expose no dots (the whole
     * delta-state zoo); populated for [us.tractat.kuilt.crdt.Rga].
     */
    public val deliveredLocal: StateFlow<VersionVector> = _deliveredLocal.asStateFlow()

    /**
     * The matrix clock: `peer → that peer's last-gossiped delivered VV`. Populated by
     * inbound [ReplicatorMessage.Delivered]; consumed by [recomputeCut] to derive the
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
     * apply, inbound delta, inbound [ReplicatorMessage.Delivered], join, eviction) and
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
     * to re-fire after [SeamReplicatorConfig.resendRetryInterval]. The job is cancelled
     * when the gap closes or a new Resend supersedes it for the same sender.
     */
    private val pendingResendJobs: MutableMap<ReplicaId, Job> = mutableMapOf()

    /**
     * Pending FullState retry jobs per peer: when a [ReplicatorMessage.FullState] is sent
     * to a new peer, a [Job] is scheduled to re-send it after
     * [SeamReplicatorConfig.fullStateRetryInterval] in case the initial snapshot was dropped.
     * The job is cancelled when any message from that peer arrives, confirming reachability.
     */
    private val pendingFullStateJobs: MutableMap<PeerId, Job> = mutableMapOf()

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

    init {
        // `scope` here is the constructor parameter (the original parent scope).
        // `this.scope` is the owned child scope inherited from ScopedCloseable.
        checkNotUnderTestDispatcher(scope, config.strictTestGuard, config.expectVirtualTime)

        val incomingJob = seam.incoming
            .onEach { swatch -> swatch.sender?.let { touch(it); dispatch(it, swatch.payload) } }
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
        logger.debug { "[SeamReplicator/$replica] close() — anti-entropy ran $antiEntropyCount iteration(s)" }
        lock.withLock {
            pendingResendJobs.values.forEach { it.cancel() }
            pendingResendJobs.clear()
            pendingFullStateJobs.values.forEach { it.cancel() }
            pendingFullStateJobs.clear()
        }
    }

    /**
     * Apply a local mutation. Updates [state] synchronously; broadcasts a [ReplicatorMessage.Delta]
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
        check(!closed) { "SeamReplicator($replica) is closed" }
        _state.update { it.piece(patch) }
        val seq = ++nextSeq
        pendingDeltas[seq] = patch.delta
        recomputeDeliveredLocal()
        broadcastDelta(seq, patch.delta)
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
     * stable cut rise as deltas land rather than only once per [SeamReplicatorConfig.antiEntropyInterval].
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
     * Gossips this replica's whole-room [deliveredLocal] as a [ReplicatorMessage.Delivered]
     * broadcast. Fired on local [apply] and on the anti-entropy tick — its own cadence,
     * separate from the delta/ack path. Skipped while the vector is empty (nothing yet
     * delivered, so no peer's matrix row gains information).
     */
    private fun gossipDelivered() {
        val vector = _deliveredLocal.value
        if (vector.entries.isEmpty()) return
        val msg = ReplicatorMessage.Delivered<S>(sender = replica, vector = vector)
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
            logger.debug { "[SeamReplicator/$replica] anti-entropy iteration=$n peers=${seam.peers.value.size}" }
            // Lock the state work, NOT the delay (which must stay a suspension point outside it).
            lock.withLock {
                evictStalePeers()
                gossipDelivered()
            }
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
        }
        recomputeUniversalAck()
        recomputeCut()
    }

    private fun isStale(peer: PeerId, nowMs: Long): Boolean {
        val seenAt = lastSeenAt[peer] ?: return true
        return (nowMs - seenAt) >= config.evictionAfter.inWholeMilliseconds
    }

    private fun broadcastDelta(seq: Long, delta: S) {
        val msg = ReplicatorMessage.Delta(sender = replica, seq = seq, delta = delta)
        val bytes = encode(msg)
        scope.launch {
            runCatchingCancellable { seam.broadcast(bytes) }
                .onFailure { logger.debug { "broadcastDelta failed: ${it.message}" } }
        }
    }

    private fun onPeersChanged(currentPeers: Set<PeerId>): Unit = lock.withLock {
        val newPeers = currentPeers - seam.selfId - knownPeers
        knownPeers += currentPeers - seam.selfId
        newPeers.forEach { peer -> sendFullStateTo(peer) }
        // A new peer that has not gossiped contributes EMPTY to `min over live` — but the
        // cut is monotonic, so it cannot lower `S`. Safe: the joiner is FullState-synced and
        // has no concurrent history to orphan (ADR §4.5). Recompute so membership is reflected.
        if (newPeers.isNotEmpty()) recomputeCut()
    }

    private fun sendFullStateTo(peer: PeerId) {
        val msg = ReplicatorMessage.FullState(sender = replica, state = _state.value)
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
                encode(ReplicatorMessage.FullState(sender = replica, state = _state.value))
            }
            runCatchingCancellable { seam.sendTo(peer, bytes) }
                .onFailure { logger.debug { "fullStateRetry sendTo $peer failed: ${it.message}" } }
            lock.withLock { scheduleFullStateRetry(peer, attemptsLeft - 1) }
        }
    }

    private fun cancelFullStateRetry(peer: PeerId) {
        pendingFullStateJobs.remove(peer)?.cancel()
    }

    private fun dispatch(sender: PeerId, payload: ByteArray): Unit = lock.withLock {
        cancelFullStateRetry(sender)
        val msg = runCatching { decode(payload) }.getOrNull() ?: return@withLock
        when (msg) {
            is ReplicatorMessage.Delta -> onDelta(sender, msg)
            is ReplicatorMessage.Ack -> onAck(msg)
            is ReplicatorMessage.FullState -> onFullState(msg)
            is ReplicatorMessage.Resend -> onResend(msg)
            is ReplicatorMessage.Delivered -> onDelivered(sender, msg)
        }
    }

    private fun onDelta(sender: PeerId, msg: ReplicatorMessage.Delta<S>) {
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
        cancelResendRetry(senderReplica)
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
        val msg = ReplicatorMessage.Resend<S>(
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
        val msg = ReplicatorMessage.Ack<S>(acker = replica, sender = originalSender, seq = seq)
        val bytes = encode(msg)
        scope.launch {
            runCatchingCancellable { seam.sendTo(to, bytes) }
                .onFailure { logger.debug { "sendAck to $to failed: ${it.message}" } }
        }
    }

    private fun onAck(msg: ReplicatorMessage.Ack<S>) {
        if (msg.sender != replica) return
        val acker = PeerId(msg.acker.value)
        val current = ackedThrough[acker] ?: 0L
        if (msg.seq > current) ackedThrough[acker] = msg.seq
        recomputeUniversalAck()
    }

    /**
     * Recomputes `min(ackedThrough over knownPeers)` and updates [universalAckFlow]
     * monotonically (never decreases). Also GCs pending deltas up to the new watermark.
     * No-op when [knownPeers] is empty.
     */
    private fun recomputeUniversalAck() {
        if (knownPeers.isEmpty()) return
        val candidate = knownPeers.minOfOrNull { peer -> ackedThrough[peer] ?: 0L } ?: return
        val next = maxOf(_universalAckFlow.value, candidate)
        _universalAckFlow.value = next
        gcPendingDeltas(next)
    }

    private fun gcPendingDeltas(universalAck: Long) {
        pendingDeltas.keys.removeAll { it <= universalAck }
    }

    private fun onFullState(msg: ReplicatorMessage.FullState<S>) {
        _state.update { it.piece(msg.state) }
        recomputeDeliveredLocal()
    }

    /**
     * Absorbs a peer's gossiped delivered VV into the [frontiers] matrix clock and
     * recomputes the cut/frontier (the inbound knowledge can raise `F_live` and, via the
     * §4.4 release rule, discharge retained entries this peer now witnesses).
     */
    private fun onDelivered(sender: PeerId, msg: ReplicatorMessage.Delivered<S>) {
        frontiers[sender] = msg.vector
        recomputeCut()
    }

    private fun onResend(msg: ReplicatorMessage.Resend<S>) {
        if (msg.sender != replica) return
        val requesterPeer = PeerId(msg.requester.value)
        val allPresent = (msg.fromSeq..msg.toSeq).all { seq -> seq in pendingDeltas }
        if (!allPresent) {
            sendFullStateTo(requesterPeer)
            return
        }
        for (seq in msg.fromSeq..msg.toSeq) {
            val delta = pendingDeltas[seq] ?: continue
            broadcastDelta(seq, delta)
        }
    }

    private fun encode(msg: ReplicatorMessage<S>): ByteArray =
        binaryFormat.encodeToByteArray(messageSerializer, msg)

    private fun decode(bytes: ByteArray): ReplicatorMessage<S> =
        binaryFormat.decodeFromByteArray(messageSerializer, bytes)
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

private fun checkNotUnderTestDispatcher(scope: CoroutineScope, strict: Boolean, expectVirtualTime: Boolean) {
    if (expectVirtualTime) return
    val interceptor = scope.coroutineContext[ContinuationInterceptor]
    val className = interceptor?.let { it::class.qualifiedName ?: it::class.simpleName ?: "" } ?: ""
    val isTestDispatcher = "TestDispatcher" in className ||
        className.startsWith("kotlinx.coroutines.test.")
    if (!isTestDispatcher) return
    val msg = "SeamReplicator constructed under a TestDispatcher ($className). " +
        "The anti-entropy loop uses real-clock delay() — under virtual time those delays " +
        "never advance automatically and your test will deadlock silently. " +
        "Either use UnconfinedTestDispatcher (delays execute eagerly) or drive virtual time via " +
        "testScheduler.advanceTimeBy(…) if you must use StandardTestDispatcher."
    if (strict) error(msg) else logger.warn { msg }
}
