package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How far one rebase moves a parent's children (#3329): every child by the same virtual time `Δ`,
 * as far as the demanding child furthest behind allows and no further, and an idle child only as
 * far as keeps it at `−R`, where `R` is a sixteenth of its ceiling.
 *
 * Positions are planted by raising a lane's gauge floor ([pushTo]), a write any peer could make.
 * Every lane weighs 1, so `Δ` in ticks is the same number on every lane. Each arm asserts first
 * that the rig put the lanes where it meant to.
 */
class LocalHeddleRebaseTest {

    private val topology = draft("rebase") {
        lane("a", Weight.ONE)
        lane("b", Weight.ONE)
        lane("c", Weight.ONE)
    }
    private val a = topology.groupId("a")
    private val b = topology.groupId("b")
    private val c = topology.groupId("c")

    /** `R` for a lane of weight 1, in ticks. */
    private val threshold = positionCeiling(Weight.ONE) / 16

    /** An engine in which every lane has been picked once, so every lane is seated and observed waiting. */
    private fun engine(rebase: Boolean = true, over: Topology = topology) =
        LocalHeddle.fromTopology(over, LocalPolicy(defaultReservation = 1L), epoch = 1L)
            .apply {
                rebaseForTest = rebase
                reconcileCapacity(1L)
                repeat(3) {
                    val lease = (acquire(setOf(a, b, c), setOf(a, b, c), startedAt = 0L) as AcquireOutcome.Acquired).lease
                    complete(lease.id, actualCost = 1L)
                }
            }

    /** One acquire and its completion at cost 1 on each engine, returning the leaves they picked. */
    private fun pickOnEach(engines: List<LocalHeddle>, waiting: Set<GroupId>, at: Long): List<GroupId> = engines.map { e ->
        val lease = (e.acquire(waiting, waiting, startedAt = at) as AcquireOutcome.Acquired).lease
        e.complete(lease.id, actualCost = 1L)
        lease.leaf
    }

    private fun LocalHeddle.edgeOf(lane: GroupId): AttachmentId =
        checkNotNull(snapshot().ledger.lineageOf(lane)?.last()) { "${lane.value} has no lineage" }

    /** [lane]'s raw position in ticks: gross, less what it returned. */
    private fun LocalHeddle.ticks(lane: GroupId): Long {
        val ledger = snapshot().ledger
        val edge = edgeOf(lane)
        val gross = ledger.gauge(edge).grossTicksAt(ledger.baseIssuance(edge), Weight.ONE)
        return netTicks(gross, ledger.edge(edge)?.returned ?: 0L, Weight.ONE)
    }

    /** Put [lane]'s position at exactly [ticks] by raising its gauge floor at its current fold. */
    private fun LocalHeddle.pushTo(lane: GroupId, ticks: Long) {
        val edge = edgeOf(lane)
        reshapeForTest { ledger ->
            val gauge = checkNotNull(ledger.gauge(edge)) { "${lane.value} is seated" }
            val issued = ledger.baseIssuance(edge)
            val floorTicks = ticks - (issued - gauge.folded) * TICKS_PER_UNIT
            ledger.piece(EntitlementLedger.of(gauges = mapOf(edge to Gauge(ticksToVirtual(floorTicks, Weight.ONE), gauge.folded))))
        }
        check(ticks(lane) == ticks) { "the rig put ${lane.value} at ${ticks(lane)}, not $ticks" }
    }

    /**
     * `a` is past the threshold and `b`, also waiting, sits 1,000 units and 5 ticks above its
     * origin. `Δ` is 1,000 units: the most that keeps `b` at or above zero. Every lane moves by it,
     * `c` too although it is idle, since 1,000 units is nowhere near `−R`. Then `b` is picked and
     * delegated one unit.
     */
    @Test
    fun aDemandingChildFarBehindHoldsTheShiftBack() {
        val engine = engine()
        engine.pushTo(a, 2 * threshold)
        engine.pushTo(b, 16_005L)
        val cBefore = engine.ticks(c)
        val rebasesBefore = engine.rebasesForTest

        val out = engine.acquire(setOf(a, b), setOf(b), startedAt = 1L)
        val shift = 1_000L * TICKS_PER_UNIT
        assertAll(
            { assertEquals(b, (out as AcquireOutcome.Acquired).lease.leaf) },
            { assertEquals(rebasesBefore + 1, engine.rebasesForTest, "one rebase") },
            { assertEquals(2 * threshold - shift, engine.ticks(a), "a moved by Δ") },
            { assertEquals(5L + TICKS_PER_UNIT, engine.ticks(b), "b moved by Δ, to 5 ticks, then took its unit") },
            { assertEquals(cBefore - shift, engine.ticks(c), "idle c moved by Δ too: it is far above −R") },
        )
    }

