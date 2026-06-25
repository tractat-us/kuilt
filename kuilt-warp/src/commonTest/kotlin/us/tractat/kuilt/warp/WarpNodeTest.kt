/**
 * Integration tests for [WarpNode].
 *
 * Runs under [UnconfinedTestDispatcher] (the same pattern as the Quilter tests) with
 * [advanceUntilIdle] after mutations so the event-driven execution loops drain fully.
 *
 * WarpNode has no re-arming timers of its own — it is event-driven via seam.peers and
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class WarpNodeTest {

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
            scope = backgroundScope,
            executor = { taskId ->
                lock.withLock { executedBy[taskId] = seamA.selfId.value }
                "result-${taskId.value}"
            },
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            scope = backgroundScope,
            executor = { taskId ->
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

        val nodeA = WarpNode(seamA.selfId, seamA, backgroundScope, executor("A"))
        val nodeB = WarpNode(seamB.selfId, seamB, backgroundScope, executor("B"))
        val nodeC = WarpNode(seamC.selfId, seamC, backgroundScope, executor("C"))

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
            scope = backgroundScope,
            executor = { taskId -> "result-${taskId.value}" },
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            scope = backgroundScope,
            executor = { taskId -> "result-${taskId.value}" },
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
            scope = backgroundScope,
            executor = { taskId -> "result-${taskId.value}" },
        )
        val nodeB = WarpNode(
            selfId = seamB.selfId,
            seam = seamB,
            scope = backgroundScope,
            executor = { taskId -> "result-${taskId.value}" },
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
}
