package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
                    is AllocationOutcome.PositionCeiling -> refusals++
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
     * repairs it instead of refusing. Both children were seen asking, so nobody joins, and `e2` at
     * `2R` is far above its floor. The raise after the pick could never run: the pick refuses first.
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

    private companion object {
        const val ROUNDS: Int = 1_000
        const val QUANTUM: Long = 1L shl 42
    }
}