    /**
     * `a` and `b` both wait, `b` the lower at `2R`, so `Δ` is about `2R`; `c` is idle a few ticks
     * above its origin, and moving it the whole way would put it near `−R` below `−R`. It moves only
     * to `−R` instead: credit the wake clamp takes back. And it does take it back. When `c` wakes
     * beside `a` it is clamped to `a`'s front and served, and the engine picks exactly as a twin
     * that never rebased.
     */
    @Test
    fun anIdleChildFarBehindIsLiftedOnlyToMinusR() {
        val engine = engine()
        val twin = engine(rebase = false)
        for (e in listOf(engine, twin)) {
            e.pushTo(a, 3 * threshold)
            e.pushTo(b, 2 * threshold)
        }
        val aBefore = engine.ticks(a)
        val cBefore = engine.ticks(c)
        check(cBefore in 0L..(4 * TICKS_PER_UNIT)) { "the rig: c sits a few units above its origin, at $cBefore" }

        val out = engine.acquire(setOf(a, b), setOf(b), startedAt = 1L)
        twin.acquire(setOf(a, b), setOf(b), startedAt = 1L)
        val shift = aBefore - engine.ticks(a)
        val afterRebase = engine.ticks(c)
        val pickedAfterWake = (0 until 40).map { i ->
            val waiting = if (i % 3 == 0) setOf(a, b, c) else setOf(a, c)
            listOf(engine, twin).map { e ->
                val lease = (e.acquire(waiting, waiting, startedAt = 2L + i) as AcquireOutcome.Acquired).lease
                e.complete(lease.id, actualCost = 1L)
                lease.leaf
            }
        }
        assertAll(
            { assertEquals(b, (out as AcquireOutcome.Acquired).lease.leaf) },
            { assertTrue(shift > 2 * threshold - TICKS_PER_UNIT && shift <= 2 * threshold, "Δ is b's position in whole units: $shift") },
            { assertTrue(afterRebase >= -threshold && afterRebase < -threshold + TICKS_PER_UNIT, "c lands at −R, not ${afterRebase + threshold} ticks from it") },
            { assertTrue(cBefore - afterRebase < shift, "c moved less than Δ") },
            { assertEquals(pickedAfterWake.map { it[1] }, pickedAfterWake.map { it[0] }, "once c wakes, every pick is the twin's") },
            { assertTrue(pickedAfterWake.any { it[0] == c }, "and c was picked, so the wake was reached") },
        )
    }

