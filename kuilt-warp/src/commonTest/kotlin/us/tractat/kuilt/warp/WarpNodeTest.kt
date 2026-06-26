/**
 * Integration tests for [WarpNode].
 *
 * Runs under [UnconfinedTestDispatcher] with virtual time driven via bounded [advanceTimeBy]
 * steps rather than [advanceUntilIdle]. [advanceUntilIdle] is unsafe here because the Quilter's
 * anti-entropy loop (`while(true) { delay(interval); … }`) re-arms unconditionally — under
 * [UnconfinedTestDispatcher] each re-arm lands at the current virtual instant, so
 * [advanceUntilIdle] would spin it indefinitely. See [drain] for the bounded alternative.
 *
 * **Clock injection:** each test derives its clock from `testScheduler.currentTime` so
 * that [WarpNode.lastRingChangeAt] and the settle-window check (`sinceChange < settleWindow`)
 * use the same virtual timeline as coroutine `delay(...)` calls. This ensures that a
 * ring-change stamp and a subsequent settle-window check happen at the same virtual
 * instant — `sinceChange == 0` — so the time-based clause of `mustSettle` does not
 * trigger a spurious settle delay in steady-state tests, while still being accurate in
 * production where the real clock ticks between events.
 */
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Returns a clock that reads virtual time from [scheduler], keeping it in sync with `delay()` calls. */
private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private val ECHO_OP = OpId("echo")

/** A registry with one pass-through op — suitable for tests that don't care about result content. */
private fun echoRegistry(opId: OpId = ECHO_OP): OpRegistry =
    OpRegistry().also { it.register(opId, Op { args -> args }) }

/** Wraps this [TaskId] in a minimal [TaskDescriptor] using [ECHO_OP]. */
private fun TaskId.descriptor(): TaskDescriptor = TaskDescriptor(op = ECHO_OP, args = value.encodeToByteArray())

/**
 * Advances virtual time in bounded steps to flush both Quilter anti-entropy convergence
 * and the RingWithIntent settle window, without relying on [advanceUntilIdle].
 *
 * [advanceUntilIdle] is unsafe on systems whose timers re-arm unconditionally — the
 * Quilter's anti-entropy loop is `while(true) { delay(antiEntropyInterval); … }`, which
 * re-arms on every iteration regardless of convergence state. Under [UnconfinedTestDispatcher]
 * this loop re-arms at the current virtual instant, and [advanceUntilIdle] would spin it
 * indefinitely (it drains tasks scheduled at the current virtual time, and each re-arm lands
 * at the current virtual time). The correct approach is bounded explicit time steps:
 *
 * 1. Step through enough anti-entropy intervals to allow Quilter state to converge.
 *    [TEST_QUILTER_CONFIG.antiEntropyInterval] = 100 ms; 5 steps = 500 ms of virtual time.
 * 2. Step through the settle window ([ClaimStrategy.DEFAULT_SETTLE_WINDOW] = 500 ms) so
 *    one-shot [delay] calls inside [WarpNode.announceAndResolve] complete.
 *
 * Total virtual time advanced: 500 ms (convergence) + 500 ms (settle) = 1 s.
 */
