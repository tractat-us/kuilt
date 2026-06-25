@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.warp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.raft.RaftNode
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.warp.WarpNode")

/**
 * Ties the warp foundation together over a real [Seam].
 *
 * Each peer in the session claims and executes the tasks it owns on the consistent-hash
 * ring — `ring.owner(task) == selfId`. Results land in a shared, replicated [Results]
 * board. The [Results] ORMap backstop absorbs any duplicate executions that arise during
 * failover: the last-writer-wins register picks one result per task, so the board always
 * converges to exactly one entry per task regardless of how many peers raced to execute it.
 *
 * **Roster source.** [WarpNode] drives the consistent-hash ring from [rosterFlow] — a
 * [Flow] of the live peer set. Two pluggable sources are provided:
 *
 * - [Seam.rosterSnapshot] — derives the roster from [Seam.peers]; cheap and eventually
 *   consistent. Preserves the pre-#826 behavior.
 * - [RaftNode.rosterSnapshot] — derives the roster from Raft's agreed [ClusterConfig];
 *   strongly consistent, minimising duplicate executions under stable membership.
 *
 * The roster source is **required** — absence would silently choose a consistency model
 * without the caller knowing. Pass the appropriate adapter for your use case.
 *
 * **Liveness-driven failover.** [WarpNode] maintains two failure-detection signals:
 *
 * 1. *Roster departure* — when a peer disappears from [rosterFlow], the ring is rebuilt
 *    immediately and pending tasks re-home to their new owner.
 * 2. *Heartbeat partition* — a [HeartbeatPartitionDetector] runs per admitted peer
 *    (peers in [rosterFlow] that are not [selfId]). When a peer becomes unresponsive
 *    (heartbeat timeout) or lost (reconnect window expired), it is added to
 *    [partitionedPeers] and excluded from the effective ring. On recovery it is removed.
 *    The effective ring is `rosterPeers - partitionedPeers`; tasks whose former owner
 *    is partitioned re-home to the next peer clockwise on the effective ring.
 *
 * The [Results] backstop ensures correctness under both signals: duplicate executions
 * during a failover window are absorbed, and the board converges to one entry per task.
 *
 * **Incoming fan-out.** [WarpNode] is the **sole collector** of [seam.incoming] (satisfying
 * the kuilt single-collection contract, ADR-034). Every received [Swatch] is fanned to
 * [rawIncoming] before the mux channels consume it. The internal [MuxSeam] subscribes to
 * [rawIncoming] rather than [seam.incoming] directly. Per-peer [HeartbeatPartitionDetector]s
 * subscribe to [rawIncoming] filtered by sender via [PerPeerSeam] — no second collection
 * of [seam.incoming] is ever needed.
 *
 * **Thread-safety.** Shared mutable state (`ring`, `claimed`, `partitionedPeers`,
 * `detectorJobs`, `rosterPeers`) is guarded by an explicit
 * [kotlinx.atomicfu.locks.ReentrantLock] so this type is safe under a multi-threaded
 * dispatcher. No `limitedParallelism(1)` confinement is used — see CLAUDE.md.
 *
 * **Injection contract.** [scope], [rosterFlow], and [clock] are required parameters.
 * Pass [kotlinx.coroutines.test.TestScope.backgroundScope] in tests, and a fixed
 * [Instant]-returning lambda for the clock.
 *
 * @param selfId This peer's identifier on the [seam].
 * @param seam The multi-peer session. [WarpNode] takes sole ownership of [Seam.incoming]
 *   by collecting it once and fanning every frame to [rawIncoming].
 * @param rosterFlow A [Flow] of the current live peer set, used to rebuild the hash ring
 *   on membership change. Use [Seam.rosterSnapshot] for eventual consistency or
 *   [RaftNode.rosterSnapshot] for strong consistency backed by Raft membership.
 * @param scope Coroutine scope for background collection jobs. Required — no default.
 * @param quilterConfig [QuilterConfig] for both internal [Quilter]s. Defaults to the
 *   [QuilterConfig] production defaults. Pass a short-cadence config in tests that need
 *   fast anti-entropy.
 * @param clock Provides the current [Instant] for per-peer [HeartbeatPartitionDetector]s.
 *   Required — never [kotlin.time.Clock.System] by default. Production callers use
 *   `{ Clock.System.now() }`; tests inject a fixed or virtual clock so liveness timeouts
 *   are deterministic.
 * @param heartbeatConfig Timing for per-peer heartbeat ping/pong. A tuning parameter —
 *   defaults to [HeartbeatConfig] production defaults (5 s interval, 15 s timeout,
 *   60 s reconnect window). Tests typically inject a short-cadence config.
 * @param strategy How owned tasks are claimed. [ClaimStrategy.Ring] is pure consistent-hash
 *   assignment; [ClaimStrategy.RingWithIntent] adds the intent-register safety net. Defaults
 *   to [ClaimStrategy.RingWithIntent].
 * @param executor Suspending function that performs the work for a given task and returns
 *   a string result. The body is called at most once per task per peer (re-entry after
 *   failover is possible; the [Results] backstop deduplicates).
 */
