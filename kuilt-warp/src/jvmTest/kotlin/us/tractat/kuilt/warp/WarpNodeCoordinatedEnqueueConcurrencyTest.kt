@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Real-threading regression harness for #2077 — concurrent
 * [WarpNode.enqueue] `(taskId, CoordinationKind.Coordinated)` must never lose a task.
 *
 * **Why real threads.** [WarpNode.enqueue] is synchronous and never suspends, so two calls cannot
 * interleave under a virtual-time dispatcher however the scheduler is driven: the read, the dot
 * mint and the write are one uninterrupted run of the calling thread. The defect only exists
 * between *threads*, so only threads can pin it. Nothing here depends on wall-clock timing or a
 * sleep — the threads race, and the assertion is on the converged queue afterwards, so the test
 * is order-independent even though the interleaving is not.
 *
 * The dispatcher stays a [StandardTestDispatcher]: the node's own background coroutines are still
 * virtual-time-driven and are simply never advanced here. This test asserts on the *synchronous*
 * result of `enqueue` — the local coordinated queue — not on anything the background loops do.
 *
 * The mechanism is pinned separately, and deterministically, by
 * [CoordinatedQueueDotUniquenessTest].
 */
class WarpNodeCoordinatedEnqueueConcurrencyTest {

    /**
     * Every coordinated enqueue issued concurrently from several threads must be present on the
     * queue afterwards.
     *
     * Against the unguarded read-modify-write this fails hard: two threads that read the same
     * snapshot mint the same dot, and the causal join annihilates **both** their tasks — so a
     * single collision loses two tasks, not one.
     */
    @Test
    fun concurrentCoordinatedEnqueuesLoseNoTask() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = InMemoryLoom().host(Pattern("coord-enqueue-race"))
        val node = WarpNode(
            selfId = seam.selfId,
            seam = seam,
            rosterFlow = seam.rosterSnapshot(),
            scope = backgroundScope,
            quilterConfig = RACE_QUILTER_CONFIG,
            clock = schedulerClock(testScheduler),
            registry = OpRegistry(),
            coordinatedExecutor = { taskId -> "coord-${taskId.value}" },
            raftNode = FakeRaftNode(initialRole = RaftRole.Leader),
            epoch = 0L,
        )

        val expected = (0 until THREADS).flatMap { t ->
            (0 until PER_THREAD).map { i -> TaskId("race-$t-$i") }
        }.toSet()

        val startLine = CyclicBarrier(THREADS)
        (0 until THREADS).map { t ->
            thread(name = "coord-enqueue-$t") {
                startLine.await()
                repeat(PER_THREAD) { i -> node.enqueue(TaskId("race-$t-$i"), CoordinationKind.Coordinated) }
            }
        }.forEach { it.join() }

        val lost = expected - node.coordinatedTaskIds()
        assertEquals(
            0,
            lost.size,
            "coordinated enqueues were annihilated by duplicate dots — ${lost.size} of ${expected.size} " +
                "tasks lost, e.g. ${lost.take(5).map(TaskId::value)}",
        )

        node.close()
    }

    private companion object {
        /**
         * Enough concurrency to make a collision overwhelmingly likely without making the
         * Quilter's pending-delta buffer (one full queue snapshot per enqueue) expensive.
         */
        const val THREADS = 6
        const val PER_THREAD = 200

        val RACE_QUILTER_CONFIG = QuilterConfig(
            antiEntropyInterval = 100.milliseconds,
            fullStateRetryInterval = 150.milliseconds,
            expectVirtualTime = true,
        )

        fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
            { Instant.fromEpochMilliseconds(scheduler.currentTime) }
    }
}
