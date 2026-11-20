package us.tractat.kuilt.heddle

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A hand-out failure that every waiter it was handed to drops, by being cancelled before it took the
 * failure, reached nobody, so it is reported unclaimed exactly once (#3587). One that any waiter took
 * reached someone, and is not reported at all, however many other copies were dropped.
 *
 * The rig: A holds the one slot and the waiters queue behind it. The dispatch fault throws on the
 * hand-out [FairSharePool.complete] sets going, so [FairSharePool] fails every queued waiter, and a
 * waiter is cancelled either inside [FairSharePool.beforeFailForTest], as its failure is resumed, or
 * after the resume and before the waiter runs. "Reported" is [FairSharePool.unclaimedForTest], the
 * hook just before the log call, so these arms run on every target;
 * `FairSharePoolUnclaimedFailureLogTest` pins that the logger is called on the JVM.
 */
class FairSharePoolDroppedFailureTest {

    @Test
    fun aFailureItsOnlyWaiterDropsAsItIsHandedOverIsReportedOnce() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val w = launch { p.acquire(web) }
        runCurrent()
        check(p.waitersForTest == 1) { "the rig: W did not queue" }
        val boom = Boom()
        val rig = failTheHandOut(p, boom, onHandOver = { w.cancel() }) { p.complete(a.id, 3L) }
        runCurrent()
        assertAll(
            { assertEquals(1, rig.handedOver, "the rig: the failure was handed to W once") },
            { assertTrue(w.isCancelled, "the rig: W was cancelled") },
            { assertEquals(listOf<Throwable>(boom), rig.unclaimed, "reported unclaimed once, the failure itself") },
        )
    }

    @Test
    fun aFailureItsOnlyWaiterDropsBeforeItRunsIsReportedOnce() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val w = launch { p.acquire(web) }
        runCurrent()
        check(p.waitersForTest == 1) { "the rig: W did not queue" }
        val boom = Boom()
        val rig = failTheHandOut(p, boom, onHandOver = {}) { p.complete(a.id, 3L) }
        val resumedButNotRun = !w.isCompleted
        w.cancel()
        runCurrent()
        assertAll(
            { assertEquals(1, rig.handedOver, "the rig: the failure was handed to W once") },
            { assertTrue(resumedButNotRun, "the rig: W had not run when it was cancelled") },
            { assertTrue(w.isCancelled, "the rig: W was cancelled") },
            { assertEquals(listOf<Throwable>(boom), rig.unclaimed, "reported unclaimed once, the failure itself") },
        )
    }

    @Test
    fun aFailureEveryWaiterDropsIsReportedOnceNotOncePerWaiter() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val waiters = listOf(web, mobile).map { leaf -> launch { p.acquire(leaf) } }
        runCurrent()
        check(p.waitersForTest == 2) { "the rig: the waiters did not queue" }
        val boom = Boom()
        val rig = failTheHandOut(p, boom, onHandOver = { waiters.forEach(Job::cancel) }) { p.complete(a.id, 3L) }
        runCurrent()
        assertAll(
            { assertEquals(2, rig.handedOver, "the rig: the failure was handed to both waiters") },
            { assertTrue(waiters.all { it.isCancelled }, "the rig: both waiters were cancelled") },
            { assertEquals(listOf<Throwable>(boom), rig.unclaimed, "reported unclaimed once, not once per dropped copy") },
        )
    }

    @Test
    fun aFailureOneWaiterTakesIsNotReportedThoughAnotherDroppedIt() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val dropper = launch { p.acquire(web) }
        val taker = async {
            try {
                p.acquire(mobile)
                null
            } catch (failure: Boom) {
                failure
            }
        }
        runCurrent()
        check(p.waitersForTest == 2) { "the rig: the waiters did not queue" }
        val boom = Boom()
        var first = true
        val rig = failTheHandOut(p, boom, onHandOver = {
            // Web queued first, so the first failure handed over is the dropper's.
            if (first) dropper.cancel()
            first = false
        }) { p.complete(a.id, 3L) }
        runCurrent()
        val taken = if (taker.isCompleted) taker.getCompleted() else null
        // A regression strands the taker: free it, so the test reds instead of hanging.
        taker.cancel()
        assertAll(
            { assertEquals(2, rig.handedOver, "the rig: the failure was handed to both waiters") },
            { assertTrue(dropper.isCancelled, "the rig: the dropper was cancelled") },
            { assertIs<Boom>(taken, "the taker's acquire throws the failure") },
            { assertEquals(emptyList(), rig.unclaimed, "nothing is reported: the failure reached the taker") },
        )
    }

    /**
     * The arm above with both waiters on one leaf, which is what makes the pool count a copy per
     * waiter and not per leaf: across two leaves the two counts agree, so only here would a pool
     * counting one copy per leaf log the dropper's drop as the last, though the taker took it.
     */
    @Test
    fun aFailureOneWaiterTakesIsNotReportedThoughAnotherOnItsLeafDroppedIt() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val dropper = launch { p.acquire(web) }
        val taker = async {
            try {
                p.acquire(web)
                null
            } catch (failure: Boom) {
                failure
            }
        }
        runCurrent()
        check(p.view().queues.mapValues { it.value.size } == mapOf(web to 2)) { "the rig: both waiters did not queue on web" }
        val boom = Boom()
        var first = true
        val rig = failTheHandOut(p, boom, onHandOver = {
            // A leaf's queue is first in, first out, and the dropper queued first.
            if (first) dropper.cancel()
            first = false
        }) { p.complete(a.id, 3L) }
        runCurrent()
        val taken = if (taker.isCompleted) taker.getCompleted() else null
        // A regression strands the taker: free it, so the test reds instead of hanging.
        taker.cancel()
        assertAll(
            { assertEquals(2, rig.handedOver, "the rig: the failure was handed to both waiters") },
            { assertTrue(dropper.isCancelled, "the rig: the dropper was cancelled") },
            { assertIs<Boom>(taken, "the taker's acquire throws the failure") },
            { assertEquals(emptyList(), rig.unclaimed, "nothing is reported: the failure reached the taker") },
        )
    }

    private class Rig(val handedOver: Int, val unclaimed: List<Throwable>)

    /**
     * Run [trigger] with the dispatch fault throwing [boom], calling [onHandOver] as each failure is
     * resumed, and answer how many failures were handed over and every failure reported unclaimed,
     * including any reported later: the returned list keeps filling until the hooks are reset.
     */
    private fun failTheHandOut(p: FairSharePool, boom: Boom, onHandOver: () -> Unit, trigger: () -> Unit): Rig {
        val unclaimed = mutableListOf<Throwable>()
        var handedOver = 0
        p.unclaimedForTest = { unclaimed += it }
        p.dispatchFaultForTest = { throw boom }
        p.beforeFailForTest = {
            handedOver++
            onHandOver()
        }
        trigger()
        p.dispatchFaultForTest = null
        p.beforeFailForTest = {}
        return Rig(handedOver, unclaimed)
    }
}