public class WarpNode(
    public val selfId: PeerId,
    private val seam: Seam,
    rosterFlow: Flow<Set<PeerId>>,
    private val scope: CoroutineScope,
    quilterConfig: QuilterConfig = QuilterConfig(),
    private val clock: () -> Instant,
    private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    private val strategy: ClaimStrategy = ClaimStrategy.RingWithIntent(),
    private val executor: suspend (TaskId) -> String,
) {
    private val replica = ReplicaId(selfId.value)

    /**
     * Broadcast bus for every raw inbound [Swatch] received on [seam].
     *
     * [WarpNode] is the **single collector** of [seam.incoming] (ADR-034). The init
     * block launches one coroutine that collects [seam.incoming] and emits each [Swatch]
     * here. All downstream consumers — the internal [MuxSeam] channels and per-peer
     * [HeartbeatPartitionDetector]s — subscribe to this flow rather than to
     * [seam.incoming] directly.
     *
     * Capacity 256 absorbs burst traffic before subscribers are scheduled. Frames emitted
     * before a subscriber starts are not replayed — for heartbeat liveness this is
     * acceptable; the next heartbeat cycle catches up.
     */
    internal val rawIncoming = MutableSharedFlow<Swatch>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // MuxSeam consumes a proxy seam whose incoming is rawIncoming, not seam.incoming
    // directly. This keeps seam.incoming single-collection: only the init fan-out loop
    // below ever collects it.
    private val mux = MuxSeam(RawIncomingProxy(seam, rawIncoming.asSharedFlow()), scope)
    private val queueSeam = mux.channel(CHANNEL_QUEUE)
    private val resultsSeam = mux.channel(CHANNEL_RESULTS)

    /** Quilter replicating the set of pending task IDs. */
    private val queueQuilter: Quilter<ORSet<TaskId>> = Quilter(
        replica = replica,
        seam = queueSeam,
        initial = ORSet.empty(),
        messageSerializer = QuiltMessage.serializer(ORSet.serializer(serializer<TaskId>())),
        scope = scope,
        config = quilterConfig,
        random = kotlin.random.Random(selfId.value.hashCode().toLong()),
    )

    /** Quilter replicating the results board. */
    private val resultsQuilter: Quilter<ORMap<TaskId, LWWRegister<String>>> = Quilter(
        replica = replica,
        seam = resultsSeam,
        initial = ORMap.empty(),
        messageSerializer = QuiltMessage.serializer(
            ORMap.serializer(serializer<TaskId>(), LWWRegister.serializer(serializer<String>()))
        ),
        scope = scope,
        config = quilterConfig,
        random = kotlin.random.Random(selfId.value.hashCode().toLong() xor 0x5555L),
    )

    private val intentSeam = mux.channel(CHANNEL_INTENT)

    /** Quilter replicating per-task claimant sets — the intent register. */
    private val intentQuilter: Quilter<ORMap<TaskId, GSet<PeerId>>> = Quilter(
        replica = replica,
        seam = intentSeam,
        initial = ORMap.empty(),
        messageSerializer = QuiltMessage.serializer(
            ORMap.serializer(serializer<TaskId>(), GSet.serializer(serializer<PeerId>()))
        ),
        scope = scope,
        config = quilterConfig,
        random = kotlin.random.Random(selfId.value.hashCode().toLong() xor 0xAAAAL),
    )

    // --- Shared mutable state (guarded by lock) ---
    private val lock = reentrantLock()

    /** Current consistent-hash ring, rebuilt whenever the effective roster changes. */
    private var ring: TaskRing = TaskRing(setOf(selfId))

    /** Wall-clock instant of the last effective-ring change. Guarded by [lock]. */
    private var lastRingChangeAt: Instant = Instant.fromEpochMilliseconds(0L)

    /** Task IDs we have already started executing; prevents double-execution on this node. */
    private val claimed = mutableSetOf<TaskId>()

    /** Peers currently known from [rosterFlow]. Guarded by [lock]. */
    private var rosterPeers: Set<PeerId> = emptySet()

    /**
     * Peers detected as unresponsive or lost by their [HeartbeatPartitionDetector].
     *
     * The effective ring = `rosterPeers - partitionedPeers`. When a peer is added here
     * (on [PartitionEvent.PeerUnresponsive] or [PartitionEvent.PeerLost]), the ring is
     * rebuilt and [claimOwned] re-evaluates ownership so the partitioned peer's tasks
     * re-home to the successor. Removed on [PartitionEvent.PeerRecovered].
     *
     * Guarded by [lock].
     */
    private val partitionedPeers = mutableSetOf<PeerId>()

    /**
     * Per-peer umbrella [Job]s, keyed by [PeerId].
     *
     * Each entry is the [Job] returned by the top-level `scope.launch { }` that owns ALL
     * three coroutines belonging to a detector:
     * - the [HeartbeatPartitionDetector]'s ping loop (started via `detector.start(this)`)
     * - the [HeartbeatPartitionDetector]'s incoming-collection loop (ditto)
     * - the events-collector coroutine (`detector.events.collect { … }`)
     *
     * Because `detector.start(this)` is passed the same [CoroutineScope] as the outer
     * `launch` body, the two detector coroutines become structured children of the umbrella
     * Job. Calling `cancel()` on the umbrella Job atomically tears down all three.
     *
     * Storing only the events-collector Job was the previous (buggy) approach — it left
     * the detector's ping and incoming loops running as siblings in [scope] forever.
     *
     * Guarded by [lock].
     */
    private val detectorJobs = mutableMapOf<PeerId, Job>()

    // Monotonic timestamp counter for LWWRegister tags.
    private var timestampCounter = 0L

    // ---------------------------------------------------------------------------
    // Lifecycle — fan-out loop, roster, and queue changes
    // ---------------------------------------------------------------------------

    init {
        // Single collector of seam.incoming — fans every frame to rawIncoming.
        // All downstream consumers (MuxSeam channels, per-peer detectors)
        // subscribe to rawIncoming; nobody else collects seam.incoming.
        scope.launch {
            seam.incoming.collect { swatch ->
                rawIncoming.emit(swatch)
            }
        }

        // Rebuild the ring whenever the roster changes, then re-evaluate ownership.
        rosterFlow
            .onEach { peers -> onPeersChanged(peers) }
            .launchIn(scope)

        // Claim newly-owned tasks whenever the pending set changes.
        queueQuilter.state
            .onEach { pendingSet -> claimOwned(pendingSet) }
            .launchIn(scope)
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Add [taskId] to the distributed work queue.
     *
     * The add is replicated immediately to all current peers via the Quilter.
     * Idempotent at the ORSet level: a concurrent add of the same ID on another
     * peer survives merge (add-wins).
     */
    public fun enqueue(taskId: TaskId) {
        queueQuilter.apply(Patch(queueQuilter.state.value.add(replica, taskId)))
    }

    /**
     * A snapshot of the current results board, as seen by this peer.
     *
     * The board is eventually consistent: it reflects all results this peer has
     * received from the replication layer so far. All peers converge to the same
     * board once the network is quiescent.
     */
    public val results: Results<TaskId, String>
        get() = Results.from(resultsQuilter.state.value)

    /**
     * Close this node's Quilter connections and stop all detectors. Idempotent.
     */
    public fun close() {
        val jobs = lock.withLock {
            val snapshot = detectorJobs.values.toList()
            detectorJobs.clear()
            snapshot
        }
        jobs.forEach { it.cancel() }
        queueQuilter.close()
        resultsQuilter.close()
        intentQuilter.close()
    }

    // ---------------------------------------------------------------------------
    // Internal — ring management and task claiming
    // ---------------------------------------------------------------------------

    private fun onPeersChanged(newRoster: Set<PeerId>) {
        val (added, removed) = lock.withLock {
            val prev = rosterPeers
            rosterPeers = newRoster
            val added = newRoster - prev - selfId
            val removed = prev - newRoster
            added to removed
        }

        // Cancel the umbrella Job for each departed peer. Cancelling the Job tears down
        // the detector's ping loop, its incoming-collection loop, and the events-collector
        // coroutine together — no coroutines leak into the parent scope.
        removed.forEach { peerId ->
            val job = lock.withLock {
                partitionedPeers.remove(peerId)
                detectorJobs.remove(peerId)
            }
            job?.cancel()
        }

        // Start detectors for newly-appeared peers.
        added.forEach { peerId -> startDetector(peerId) }

        rebuildRingAndClaim()
    }

    /**
     * Starts a [HeartbeatPartitionDetector] for [peerId] inside a dedicated umbrella coroutine.
     *
     * The detector receives a [PerPeerSeam] — a thin adapter that filters [rawIncoming]
     * to frames from [peerId] only and delegates sends to the underlying [seam]. This
     * lets the detector subscribe to per-peer ping/pong traffic without competing for
     * the single-consumer [seam.incoming] channel (which the init fan-out loop holds).
     *
     * `scope.launch { }` creates an umbrella [Job] that is a structured child of [scope].
     * `detector.start(this)` passes the umbrella coroutine's [CoroutineScope] to the
     * detector, so both the ping loop and the incoming-collection loop are launched as
     * children of the umbrella Job. The events-collector (`detector.events.collect { }`)
     * runs inside the same umbrella body. Cancelling the umbrella Job (via [detectorJobs])
     * atomically tears down all three coroutines. The umbrella Job is stored in [detectorJobs].
     */
    private fun startDetector(peerId: PeerId) {
        val perPeerSeam = PerPeerSeam(seam, peerId, rawIncoming)
        val detector = HeartbeatPartitionDetector(
            link = perPeerSeam,
            peerId = peerId,
            config = heartbeatConfig,
            clock = clock,
        )
        // The umbrella launch body is `this` CoroutineScope.
        // `detector.start(this)` makes the ping loop and incoming-collection loop children
        // of this coroutine so they are cancelled when this Job is cancelled.
        val umbrellaJob = scope.launch {
            detector.start(this)
            detector.events.collect { event -> handlePartitionEvent(event) }
        }
        lock.withLock { detectorJobs[peerId] = umbrellaJob }
    }

    private fun handlePartitionEvent(event: PartitionEvent) {
        when (event) {
            is PartitionEvent.PeerUnresponsive -> markPartitioned(event.peerId)
            is PartitionEvent.PeerLost -> markPartitioned(event.peerId)
            is PartitionEvent.PeerRecovered -> markRecovered(event.peerId)
        }
    }

    private fun markPartitioned(peerId: PeerId) {
        lock.withLock { partitionedPeers.add(peerId) }
        logger.info { "WarpNode($selfId): peer $peerId partitioned — excluding from ring" }
        rebuildRingAndClaim()
    }

    private fun markRecovered(peerId: PeerId) {
        lock.withLock { partitionedPeers.remove(peerId) }
        logger.info { "WarpNode($selfId): peer $peerId recovered — restoring to ring" }
        rebuildRingAndClaim()
    }

    /**
     * Recomputes the effective ring from `rosterPeers - partitionedPeers` and re-evaluates
     * task ownership. Tasks whose former owner is now absent or partitioned re-home to
     * their new ring successor.
     */
    private fun rebuildRingAndClaim() {
        val effectiveRoster = lock.withLock { rosterPeers - partitionedPeers }
        val newRing = RosterSnapshot(effectiveRoster).toTaskRing()
        lock.withLock {
            ring = newRing
            lastRingChangeAt = clock()
        }
        claimOwned(queueQuilter.state.value)
    }

    private fun claimOwned(pendingSet: ORSet<TaskId>) {
        when (val s = strategy) {
            is ClaimStrategy.Ring -> claimOwnedRing(pendingSet)
            is ClaimStrategy.RingWithIntent -> claimOwnedWithIntent(pendingSet, s)
        }
    }

    /** Today's behavior: execute every owned, unclaimed task immediately. */
    private fun claimOwnedRing(pendingSet: ORSet<TaskId>) {
        val toExecute = lock.withLock {
            pendingSet.elements
                .filter { taskId -> taskId !in claimed && ring.owner(taskId) == selfId }
                .also { tasks -> claimed.addAll(tasks) }
        }
        toExecute.forEach { taskId -> executeAsync(taskId) }
    }

    private fun claimOwnedWithIntent(pendingSet: ORSet<TaskId>, strategy: ClaimStrategy.RingWithIntent) {
        // Owned, not-yet-claimed tasks under this peer's current ring view.
        val owned = lock.withLock {
            pendingSet.elements.filter { taskId -> taskId !in claimed && ring.owner(taskId) == selfId }
        }
        owned.forEach { taskId -> announceAndResolve(taskId, strategy) }
    }

    /**
     * Announce this peer's claim to [taskId], then resolve the winner. Executes immediately
     * when no competitors are seen in the current local intent state; waits
     * [ClaimStrategy.RingWithIntent.settleWindow] when a competing claim is already present
     * so the converged claimant set can determine the winner deterministically.
     */
    private fun announceAndResolve(taskId: TaskId, strategy: ClaimStrategy.RingWithIntent) {
        // Announce (free): union selfId into the claimant set. ORMap.put is additive.
        lock.withLock {
            intentQuilter.apply(Patch(intentQuilter.state.value.put(replica, taskId, GSet.of(selfId))))
        }

        val mustSettle = lock.withLock {
            (intentQuilter.state.value[taskId]?.elements ?: emptySet()).any { it != selfId }
        }

        scope.launch {
            if (mustSettle) delay(strategy.settleWindow) // suspend OUTSIDE the lock
            val execute = lock.withLock {
                if (taskId in claimed) return@withLock false
                if (winner(taskId) == selfId) {
                    claimed.add(taskId)
                    true
                } else {
                    false // stand down — stay eligible to re-home later
                }
            }
            if (execute) doExecute(taskId)
        }
    }

    /**
     * The winner of [taskId]: the lowest-`PeerId` claimant that is still in the effective
     * roster. Every peer computes the same value from the converged claimant set, so losers
     * deterministically stand down. Returns `null` if no live claimant remains.
     */
    private fun winner(taskId: TaskId): PeerId? {
        // Caller holds [lock].
        val claimants = intentQuilter.state.value[taskId]?.elements ?: emptySet()
        val effectiveRoster = rosterPeers - partitionedPeers
        return claimants.intersect(effectiveRoster).minByOrNull { it.value }
    }

    private fun executeAsync(taskId: TaskId) {
        scope.launch {
            runCatchingCancellable { doExecute(taskId) }
                .onFailure { e ->
                    logger.warn(e) { "WarpNode($selfId): executor failed for $taskId — unclaiming" }
                    lock.withLock { claimed.remove(taskId) }
                }
        }
    }

    private suspend fun doExecute(taskId: TaskId) {
        val result = executor(taskId)
        recordResult(taskId, result)
        removeFromQueue(taskId)
    }

    private fun recordResult(taskId: TaskId, result: String) {
        // Hold the WarpNode lock across the read-compute-apply so every concurrent
        // executor coroutine mints a *unique* dot from the latest state. Without this,
        // two concurrent `put` calls on the same base state each call `nextDot(replica)`,
        // producing duplicate dots. When those patches are joined, the causal CRDT
        // tombstoning logic silently drops one entry (the entry whose dot the other's
        // context already witnesses). Bug: concurrent `recordResult` calls were losing
        // results non-deterministically.
        lock.withLock {
            val ts = ++timestampCounter
            resultsQuilter.apply(
                Patch(
                    resultsQuilter.state.value.put(
                        replica = replica,
                        key = taskId,
                        value = LWWRegister.empty<String>().set(replica, ts, result),
                    )
                )
            )
        }
    }

    private fun removeFromQueue(taskId: TaskId) {
        // Hold the WarpNode lock so concurrent `remove` calls always operate on the
        // latest state. Without this, two concurrent removes on the same base state
        // would each produce a `remove` delta, both of which are correct (idempotent),
        // but the key invariant below is that the state read happens atomically with
        // the apply so the delta is always a subset of the current state.
        lock.withLock {
            queueQuilter.apply(Patch(queueQuilter.state.value.remove(taskId)))
            intentQuilter.apply(Patch(intentQuilter.state.value.remove(taskId)))
        }
    }

    private companion object {
        const val CHANNEL_QUEUE: Byte = 0x01
        const val CHANNEL_RESULTS: Byte = 0x02

        /** Mux-channel tag reserved for per-peer heartbeat ping/pong frames. */
        @Suppress("unused")
        const val CHANNEL_HEARTBEAT: Byte = 0x03

        const val CHANNEL_INTENT: Byte = 0x04
    }
}

/**
 * A thin [Seam] adapter that replaces [incoming] with a caller-supplied [SharedFlow].
 *
 * Used by [WarpNode] to satisfy the single-collection contract (ADR-034): [WarpNode]
 * collects [delegate.incoming] exactly once and fans every [Swatch] to [rawIncoming];
 * [MuxSeam] then subscribes to [rawIncoming] via this proxy, never touching
 * [delegate.incoming] a second time.
 *
 * All other [Seam] members delegate to [delegate] unchanged.
 */
private class RawIncomingProxy(
    private val delegate: Seam,
    override val incoming: Flow<Swatch>,
) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state
    override suspend fun broadcast(payload: ByteArray) = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = delegate.sendTo(peer, payload)
    override suspend fun close(reason: CloseReason) = delegate.close(reason)
}

