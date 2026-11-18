package us.tractat.kuilt.heddle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** [FairSharePool]'s acquire, complete and cancel, one property per test, on virtual time (#3555). */
@OptIn(ExperimentalCoroutinesApi::class)
class FairSharePoolTest {

    private val done3 = LeaseOutcome.Done(3L, 0L, 0L)

    @Test
    fun aWaiterOnAnIdlePoolIsGrantedAtOnce() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 2)
        val out = p.acquire(web)
        assertAll(
            { assertEquals(web, out.granted().leaf) },
            { assertEquals(1, p.openForTest) },
        )
    }

    @Test
    fun aWaiterBeyondTheSlotCountWaitsUntilALeaseCloses() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val b = async { p.acquire(web) }
        runCurrent()
        assertAll(
            { assertFalse(b.isCompleted, "B waits while A holds the one slot") },
            { assertEquals(1, p.waitersForTest) },
        )
        assertEquals(done3, p.complete(a.id, 3L))
        runCurrent()
        assertEquals(web, b.await().granted().leaf)
    }

    @Test
    fun neverMoreThanSlotsOpenUnderVirtualTime() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 2)
        var inFlight = 0
        var maxInFlight = 0
        var grants = 0
        List(12) { i ->
            launch {
                val lease = p.acquire(if (i % 2 == 0) web else mobile).granted()
                grants++
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                delay((1 + i % 3).seconds)
                inFlight--
                p.complete(lease.id, 1L)
            }
        }.joinAll()
        assertAll(
            { assertEquals(2, maxInFlight, "the bound was reached and never passed") },
            { assertEquals(12, grants) },
            { assertEquals(0, p.openForTest) },
        )
    }

    @Test
    fun aReservationAboveTheDefaultExtendsTheLease() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val lease = p.acquire(web, reservation = 10L).granted()
        assertAll(
            { assertEquals(10L, lease.reserved) },
            { assertEquals(10L, engine.snapshot().leases.single().reserved) },
        )
    }

    @Test
    fun aReservationOutOfRangeThrowsBeforeSuspending() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        p.acquire(web).granted()
        // The one slot is held, so a call that got as far as suspending would wait rather than fail.
        val calls = listOf(0L, LocalHeddle.MAX_RESERVATION + 1).map { r ->
            backgroundScope.async { assertFailsWith<IllegalArgumentException> { p.acquire(web, reservation = r) } }
        }
        runCurrent()
        assertAll(
            { assertTrue(calls.all { it.isCompleted }, "every out-of-range call returned without waiting") },
            { assertEquals(0, p.waitersForTest) },
        )
    }

    @Test
    fun aNameNoLeaseCanEndAtIsRefused() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 2)
        val out = p.acquire(nope)
        assertAll(
            { assertEquals(SlotOutcome.Refused(WaitExplanation.NoLiveLineage(nope)), out) },
            { assertEquals(0, p.openForTest) },
            { assertEquals(0, p.waitersForTest) },
        )
    }

    /**
     * A refusal names a leaf no lease can end at, so its reason is one of the two that say so; every
     * other explanation describes a wait. `BehindAtLevel` is left out: its constructor takes
     * deprecated positions, and it falls to the same `require` as the others.
     */
    @Test
    fun aRefusalTakesOnlyAnUnleasableReason() {
        val waits = listOf(
            WaitExplanation.NotWaiting,
            WaitExplanation.NotEligible,
            WaitExplanation.AtCeiling(web, 1L),
            WaitExplanation.SupplyExhausted,
            WaitExplanation.WouldBePickedNext,
        )
        val refusesAWait: List<() -> Unit> = waits.map { wait ->
            { assertFailsWith<IllegalArgumentException>("$wait") { SlotOutcome.Refused(wait) } }
        }
        assertAll(
            { assertEquals(WaitExplanation.NoLiveLineage(nope), SlotOutcome.Refused(WaitExplanation.NoLiveLineage(nope)).reason) },
            { assertEquals(WaitExplanation.LifetimeExhausted(web), SlotOutcome.Refused(WaitExplanation.LifetimeExhausted(web)).reason) },
            *refusesAWait.toTypedArray(),
        )
    }

    /**
     * An arrival refusal leaves a queued healthy waiter alone. With the arrival check (Ruling 12),
     * `nope` is refused the moment it arrives, since the one slot is busy, so this arm does not reach
     * dispatch's refusal pass: the arm below, [aRefusalPassNeverTakesACappedLanesWaiter], is the one
     * that does.
     */
    @Test
    fun anArrivalRefusalLeavesAQueuedHealthyWaiterAlone() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val w2 = async { p.acquire(web) }
        val bad = async { p.acquire(nope) }
        runCurrent()
        p.complete(a.id, 3L)
        runCurrent()
        val w2Lease = w2.await().granted()
        p.complete(w2Lease.id, 3L)
        runCurrent()
        val badOut = bad.await()
        assertAll(
            { assertEquals(web, w2Lease.leaf) },
            { assertEquals(SlotOutcome.Refused(WaitExplanation.NoLiveLineage(nope)), badOut) },
            { assertEquals(0, p.openForTest) },
        )
    }

    /**
     * Dispatch's refusal pass with a healthy waiter queued beside an unleasable one. Web is capped at
     * one lease, so with a slot free a second web waiter queues (the acquire answers NothingEligible
     * for it), and `nope` then arrives with a slot free, so the arrival check does not see it and the
     * acquire over both answers NoLiveLineage. The pass must refuse `nope` and only `nope`.
     */
    @Test
    fun aRefusalPassNeverTakesACappedLanesWaiter() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 2, engine = farm(cappedWebTopology))
        val a = p.acquire(web).granted()
        val w2 = async { p.acquire(web) }
        runCurrent()
        // The rig: a slot is free and web's second waiter still queues, at its ceiling.
        assertAll(
            { assertEquals(1, p.openForTest) },
            { assertEquals(1, p.waitersForTest) },
        )
        val bad = async { p.acquire(nope) }
        runCurrent()
        val badOut = bad.await()
        assertAll(
            { assertEquals(SlotOutcome.Refused(WaitExplanation.NoLiveLineage(nope)), badOut) },
            { assertFalse(w2.isCompleted, "web's waiter is neither refused nor granted past its ceiling") },
            { assertEquals(1, p.waitersForTest) },
        )
        p.complete(a.id, 3L)
        runCurrent()
        assertEquals(web, w2.await().granted().leaf)
    }

    @Test
    fun aDoubleCompleteFreesOneSlot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        assertAll(
            { assertEquals(done3, p.complete(a.id, 3L)) },
            { assertEquals(LeaseOutcome.Unknown, p.complete(a.id, 3L)) },
            { assertEquals(LeaseOutcome.Unknown, p.cancel(a.id)) },
        )
        val b = backgroundScope.async { p.acquire(web) }
        val c = backgroundScope.async { p.acquire(web) }
        runCurrent()
        assertAll(
            { assertTrue(b.isCompleted, "B is granted the slot A freed") },
            { assertFalse(c.isCompleted, "C waits: A freed one slot, not two") },
            { assertEquals(1, p.openForTest) },
            { assertEquals(1, p.waitersForTest) },
        )
    }

    @Test
    fun aLeaseThePoolDidNotIssueLeavesTheCountAlone() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 2)
        p.acquire(web).granted()
        val stranger = LeaseId(0L, 99L)
        assertAll(
            { assertEquals(LeaseOutcome.Unknown, p.complete(stranger, 1L)) },
            { assertEquals(LeaseOutcome.Unknown, p.cancel(stranger)) },
            { assertEquals(1, p.openForTest) },
        )
    }

    @Test
    fun cancelReturnsTheSlot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val b = async { p.acquire(mobile) }
        runCurrent()
        assertEquals(LeaseOutcome.Done(0L, 0L, 0L), p.cancel(a.id))
        runCurrent()
        assertEquals(mobile, b.await().granted().leaf)
    }

    @Test
    fun anEngineWithOpenLeasesIsAdopted() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        engine.reconcileCapacity(1L)
        val adopted = assertIs<AcquireOutcome.Acquired>(engine.acquire(setOf(web), setOf(web), startedAt = 0L)).lease
        val p = pool(slots = 1, engine = engine)
        assertEquals(1, p.openForTest, "the engine's open lease holds the one slot")
        val b = async { p.acquire(web) }
        runCurrent()
        assertFalse(b.isCompleted, "B waits behind the adopted lease")
        assertEquals(done3, p.complete(adopted.id, 3L))
        runCurrent()
        assertEquals(web, b.await().granted().leaf)
    }

    @Test
    fun nowStartsAtClockStart() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1, clockStart = 40L)
        val atStart = p.now()
        advanceTimeBy(3.seconds)
        val afterThree = p.now()
        val a = p.acquire(web).granted()
        val b = async { p.acquire(web) }
        runCurrent()
        advanceTimeBy(2.seconds)
        p.complete(a.id, 3L)
        runCurrent()
        val bLease = b.await().granted()
        assertAll(
            { assertEquals(40L, atStart) },
            { assertEquals(43L, afterThree) },
            { assertEquals(43L, a.startedAt, "a lease granted at once starts at now()") },
            { assertEquals(45L, bLease.startedAt, "a queued lease starts at now() when it is dispatched") },
        )
    }

    @Test
    fun aWaiterForAnUnleasableNameIsRefusedEvenWhenEverySlotIsBusy() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val busy = pool(slots = 1)
        busy.acquire(web).granted()
        val refused = backgroundScope.async { busy.acquire(nope) }
        val zero = pool(slots = 0)
        val refusedAtZero = backgroundScope.async { zero.acquire(nope) }
        val healthyAtZero = backgroundScope.async { zero.acquire(web) }
        runCurrent()
        val expected = SlotOutcome.Refused(WaitExplanation.NoLiveLineage(nope))
        assertAll(
            { assertTrue(refused.isCompleted, "refused while every slot is busy") },
            { assertEquals(1, busy.openForTest) },
            { assertEquals(0, busy.waitersForTest) },
            { assertTrue(refusedAtZero.isCompleted, "refused at zero slots") },
            { assertFalse(healthyAtZero.isCompleted, "a healthy name at zero slots waits") },
            { assertEquals(1, zero.waitersForTest) },
        )
        val outs = listOf(refused.await(), refusedAtZero.await())
        assertEquals(listOf(expected, expected), outs)
    }

    @Test
    fun aDispatchFailureFailsTheWaitersAndLeavesCompleteIntact() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val waiters = listOf(web, mobile).map { leaf ->
            async {
                try {
                    p.acquire(leaf)
                    null
                } catch (failure: Boom) {
                    failure
                }
            }
        }
        runCurrent()
        val boom = Boom()
        p.dispatchFaultForTest = { throw boom }
        val completed = p.complete(a.id, 3L)
        runCurrent()
        p.dispatchFaultForTest = null
        val failures = waiters.map { it.await() }
        val fresh = p.acquire(web)
        assertAll(
            { assertEquals(done3, completed, "complete answers its own Done") },
            // A copy, not the same instance: coroutines recover stack traces by copying the exception on the JVM.
            { failures.forEach { assertIs<Boom>(it) } },
            { assertEquals(0, p.waitersForTest) },
            { assertEquals(web, fresh.granted().leaf) },
        )
    }

    /**
     * Ruling 11's other door: dispatch reached from a cancellation handler. Web is capped at one
     * lease, so with a slot free two web waiters queue; cancelling one runs its handler, whose
     * dispatch finds a free slot and trips the fault. The other waiter fails with it, and nothing
     * reaches an exception handler.
     */
    @Test
    fun aDispatchFailureInACancellationHandlerReachesNoExceptionHandler() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val uncaught = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> uncaught += failure }
        val p = pool(slots = 2, engine = farm(cappedWebTopology))
        p.acquire(web).granted()
        val cancelled = launch(handler) { p.acquire(web) }
        val survivor = async(handler) {
            try {
                p.acquire(web)
                null
            } catch (failure: Boom) {
                failure
            }
        }
        runCurrent()
        assertEquals(2, p.waitersForTest, "the rig: both web waiters queue with a slot free")
        val boom = Boom()
        var faults = 0
        p.dispatchFaultForTest = {
            faults++
            throw boom
        }
        cancelled.cancel()
        runCurrent()
        p.dispatchFaultForTest = null
        val survivorFailure = survivor.await()
        assertAll(
            { assertEquals(1, faults, "the handler's dispatch reached the fault") },
            { assertIs<Boom>(survivorFailure) },
            { assertEquals(emptyList(), uncaught) },
            { assertEquals(0, p.waitersForTest) },
        )
    }

    /**
     * The one LifetimeExhausted the public API reaches (measured, #3555): each complete records an
     * overrun of up to [LocalHeddle.MAX_RECORDED_OVERRUN], and the root mints every one, so after 62
     * the root's minted supply is at the watermark and the engine answers LifetimeExhausted at the
     * root for every lane. A lane full on its own, with the root still able to fund a sibling, is
     * out of reach here: the root's mint is at least any one edge's issuance, so it trips first.
     */
    @Test
    fun aFullRootRefusesEveryLaneWithLifetimeExhausted() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        var overruns = 0
        while (overruns < 80) {
            val lease = (p.acquire(web) as? SlotOutcome.Granted)?.lease ?: break
            p.complete(lease.id, lease.reserved + LocalHeddle.MAX_RECORDED_OVERRUN)
            overruns++
        }
        val full = SlotOutcome.Refused(WaitExplanation.LifetimeExhausted(farmTopology.groupId()))
        val outs = listOf(p.acquire(web), p.acquire(mobile))
        assertAll(
            { assertEquals(62, overruns, "the rig: the root's mint reached the watermark") },
            { assertEquals(listOf(full, full), outs) },
            { assertEquals(0, p.openForTest) },
            { assertEquals(0, p.waitersForTest) },
        )
    }

    @Test
    fun anAlreadyCancelledCallerIsNeverQueued() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        p.acquire(web).granted()
        val job = launch {
            currentCoroutineContext().job.cancel()
            p.acquire(web)
        }
        runCurrent()
        assertAll(
            { assertTrue(job.isCancelled) },
            { assertEquals(0, p.waitersForTest) },
        )
    }

    /**
     * Each grant holds its waiter's reservation, the 12 extended on the engine's side too, and the
     * grants come in the engine's own preview order.
     *
     * The order half is narrower than it looks. [LocalHeddle.previewPicks] counts each pick at its
     * reservation and never completes one, and an extend makes no pick, so the order cannot see a
     * missing extend: the reservations are what pin it. The pool completes each job between picks,
     * so the order matches only while each job costs about what it reserved. Here that is its
     * reservation + 1, a one-unit overrun that flips no pick; measured, charging exactly the
     * reservation gives the same order, while charging 1 or 40 a job reorders the last four picks.
     * What the order pins is that the pool asks the engine with the waiting leaves as both sets and
     * grants the head of the leaf the engine picked.
     */
    @Test
    fun mixedReservationsHoldTheirReservationsAndGrantInPreviewOrder() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val queues = mapOf(web to listOf(3L, 12L, 3L, 3L), mobile to listOf(3L, 3L, 3L))
        val both = setOf(web, mobile)
        val twin = farm()
        twin.reconcileCapacity(1L)
        val twinHeld = assertIs<AcquireOutcome.Acquired>(twin.acquire(setOf(web), setOf(web), startedAt = 0L)).lease
        twin.complete(twinHeld.id, 3L, both)
        val preview = twin.previewPicks(queues, eligible = both)

        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val default = engine.defaultReservation
        val held = p.acquire(web).granted()
        val granted = mutableListOf<GroupId>()
        // Per grant: what the waiter asked for, what its lease says it holds, what the engine holds for it.
        val reservations = mutableListOf<Triple<Long, Long, Long>>()
        val jobs = queues.flatMap { (leaf, asked) ->
            asked.map { r ->
                launch {
                    val lease = p.acquire(leaf, reservation = r).granted()
                    val engineSide = engine.snapshot().leases.single { it.id == lease.id }.reserved
                    granted += lease.leaf
                    reservations += Triple(r, lease.reserved, engineSide)
                    p.complete(lease.id, r + 1)
                }
            }
        }
        runCurrent()
        p.complete(held.id, 3L)
        jobs.joinAll()
        val expected = reservations.map { (r, _, _) -> maxOf(r, default).let { Triple(r, it, it) } }
        assertAll(
            { assertEquals(7, granted.size) },
            { assertEquals(listOf(12L), reservations.map { it.first }.filter { it > default }, "the rig: one waiter asks above the default") },
            { assertEquals(expected, reservations, "each lease holds max(asked, default), on both sides") },
            { assertEquals(preview, granted) },
        )
    }

    @Test
    fun constructionValidatesItsArguments() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = testScheduler.timeSource
        assertAll(
            { assertFailsWith<IllegalArgumentException> { FairSharePool(farm(), -1, clock, 1.seconds) } },
            { assertFailsWith<IllegalArgumentException> { FairSharePool(farm(), 1, clock, Duration.ZERO) } },
            { assertFailsWith<IllegalArgumentException> { FairSharePool(farm(), 1, clock, (-1).seconds) } },
            { assertFailsWith<IllegalArgumentException> { FairSharePool(farm(), 1, clock, Duration.INFINITE) } },
        )
    }
}
