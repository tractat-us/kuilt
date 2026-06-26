/**
 * Sim tests for coordination-tagged task routing in [WarpNode].
 *
 * Verifies that:
 * - A task enqueued via [WarpNode.enqueue] with a [TaskDescriptor] is dispatched through the
 *   local [OpRegistry] (the free path).
 * - A task enqueued as [CoordinationKind.Coordinated] is dispatched to the coordinated executor.
 * - The free path is byte-for-byte unchanged: results, routing, and deduplication are identical
 *   to a node that never enqueues a coordinated task.
 *
 * Uses the same [drain] / [TEST_QUILTER_CONFIG] / [schedulerClock] ceremony as [WarpNodeTest].
 */
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.drain() {
    repeat(5) { advanceTimeBy(COORD_TEST_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
    advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW)
    runCurrent()
}

private val COORD_TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private val COORD_FREE_OP = OpId("coord-free-echo")

private fun TaskId.coordFreeDescriptor() = TaskDescriptor(op = COORD_FREE_OP, args = value.encodeToByteArray())

class WarpNodeCoordinationTagTest {

    /**
     * A task enqueued via [WarpNode.enqueue] with a [TaskDescriptor] is dispatched to the
     * registry (the free path). The coordination-free path is unchanged — same results, same routing.
     */
    @Test
    fun freeTaskRoutesToFreeExecutor() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("coord-tag-free"))
        val seamB = loom.join(InMemoryTag("b"))
        val clock = schedulerClock(testScheduler)

        val freeExecuted = mutableListOf<TaskId>()
        val coordExecuted = mutableListOf<TaskId>()
        val lock = reentrantLock()

        fun makeNode(seam: us.tractat.kuilt.core.Seam) = WarpNode(
            selfId = seam.selfId,
            seam = seam,
            rosterFlow = seam.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = COORD_TEST_QUILTER_CONFIG,
            clock = clock,
            registry = OpRegistry().also { r ->
                r.register(COORD_FREE_OP, Op { args ->
                    lock.withLock { freeExecuted.add(TaskId(args.decodeToString())) }
                    args
                })
            },
            coordinatedExecutor = { taskId ->
                lock.withLock { coordExecuted.add(taskId) }
                "coord-${taskId.value}"
            },
        )

        val nodeA = makeNode(seamA)
        val nodeB = makeNode(seamB)

        val freeTasks = (1..4).map { TaskId("free-task-$it") }
        freeTasks.forEach { nodeA.enqueue(it, it.coordFreeDescriptor()) }
        drain()

        assertAll(
            { assertEquals(freeTasks.toSet(), lock.withLock { freeExecuted.toSet() }, "all free tasks must reach the free registry op") },
            { assertTrue(lock.withLock { coordExecuted.isEmpty() }, "coordinated executor must not be called for free tasks") },
            { assertEquals(freeTasks.toSet(), nodeA.results.taskIds, "results must include all free tasks") },
        )

        nodeA.close()
        nodeB.close()
    }

    /**
     * A task enqueued with [CoordinationKind.Coordinated] is dispatched to the
     * coordinated executor, not the free registry.
     */
    @Test
    fun coordinatedTaskRoutesToCoordinatedExecutor() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("coord-tag-coord"))
        val seamB = loom.join(InMemoryTag("b"))
        val clock = schedulerClock(testScheduler)

        val freeExecuted = mutableListOf<TaskId>()
        val coordExecuted = mutableListOf<TaskId>()
        val lock = reentrantLock()

        fun makeNode(seam: us.tractat.kuilt.core.Seam) = WarpNode(
            selfId = seam.selfId,
            seam = seam,
            rosterFlow = seam.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = COORD_TEST_QUILTER_CONFIG,
            clock = clock,
            registry = OpRegistry().also { r ->
                r.register(COORD_FREE_OP, Op { args ->
                    lock.withLock { freeExecuted.add(TaskId(args.decodeToString())) }
                    args
                })
            },
            coordinatedExecutor = { taskId ->
                lock.withLock { coordExecuted.add(taskId) }
                "coord-${taskId.value}"
            },
            raftNode = FakeRaftNode(initialRole = RaftRole.Leader),
        )

        val nodeA = makeNode(seamA)
        val nodeB = makeNode(seamB)

        val coordTasks = (1..4).map { TaskId("coord-task-$it") }
        coordTasks.forEach { nodeA.enqueue(it, CoordinationKind.Coordinated) }
        drain()

        assertAll(
            { assertEquals(coordTasks.toSet(), lock.withLock { coordExecuted.toSet() }, "all coordinated tasks must reach the coordinated executor") },
            { assertTrue(lock.withLock { freeExecuted.isEmpty() }, "free registry must not be called for coordinated tasks") },
            { assertEquals(coordTasks.toSet(), nodeA.results.taskIds, "results must include all coordinated tasks") },
        )

        nodeA.close()
        nodeB.close()
    }

    /**
     * Both routings co-exist: free and coordinated tasks on the same node are dispatched
     * to their respective paths without interference.
     */
    @Test
    fun bothRoutingsCoexistOnSameNode() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("coord-tag-both"))
        val seamB = loom.join(InMemoryTag("b"))
        val clock = schedulerClock(testScheduler)

        val freeExecuted = mutableListOf<TaskId>()
        val coordExecuted = mutableListOf<TaskId>()
        val lock = reentrantLock()

        fun makeNode(seam: us.tractat.kuilt.core.Seam) = WarpNode(
            selfId = seam.selfId,
            seam = seam,
            rosterFlow = seam.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = COORD_TEST_QUILTER_CONFIG,
            clock = clock,
            registry = OpRegistry().also { r ->
                r.register(COORD_FREE_OP, Op { args ->
                    lock.withLock { freeExecuted.add(TaskId(args.decodeToString())) }
                    args
                })
            },
            coordinatedExecutor = { taskId ->
                lock.withLock { coordExecuted.add(taskId) }
                "coord-${taskId.value}"
            },
            raftNode = FakeRaftNode(initialRole = RaftRole.Leader),
        )

        val nodeA = makeNode(seamA)
        val nodeB = makeNode(seamB)

        val freeTasks = (1..3).map { TaskId("both-free-$it") }
        val coordTasks = (1..3).map { TaskId("both-coord-$it") }

        freeTasks.forEach { nodeA.enqueue(it, it.coordFreeDescriptor()) }
        coordTasks.forEach { nodeA.enqueue(it, CoordinationKind.Coordinated) }
        drain()

        assertAll(
            { assertEquals(freeTasks.toSet(), lock.withLock { freeExecuted.toSet() }, "free tasks reach free registry") },
            { assertEquals(coordTasks.toSet(), lock.withLock { coordExecuted.toSet() }, "coordinated tasks reach coordinated executor") },
            { assertEquals((freeTasks + coordTasks).toSet(), nodeA.results.taskIds, "all results present") },
            { assertEquals((freeTasks + coordTasks).toSet(), nodeB.results.taskIds, "results replicate to B") },
        )

        nodeA.close()
        nodeB.close()
    }
}
