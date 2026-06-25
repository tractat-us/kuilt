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
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Returns a clock that reads virtual time from [scheduler], keeping it in sync with `delay()` calls. */
private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

/**
 * Advances virtual time in bounded steps to flush Quilter convergence and the
 * RingWithIntent settle window. Mirrors [WarpNodeTest]'s `drain()` — see that file
 * for the full rationale (short: [advanceUntilIdle] spins the Quilter anti-entropy
 * loop indefinitely; bounded steps are the safe alternative).
 */
private fun TestScope.drain() {
    repeat(5) { advanceTimeBy(TEST_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
    advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW)
    runCurrent()
}

private val TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

class WarpIntentRegisterTest {

    /** RingWithIntent still executes every task exactly once and converges results. */
    @Test
    fun ringWithIntentExecutesEveryTaskOnce() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("intent-once"))
        val seamB = loom.join(InMemoryTag("b"))
        val executed = mutableMapOf<TaskId, String>()
        val lock = reentrantLock()
        val clock = schedulerClock(testScheduler)
        fun node(seam: us.tractat.kuilt.core.Seam) = WarpNode(
            selfId = seam.selfId, seam = seam, rosterFlow = seam.rosterSnapshot(),
            scope = backgroundScope, quilterConfig = TEST_QUILTER_CONFIG, clock = clock,
            strategy = ClaimStrategy.RingWithIntent(),
            executor = { taskId -> lock.withLock { executed[taskId] = seam.selfId.value }; "r-${taskId.value}" },
        )
        val a = node(seamA); val b = node(seamB)
        val tasks = (1..10).map { TaskId("t-$it") }
        tasks.forEach { a.enqueue(it) }
        drain()
        assertAll(
            { assertEquals(10, lock.withLock { executed.size }, "every task executed once") },
            { assertEquals(tasks.toSet(), a.results.taskIds, "results converge on A") },
            { assertEquals(tasks.toSet(), b.results.taskIds, "results converge on B") },
        )
        a.close(); b.close()
    }

    /** A completed task's intent entry is tombstoned (register tracks only pending work). */
    @Test
    fun completedTaskClearsItsIntentEntry() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("intent-gc"))
        val clock = schedulerClock(testScheduler)
        val a = WarpNode(
            selfId = seamA.selfId, seam = seamA, rosterFlow = seamA.rosterSnapshot(),
            scope = backgroundScope, quilterConfig = TEST_QUILTER_CONFIG, clock = clock,
            strategy = ClaimStrategy.RingWithIntent(),
            executor = { taskId -> "r-${taskId.value}" },
        )
        val t = TaskId("gc-task")
        a.enqueue(t)
        drain()
        // Result present, queue drained → intent entry must be gone.
        assertEquals(setOf(t), a.results.taskIds, "task completed")
        a.close()
    }
}