    /**
     * An acquire refused with `NoLiveLineage` keeps what its pick observed and nothing else, even at
     * a parent past the threshold whose waker carries an offset a rebase would absorb (#3329 review).
     * The offset and the folds are one change: keeping the lowered offset while the refused call
     * drops the raised folds would hand the waker its offset back as credit.
     *
     * `g` is a group with a child, so it is never leasable, and naming it as the only eligible group
     * leaves the root's pick with no candidate.
     */
    @Test
    fun aRefusedAcquireKeepsNoPartOfARebase() {
        val withGroup = draft("rebase") {
            lane("a", Weight.ONE)
            lane("b", Weight.ONE)
            lane("c", Weight.ONE)
            group("g", Weight.ONE) { lane("x", Weight.ONE) }
        }
        val g = withGroup.groupId("g")
        val engine = engine(over = withGroup)
        val twin = engine(rebase = false, over = withGroup)
        val both = listOf(engine, twin)
        // c sits out six picks and then wakes, so it carries a wake offset.
        repeat(6) { pickOnEach(both, setOf(a, b), at = 1L) }
        pickOnEach(both, setOf(a, b, c), at = 2L)
        for (e in both) {
            e.pushTo(a, 3 * threshold)
            e.pushTo(b, 2 * threshold)
            e.pushTo(c, 2 * threshold)
        }
        val memoryBefore = engine.snapshot().allocatorMemory
        val cOffset = memoryBefore.wakeOffsets[engine.edgeOf(c)]
        check(cOffset != null && cOffset > Rational.ZERO) { "the rig: c carries a wake offset, not $cOffset" }

        val refused = (0 until 3).map { engine.acquire(setOf(a, b, c, g), setOf(g), startedAt = 3L) }
        repeat(3) { twin.acquire(setOf(a, b, c, g), setOf(g), startedAt = 3L) }
        val memoryAfter = engine.snapshot().allocatorMemory
        val picks = (0 until 30).map { i -> pickOnEach(both, setOf(a, b, c), at = 4L + i) }
        assertAll(
            { assertEquals(List(3) { AcquireOutcome.NoLiveLineage(engine.root) }, refused) },
            { assertEquals(memoryBefore.wakeOffsets, memoryAfter.wakeOffsets, "no wake offset moved") },
            { assertEquals(picks.map { it[1] }, picks.map { it[0] }, "and the picks after are the twin's") },
            { assertTrue(engine.rebasesForTest >= 1, "the picks after did rebase: ${engine.rebasesForTest}") },
        )
    }

    /**
     * Every active child a joiner: the busy lane is reweighted, so its new edge is a newborn, while
     * the two idle lanes wake far behind it, lifted to `−R` in the rebasing engine. With no one left
     * to be level with, the wakers are clamped to the one furthest ahead and the newborn is seated
     * there too (#3329 review): seating it at its own origin instead would leave it about `R` ahead
     * of the lifted wakers, and they would take every pick until they caught up.
     */
    @Test
    fun aNewbornAmongWakersMeetsTheWakerFurthestAhead() {
        val engine = engine()
        val twin = engine(rebase = false)
        val both = listOf(engine, twin)
        // b and c are seen idle, then a runs alone from far up, so the engine rebases past them.
        pickOnEach(both, setOf(a), at = 1L)
        for (e in both) e.pushTo(a, 3 * threshold)
        pickOnEach(both, setOf(a), at = 2L)
        val lifted = engine.ticks(b)
        check(lifted >= -threshold && lifted < -threshold + TICKS_PER_UNIT) { "the rig: b was lifted to −R, not $lifted" }
        check(twin.ticks(b) >= 0L) { "the twin's b is where it was" }

        val oldEdge = engine.edgeOf(a)
        val reweighted = draft("rebase") {
            lane("a", Weight.of(2))
            lane("b", Weight.ONE)
            lane("c", Weight.ONE)
        }
        for (e in both) check(e.apply(reweighted, generation = 1L).refusals.isEmpty()) { "the reweight landed" }
        check(engine.edgeOf(a) != oldEdge) { "the rig: a's edge is a newborn" }

        val picks = (0 until 40).map { i -> pickOnEach(both, setOf(a, b, c), at = 3L + i) }
        assertAll(
            { assertEquals(picks.map { it[1] }, picks.map { it[0] }, "every pick is the twin's") },
            { assertTrue(picks.any { it[0] == a }, "and the newborn is served") },
        )
    }

    /**
     * A parent with a child at its ceiling is not rebased: that position may stand for more, and its
     * floor stays at the ceiling whatever the fold says. The engine's pick refuses before it would
     * rebase, so this asks the rebase itself, with every other child far above zero, where a missing
     * guard would move them.
     */
    @Test
    fun aParentWithAChildAtItsCeilingIsNotRebased() {
        val engine = engine()
        engine.pushTo(a, positionCeiling(Weight.ONE))
        engine.pushTo(b, 2 * threshold)
        engine.pushTo(c, 2 * threshold)
        val wants = Demand(targetOutstanding = Long.MAX_VALUE, maximumUsefulGrant = 1L)
        val allocator = FairShareAllocator(LocalHeddle.SELF, PolicyConfig(quantum = 1L))
        val rebase = allocator.rebased(engine.root, engine.snapshot().ledger) { wants }
        assertNull(rebase, "no rebase at a parent with a child at its ceiling")
    }
}
