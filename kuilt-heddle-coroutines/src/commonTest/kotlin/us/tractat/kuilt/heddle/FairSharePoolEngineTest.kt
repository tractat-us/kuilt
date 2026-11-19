package us.tractat.kuilt.heddle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

    /** Ten times the default reservation of 3, so a settle at it overruns by 27. */
    private val overrunCost = 30L

    /** Waiters queued behind one slot, more than the supply minted ahead at one slot can grant. */
    private val queuedWaiters = 8

    @Test
    fun resizeFromZeroGrantsInThePreviewOrder() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 0)
        val grants = mutableListOf<Lease>()
        (List(20) { web } + List(20) { mobile }).forEach { leaf ->
            backgroundScope.launch { grants += p.acquire(leaf).granted() }
        }
        runCurrent()
        val queuedAtZero = p.waitersForTest
        val openAtZero = p.view().open

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
            { assertEquals(6, p.view().open) },
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
        val openAfterResize = p.view().open
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
        val reload = p.withEngine { engine, _ -> engine.apply(farmTopology, 1L) }
        runCurrent()
        val secondOut = second.settled()
        assertAll(
            { assertTrue(waitedAtCeiling, "the rig: web's second waiter waits at its ceiling") },
            { assertEquals(emptyList(), reload.refusals) },
            { assertEquals(web, secondOut?.granted()?.leaf) },
            { assertEquals(2, p.view().open) },
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
        val reload = p.withEngine { engine, _ -> engine.apply(webOnly, 1L) }
        runCurrent()
        val gone = SlotOutcome.Refused(WaitExplanation.NoLiveLineage(mobile))
        val outs = waiters.map { it.settled() }
        assertAll(
            { assertEquals(2, queued, "the rig: both mobile waiters queue behind web's lease") },
            { assertEquals(emptyList(), reload.refusals) },
            { assertEquals(listOf(gone, gone), outs) },
            { assertEquals(0, p.waitersForTest) },
            { assertEquals(1, p.view().open) },
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
        p.withEngine { engine, _ ->
            inBlock = true
            engine.apply(webOnly, 1L)
            p.withEngine { _, _ -> }
            w.cancel()
            waitersAfterCancel = p.waitersForTest
            inBlock = false
        }
        runCurrent()
        val mOut = m.settled()
        p.beforeResumeForTest = {}
        val openWhileAHeld = p.view().open
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
            p.withEngine { engine, _ ->
                engine.apply(farmTopology, 1L)
                error("boom")
            }
        }
        runCurrent()
        val secondOut = second.settled()
        assertAll(
            { assertEquals(1, queued, "the rig: web's second waiter waits at its ceiling") },
            { assertEquals("boom", thrown.message, "the block's own throwable reaches the caller") },
            { assertEquals(web, secondOut?.granted()?.leaf) },
            { assertEquals(2, p.view().open) },
        )
    }

    @Test
    fun aLeaseCompletedThroughTheEngineFreesItsSlot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val b = backgroundScope.async { p.acquire(web) }
        runCurrent()
        val waited = !b.isCompleted
        val done = p.withEngine { engine, view -> engine.complete(a.id, 3L, view.waiting) }
        runCurrent()
        val bOut = b.settled()
        assertAll(
            { assertTrue(waited, "the rig: B waits behind A") },
            { assertEquals(LeaseOutcome.Done(3L, 0L, 0L), done) },
            { assertEquals(web, bOut?.granted()?.leaf, "B is granted the slot A's completion freed") },
            { assertEquals(1, p.view().open) },
        )
    }

    /**
     * A stale holder's late [FairSharePool.complete], after its lease was closed through the
     * engine and the slot granted again, closes nothing: the new lease has a new id.
     */
    @Test
    fun aLateCompleteAfterAnEngineCloseLeavesTheNextLeaseOpen() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val a = p.acquire(web).granted()
        val b = backgroundScope.async { p.acquire(web) }
        runCurrent()
        p.withEngine { engine, _ -> engine.cancel(a.id) }
        runCurrent()
        val bLease = b.settled()?.granted()
        val late = p.complete(a.id, 3L)
        assertAll(
            { assertTrue(bLease != null && bLease.id != a.id, "the rig: B holds the freed slot under a new id") },
            { assertEquals(LeaseOutcome.Unknown, late) },
            { assertEquals(listOfNotNull(bLease?.id), engine.snapshot().leases.map { it.id }, "B's lease is still open") },
            { assertEquals(1, p.view().open) },
        )
    }

    /**
     * A target one slot can size and two cannot: `totalSlots × 3 × targetSlots` must stay at or below
     * `Long.MAX_VALUE / 4`, and `targetSlots` is a third of that, so the bound is exactly one slot.
     */
    /**
     * A lease a block opens through the engine is never counted against the slots, and completing
     * it through the pool frees none. Web holds one of two slots; the block opens a mobile lease
     * beside it. After it closes, one slot is still free and no more: of a web and a mobile waiter,
     * exactly one is granted. A pool that counted the block's lease, or freed a slot for it, would
     * grant both or neither.
     */
    @Test
    fun aLeaseABlockOpensIsNeverCountedAndFreesNoSlot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 2)
        val a = p.acquire(web).granted()
        val openBefore = p.view().open
        val stray = p.withEngine { e, _ -> e.acquire(setOf(mobile), setOf(mobile), p.now()) }
        val strayLease = (stray as? AcquireOutcome.Acquired)?.lease
        val openAfterBlock = p.view().open
        val closed = strayLease?.let { p.complete(it.id, 3L) }
        val openAfterComplete = p.view().open
        val w = async { p.acquire(web) }
        val m = async { p.acquire(mobile) }
        runCurrent()
        val granted = listOf(w, m).count { it.isCompleted }
        // Free whichever waiter is still queued, so a regression reds instead of hanging.
        w.cancel()
        m.cancel()
        assertAll(
            { assertEquals(1, openBefore, "the rig: A holds one of two slots") },
            { assertEquals(mobile, strayLease?.leaf, "the rig: the block opened a lease, was $stray") },
            { assertEquals(1, openAfterBlock, "the block's lease is never counted") },
            { assertEquals(LeaseOutcome.Done(charged = 3L, overrun = 0L, unrecorded = 0L), closed) },
            { assertEquals(1, openAfterComplete, "completing an uncounted lease frees no slot") },
            { assertEquals(1, granted, "one slot is free, and only one") },
        )
        p.complete(a.id, 3L)
    }

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
            { assertEquals(1, p.view().open) },
            { assertEquals(1, p.waitersForTest) },
            { assertEquals(1L, engine.persist().totalSlots, "the engine kept its capacity") },
        )
    }

    /**
     * The waiting set a block is handed, [PoolView.waiting], is the one [FairSharePool.complete] uses: an overrunning
     * complete through the engine, given it, lands exactly as the pool's own complete does on a
     * twin pool. An overrun observes the waiting set as demand, so a set missing mobile's queued
     * waiters would change who goes next.
     */
    @Test
    fun anOverrunThroughTheEngineWithTheHandedSetMatchesThePoolsComplete() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val throughEngine = overrunScenario { p, a ->
            p.withEngine { engine, view -> engine.complete(a, overrunCost, view.waiting) }
        }
        val throughPool = overrunScenario { p, a -> p.complete(a, overrunCost) }
        assertAll(
            { assertEquals(setOf(web, mobile), throughEngine.handed, "the rig: both lanes are queued as the block starts") },
            { assertEquals(LeaseOutcome.Done(overrunCost, overrunCost - 3L, 0L), throughEngine.done, "the rig: it overruns") },
            { assertEquals(throughPool.done, throughEngine.done) },
            { assertEquals(throughPool.grants, throughEngine.grants, "the next grants") },
            { assertEquals(throughPool.preview, throughEngine.preview, "who goes after") },
        )
    }

    /**
     * A block's own [LocalHeddle.reconcileCapacity] is undone when it ends: the pool's count stands.
     * A zeroed engine mints no supply ahead, so the supply minted before the block runs out after a
     * few grants and the next one is [AcquireOutcome.SupplyExhausted], which fails every waiter.
     * Eight waiters in turn are enough to get there.
     */
    @Test
    fun aBlockThatZeroesTheEnginesCapacityStillLeavesEveryWaiterGranted() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val engine = farm()
        val p = pool(slots = 1, engine = engine)
        val first = p.acquire(web).granted()
        val grants = mutableListOf<Lease>()
        val failures = mutableListOf<String?>()
        repeat(queuedWaiters) {
            backgroundScope.launch {
                try {
                    grants += p.acquire(web).granted()
                } catch (failure: IllegalStateException) {
                    currentCoroutineContext().ensureActive()
                    failures += failure.message
                }
            }
        }
        runCurrent()
        val queued = p.waitersForTest
        val inBlock = p.withEngine { e, _ ->
            e.reconcileCapacity(0L)
            e.persist().totalSlots
        }
        val afterBlock = engine.persist().totalSlots
        var held = first
        for (next in 0 until queuedWaiters) {
            p.complete(held.id, 3L)
            runCurrent()
            held = grants.getOrNull(next) ?: break
        }
        assertAll(
            { assertEquals(queuedWaiters, queued, "the rig: every waiter queues behind the one slot") },
            { assertEquals(0L, inBlock, "the rig: the block zeroed the engine's capacity") },
            { assertEquals(1L, afterBlock, "the pool reconciled the engine back to its count") },
            { assertEquals(emptyList(), failures, "no waiter is failed") },
            { assertEquals(queuedWaiters, grants.size, "each waiter is granted the slot the one before freed") },
        )
    }

    /** The catch-up runs however the block ends: a lease it closed and then threw still frees its slot. */
    @Test
    fun aBlockThatClosesAPoolLeaseAndThrowsStillFreesTheSlot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val b = backgroundScope.async { p.acquire(web) }
        runCurrent()
        val waited = !b.isCompleted
        var done: LeaseOutcome? = null
        assertFailsWith<Boom> {
            p.withEngine { engine, view ->
                done = engine.complete(a.id, 3L, view.waiting)
                throw Boom()
            }
        }
        runCurrent()
        val bOut = b.settled()
        assertAll(
            { assertTrue(waited, "the rig: B waits behind A") },
            { assertEquals(LeaseOutcome.Done(3L, 0L, 0L), done, "the rig: the block closed A before it threw") },
            { assertEquals(web, bOut?.granted()?.leaf, "B is granted the slot A's completion freed") },
            { assertEquals(1, p.view().open, "A is no longer counted, B is") },
        )
    }

    /** What [overrunScenario] saw: the settle's answer, the grants after it, and the preview after those. */
    private data class Overrun(val handed: Set<GroupId>, val done: LeaseOutcome, val grants: List<GroupId>, val preview: List<GroupId>)

    /**
     * One slot, held by web, with three waiters queued in each lane; [settle] closes web's lease at
     * an overrun. Answers the leaves the next three grants went to, each closed at its reservation
     * in turn, and who the engine would pick after them.
     */
    private suspend fun TestScope.overrunScenario(settle: (FairSharePool, LeaseId) -> LeaseOutcome): Overrun {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val grants = mutableListOf<Lease>()
        (List(3) { mobile } + List(3) { web }).forEach { leaf ->
            backgroundScope.launch { grants += p.acquire(leaf).granted() }
        }
        runCurrent()
        val handed = p.withEngine { _, view -> view.waiting }
        val done = settle(p, a.id)
        runCurrent()
        repeat(2) {
            p.complete(grants.last().id, 3L)
            runCurrent()
        }
        val preview = p.withEngine { engine, _ ->
            engine.previewPicks(mapOf(web to 10, mobile to 10), setOf(web, mobile), List(6) { 3L })
        }
        return Overrun(handed, done, grants.map { it.leaf }, preview)
    }

    /**
     * What this call answered, or `null` while it still waits. Read rather than awaited, so a waiter
     * the pool failed to grant reds the assertion that names it instead of hanging to the backstop.
     */
    private fun <T> Deferred<T>.settled(): T? = if (isCompleted) getCompleted() else null
}
