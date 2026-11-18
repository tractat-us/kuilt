package us.tractat.kuilt.heddle

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * No slot leaks when a waiter is cancelled at any point of its grant: while queued, after its
 * resume but before it runs, and before its resume takes (#3555). Every arm ends on the same four
 * invariants.
 */
class FairSharePoolCancellationTest {

    private suspend fun TestScope.assertNothingLeaked(p: FairSharePool, engine: LocalHeddle) {
        runCurrent()
        val open = p.openForTest
        val leases = engine.snapshot().leases
        val waiters = p.waitersForTest
        val fresh = p.acquire(web)
        assertAll(
            { assertEquals(0, open, "the pool counts no open lease") },
            { assertEquals(emptyList(), leases, "the engine holds no open lease") },
            { assertEquals(0, waiters, "no waiter is queued") },
            { assertEquals(web, fresh.granted().leaf, "a fresh waiter is granted") },
        )
    }

    @Test
    fun aWaiterCancelledWhileQueuedLeavesNoTrace() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val a = p.acquire(web).granted()
        val b = launch { p.acquire(web) }
        runCurrent()
        assertEquals(1, p.waitersForTest, "the rig: B is queued")
        b.cancel()
        runCurrent()
        // Before A completes: a waiter left in the queue would be granted the slot A frees and hide this.
        assertEquals(0, p.waitersForTest, "the cancelled waiter left its queue")
        p.complete(a.id, 3L)
        assertNothingLeaked(p, engine)
    }

    @Test
    fun aWaiterCancelledAfterItsResumeButBeforeItRunsHandsTheLeaseBack() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val a = p.acquire(web).granted()
        var bRan = false
        val b = launch {
            p.acquire(web)
            bRan = true
        }
        runCurrent()
        p.complete(a.id, 3L)
        // The rig: B is granted and resumed, but the test dispatcher has not run it yet.
        assertAll(
            { assertEquals(1, p.openForTest, "B's lease is open") },
            { assertFalse(b.isCompleted, "B has not run") },
        )
        b.cancel()
        runCurrent()
        assertFalse(bRan, "B never got past acquire")
        assertNothingLeaked(p, engine)
    }

    @Test
    fun aWaiterCancelledBeforeItsResumeHandsTheLeaseBack() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val a = p.acquire(web).granted()
        var bRan = false
        val b = launch {
            p.acquire(web)
            bRan = true
        }
        runCurrent()
        var fired = 0
        p.beforeResumeForTest = {
            fired++
            b.cancel()
        }
        p.complete(a.id, 3L)
        p.beforeResumeForTest = {}
        runCurrent()
        assertAll(
            { assertEquals(1, fired, "the rig: the hook cancelled B once, just before its resume") },
            { assertTrue(b.isCancelled) },
            { assertFalse(bRan, "B never got past acquire") },
        )
        assertNothingLeaked(p, engine)
    }

    /**
     * A thousand waiters, each completing its lease the moment it is granted, from an unconfined
     * resume. It cannot show a deadlock (one thread on a reentrant lock cannot deadlock) or a stack
     * overflow (`Unconfined` queues nested resumes on the thread's event loop). What it catches is a
     * resume under the lock: the inline complete would run at depth 2, skip dispatch, and strand the
     * next waiter.
     */
    @Test
    fun aResumedWaiterThatCompletesInlineGrantsTheNext() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val held = p.acquire(web).granted()
        val order = mutableListOf<Int>()
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        val jobs: List<Job> = List(1_000) { i ->
            launch(unconfined) {
                val lease = p.acquire(web).granted()
                order += i
                p.complete(lease.id, 1L)
            }
        }
        assertEquals(1_000, p.waitersForTest, "the rig: every waiter queued behind the held lease")
        p.complete(held.id, 3L)
        runCurrent()
        assertAll(
            { assertEquals((0 until 1_000).toList(), order, "every waiter granted, in arrival order") },
            { assertTrue(jobs.all { it.isCompleted }) },
        )
        assertNothingLeaked(p, engine)
    }
}
