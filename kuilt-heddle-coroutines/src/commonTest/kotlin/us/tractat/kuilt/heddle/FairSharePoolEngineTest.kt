package us.tractat.kuilt.heddle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [FairSharePool.withEngine] and [FairSharePool.resize], one property per test, on virtual time (#3557). */
@OptIn(ExperimentalCoroutinesApi::class)
class FairSharePoolEngineTest {

    /** The farm with mobile removed: what a reload that drops a lane applies. */
    private val webOnly = Topology("farm", listOf(Topology.Lane("web", Weight.of(3))))

    @Test
    fun resizeFromZeroGrantsInThePreviewOrder() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 0)
        val grants = mutableListOf<Lease>()
        (List(20) { web } + List(20) { mobile }).forEach { leaf ->
            backgroundScope.launch { grants += p.acquire(leaf).granted() }
        }
        runCurrent()
        val queuedAtZero = p.waitersForTest
        val openAtZero = p.openForTest

        val twin = farm()
        twin.reconcileCapacity(6L)
        val both = setOf(web, mobile)
        val preview = twin.previewPicks(mapOf(web to 20, mobile to 20), both, List(6) { 3L })

        p.resize(6)
        runCurrent()
        assertAll(
            { assertEquals(40, queuedAtZero, "nothing is refused at zero slots") },
            { assertEquals(0, openAtZero) },
            { assertTrue(mobile in preview, "the rig: the preview grants mobile a slot") },
            { assertEquals(preview, grants.sortedBy { it.id }.map { it.leaf }) },
            { assertEquals(6, p.openForTest) },
            { assertEquals(34, p.waitersForTest) },
        )
    }

    @Test
    fun loweringTheSlotCountPreemptsNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 3)
        val held = List(3) { p.acquire(web).granted() }
        p.resize(1)
        val fourth = backgroundScope.async { p.acquire(web) }
        runCurrent()
        val openAfterResize = p.openForTest
        val waitsAtFirst = !fourth.isCompleted
        p.complete(held[0].id, 3L)
        p.complete(held[1].id, 3L)
        runCurrent()
        val waitsWithOneOpen = !fourth.isCompleted
        p.complete(held[2].id, 3L)
        runCurrent()
        val fourthOut = fourth.settled()
        assertAll(
            { assertEquals(3, openAfterResize, "every lease stays open past a lowered count") },
            { assertTrue(waitsAtFirst, "a fourth waiter waits while three are open") },
            { assertTrue(waitsWithOneOpen, "with one open and one slot, the fourth still waits") },
            { assertEquals(web, fourthOut?.granted()?.leaf) },
        )
    }

    @Test
    fun resizeValidates() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        assertFailsWith<IllegalArgumentException> { p.resize(-1) }
        assertEquals(web, p.acquire(web).granted().leaf, "the pool keeps its one slot")
    }

    @Test
    fun withEngineRedispatchesAfterAReloadLiftsACeiling() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 2, engine = farm(cappedWebTopology))
        p.acquire(web).granted()
        val second = backgroundScope.async { p.acquire(web) }
        runCurrent()
        val waitedAtCeiling = !second.isCompleted
        val reload = p.withEngine { it.apply(farmTopology, 1L) }
        runCurrent()
        val secondOut = second.settled()
        assertAll(
            { assertTrue(waitedAtCeiling, "the rig: web's second waiter waits at its ceiling") },
            { assertEquals(emptyList(), reload.refusals) },
            { assertEquals(web, secondOut?.granted()?.leaf) },
            { assertEquals(2, p.openForTest) },
        )
    }

    @Test
    fun aLaneRemovedByAReloadRefusesItsWaiters() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val a = p.acquire(web).granted()
        val waiters = List(2) { backgroundScope.async { p.acquire(mobile) } }
        runCurrent()
        val queued = p.waitersForTest
        val reload = p.withEngine { it.apply(webOnly, 1L) }
        runCurrent()
        val gone = SlotOutcome.Refused(WaitExplanation.NoLiveLineage(mobile))
        val outs = waiters.map { it.settled() }
        assertAll(
            { assertEquals(2, queued, "the rig: both mobile waiters queue behind web's lease") },
            { assertEquals(emptyList(), reload.refusals) },
            { assertEquals(listOf(gone, gone), outs) },
            { assertEquals(0, p.waitersForTest) },
            { assertEquals(1, p.openForTest) },
            { assertEquals(listOf(a.id), engine.snapshot().leases.map { it.id }, "web's lease is still open") },
        )
    }

    /**
     * Ruling 7: a frame reached from inside a [FairSharePool.withEngine] block, a nested call or a
     * cancellation handler, resumes nothing. The nested `withEngine` refuses mobile's waiter, so a
     * refusal is pending while the block goes on to cancel web's queued waiter, whose handler is a
     * second inner frame. Every resume is recorded with whether the block was still running.
     */
    @Test
    fun anInnerMutationNeverResumes() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val w = launch { p.acquire(web) }
        val m = backgroundScope.async { p.acquire(mobile) }
        runCurrent()
        val queued = p.waitersForTest
        var inBlock = false
        val resumedInBlock = mutableListOf<Boolean>()
        p.beforeResumeForTest = { resumedInBlock += inBlock }
        var waitersAfterCancel = -1
        p.withEngine { engine ->
            inBlock = true
            engine.apply(webOnly, 1L)
            p.withEngine { }
            w.cancel()
            waitersAfterCancel = p.waitersForTest
            inBlock = false
        }
        runCurrent()
        val mOut = m.settled()
        p.beforeResumeForTest = {}
        val openWhileAHeld = p.openForTest
        p.complete(a.id, 3L)
        val fresh = p.acquire(web)
        assertAll(
            { assertEquals(2, queued, "the rig: web's and mobile's waiters both queue") },
            { assertEquals(0, waitersAfterCancel, "the rig: the refusal and the cancel both landed in the block") },
            { assertEquals(listOf(false), resumedInBlock, "one resume, the refusal, and only after the block returned") },
            { assertEquals(SlotOutcome.Refused(WaitExplanation.NoLiveLineage(mobile)), mOut) },
            { assertTrue(w.isCancelled) },
            { assertEquals(1, openWhileAHeld, "the cancelled waiter took no slot") },
            { assertEquals(web, fresh.granted().leaf, "the freed slot is granted again") },
        )
    }

    @Test
    fun aWithEngineBlockThatThrowsStillResumesItsGrants() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 2, engine = farm(cappedWebTopology))
        p.acquire(web).granted()
        val second = backgroundScope.async { p.acquire(web) }
        runCurrent()
        val queued = p.waitersForTest
        val thrown = assertFailsWith<IllegalStateException> {
            p.withEngine {
                it.apply(farmTopology, 1L)
                error("boom")
            }
        }
        runCurrent()
        val secondOut = second.settled()
        assertAll(
            { assertEquals(1, queued, "the rig: web's second waiter waits at its ceiling") },
            { assertEquals("boom", thrown.message, "the block's own throwable reaches the caller") },
            { assertEquals(web, secondOut?.granted()?.leaf) },
            { assertEquals(2, p.openForTest) },
        )
    }

    @Test
    fun aLeaseCompletedThroughTheEngineFreesItsSlot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val b = backgroundScope.async { p.acquire(web) }
        runCurrent()
        val waited = !b.isCompleted
        val done = p.withEngine { it.complete(a.id, 3L, emptySet()) }
        runCurrent()
        val bOut = b.settled()
        assertAll(
            { assertTrue(waited, "the rig: B waits behind A") },
            { assertEquals(LeaseOutcome.Done(3L, 0L, 0L), done) },
            { assertEquals(web, bOut?.granted()?.leaf, "B is granted the slot A's completion freed") },
            { assertEquals(1, p.openForTest) },
        )
    }

    /**
     * A target one slot can size and two cannot: `totalSlots × 3 × targetSlots` must stay at or below
     * `Long.MAX_VALUE / 4`, and `targetSlots` is a third of that, so the bound is exactly one slot.
     */
    @Test
    fun resizeAboveTheSupplyLimitChangesNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val limit = Long.MAX_VALUE / 4
        val policy = LocalPolicy(defaultReservation = 3L, supply = SupplyPolicy(lowWatermarkSlots = 1L, targetSlots = limit / 3))
        val engine = LocalHeddle.fromTopology(farmTopology, policy, epoch = 0L).heddle
        val p = pool(slots = 1, engine = engine)
        p.acquire(web).granted()
        val b = backgroundScope.async { p.acquire(web) }
        runCurrent()
        val waited = !b.isCompleted
        assertFailsWith<IllegalArgumentException> { p.resize(2) }
        runCurrent()
        assertAll(
            { assertTrue(waited, "the rig: B waits behind the one slot") },
            { assertFalse(b.isCompleted, "the pool still has one slot") },
            { assertEquals(1, p.openForTest) },
            { assertEquals(1, p.waitersForTest) },
            { assertEquals(1L, engine.persist().totalSlots, "the engine kept its capacity") },
        )
    }

    /**
     * What this call answered, or `null` while it still waits. Read rather than awaited, so a waiter
     * the pool failed to grant reds the assertion that names it instead of hanging to the backstop.
     */
    private fun <T> Deferred<T>.settled(): T? = if (isCompleted) getCompleted() else null
}
