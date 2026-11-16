package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [FairShareAllocator.schedule]'s refusal, driven over an in-memory ledger (#3353): a parent whose
 * children cannot be ordered says so, rather than going quiet or throwing. And the position
 * ceiling, which used to be a second refusal, now refuses nothing: a pick orders the wide position.
 *
 * - [AllocationOutcome.WeightBudget]: the active children's weights put a pick's products out of
 *   `Long`. A replicated ledger can hold such a set even when every write was checked, because two
 *   peers can each add a child that fits beside what they see; the merge is what does not fit.
 */
class FairShareAllocatorRefusalTest {

    private val root = GroupId("root")
    private val e1 = AttachmentId("e1")
    private val e2 = AttachmentId("e2")
    private val e4 = AttachmentId("e4")
    private val self = ReplicaId("self")
    private val policy = PolicyConfig(quantum = 10L)
    private val hungry = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L)

    /** One process's ledger in a `var`, counting the patches it applied. */
    private class CountingCell(initial: EntitlementLedger) : LedgerCell {
        override var current: EntitlementLedger = initial
            private set
        var writes: Int = 0
            private set

        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            current = current.piece(patch.delta)
            writes++
            return true
        }
    }

    /** [ledger] with [records] prepared and activated under [root], each as its own write. */
    private fun withChildren(ledger: EntitlementLedger, vararg records: AttachmentRecord): EntitlementLedger {
        var s = ledger
        for (record in records) {
            s = s.piece(assertNotNull(s.prepare(record), "prepare ${record.id.value}").delta)
            s = s.piece(assertNotNull(s.activate(record.id), "activate ${record.id.value}").delta)
        }
        return s
    }

    /** `root → {e1, e2}` at weight 1, with [mint] held at the root by [self]. */
    private fun flat(mint: Long): EntitlementLedger = withChildren(
        EntitlementLedger.bootstrap(root, mapOf(self to mint), nonce = "genesis"),
        AttachmentRecord(e1, root, GroupId("g1"), Weight.ONE),
        AttachmentRecord(e2, root, GroupId("g2"), Weight.ONE),
    )

    /** [ledger] with [edge] carrying a gauge whose floor is exactly [ticks] on weight 1's grid. */
    private fun seatedAt(ledger: EntitlementLedger, edge: AttachmentId, ticks: Long): EntitlementLedger =
        ledger.piece(EntitlementLedger.of(gauges = mapOf(edge to gaugeAt(Int128.of(ticks), folded = 0L))))

    /**
     * The root's origin at [FLOOR_ORIGIN], so a gauge at zero reads one tick below its floor, `−C`, and
     * [edge] seated [relative] ticks above that origin on weight 1's grid (#3353, the window rule).
     */
    private fun seatedAbove(ledger: EntitlementLedger, edge: AttachmentId, relative: Long): EntitlementLedger =
        ledger.piece(
            EntitlementLedger.of(
                origins = mapOf(root to FLOOR_ORIGIN),
                gauges = mapOf(edge to gaugeAt(originTicks(FLOOR_ORIGIN, Weight.ONE) + Int128.of(relative), folded = 0L)),
            ),
        )

    /** [edge]'s raw position in [ledger], relative to the root's origin, in ticks. */
    private fun relative(ledger: EntitlementLedger, edge: AttachmentId): Int128 =
        ledger.gauge(edge).rawTicksAt(ledger.baseIssuance(edge), ledger.edge(edge)?.returned ?: 0L, Weight.ONE, ledger.origin(root))

    /**
     * A child at its ceiling no longer stops its parent: the pick orders the wide positions, so the
     * whole supply goes to `e2`, the child furthest behind. This used to answer `PositionCeiling`
     * and grant nothing. `e2` demanding below its floor, seen demanding, has no room to come down,
     * so the rebase is held at nothing (#3353, the window rule), and `e1` stays at its ceiling for
     * every round. Seen demanding, since a child this allocator has never seen, below zero and
     * demanding, is clamped up to the front as a waker.
     */
    @Test
    fun aParentWithAChildAtItsCeilingGrantsTheChildBehind() {
        val cell = CountingCell(seatedAbove(seatedAt(flat(mint = 100L), e2, 0L), e1, positionCeiling(Weight.ONE)))
        check(relative(cell.current, e1) == Int128.of(positionCeiling(Weight.ONE))) { "the rig: e1 sits at its ceiling" }
        check(relative(cell.current, e2) <= Int128.of(positionFloor(Weight.ONE))) { "the rig: e2 sits at its floor" }
        val seen = AllocatorMemory(wakeOffsets = emptyMap(), demandingObserved = mapOf(e1 to true, e2 to true))
        val outcome = FairShareAllocator(self, policy, seen).schedule(root, cell, earmarked = 0L) { hungry }
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(10), outcome, "every quantum of the supply was granted") },
            { assertEquals(100L, cell.current.edge(e2)?.issued, "all of it to e2, the child behind") },
            { assertEquals(0L, cell.current.holdings(root, self), "the root's supply is spent") },
        )
    }

    /**
     * A first-round grant whose delegation carries its child past the ceiling is made, and the
     * position goes past it exactly. `e1` sits 100 ticks below its ceiling, and one grant of 10 units
     * is 160 ticks, so the very first grant crosses, and nothing brings `e1` back down (#3472). This
     * used to answer `PositionCeiling` and write nothing.
     */
    @Test
    fun aGrantWhoseDelegationCrossesTheCeilingIsMadeExactly() {
        val start = seatedAt(flat(mint = 100L), e1, positionCeiling(Weight.ONE) - 100L)
        val cell = CountingCell(start)
        check(relative(start, e1) == Int128.of(positionCeiling(Weight.ONE) - 100L)) { "the rig: e1 starts below its ceiling" }
        val allocator = FairShareAllocator(self, policy)
        val wantsE1 = { edge: AttachmentId -> if (edge == e1) hungry else Demand.NONE }
        val outcome = allocator.schedule(root, cell, earmarked = 0L, wantsE1)
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(10), outcome, "the first grant and every one after it landed") },
            { assertEquals(Int128.of(positionCeiling(Weight.ONE) - 100L + 100L * TICKS_PER_UNIT), relative(cell.current, e1), "e1 is exactly 100 units on") },
        )
    }

    /**
     * The parent's front reads past the ceiling too, held at it, and does not throw (#3472). The read
     * used to hold a child by lowering its wake offset, which [PolicyEdge] refuses once that goes
     * negative, so every front over a child past its ceiling threw `IllegalArgumentException`:
     * `HeddleNode.parentVirtualTime` and `explain`'s `BehindAtLevel` among them. With no rebase,
     * every busy parent gets there. Read with `e1` alone demanding, past its ceiling, and with
     * nobody demanding, so both arms of the front are read.
     */
    @Test
    fun aFrontOverAChildPastItsCeilingReadsTheCeiling() {
        val cell = CountingCell(seatedAt(flat(mint = 100L), e1, positionCeiling(Weight.ONE) - 100L))
        val allocator = FairShareAllocator(self, policy)
        val wantsE1 = { edge: AttachmentId -> if (edge == e1) hungry else Demand.NONE }
        allocator.schedule(root, cell, earmarked = 0L, wantsE1)
        val atCeiling = ticksToVirtual(positionCeiling(Weight.ONE), Weight.ONE)
        assertAll(
            { assertTrue(relative(cell.current, e1) > Int128.of(positionCeiling(Weight.ONE)), "the rig: e1 is past its ceiling") },
            { assertEquals(atCeiling, allocator.virtualTime(cell.current, root, wantsE1), "the demanding front reads the ceiling") },
            { assertEquals(atCeiling, allocator.virtualTime(cell.current, root) { Demand.NONE }, "and so does the idle one, the furthest ahead") },
        )
    }

    /**
     * The same in a later round: `e1` sits 200 ticks below its ceiling, so the first grant lands 40
     * ticks under it and the second passes it. The call goes on granting, and the next call finds
     * the supply spent rather than a ceiling. This used to stop after one grant and name the ceiling.
     */
    @Test
    fun aLaterRoundWhoseDelegationCrossesTheCeilingKeepsGranting() {
        val cell = CountingCell(seatedAt(flat(mint = 100L), e1, positionCeiling(Weight.ONE) - 200L))
        val allocator = FairShareAllocator(self, policy)
        val wantsE1 = { edge: AttachmentId -> if (edge == e1) hungry else Demand.NONE }
        val first = allocator.schedule(root, cell, earmarked = 0L, wantsE1)
        val second = allocator.schedule(root, cell, earmarked = 0L, wantsE1)
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(10), first, "every round landed, the ones past the ceiling too") },
            { assertEquals(100L, cell.current.edge(e1)?.issued) },
            { assertEquals(Int128.of(positionCeiling(Weight.ONE) - 200L + 100L * TICKS_PER_UNIT), relative(cell.current, e1)) },
            { assertEquals(AllocationOutcome.NothingToDelegate, second, "the next call finds the supply spent") },
        )
    }

    /**
     * Two peers each activate one child beside `e1` and `e2`, and each set fits on its own. Merged,
     * `S = Σ n · L/d` over `L = 192` is `2⁶³ + 385`, out of `Long`: today the first pick throws
     * `Long overflow` on every call, and the parent is wedged.
     */
    @Test
    fun anOverBudgetSetMergedFromTwoPeersAnswersWeightBudgetAndWritesNothing() {
        val base = flat(mint = 100L)
        val heavy = AttachmentRecord(AttachmentId("heavy"), root, GroupId("g-heavy"), Weight.of(1L shl 57, 3L))
        val light = AttachmentRecord(AttachmentId("light"), root, GroupId("g-light"), Weight.of(1L, 192L))
        val onA = withChildren(base, heavy)
        val onB = withChildren(base, light)
        val merged = onA.piece(onB)
        fun weightsOf(s: EntitlementLedger) = s.activeChildren(root).map { assertNotNull(s.record(it.attachment)).weight }
        val cell = CountingCell(merged)
        val allocator = FairShareAllocator(self, policy)
        val memoryBefore = allocator.memory
        val outcome = allocator.schedule(root, cell, earmarked = 0L) { hungry }
        assertAll(
            { assertTrue(weightsFitTheBudget(weightsOf(onA)), "the rig: A's own view fits") },
            { assertTrue(weightsFitTheBudget(weightsOf(onB)), "the rig: B's own view fits") },
            { assertEquals(4, weightsOf(merged).size, "the rig: all four are active in the merge") },
            { assertFalse(weightsFitTheBudget(weightsOf(merged)), "the rig: the merge does not fit") },
            { assertEquals(AllocationOutcome.WeightBudget(root), outcome, "the parent says why it stopped") },
            { assertEquals(merged, cell.current, "and nothing was written: no seat, no grant") },
            { assertEquals(0, cell.writes, "not one patch") },
            { assertEquals(memoryBefore, allocator.memory, "and the allocator observed nothing") },
        )
    }

    /**
     * The same set, completed by a merge that lands **between two rounds** of one call: the replicated
     * cell takes inbound writes between the allocator's own. The round after the merge stops, the
     * call reports the grant it made, and the next call names the budget.
     */
    @Test
    fun aSetThatGoesOverBudgetBetweenRoundsStopsTheCall() {
        val base = withChildren(
            flat(mint = 100L),
            AttachmentRecord(AttachmentId("heavy"), root, GroupId("g-heavy"), Weight.of(1L shl 57, 3L)),
        )
        val light = AttachmentRecord(AttachmentId("light"), root, GroupId("g-light"), Weight.of(1L, 192L))
        val fromB = withChildren(base, light)
        val cell = object : LedgerCell {
            override var current: EntitlementLedger = base
            var merged = false

            override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
                val patch = transform(current) ?: return false
                current = current.piece(patch.delta)
                // Once the allocator has delegated, B's activation arrives, before the next round.
                if (!merged && current.edge(e1)?.issued?.let { it > 0L } == true) {
                    current = current.piece(fromB)
                    merged = true
                }
                return true
            }
        }
        val allocator = FairShareAllocator(self, policy)
        val wantsE1 = { edge: AttachmentId -> if (edge == e1) hungry else Demand.NONE }
        val first = allocator.schedule(root, cell, earmarked = 0L, wantsE1)
        val second = allocator.schedule(root, cell, earmarked = 0L, wantsE1)
        assertAll(
            { assertTrue(cell.merged, "the rig: the merge landed inside the first call") },
            { assertEquals(AllocationOutcome.Delegated(1), first, "the round before the merge granted, the one after stopped") },
            { assertEquals(10L, cell.current.edge(e1)?.issued, "one quantum, not two") },
            { assertEquals(AllocationOutcome.WeightBudget(root), second, "the next call names the budget") },
        )
    }

    // ── A refused schedule changes nothing (#3353 slice 3) ──────────────────────────

    private val e3 = AttachmentId("e3")

    /**
     * The joining rig: `e1` competing at [e1Ticks], `e2` a waker at 0 (seen idle, demanding now),
     * `e3` a newborn with no seat, and [mint] at the root. Each joiner has something for the seat
     * step to write: `e3` a seat, `e2` a clamp up to `e1`.
     */
    /**
     * [blocker] adds `e4`, at its floor and seen demanding: it holds the rebase at nothing, so a
     * child at its ceiling stays there through the round (#3353, the window rule). The root's origin is
     * raised to put it there, and [e1Ticks] is then read above that origin ([seatedAbove]).
     */
    private fun joining(mint: Long, e1Ticks: Long, blocker: Boolean = false): Pair<CountingCell, FairShareAllocator> {
        val unseated = withChildren(seatedAt(flat(mint), e2, 0L), AttachmentRecord(e3, root, GroupId("g3"), Weight.ONE))
        var s = if (blocker) seatedAbove(unseated, e1, e1Ticks) else seatedAt(unseated, e1, e1Ticks)
        val observed = HashMap(mapOf(e1 to true, e2 to false))
        if (blocker) {
            s = seatedAt(withChildren(s, AttachmentRecord(e4, root, GroupId("g4"), Weight.ONE)), e4, 0L)
            observed[e4] = true
        }
        val allocator = FairShareAllocator(self, policy, AllocatorMemory(wakeOffsets = emptyMap(), demandingObserved = observed))
        return CountingCell(s) to allocator
    }

    /**
     * The joining rig with `e1` at its ceiling: the first pick, which used to refuse here and drop its
     * seats, grants, and the newborn's seat and the waker's clamp land with it. A refused first round
     * writing nothing is now only the weight budget's, which
     * [anOverBudgetSetMergedFromTwoPeersAnswersWeightBudgetAndWritesNothing] pins.
     */
    @Test
    fun aFirstPickBesideAChildAtItsCeilingLandsItsSeats() {
        val (cell, allocator) = joining(mint = 100L, e1Ticks = positionCeiling(Weight.ONE), blocker = true)
        val before = cell.current
        val memoryBefore = allocator.memory
        val outcome = allocator.schedule(root, cell, earmarked = 0L) { hungry }
        assertAll(
            { assertTrue(relative(before, e4) <= Int128.of(positionFloor(Weight.ONE)), "the rig: e4 sits at its floor") },
            { assertEquals(Int128.of(positionCeiling(Weight.ONE)), relative(before, e1), "the rig: e1 sits at its ceiling") },
            { assertTrue(before.gauge(e3) == null, "the rig: e3 is unseated") },
            { assertEquals(false, memoryBefore.demandingObserved[e2], "the rig: e2 was seen idle, so it is waking") },
            { assertEquals(AllocationOutcome.Delegated(10), outcome, "the first pick and every one after it granted") },
            { assertTrue(cell.current.gauge(e3) != null, "the newborn's seat landed") },
            { assertTrue(allocator.memory.wakeOffsets.containsKey(e2), "and the waker's clamp was kept") },
        )
    }

    /**
     * The same parent with nothing at the root: an ordinary empty pick keeps what it observed, and
     * writes nothing, as [LocalHeddle] does. The newborn's seat waits for the call that grants.
     */
    @Test
    fun anOrdinaryEmptyPickKeepsItsWakeMemoryAndWritesNothing() {
        val (cell, allocator) = joining(mint = 0L, e1Ticks = 1_600L)
        val before = cell.current
        val outcome = allocator.schedule(root, cell, earmarked = 0L) { hungry }
        val memory = allocator.memory
        assertAll(
            { assertEquals(AllocationOutcome.NothingToDelegate, outcome, "the rig: nothing to grant") },
            { assertEquals(null, before.gauge(e3), "the rig: e3 is unseated") },
            { assertEquals(before, cell.current, "no seat landed for the newborn") },
            { assertEquals(0, cell.writes, "not one patch") },
            { assertEquals(Int128.of(1_600L), memory.wakeOffsets[e2], "the waker's clamp is kept") },
            { assertEquals(mapOf(e1 to true, e2 to true, e3 to true), memory.demandingObserved, "and so is what it saw") },
        )
    }

    /** With supply, the seats and the first grant land together, as one patch. */
    @Test
    fun theFirstGrantLandsWithItsSeatsInOnePatch() {
        val (cell, allocator) = joining(mint = 10L, e1Ticks = 1_600L)
        val outcome = allocator.schedule(root, cell, earmarked = 0L) { hungry }
        val issued = listOf(e1, e2, e3).sumOf { cell.current.edge(it)?.issued ?: 0L }
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(1), outcome, "the rig: one quantum's supply, one grant") },
            { assertEquals(10L, issued, "the grant landed") },
            { assertTrue(cell.current.gauge(e3) != null, "and so did the newborn's seat") },
            { assertEquals(1, cell.writes, "in a single patch") },
        )
    }

    private companion object {
        /** An origin at which a gauge at zero reads `−C − 1` on weight 1's grid: `16 · O = C + 1`. */
        val FLOOR_ORIGIN: Int128 = Int128.of(positionCeiling(Weight.ONE) / TICKS_PER_UNIT + 1L)
    }
}