/**
 * A thin [Seam] view that presents only frames from [targetPeerId] via [rawIncoming].
 *
 * [HeartbeatPartitionDetector] subscribes to [incoming] to process pings/pongs for a
 * specific peer. Since [Seam.incoming] is a channel-backed flow (single-consumer), we
 * cannot let every detector collect it directly. Instead, [WarpNode]'s init block fans
 * each inbound swatch to [rawIncoming] (a [MutableSharedFlow]) and each [PerPeerSeam]
 * filters to its assigned [targetPeerId].
 *
 * [broadcast] and [sendTo] delegate to [delegate] unchanged — heartbeat pings/pongs are
 * sent as raw frames on the underlying seam, bypassing [MuxSeam] channel wrapping. This
 * matches how [us.tractat.kuilt.session.SeamRoom] wires its per-peer detectors.
 *
 * [close] is a no-op — the [PerPeerSeam] does not own the link lifecycle.
 */
private class PerPeerSeam(
    private val delegate: Seam,
    private val targetPeerId: PeerId,
    private val rawIncoming: MutableSharedFlow<Swatch>,
) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state

    override val incoming: Flow<Swatch>
        get() = rawIncoming.filter { it.sender == targetPeerId }

    override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = delegate.sendTo(peer, payload)

    /** No-op — lifecycle is owned by [WarpNode], not this view. */
    override suspend fun close(reason: CloseReason) = Unit
}