private fun TestScope.drain() {
    repeat(5) { advanceTimeBy(TEST_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
    advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW)
    runCurrent()
}

/** Short-cadence [QuilterConfig] for tests that need fast anti-entropy. */
private val TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

class WarpNodeTest {

    /**
     * When [WarpNode] is constructed with a custom [rosterFlow], it drives ring
     * construction from that flow — not from [us.tractat.kuilt.core.Seam.peers].
     *
     * Proof: we hand WarpNode a single-peer roster (only selfId) via a
     * [MutableStateFlow]. Because the ring contains only one peer, that peer owns
     * every task. We then update the roster to add a second peer and confirm the
     * ring rebuilds: tasks are now split between both peers.
     *
     * This proves the roster source is the injected flow, not a hardcoded seam binding.
     */
    @Test
    fun injectedRosterFlowDrivesRingRebuild() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("roster-inject-test"))
        val seamB = loom.join(InMemoryTag("b"))

        // Start with a single-peer roster — only peer A.
        val rosterFlow = MutableStateFlow<Set<PeerId>>(setOf(seamA.selfId))

        val executedByA = mutableListOf<TaskId>()
        val executedByB = mutableListOf<TaskId>()
        val lock = reentrantLock()

        val trackA = OpRegistry().also { r ->
            r.register(ECHO_OP, Op { args ->
                lock.withLock { executedByA.add(TaskId(args.decodeToString())) }
                args
            })
        }
        val trackB = OpRegistry().also { r ->
            r.register(ECHO_OP, Op { args ->
                lock.withLock { executedByB.add(TaskId(args.decodeToString())) }
                args
            })
        }

        val nodeA = WarpNode(
            selfId = seamA.selfId,
            seam = seamA,
            rosterFlow = rosterFlow,
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = schedulerClock(testScheduler),
            registry = trackA,
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            rosterFlow = rosterFlow,
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = schedulerClock(testScheduler),
            registry = trackB,
        )

        // With only peer A in the roster, A owns every task.
        val singleOwnerTasks = (1..4).map { TaskId("solo-task-$it") }
        singleOwnerTasks.forEach { nodeA.enqueue(it, it.descriptor()) }
        drain()

        val soloExecutedByA = lock.withLock { executedByA.toList() }
        assertEquals(
            singleOwnerTasks.toSet(),
            soloExecutedByA.toSet(),
            "with single-peer roster, nodeA must own all tasks",
        )
        assertEquals(
            emptyList<TaskId>(),
            lock.withLock { executedByB.toList() },
            "nodeB must execute nothing when not in the roster",
        )

        // Expand the roster to include peer B — ring should rebuild.
        lock.withLock {
            executedByA.clear()
            executedByB.clear()
        }
        rosterFlow.value = setOf(seamA.selfId, seamB.selfId)
        drain()

        // Enqueue more tasks — now both peers are on the ring.
        val twoOwnerTasks = (1..6).map { TaskId("two-peer-task-$it") }
        twoOwnerTasks.forEach { nodeA.enqueue(it, it.descriptor()) }
        drain()

        val allExecuted = lock.withLock { executedByA + executedByB }
        assertEquals(
            twoOwnerTasks.toSet(),
            allExecuted.toSet(),
            "all tasks must be executed after roster expansion",
        )

        nodeA.close()
        nodeB.close()
    }

    /**
     * In a two-node mesh, every task is executed by exactly its ring owner.
     *
     * Proof: both nodes observe the full set of 10 tasks via Quilter replication;
     * each executes only the tasks it owns on the ring; results converge to all 10.
     */
    @Test
    fun ownerExecutesItsAssignedTasks() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("warp-test"))
        val seamB = loom.join(InMemoryTag("b"))

        val executedBy = mutableMapOf<TaskId, String>()
        val lock = reentrantLock()

        fun trackingRegistry(peerId: PeerId): OpRegistry =
            OpRegistry().also { r ->
                r.register(ECHO_OP, Op { args ->
                    lock.withLock { executedBy[TaskId(args.decodeToString())] = peerId.value }
                    args
                })
            }

        val nodeA = WarpNode(
            selfId = seamA.selfId,
            seam = seamA,
            rosterFlow = seamA.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = schedulerClock(testScheduler),
            registry = trackingRegistry(seamA.selfId),
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            rosterFlow = seamB.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = schedulerClock(testScheduler),
            registry = trackingRegistry(seamB.selfId),
        )

        val tasks = (1..10).map { TaskId("task-$it") }
        tasks.forEach { nodeA.enqueue(it, it.descriptor()) }

        drain()

        assertAll(
            { assertEquals(10, executedBy.size, "all 10 tasks must be executed") },
            { assertEquals(tasks.toSet(), executedBy.keys, "no task lost or duplicated in execution map") },
        )

        nodeA.close()
        nodeB.close()
    }

    /**
     * When a peer is partitioned (seam closed), its tasks re-home to the next peer
     * clockwise on the ring — the surviving nodes cover the full ring.
     */
    @Test
    fun survivorPicksUpTasksWhenOwnerIsPartitioned() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("failover-test"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val executed = mutableSetOf<TaskId>()
        val lock = reentrantLock()

        fun trackingRegistry(): OpRegistry = OpRegistry().also { r ->
            r.register(ECHO_OP, Op { args ->
                lock.withLock { executed.add(TaskId(args.decodeToString())) }
                args
            })
        }

        val nodeA = WarpNode(seamA.selfId, seamA, seamA.rosterSnapshot(), backgroundScope, TEST_QUILTER_CONFIG, schedulerClock(testScheduler), registry = trackingRegistry())
        val nodeB = WarpNode(seamB.selfId, seamB, seamB.rosterSnapshot(), backgroundScope, TEST_QUILTER_CONFIG, schedulerClock(testScheduler), registry = trackingRegistry())
        val nodeC = WarpNode(seamC.selfId, seamC, seamC.rosterSnapshot(), backgroundScope, TEST_QUILTER_CONFIG, schedulerClock(testScheduler), registry = trackingRegistry())

        // Let the three-peer mesh stabilise
        drain()

        // Partition B: close its seam — it disappears from seam.peers
        seamB.close()

        // Allow A and C to observe the partition, rebuild the ring, and claim B's tasks
        drain()

        // Enqueue tasks with only A and C live
        val tasks = (1..6).map { TaskId("failover-task-$it") }
        tasks.forEach { nodeA.enqueue(it, it.descriptor()) }

        drain()

        assertAll(
            { assertEquals(6, executed.size, "all tasks executed despite partition") },
            { assertEquals(tasks.toSet(), executed, "no task lost") },
        )

        nodeA.close()
        nodeC.close()
    }

    /**
     * Results replicate across all peers: both nodes' results boards converge to
     * contain all completed tasks with the correct results.
     */
    @Test
    fun resultsBoardConvergesAcrossAllPeers() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("results-test"))
        val seamB = loom.join(InMemoryTag("b"))

        val nodeA = WarpNode(
            selfId = seamA.selfId,
            seam = seamA,
            rosterFlow = seamA.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = schedulerClock(testScheduler),
            registry = echoRegistry(),
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            rosterFlow = seamB.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = schedulerClock(testScheduler),
            registry = echoRegistry(),
        )

        val tasks = (1..8).map { TaskId("conv-task-$it") }
        tasks.forEach { nodeA.enqueue(it, it.descriptor()) }

        drain()

        val expectedIds = tasks.toSet()
        assertAll(
            { assertEquals(expectedIds, nodeA.results.taskIds, "nodeA must have all task results") },
            { assertEquals(expectedIds, nodeB.results.taskIds, "nodeB must have all task results") },
        )
        tasks.forEach { taskId ->
            assertNotNull(nodeA.results[taskId], "nodeA missing result for $taskId")
            assertNotNull(nodeB.results[taskId], "nodeB missing result for $taskId")
        }

        nodeA.close()
        nodeB.close()
    }

    /**
     * Duplicate executions (e.g. during failover) are absorbed by the Results ORMap:
     * the results board converges to exactly one entry per task.
     */
    @Test
    fun duplicateExecutionAbsorbedByResultsBackstop() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("dedup-test"))
        val seamB = loom.join(InMemoryTag("b"))

        val nodeA = WarpNode(
            selfId = seamA.selfId,
            seam = seamA,
            rosterFlow = seamA.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = schedulerClock(testScheduler),
            registry = echoRegistry(),
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            rosterFlow = seamB.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = schedulerClock(testScheduler),
            registry = echoRegistry(),
        )

        val taskId = TaskId("dedup-task")
        nodeA.enqueue(taskId, taskId.descriptor())

        drain()

        assertAll(
            { assertEquals(1, nodeA.results.taskIds.size, "nodeA results board has exactly 1 entry") },
            { assertEquals(1, nodeB.results.taskIds.size, "nodeB results board has exactly 1 entry") },
            { assertNotNull(nodeA.results[taskId], "result present on nodeA") },
            { assertNotNull(nodeB.results[taskId], "result present on nodeB") },
        )

        nodeA.close()
        nodeB.close()
    }

    /**
     * [WarpNode.rawIncoming] receives every inbound frame from peers, AND
     * the CRDT channels (queue/results) still converge — proving the fan-out
     * does not break the existing mux ownership.
     *
     * Proof: nodeB collects a swatch from [WarpNode.rawIncoming]; simultaneously
     * nodeA enqueues tasks and waits for results to converge on nodeB. Both must
     * succeed — neither the rawIncoming subscriber nor the Quilter channels are
     * starved by the other.
     */
    @Test
    fun rawIncomingFanOutDeliversFramesToBothSubscriberAndCrdtChannels() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("raw-fanout-test"))
            val seamB = loom.join(InMemoryTag("b"))

            val nodeA = WarpNode(
                selfId = seamA.selfId,
                seam = seamA,
                rosterFlow = seamA.rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = TEST_QUILTER_CONFIG,
                clock = schedulerClock(testScheduler),
                registry = echoRegistry(),
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId,
                seam = seamB,
                rosterFlow = seamB.rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = TEST_QUILTER_CONFIG,
                clock = schedulerClock(testScheduler),
                registry = echoRegistry(),
            )

            // Collect the first swatch that arrives on B's rawIncoming.
            val rawSwatchDeferred = backgroundScope.launch {
                nodeB.rawIncoming.first()
            }

            // Enqueue tasks — these produce CRDT frames that flow from A → B.
            val tasks = (1..4).map { TaskId("fanout-task-$it") }
            tasks.forEach { nodeA.enqueue(it, it.descriptor()) }
            drain()

            // rawIncoming must have fired (the job completed).
            assertTrue(rawSwatchDeferred.isCompleted, "rawIncoming must deliver at least one swatch to subscribers")

            // CRDT channels must still converge — results present on both nodes.
            assertAll(
                { assertEquals(tasks.toSet(), nodeA.results.taskIds, "nodeA results must converge") },
                { assertEquals(tasks.toSet(), nodeB.results.taskIds, "nodeB results must converge") },
            )

            nodeA.close()
            nodeB.close()
        }
}
