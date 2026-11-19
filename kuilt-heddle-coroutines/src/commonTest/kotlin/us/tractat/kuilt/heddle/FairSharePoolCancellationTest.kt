package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * No slot leaks when a waiter is cancelled at any point of its grant: while queued, after its
 * resume but before it runs, and before its resume takes (#3555). Every arm ends on the same four
 * invariants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FairSharePoolCancellationTest {

    private suspend fun TestScope.assertNothingLeaked(p: FairSharePool, engine: LocalHeddle) {
        runCurrent()
        val open = p.view().open
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
            { assertEquals(1, p.view().open, "B's lease is open") },
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
     * The hand-back runs as a cancellation callback, so an engine that refuses its cancel must not
     * throw out of it: that reaches the exception handler, an abort on Kotlin/Native, and a lease
     * counted before the throw stays counted for good. B is cancelled just before its resume, C
     * queues behind it, and the hook throws where the engine cancel would run, leaving the engine
     * unchanged as its own refusal does. The slot comes back, C fails with the engine's failure, and
     * nothing reaches the handler. Every value is read without suspending, so a regression reds
     * instead of hanging on a waiter nobody grants.
     */
    @Test
    fun anEngineThatRefusesAHandBackFailsTheWaitersAndFreesTheSlot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val uncaught = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> uncaught += failure }
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val a = p.acquire(web).granted()
        val b = launch(handler) { p.acquire(web) }
        val c = async(handler) {
            try {
                p.acquire(web)
                null
            } catch (failure: Boom) {
                failure
            }
        }
        runCurrent()
        assertEquals(2, p.waitersForTest, "the rig: B and C queue behind A")
        val boom = Boom()
        var faults = 0
        var bLease: LeaseId? = null
        p.handBackFaultForTest = {
            faults++
            throw boom
        }
        var resumes = 0
        p.beforeResumeForTest = { outcome ->
            resumes++
            bLease = (outcome as SlotOutcome.Granted).lease.id
            b.cancel()
        }
        p.complete(a.id, 3L)
        p.beforeResumeForTest = {}
        p.handBackFaultForTest = null
        runCurrent()
        val open = p.view().open
        val waiters = p.waitersForTest
        val engineLeases = engine.snapshot().leases.map { it.id }
        val cFailure = if (c.isCompleted) c.getCompleted() else null
        val fresh = async { p.acquire(mobile) }
        runCurrent()
        val freshOutcome = if (fresh.isCompleted) fresh.getCompleted() else null
        // A regression strands C and the fresh waiter: free both, so the test reds instead of hanging.
        c.cancel()
        fresh.cancel()
        assertAll(
            { assertEquals(1, faults, "the rig: the hand-back reached the fault once") },
            { assertEquals(1, resumes, "the rig: B's grant is the only one resumed") },
            { assertEquals(listOf(bLease), engineLeases, "the rig: the engine refused, so it still holds B's lease") },
            { assertTrue(b.isCancelled) },
            { assertEquals(emptyList(), uncaught, "nothing reaches the exception handler") },
            { assertEquals(0, open, "the pool no longer counts B's lease") },
            { assertIs<Boom>(cFailure, "C fails with the engine's failure") },
            { assertEquals(0, waiters) },
            { assertEquals(mobile, assertIs<SlotOutcome.Granted>(freshOutcome, "a fresh waiter is granted").lease.leaf) },
        )
    }

    /**
     * A lease the pool let go of is never counted again. B is cancelled just before its resume and
     * the engine refuses the hand-back, so the engine still holds B's lease while the pool has freed
     * its slot. A later [FairSharePool.withEngine] catches the pool up from the engine's open leases,
     * and must not take B's back: if it did, the one slot would be held by a lease nobody will ever
     * settle, and a fresh waiter would never be granted.
     */
    @Test
    fun aLaterWithEngineNeverRecountsALeaseTheHandBackLetGo() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val a = p.acquire(web).granted()
        val b = launch { p.acquire(web) }
        runCurrent()
        var bLease: LeaseId? = null
        var faults = 0
        p.handBackFaultForTest = {
            faults++
            throw Boom()
        }
        p.beforeResumeForTest = { outcome ->
            bLease = (outcome as SlotOutcome.Granted).lease.id
            b.cancel()
        }
        p.complete(a.id, 3L)
        p.beforeResumeForTest = {}
        p.handBackFaultForTest = null
        runCurrent()
        val openBefore = p.view().open
        p.withEngine { _, _ -> }
        val openAfter = p.view().open
        val engineLeases = engine.snapshot().leases.map { it.id }
        val fresh = async { p.acquire(mobile) }
        runCurrent()
        val freshOutcome = if (fresh.isCompleted) fresh.getCompleted() else null
        // A regression strands the fresh waiter: free it, so the test reds instead of hanging.
        fresh.cancel()
        assertAll(
            { assertEquals(1, faults, "the rig: the hand-back reached the fault once") },
            { assertTrue(b.isCancelled) },
            { assertEquals(listOf(bLease), engineLeases, "the rig: the engine still holds B's lease") },
            { assertEquals(0, openBefore, "the rig: the hand-back freed the slot") },
            { assertEquals(0, openAfter, "withEngine never counts B's lease again") },
            { assertIs<SlotOutcome.Granted>(freshOutcome, "a fresh waiter is granted") },
        )
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