// ---------------------------------------------------------------------------
// Roster source adapters
// ---------------------------------------------------------------------------

/**
 * Returns a [Flow] of the current peer set derived from [Seam.peers], suitable for
 * passing to [WarpNode] as its [rosterFlow].
 *
 * This is the **eventual** roster source: the ring tracks who is currently connected
 * to the seam. Under hub-spoke / churn the seam's peer view differs between nodes,
 * introducing some ring disagreement and duplicate executions (backstopped by the
 * Results ORMap). Use [RaftNode.rosterSnapshot] for a strongly-consistent alternative.
 */
public fun Seam.rosterSnapshot(): Flow<Set<PeerId>> = peers

/**
 * Returns a [Flow] of voter [PeerId]s derived from this [RaftNode]'s current
 * [us.tractat.kuilt.raft.ClusterConfig], suitable for passing to [WarpNode] as its [rosterFlow].
 *
 * This is the **strong** roster source: the ring tracks the agreed Raft voter set.
 * Under stable membership the ring is identical on every peer, eliminating duplicate
 * executions from ring disagreement. Use [Seam.rosterSnapshot] for a cheaper,
 * eventually-consistent alternative when you don't already have a Raft cluster.
 *
 * The emitted [PeerId] values are derived from [us.tractat.kuilt.raft.NodeId.value] by
 * wrapping each in a [PeerId] — callers must ensure the [WarpNode.selfId] passed to
 * [WarpNode] matches the corresponding voter's [us.tractat.kuilt.raft.NodeId.value].
 */
public fun RaftNode.rosterSnapshot(): Flow<Set<PeerId>> =
    membership.map { config -> config.voters.mapTo(mutableSetOf()) { PeerId(it.value) } }
