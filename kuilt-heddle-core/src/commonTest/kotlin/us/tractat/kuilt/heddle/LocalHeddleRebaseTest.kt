package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How far one rebase moves a parent's children (#3329): every child by the same virtual time `Δ`,
 * as far as the demanding child furthest behind allows and no further, an idle child included:
 * since #3353 the rebase raises the root's origin, so an idle child goes as far below zero as `Δ`
 * takes it. `R` is a sixteenth of a lane's ceiling.
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

    /** [lane]'s raw position in ticks: gross, less what it returned, relative to the root's origin. */
    private fun LocalHeddle.ticks(lane: GroupId): Long {
        val ledger = snapshot().ledger
        val edge = edgeOf(lane)
        val raw = ledger.gauge(edge).rawTicksAt(ledger.baseIssuance(edge), ledger.edge(edge)?.returned ?: 0L, Weight.ONE, ledger.origin(root))
        return checkNotNull(raw.toLongOrNull()) { "${lane.value} reads $raw ticks, past Long" }
    }

    /** Raise the root's origin by [units], every lane coming down by as many units. */
    private fun LocalHeddle.raiseOriginBy(units: Int128) {
        reshapeForTest { ledger -> ledger.piece(EntitlementLedger.of(origins = mapOf(root to ledger.origin(root) + units))) }
    }

    /** [lane]'s raw position, wide: gross, less what it returned, relative to the root's origin. */
    private fun LocalHeddle.wideTicks(lane: GroupId): Int128 {
        val ledger = snapshot().ledger
        val edge = edgeOf(lane)
        return ledger.gauge(edge).rawTicksAt(ledger.baseIssuance(edge), ledger.edge(edge)?.returned ?: 0L, Weight.ONE, ledger.origin(root))
    }

    /** [pushTo] for a position any distance from a raised origin: the gauge's ticks, wide, at the current issuance. */
    private fun LocalHeddle.pushToWide(lane: GroupId, ticks: Int128) {
        val edge = edgeOf(lane)
        reshapeForTest { ledger ->
            val returned = Int128.of((ledger.edge(edge)?.returned ?: 0L) * TICKS_PER_UNIT)
            val stored = ticks + returned + originTicks(ledger.origin(root), Weight.ONE)
            ledger.piece(EntitlementLedger.of(gauges = mapOf(edge to gaugeAt(stored, folded = ledger.baseIssuance(edge)))))
        }
        check(wideTicks(lane) == ticks) { "the rig put ${lane.value} at ${wideTicks(lane)}, not $ticks" }
    }

    /** Put [lane]'s position at exactly [ticks] by raising its gauge floor at its current fold. */
    private fun LocalHeddle.pushTo(lane: GroupId, ticks: Long) {
        val edge = edgeOf(lane)
        reshapeForTest { ledger ->
            val gauge = checkNotNull(ledger.gauge(edge)) { "${lane.value} is seated" }
            val issued = ledger.baseIssuance(edge)
            val origin = checkNotNull(originTicks(ledger.origin(root), Weight.ONE).toLongOrNull())
            val floorTicks = ticks + origin - (issued - gauge.folded) * TICKS_PER_UNIT
            ledger.piece(EntitlementLedger.of(gauges = mapOf(edge to Gauge(ticksToVirtual(floorTicks, Weight.ONE), gauge.folded))))
        }
        check(ticks(lane) == ticks) { "the rig put ${lane.value} at ${ticks(lane)}, not $ticks" }
    }

    /**
     * `a` is past the threshold and `b`, also waiting, sits 1,000 units and 5 ticks above its
     * origin. `Δ` is the pair's mean in whole units, `⌊(2R + 16,005) / 32⌋` (#3353, the window rule):
     * the rule before it stopped at `b`, 1,000 units, and held `b` at or above zero. So `b` goes below
     * zero, by about `R`, which is far inside its window. Every lane moves by `Δ`, `c` too although it
     * is idle. Then `b` is picked and delegated one unit.
     */
    @Test
    fun aDemandingChildFarBehindGoesBelowZeroInsideItsWindow() {
        val engine = engine()
        engine.pushTo(a, 2 * threshold)
        engine.pushTo(b, 16_005L)
        val cBefore = engine.ticks(c)
        val rebasesBefore = engine.rebasesForTest

        val out = engine.acquire(setOf(a, b), setOf(b), startedAt = 1L)
        val shift = (2 * threshold + 16_005L) / (2 * TICKS_PER_UNIT) * TICKS_PER_UNIT
        assertAll(
            { assertEquals(b, (out as AcquireOutcome.Acquired).lease.leaf) },
            { assertEquals(rebasesBefore + 1, engine.rebasesForTest, "one rebase") },
            { assertEquals(2 * threshold - shift, engine.ticks(a), "a moved by Δ") },
            { assertEquals(16_005L - shift + TICKS_PER_UNIT, engine.ticks(b), "b moved by Δ, below zero, then took its unit") },
            { assertTrue(engine.ticks(b) < -threshold / 2, "b went about R below zero: ${engine.ticks(b)}") },
            { assertTrue(engine.ticks(b) > positionFloor(Weight.ONE), "and stayed inside its window") },
            { assertEquals(cBefore - shift, engine.ticks(c), "idle c moved by Δ too") },
        )
    }

    /**
     * A joiner seated where the front is below its floor lands at the floor (#3353, the window rule).
     * The rig of [aDemandingChildFarBehindGoesBelowZeroInsideItsWindow] leaves `b` about `R` below
     * zero; then `a` stops asking and a fresh lane `n` at weight 2²⁰ joins beside `b` alone. The
     * front is `b`'s, which on `n`'s grid is 2²⁰ times as far below zero: far past `n`'s floor. `n`
     * is seated at its floor instead, above the front, so it is not credited the difference; it
     * waits its turn behind `b`, the penalty side.
     */
    @Test
    fun aHeavyNewbornBesideARunnerBelowZeroLandsAtItsFloor() {
        val engine = engine()
        engine.pushTo(a, 2 * threshold)
        engine.pushTo(b, 16_005L)
        engine.acquire(setOf(a, b), setOf(b), startedAt = 1L)
        val bAt = engine.ticks(b)
        check(bAt < -threshold / 2) { "the rig: b sits about R below zero, at $bAt" }
        val heavy = Weight.of(1L shl 20)
        val withHeavy = draft("rebase") {
            lane("a", Weight.ONE)
            lane("b", Weight.ONE)
            lane("c", Weight.ONE)
            lane("n", heavy)
        }
        check(engine.apply(withHeavy, generation = 1L).refusals.isEmpty()) { "the rig: n landed" }
        val n = withHeavy.groupId("n")
        val landingsBefore = engine.floorLandingsForTest
        val out = engine.acquire(setOf(b, n), setOf(b, n), startedAt = 2L)
        val ledger = engine.snapshot().ledger
        val nEdge = engine.edgeOf(n)
        val nAt = ledger.gauge(nEdge).rawTicksAt(ledger.baseIssuance(nEdge), ledger.edge(nEdge)?.returned ?: 0L, heavy, ledger.origin(engine.root))
        assertAll(
            { assertEquals(landingsBefore + 1, engine.floorLandingsForTest, "the rig: one joiner landed at its floor") },
            { assertEquals(Int128.of(positionFloor(heavy)), nAt, "n sits at its floor") },
            { assertTrue(nAt > bAt.timesWide(1L shl 20), "above the front on its grid, so it is owed nothing") },
            { assertEquals(b, (out as AcquireOutcome.Acquired).lease.leaf, "and b, behind it, is served first") },
        )
    }

    /**
     * The window's own bound (#3353): `a` and `c` wait at their ceilings and `b` waits ten units above
     * its floor, `−C`. Their mean is far up, so the front does not bind; `b`'s room does, and `Δ` is
     * ten units exactly, which lands `b` on its floor. The rule before it bounded `Δ` by `b`'s
     * position, below zero, so it did not move at all.
     */
    @Test
    fun aDemandingChildNearItsFloorBoundsTheShiftByItsRoom() {
        val engine = engine()
        engine.raiseOriginBy(FLOOR_ORIGIN)
        engine.pushToWide(a, Int128.of(positionCeiling(Weight.ONE)))
        engine.pushToWide(c, Int128.of(positionCeiling(Weight.ONE)))
        engine.pushToWide(b, Int128.of(positionFloor(Weight.ONE) + 10L * TICKS_PER_UNIT))
        val wants = Demand(targetOutstanding = Long.MAX_VALUE, maximumUsefulGrant = 1L)
        val allocator = FairShareAllocator(LocalHeddle.SELF, PolicyConfig(quantum = 1L))
        val rebase = allocator.originRebase(engine.root, engine.snapshot().ledger) { wants }
        assertEquals(Int128.of(10L), rebase, "Δ is b's room, ten units, which lands it on its floor")
    }

    /**
     * A `LocalHeddle` rebase raises the parent's origin and rewrites no gauge (#3353, slice 7). The
     * rig is [aDemandingChildFarBehindGoesBelowZeroInsideItsWindow]'s: `Δ` is the pair's mean in
     * whole units. The fold rebase it replaces raised `a`'s and `c`'s folds by `Δ` and left the
     * origin at zero.
     */
    @Test
    fun aLocalRebaseRaisesTheOriginAndNoFold() {
        val engine = engine()
        engine.pushTo(a, 2 * threshold)
        engine.pushTo(b, 16_005L)
        val before = engine.snapshot().ledger
        val rebasesBefore = engine.rebasesForTest

        engine.acquire(setOf(a, b), setOf(b), startedAt = 1L)
        val after = engine.snapshot().ledger
        assertAll(
            { assertEquals(rebasesBefore + 1, engine.rebasesForTest, "the rig: one rebase") },
            { assertEquals(Int128.of((2 * threshold + 16_005L) / (2 * TICKS_PER_UNIT)), after.origin(engine.root), "the origin rose by Δ") },
            { assertEquals(before.gauge(engine.edgeOf(a)), after.gauge(engine.edgeOf(a)), "a's gauge is as it was") },
            { assertEquals(before.gauge(engine.edgeOf(c)), after.gauge(engine.edgeOf(c)), "and so is c's") },
        )
    }

    /**
     * A heavy newborn seated through `admit` beside a busy runner (#3353 round 2). The runner `a`,
     * the one lane last seen waiting, sits just under its own threshold `R`. A fresh lane `n` at
     * weight 60 is admitted before any pick, so it is seated by the delegation, not by a pick: at
     * `a`'s front, about `60 · R` on its own grid, past its ceiling.
     * The repair used to bail at that child's ceiling, the charge rebase and the next pick's alike,
     * so the parent refused every acquire after.
     */
    @Test
    fun aHeavyNewbornSeatedByAdmitDoesNotWedgeTheParent() {
        val engine = engine()
        // b and c are seen idle, so a is the one runner, the front n is seated at.
        pickOnEach(listOf(engine), setOf(a), at = 1L)
        engine.pushTo(a, threshold - TICKS_PER_UNIT)
        val withHeavy = draft("rebase") {
            lane("a", Weight.ONE)
            lane("b", Weight.ONE)
            lane("c", Weight.ONE)
            lane("n", Weight.of(60L))
        }
        check(engine.apply(withHeavy, generation = 1L).refusals.isEmpty()) { "the rig: n landed" }
        val n = withHeavy.groupId("n")
        val admitted = engine.admit(n, startedAt = 1L)
        check(admitted is AcquireOutcome.Acquired) { "the rig: n was admitted, $admitted" }
        engine.complete(admitted.lease.id, actualCost = 1L)
        val outcome = engine.acquire(setOf(a, b, c, n), setOf(a, b, c, n), startedAt = 2L)
        assertAll(
            { assertTrue(outcome is AcquireOutcome.Acquired, "the parent is not wedged: $outcome") },
            { assertTrue(engine.rebasesForTest > 0, "the rig: it rebased") },
        )
    }

    /**
     * The same heavy newborn, admitted beside two runners far apart (#3353, the window rule). `a`
     * sits just under `R` and `b` within a unit of its origin, both last seen waiting, so the front `n` is seated
     * at is half `a`'s position, which on `n`'s grid is past its ceiling. The charge rebase used to
     * stop at `b`, the runner furthest behind: one unit, which left `n` past its ceiling, and the parent
     * refused every acquire after. It now lowers the origin to the front its demanding children
     * stand at, `n` included, and lets `b` go below zero, inside its window.
     */
    @Test
    fun aHeavyNewbornAdmittedBesideTwoRunnersFarApartDoesNotWedgeTheParent() {
        val engine = engine()
        pickOnEach(listOf(engine), setOf(a, b), at = 1L)
        engine.pushTo(a, threshold - TICKS_PER_UNIT)
        check(engine.ticks(b) in 0L..TICKS_PER_UNIT) { "the rig: b sits within a unit of its origin, at ${engine.ticks(b)}" }
        val withHeavy = draft("rebase") {
            lane("a", Weight.ONE)
            lane("b", Weight.ONE)
            lane("c", Weight.ONE)
            lane("n", Weight.of(60L))
        }
        check(engine.apply(withHeavy, generation = 1L).refusals.isEmpty()) { "the rig: n landed" }
        val n = withHeavy.groupId("n")
        val rebasesBefore = engine.rebasesForTest
        val admitted = engine.admit(n, startedAt = 1L)
        check(admitted is AcquireOutcome.Acquired) { "the rig: n was admitted, $admitted" }
        engine.complete(admitted.lease.id, actualCost = 1L)
        val outcome = engine.acquire(setOf(a, b, n), setOf(a, b, n), startedAt = 2L)
        assertAll(
            { assertTrue(outcome is AcquireOutcome.Acquired, "the parent is not wedged: $outcome") },
            { assertTrue(engine.rebasesForTest > rebasesBefore, "the rig: the admit rebased") },
            { assertTrue(engine.ticks(b) < 0L, "b went below zero, as only the window rule takes it: ${engine.ticks(b)}") },
            { assertTrue(engine.ticks(b) >= -positionCeiling(Weight.ONE), "and stayed inside its window") },
        )
    }

    /**
     * `a` and `b` both wait, at `3R` and `2R`, so `Δ` is their mean, about `2.5R` (#3353, the window
     * rule); `c` is idle a few ticks above its origin and moves the whole `Δ`, to about `−2.5R`. The
     * fold rebase this replaced stopped it at `−R`. The wake clamp takes all of it back, since it is taken wide: when `c` wakes
     * beside `a` it is clamped to `a`'s front and served, and the engine picks exactly as a twin that
     * never rebased.
     */
    @Test
    fun anIdleChildFarBehindMovesTheWholeShiftAndItsWakeTakesItBack() {
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
            { assertTrue(shift > 5 * threshold / 2 - TICKS_PER_UNIT && shift <= 5 * threshold / 2, "Δ is a and b's mean in whole units: $shift") },
            { assertEquals(shift, cBefore - afterRebase, "c moved the whole Δ, past −R") },
            { assertTrue(afterRebase < -threshold, "the rig: c is below −R, at $afterRebase") },
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
        check(cOffset != null && cOffset > Int128.ZERO) { "the rig: c carries a wake offset, not $cOffset" }

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
     * the two idle lanes wake far behind it, below `−R` in the rebasing engine. With no one left
     * to be level with, the wakers are clamped to the one furthest ahead and the newborn is seated
     * there too (#3329 review): seating it at its own origin instead would leave it about `R` ahead
     * of the lifted wakers, and they would take every pick until they caught up.
     */
    @Test
    fun aNewbornAmongWakersMeetsTheWakerFurthestAhead() = assertNewbornJoinsLevel(waking = setOf(b, c))

    /**
     * The same, with `c` left idle below `−R` in the rebasing engine. No child outside the joiners is
     * demanding, so the front is the furthest-ahead child at the parent, the newborn counted at its
     * own origin (§7.2, #3353): the waker `b` is clamped level with the newborn, not left below `−R`
     * beside the idle `c`, `R` behind it (#3329 re-review).
     */
    @Test
    fun aNewbornBesideAWakerAndAnIdleSiblingStartsLevel() = assertNewbornJoinsLevel(waking = setOf(b))

    private fun assertNewbornJoinsLevel(waking: Set<GroupId>) {
        val engine = engine()
        val twin = engine(rebase = false)
        val both = listOf(engine, twin)
        // b and c are seen idle, then a runs alone from far up, so the engine rebases past them.
        pickOnEach(both, setOf(a), at = 1L)
        for (e in both) e.pushTo(a, 3 * threshold)
        pickOnEach(both, setOf(a), at = 2L)
        val lifted = engine.ticks(b)
        check(lifted < -threshold) { "the rig: b moved the whole Δ, below −R, not to $lifted" }
        check(twin.ticks(b) >= 0L) { "the twin's b is where it was" }

        val oldEdge = engine.edgeOf(a)
        val reweighted = draft("rebase") {
            lane("a", Weight.of(2))
            lane("b", Weight.ONE)
            lane("c", Weight.ONE)
        }
        for (e in both) check(e.apply(reweighted, generation = 1L).refusals.isEmpty()) { "the reweight landed" }
        check(engine.edgeOf(a) != oldEdge) { "the rig: a's edge is a newborn" }

        val waiting = waking + a
        val picks = (0 until 40).map { i -> pickOnEach(both, waiting, at = 3L + i) }
        assertAll(
            { assertEquals(picks.map { it[1] }, picks.map { it[0] }, "every pick is the twin's") },
            { assertTrue(waiting.all { lane -> picks.any { it[0] == lane } }, "and nobody waiting starves: ${picks.map { it[0].value }}") },
        )
    }

    /**
     * A parent with a child at its ceiling is rebased like any other (#3353 round 2). Every read up
     * to the effective position is wide and exact, so the position there stands for no more than it
     * says, and the raise is the one repair for a child a delegation seated past its ceiling. With
     * every other child far above zero, the step is not held at zero. Since review of #3352 this was
     * refused, to guard a saturated position that no longer exists.
     */
    @Test
    fun aParentWithAChildAtItsCeilingIsRebased() {
        val engine = engine()
        engine.pushTo(a, positionCeiling(Weight.ONE))
        engine.pushTo(b, 2 * threshold)
        engine.pushTo(c, 2 * threshold)
        val wants = Demand(targetOutstanding = Long.MAX_VALUE, maximumUsefulGrant = 1L)
        val allocator = FairShareAllocator(LocalHeddle.SELF, PolicyConfig(quantum = 1L))
        val rebase = allocator.originRebase(engine.root, engine.snapshot().ledger) { wants }
        assertTrue(rebase != null && rebase > us.tractat.kuilt.math.Int128.ZERO, "the parent is rebased: $rebase")
    }

    private companion object {
        /** An origin at which a lane at zero reads one tick below its floor: `16 · O = C + 1`. */
        val FLOOR_ORIGIN: Int128 = Int128.of(positionCeiling(Weight.ONE) / TICKS_PER_UNIT + 1L)
    }
}
