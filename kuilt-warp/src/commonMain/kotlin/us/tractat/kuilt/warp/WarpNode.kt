@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.warp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.serializer
import kotlin.time.Duration.Companion.seconds
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.quilter.Quilter

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
 * **Liveness-driven failover.** [WarpNode] watches [Seam.peers]. When a peer disappears,
 * every surviving peer rebuilds the ring and re-evaluates pending tasks. Tasks whose
 * former owner is now absent are claimed by their new ring owner (the next peer clockwise
 * that is still present). The [Results] backstop ensures correctness even if the former
 * owner had partially finished and recorded a result before disappearing.
 *
 * **Thread-safety.** Shared mutable state (`ring`, `claimed`) is guarded by an explicit
 * [kotlinx.atomicfu.locks.ReentrantLock] so this type is safe under a multi-threaded
 * dispatcher. No `limitedParallelism(1)` confinement is used — see CLAUDE.md.
 *
 * **Injection contract.** [scope] is a required parameter — no real-dispatcher default.
 * Pass [kotlinx.coroutines.test.TestScope.backgroundScope] in tests.
 *
 * @param selfId This peer's identifier on the [seam].
 * @param seam The multi-peer session. [WarpNode] takes sole ownership of [Seam.incoming]
 *   via an internal [MuxSeam].
 * @param scope Coroutine scope for background collection jobs. Required — no default.
 * @param executor Suspending function that performs the work for a given task and returns
 *   a string result. The body is called at most once per task per peer (re-entry after
 *   failover is possible; the [Results] backstop deduplicates).
 */
public class WarpNode(
    public val selfId: PeerId,
    seam: Seam,
    private val scope: CoroutineScope,
    private val executor: suspend (TaskId) -> String,
) {
    private val replica = ReplicaId(selfId.value)

    // MuxSeam splits the single seam.incoming across our two Quilters.
    // Channel tags are module-private constants; heartbeat channel reserved for future use.
    private val mux = MuxSeam(seam, scope)
    private val queueSeam = mux.channel(CHANNEL_QUEUE)
    private val resultsSeam = mux.channel(CHANNEL_RESULTS)

    /** Quilter replicating the set of pending task IDs. */
    private val queueQuilter: Quilter<ORSet<TaskId>> = Quilter(
        replica = replica,
        seam = queueSeam,
        initial = ORSet.empty(),
        messageSerializer = QuiltMessage.serializer(ORSet.serializer(serializer<TaskId>())),
        scope = scope,
        config = QUILTER_CONFIG,
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
        config = QUILTER_CONFIG,
        random = kotlin.random.Random(selfId.value.hashCode().toLong() xor 0x5555L),
    )

    // --- Shared mutable state (guarded by lock) ---
    private val lock = reentrantLock()

    /** Current consistent-hash ring, rebuilt whenever [seam.peers] changes. */
    private var ring: TaskRing = TaskRing(setOf(selfId))

    /** Task IDs we have already started executing; prevents double-execution on this node. */
    private val claimed = mutableSetOf<TaskId>()

    // Monotonic timestamp counter for LWWRegister tags.
    private var timestampCounter = 0L

    // ---------------------------------------------------------------------------
    // Lifecycle — react to roster and queue changes
    // ---------------------------------------------------------------------------

    init {
        // Rebuild the ring whenever the peer set changes, then re-evaluate ownership.
        seam.peers
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
     * Close this node's Quilter connections. Idempotent.
     */
    public fun close() {
        queueQuilter.close()
        resultsQuilter.close()
    }

    // ---------------------------------------------------------------------------
    // Internal — ring management and task claiming
    // ---------------------------------------------------------------------------

    private fun onPeersChanged(peers: Set<PeerId>) {
        val newRing = RosterSnapshot(peers).toTaskRing()
        lock.withLock { ring = newRing }
        // Re-evaluate the current pending set in case newly-owned tasks appeared
        // (e.g. former owner departed and its tasks re-home to us).
        claimOwned(queueQuilter.state.value)
    }

    private fun claimOwned(pendingSet: ORSet<TaskId>) {
        val toExecute = lock.withLock {
            pendingSet.elements
                .filter { taskId -> taskId !in claimed && ring.owner(taskId) == selfId }
                .also { tasks -> claimed.addAll(tasks) }
        }
        toExecute.forEach { taskId -> executeAsync(taskId) }
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
        }
    }

    private companion object {
        const val CHANNEL_QUEUE: Byte = 0x01
        const val CHANNEL_RESULTS: Byte = 0x02

        /**
         * Quilter config for real-IO contexts (production or real-socket tests).
         * Anti-entropy and full-state retry are kept short so a missed delta is
         * healed within seconds rather than waiting the production-default 1 min / 30 s.
         *
         * `expectVirtualTime = true` suppresses the TestDispatcher warning — callers
         * choose their own dispatcher.
         */
        val QUILTER_CONFIG = QuilterConfig(
            antiEntropyInterval = 2.seconds,
            fullStateRetryInterval = 3.seconds,
            expectVirtualTime = true,
        )
    }
}
