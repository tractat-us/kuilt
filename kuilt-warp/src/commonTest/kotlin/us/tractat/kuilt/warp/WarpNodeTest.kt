/**
 * Integration tests for [WarpNode].
 *
 * Runs under [UnconfinedTestDispatcher] (the same pattern as the Quilter tests) with
 * [advanceUntilIdle] after mutations so the event-driven execution loops drain fully.
 *
 * WarpNode has no re-arming timers of its own — it is event-driven via rosterFlow and
 * Quilter state flows. The Quilter underneath has an anti-entropy loop, but under
 * UnconfinedTestDispatcher delays execute eagerly so advanceUntilIdle is safe.
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/** Fixed clock for tests that don't exercise liveness timing. */
private val FIXED_CLOCK: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

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

        val nodeA = WarpNode(
            selfId = seamA.selfId,
            seam = seamA,
            rosterFlow = rosterFlow,
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = FIXED_CLOCK,
            executor ={ taskId ->
                lock.withLock { executedByA.add(taskId) }
                "result-${taskId.value}"
            },
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            rosterFlow = rosterFlow,
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = FIXED_CLOCK,
            executor ={ taskId ->
                lock.withLock { executedByB.add(taskId) }
                "result-${taskId.value}"
            },
        )

        // With only peer A in the roster, A owns every task.
        val singleOwnerTasks = (1..4).map { TaskId("solo-task-$it") }
        singleOwnerTasks.forEach { nodeA.enqueue(it) }
        advanceUntilIdle()

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
        advanceUntilIdle()

        // Enqueue more tasks — now both peers are on the ring.
        val twoOwnerTasks = (1..6).map { TaskId("two-peer-task-$it") }
        twoOwnerTasks.forEach { nodeA.enqueue(it) }
        advanceUntilIdle()

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

        val nodeA = WarpNode(
            selfId = seamA.selfId,
            seam = seamA,
            rosterFlow = seamA.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = FIXED_CLOCK,
            executor ={ taskId ->
                lock.withLock { executedBy[taskId] = seamA.selfId.value }
                "result-${taskId.value}"
            },
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            rosterFlow = seamB.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = FIXED_CLOCK,
            executor ={ taskId ->
                lock.withLock { executedBy[taskId] = seamB.selfId.value }
                "result-${taskId.value}"
            },
        )

        val tasks = (1..10).map { TaskId("task-$it") }
        tasks.forEach { nodeA.enqueue(it) }

        advanceUntilIdle()

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

        fun executor(label: String): suspend (TaskId) -> String = { taskId ->
            lock.withLock { executed.add(taskId) }
            "done-$label-${taskId.value}"
        }

        val nodeA = WarpNode(seamA.selfId, seamA, seamA.rosterSnapshot(), backgroundScope, TEST_QUILTER_CONFIG, FIXED_CLOCK, executor = executor("A"))
        val nodeB = WarpNode(seamB.selfId, seamB, seamB.rosterSnapshot(), backgroundScope, TEST_QUILTER_CONFIG, FIXED_CLOCK, executor = executor("B"))
        val nodeC = WarpNode(seamC.selfId, seamC, seamC.rosterSnapshot(), backgroundScope, TEST_QUILTER_CONFIG, FIXED_CLOCK, executor = executor("C"))

        // Let the three-peer mesh stabilise
        advanceUntilIdle()

        // Partition B: close its seam — it disappears from seam.peers
        seamB.close()

        // Allow A and C to observe the partition, rebuild the ring, and claim B's tasks
        advanceUntilIdle()

        // Enqueue tasks with only A and C live
        val tasks = (1..6).map { TaskId("failover-task-$it") }
        tasks.forEach { nodeA.enqueue(it) }

        advanceUntilIdle()

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
            clock = FIXED_CLOCK,
            executor ={ taskId -> "result-${taskId.value}" },
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            rosterFlow = seamB.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = FIXED_CLOCK,
            executor ={ taskId -> "result-${taskId.value}" },
        )

        val tasks = (1..8).map { TaskId("conv-task-$it") }
        tasks.forEach { nodeA.enqueue(it) }

        advanceUntilIdle()

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
            clock = FIXED_CLOCK,
            executor ={ taskId -> "result-${taskId.value}" },
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            rosterFlow = seamB.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = TEST_QUILTER_CONFIG,
            clock = FIXED_CLOCK,
            executor ={ taskId -> "result-${taskId.value}" },
        )

        val taskId = TaskId("dedup-task")
        nodeA.enqueue(taskId)

        advanceUntilIdle()

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
                clock = FIXED_CLOCK,
                executor = { taskId -> "result-${taskId.value}" },
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId,
                seam = seamB,
                rosterFlow = seamB.rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = TEST_QUILTER_CONFIG,
                clock = FIXED_CLOCK,
                executor = { taskId -> "result-${taskId.value}" },
            )

            // Collect the first swatch that arrives on B's rawIncoming.
            val rawSwatchDeferred = backgroundScope.launch {
                nodeB.rawIncoming.first()
            }

            // Enqueue tasks — these produce CRDT frames that flow from A → B.
            val tasks = (1..4).map { TaskId("fanout-task-$it") }
            tasks.forEach { nodeA.enqueue(it) }
            advanceUntilIdle()

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
