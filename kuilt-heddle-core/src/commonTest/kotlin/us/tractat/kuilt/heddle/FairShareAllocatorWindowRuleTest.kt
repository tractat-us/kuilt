package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two allocators, each with its own view of who is asking, scheduling one parent and joined now and
 * then (#3353, the window rule). Each sees the other's grants and rebases only on a join, so their
 * origins and fronts disagree in between. The heavy lane's weight, 2¹⁸, is where the review's
 * prototype first saw this shape refuse.
 *
 * A guard, not a red: on this base the rule before the window rule does not refuse here either,
 * since a returner now comes back by its parent's clock (#3376). Both rules are run, and both must
 * hold.
 */
class FairShareAllocatorWindowRuleTest {

    private val root = GroupId("root")
    private val ids = listOf(AttachmentId("l1"), AttachmentId("l2"), AttachmentId("heavy"))

    private class Cell(override var current: EntitlementLedger) : LedgerCell {
        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            current = current.piece(patch.delta)
            return true
        }
    }

    private class Run(val grants: Int, val refusals: Int, val joins: Int, val rebases: Int)

    private fun run(heavy: Weight, windowRule: Boolean): Run {
        val a = ReplicaId("a")
        val b = ReplicaId("b")
        var s = EntitlementLedger.bootstrap(root, mapOf(a to (1L shl 61), b to (1L shl 61)), nonce = "genesis")
        val weights = listOf(Weight.ONE, Weight.ONE, heavy)
        for ((i, id) in ids.withIndex()) {
            val record = AttachmentRecord(id, root, GroupId("g$i"), weights[i])
            s = s.piece(checkNotNull(s.prepare(record)).delta)
            s = s.piece(checkNotNull(s.activate(id)).delta)
        }
        val policy = PolicyConfig(quantum = QUANTUM)
        val cells = listOf(Cell(s), Cell(s))
        val allocators = listOf(FairShareAllocator(a, policy), FairShareAllocator(b, policy)).onEach { it.windowRuleForTest = windowRule }
        val random = Random(3353)
        var grants = 0
        var refusals = 0
        var joins = 0
        repeat(ROUNDS) {
            for (p in 0..1) {
                val before = cells[p].current
                val wants = ids.filter { random.nextBoolean() }.toSet()
                val outcome = allocators[p].schedule(root, cells[p], earmarked = 0L) { id ->
                    if (id in wants) Demand((before.edge(id)?.outstanding ?: 0L) + QUANTUM, QUANTUM) else Demand.NONE
                }
                when (outcome) {
                    is AllocationOutcome.Delegated -> grants += outcome.grants
                    is AllocationOutcome.LifetimeExhausted -> refusals++
                    else -> Unit
                }
            }
            if (random.nextInt(3) == 0) {
                val joined = cells[0].current.piece(cells[1].current)
                cells[0].current = joined
                cells[1].current = joined
                joins++
            }
        }
        return Run(grants, refusals, joins, allocators.sumOf { it.rebases })
    }

    @Test
    fun twoAllocatorsWithTheirOwnViewsNeverReachTheCeiling() {
        val runs = listOf(false, true).map { run(Weight.of(1L shl 18), windowRule = it) }
        assertAll(
            *runs.flatMap { r ->
                listOf(
                    { assertEquals(0, r.refusals, "no call refused at the ceiling") },
                    { assertTrue(r.grants > ROUNDS, "the rig: both granted, ${r.grants} grants") },
                    { assertTrue(r.joins > ROUNDS / 4, "the rig: the two views were joined, ${r.joins} times") },
                    { assertTrue(r.rebases > 0, "the rig: the parent rebased") },
                )
            }.toTypedArray(),
        )
    }

    // ── One parent, set by hand ──────────────────────────────────────────────────

    private val e1 = AttachmentId("e1")
    private val e2 = AttachmentId("e2")
    private val self = ReplicaId("self")
    private val hungry = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L)

    /** `root → {e1, e2}` at [weight], [mint] at the root, each child's gauge at the absolute [ticks] it names. */
    private fun pair(weight: Weight, mint: Long, ticks: Map<AttachmentId, Int128>, origin: Int128 = Int128.ZERO): Cell {
        var s = EntitlementLedger.bootstrap(root, mapOf(self to mint), nonce = "genesis")
        for (id in listOf(e1, e2)) {
            val record = AttachmentRecord(id, root, GroupId("g-${id.value}"), weight)
            s = s.piece(checkNotNull(s.prepare(record)).delta)
            s = s.piece(checkNotNull(s.activate(id)).delta)
        }
        val gauges = ticks.mapValues { (_, t) -> gaugeAt(t, folded = 0L) }
        return Cell(s.piece(EntitlementLedger.of(gauges = gauges, origins = if (origin == Int128.ZERO) emptyMap() else mapOf(root to origin))))
    }

    private fun seen(vararg demanding: Pair<AttachmentId, Boolean>): AllocatorMemory =
        AllocatorMemory(wakeOffsets = emptyMap(), demandingObserved = mapOf(*demanding))

    /**
     * A child a merge carried to its ceiling is rebased **before** the pick (#3353), so the call
     * brings it back inside its window before it orders it. Both children were seen asking, so nobody
     * joins, and `e2` at `2R` is far above its floor. Picks are wide, so this was once a repair of a
     * refusal and is now only the reads' exactness.
     */
    @Test
    fun aChildAtItsCeilingIsRebasedBeforeThePick() {
        val c = positionCeiling(Weight.ONE)
        val cell = pair(Weight.ONE, mint = 100L, ticks = mapOf(e1 to Int128.of(c), e2 to Int128.of(2 * rebaseThreshold(Weight.ONE))))
        check(cell.current.isAtPositionCeiling(e1)) { "the rig: e1 sits at its ceiling" }
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = 10L), seen(e1 to true, e2 to true))
        val outcome = allocator.schedule(root, cell, earmarked = 0L) { hungry }
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(10), outcome, "repaired, and the supply granted") },
            { assertTrue(allocator.rebases > 0, "the rig: the repair is a rebase") },
            { assertTrue(!cell.current.isAtPositionCeiling(e1), "e1 is below its ceiling now") },
        )
    }

    /**
     * The same child at its ceiling, waking: seen idle, asking now. A waker's own position past `R`
     * triggers the raise before the seat and wake step (#3353), as any seated child's does; the
     * clamp only ever raises a waker, so one past `R` before it stays past `R` after. `e2` sits at
     * `R / 2`, and the front with it, so nothing but the waker's own position calls for the raise.
     */
    @Test
    fun aWakerAtItsCeilingIsRebasedBeforeItIsClamped() {
        val c = positionCeiling(Weight.ONE)
        val cell = pair(Weight.ONE, mint = 100L, ticks = mapOf(e1 to Int128.of(c), e2 to Int128.of(rebaseThreshold(Weight.ONE) / 2)))
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = 10L), seen(e1 to false, e2 to true))
        val outcome = allocator.schedule(root, cell, earmarked = 0L) { hungry }
        assertAll(
            { assertTrue(outcome is AllocationOutcome.Delegated, "repaired, not refused: $outcome") },
            { assertTrue(allocator.rebases > 0, "the rig: the repair is a rebase") },
        )
    }

    /**
     * An idle heavy child's grid does not trigger a rebase (#3353, the window rule): the front is read
     * on the grid of a child that is demanding or joining, and an idle one is read again when it wakes.
     * The two light children stand `4 · 10¹⁰` units up, far below their own `R`, which on the idle
     * weight-2²⁰ child's grid is past its `R`. Read on every grid, as before, that rebased the parent
     * on every pick for a child that was not asking.
     */
    @Test
    fun anIdleHeavyChildsGridTriggersNoRebase() {
        val heavy = Weight.of(1L shl 20)
        val lights = 40_000_000_000L * TICKS_PER_UNIT
        var s = EntitlementLedger.bootstrap(root, mapOf(self to 100L), nonce = "genesis")
        val records = listOf(
            AttachmentRecord(AttachmentId("l1"), root, GroupId("l1"), Weight.ONE),
            AttachmentRecord(AttachmentId("l2"), root, GroupId("l2"), Weight.ONE),
            AttachmentRecord(AttachmentId("heavy"), root, GroupId("heavy"), heavy),
        )
        for (r in records) {
            s = s.piece(checkNotNull(s.prepare(r)).delta)
            s = s.piece(checkNotNull(s.activate(r.id)).delta)
        }
        s = s.piece(
            EntitlementLedger.of(
                gauges = mapOf(
                    AttachmentId("l1") to gaugeAt(Int128.of(lights), 0L),
                    AttachmentId("l2") to gaugeAt(Int128.of(lights), 0L),
                    AttachmentId("heavy") to gaugeAt(Int128.ZERO, 0L),
                ),
            ),
        )
        val wantsLights = { id: AttachmentId -> if (id.value.startsWith("l")) hungry else Demand.NONE }
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = 10L))
        val front = lights.timesWide(1L shl 20)
        assertAll(
            { assertTrue(front >= Int128.of(rebaseThreshold(heavy)), "the rig: the front on heavy's grid is past its R") },
            { assertTrue(lights < rebaseThreshold(Weight.ONE), "the rig: and on the lights' own, it is not") },
            { assertNull(allocator.originRebase(root, s, wantsLights), "no rebase for a child that is not asking") },
        )
    }

    /**
     * The public reads hold a child below its floor at the floor (#3353), on the penalty side,
     * rather than throwing. Under the window rule a demanding child can sit at `−C` with a lease out,
     * and a `cancel` then walks it back by the leases it had out. More than one full lease below,
     * `ticks · d` leaves `Long`, since the floor is sized to leave exactly one lease of room: `e2`
     * sits just over one below, as two leases cancelled from the floor leave it.
     */
    @Test
    fun theReadsHoldAChildBelowItsFloorAtItsFloor() {
        val half = Weight.of(1L, 2L)
        val origin = Int128.of((1L shl 59) + (1L shl 40))
        val atOrigin = originTicks(origin, half)
        val cell = pair(half, mint = 100L, ticks = mapOf(e1 to atOrigin + Int128.of(16L), e2 to Int128.of(1L)), origin = origin)
        val s = cell.current
        val e2At = s.gauge(e2).rawTicksAt(s.baseIssuance(e2), 0L, half, s.origin(root))
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = 10L))
        val floor = ticksToVirtual(positionFloor(half), half)
        assertAll(
            { assertTrue(e2At < Int128.of(positionFloor(half)), "the rig: e2 is below its floor, at $e2At") },
            { assertTrue(e2At > Int128.of(positionFloor(half)) - Int128.of(LocalHeddle.MAX_RESERVATION * 16L), "the rig: by under two full leases") },
            { assertTrue(e2At < Int128.of(positionFloor(half)) - Int128.of(LocalHeddle.MAX_RESERVATION * 8L), "the rig: and by over one, past which ticks · 2 leaves Long") },
            { assertEquals(floor, s.virtualService(e2), "the ledger's net read holds it at its floor") },
            { assertEquals(floor, s.grossVirtualService(e2), "and its gross read") },
            { assertEquals(floor, allocator.effectiveVirtualService(s, root, e2), "and the allocator's effective read") },
            { assertTrue(allocator.virtualTime(s, root) { hungry } != null, "and the front over it is read, not thrown") },
        )
    }

    // ── Landings at the floor ───────────────────────────────────────────────────

    /** `root → {e1 at [w1], e2 at [w2]}`, [mint] at the root, each gauge at the absolute [ticks] it names, the origin at [origin]. */
    private fun parent(w1: Weight, w2: Weight, ticks: Map<AttachmentId, Int128>, origin: Int128, mint: Long = 100L): Cell {
        var s = EntitlementLedger.bootstrap(root, mapOf(self to mint), nonce = "genesis")
        for ((id, w) in listOf(e1 to w1, e2 to w2)) {
            val record = AttachmentRecord(id, root, GroupId("g-${id.value}"), w)
            s = s.piece(checkNotNull(s.prepare(record)).delta)
            s = s.piece(checkNotNull(s.activate(id)).delta)
        }
        val gauges = ticks.mapValues { (_, t) -> gaugeAt(t, folded = 0L) }
        return Cell(s.piece(EntitlementLedger.of(gauges = gauges, origins = mapOf(root to origin))))
    }

    /** [edge]'s effective position in [s], relative to the root's origin, as [allocator] reads it. */
    private fun effective(allocator: FairShareAllocator, s: EntitlementLedger, edge: AttachmentId): Int128 {
        val w = checkNotNull(s.record(edge)).weight
        val raw = s.gauge(edge).rawTicksAt(s.baseIssuance(edge), s.edge(edge)?.returned ?: 0L, w, s.origin(root))
        return raw + (allocator.memory.wakeOffsets[edge] ?: Int128.ZERO)
    }

    /** `e2` came back by its parent's clock, which has not run since it left: a returner whose shift is nothing. */
    private val e2Returns = AllocatorMemory(
        wakeOffsets = emptyMap(),
        demandingObserved = mapOf(e1 to true, e2 to false),
        clocks = mapOf(root to Int128.ZERO),
        served = mapOf(e1 to 0L),
        departures = mapOf(e2 to Int128.ZERO),
    )

    /**
     * A returner near its floor bounds the pre-seat raise by its room (#3353 review). Every child
     * weighs 1. `e1` and `e3` wait at three quarters of their ceilings; `e2` comes back by its parent's
     * clock to `2⁴⁰` ticks above its floor and counts in the front there, which stands about `C / 6`
     * ticks up. Bounded by `e1` and `e3` alone, as it was, the raise aimed at that front carried `e2`
     * about `C / 6` ticks below its floor, where the floor held it: it lost that much of its lag,
     * which one lease does not come near. Bounded by `e2`'s room too, `Δ` is 2³⁶ units, `e2` lands on
     * its floor, and its lag is exactly that of a twin that never rebases, so the pick check after
     * the raise runs on a pure translation. The root holds one grant, so one round runs.
     */
    @Test
    fun aReturnerNearItsFloorBoundsThePreSeatRaiseByItsRoom() {
        val c = positionCeiling(Weight.ONE)
        val e3 = AttachmentId("e3")
        // 16 · O = C + 1, so a gauge at 2⁴⁰ + 1 reads 2⁴⁰ above the floor.
        val origin = Int128.of(c / TICKS_PER_UNIT + 1L)
        val atOrigin = originTicks(origin, Weight.ONE)
        fun run(rebase: Boolean): Triple<FairShareAllocator, EntitlementLedger, AllocationOutcome> {
            var s = EntitlementLedger.bootstrap(root, mapOf(self to 10L), nonce = "genesis")
            for (id in listOf(e1, e2, e3)) {
                val record = AttachmentRecord(id, root, GroupId("g-${id.value}"), Weight.ONE)
                s = s.piece(checkNotNull(s.prepare(record)).delta)
                s = s.piece(checkNotNull(s.activate(id)).delta)
            }
            val high = atOrigin + Int128.of(c / 4L * 3L)
            val gauges = mapOf(e1 to high, e3 to high, e2 to atOrigin - Int128.of(c) + Int128.of(1L shl 40)).mapValues { gaugeAt(it.value, 0L) }
            val cell = Cell(s.piece(EntitlementLedger.of(gauges = gauges, origins = mapOf(root to origin))))
            val memory = AllocatorMemory(
                wakeOffsets = emptyMap(),
                demandingObserved = mapOf(e1 to true, e2 to false, e3 to true),
                clocks = mapOf(root to Int128.ZERO),
                served = mapOf(e1 to 0L, e3 to 0L),
                departures = mapOf(e2 to Int128.ZERO),
            )
            val allocator = FairShareAllocator(self, PolicyConfig(quantum = 10L), memory).apply { rebaseEnabled = rebase }
            check(effective(allocator, cell.current, e2) == Int128.of(-c) + Int128.of(1L shl 40)) { "the rig: e2 returns 2⁴⁰ ticks above its floor" }
            val outcome = allocator.schedule(root, cell, earmarked = 0L) { hungry }
            return Triple(allocator, cell.current, outcome)
        }
        val (rebasing, rebased, outcome) = run(rebase = true)
        val (twin, unrebased, twinOutcome) = run(rebase = false)
        fun lag(a: FairShareAllocator, s: EntitlementLedger) = effective(a, s, e1) - effective(a, s, e2)
        assertAll(
            { assertEquals(twinOutcome, outcome, "the rig: both granted alike") },
            { assertTrue(rebasing.rebases > 0, "the rig: the raise landed") },
            { assertTrue(e2 !in rebasing.memory.departures, "the rig: e2 came back as a returner") },
            { assertEquals(Int128.of(1L shl 36), rebased.origin(root) - origin, "Δ is e2's room, 2³⁶ units") },
            { assertTrue(effective(rebasing, rebased, e2) >= Int128.of(-c), "e2 is not below its floor") },
            { assertEquals(lag(twin, unrebased), lag(rebasing, rebased), "and keeps its lag, as a twin with no rebase does") },
        )
    }

    /** The origin for the two rigs below a floor: weight 1's grid reads `16 · O = C + 1 + 2⁴¹`. */
    private val deepOrigin = Int128.of(positionCeiling(Weight.ONE) / TICKS_PER_UNIT + 1L + (1L shl 37))

    /**
     * A returner that comes back below its floor lands on it (#3353, the window rule). `e2` sits 2⁴⁰
     * ticks below its floor, as cancelled leases can leave a child, and its shift is nothing. It
     * returns to its floor: the floor arm of the shift, which costs it those ticks and gives nothing.
     * `e1` near zero calls for no raise, so the floor is the only thing that moves it.
     */
    @Test
    fun aReturnerBelowItsFloorLandsOnIt() {
        val c = positionCeiling(Weight.ONE)
        val atOrigin = originTicks(deepOrigin, Weight.ONE)
        val cell = parent(
            Weight.ONE,
            Weight.ONE,
            ticks = mapOf(e1 to atOrigin + Int128.of(1_000L * TICKS_PER_UNIT), e2 to atOrigin - Int128.of(c) - Int128.of(1L shl 40)),
            origin = deepOrigin,
        )
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = 10L), e2Returns)
        val before = effective(allocator, cell.current, e2)
        val ledger = allocator.settle(root, cell.current) { hungry }
        assertAll(
            { assertEquals(Int128.of(-c) - Int128.of(1L shl 40), before, "the rig: e2 starts 2⁴⁰ ticks below its floor") },
            { assertTrue(e2 !in allocator.memory.departures, "the rig: e2 came back as a returner") },
            { assertEquals(deepOrigin, ledger.origin(root), "the rig: no raise moved it") },
            { assertEquals(Int128.of(-c), effective(allocator, ledger, e2), "e2 landed on its floor") },
        )
    }

    /**
     * A waker is clamped no lower than its floor, after its sleeper credit (#3353 review). `e2` waits
     * at `−C + 5` ticks, so the front is there; `e1`, seen idle and never seen to stop, wakes from
     * 2⁴⁰ ticks below its floor with one unit of sleeper credit. Applied before the credit, the floor
     * left its clamp eleven ticks below the floor; applied after, the clamp lands on it.
     */
    @Test
    fun aWakerWithSleeperCreditIsClampedNoLowerThanItsFloor() {
        val c = positionCeiling(Weight.ONE)
        val atOrigin = originTicks(deepOrigin, Weight.ONE)
        val cell = parent(
            Weight.ONE,
            Weight.ONE,
            ticks = mapOf(e1 to atOrigin - Int128.of(c) - Int128.of(1L shl 40), e2 to atOrigin - Int128.of(c) + Int128.of(5L)),
            origin = deepOrigin,
        )
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = 10L, sleeperCredit = 1L), seen(e1 to false, e2 to true))
        val ledger = allocator.settle(root, cell.current) { hungry }
        assertAll(
            { assertTrue(e1 !in allocator.memory.departures, "the rig: e1 was never seen to stop, so it is clamped") },
            { assertEquals(deepOrigin, ledger.origin(root), "the rig: no raise moved it") },
            { assertEquals(Int128.of(-c), effective(allocator, ledger, e1), "e1 is clamped to its floor, not below it") },
        )
    }

    /**
     * A sleeper credit past one lease is refused by both configs (#3353 review). Every window is at
     * least `R` plus one lease wide, so a credit of at most one lease never takes a clamped waker
     * below its floor under a raise, and the raise moves every child alike. A larger credit could,
     * and the pick check after the raise would then throw out of `schedule`.
     */
    @Test
    fun aSleeperCreditPastOneLeaseIsRefused() {
        val max = LocalHeddle.MAX_RESERVATION
        assertAll(
            { assertEquals(max, PolicyConfig(quantum = 1L, sleeperCredit = max).sleeperCredit, "one lease is kept") },
            { assertEquals(max, LocalPolicy(defaultReservation = 1L, sleeperCredit = max).sleeperCredit, "by both configs") },
            { assertFailsWith<IllegalArgumentException> { PolicyConfig(quantum = 1L, sleeperCredit = max + 1L) } },
            { assertFailsWith<IllegalArgumentException> { LocalPolicy(defaultReservation = 1L, sleeperCredit = max + 1L) } },
        )
    }

    private companion object {
        const val ROUNDS: Int = 1_000
        const val QUANTUM: Long = 1L shl 42
    }
}
