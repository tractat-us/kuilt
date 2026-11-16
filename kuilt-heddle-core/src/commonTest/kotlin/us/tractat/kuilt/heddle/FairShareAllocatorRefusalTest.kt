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

    /** [edge]'s raw position in [ledger], in ticks. */
    private fun relative(ledger: EntitlementLedger, edge: AttachmentId): Int128 =
        ledger.gauge(edge).rawTicksAt(ledger.baseIssuance(edge), ledger.edge(edge)?.returned ?: 0L)

    /**
     * A child at its ceiling no longer stops its parent: the pick orders the wide positions, so the
     * whole supply goes to `e2`, the child furthest behind. This used to answer `PositionCeiling`
     * and grant nothing. `e2` sits at zero, seen demanding, since a child this allocator has never
     * seen and finds demanding is clamped up to the front as a waker (the restart rule, §7.2).
     */
    @Test
    fun aParentWithAChildAtItsCeilingGrantsTheChildBehind() {
        val cell = CountingCell(seatedAt(seatedAt(flat(mint = 100L), e2, 0L), e1, positionCeiling(Weight.ONE)))
        check(relative(cell.current, e1) == Int128.of(positionCeiling(Weight.ONE))) { "the rig: e1 sits at its ceiling" }
        check(relative(cell.current, e2) == Int128.ZERO) { "the rig: e2 sits at zero, far behind" }
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
    private fun joining(mint: Long, e1Ticks: Long): Pair<CountingCell, FairShareAllocator> {
        val unseated = withChildren(seatedAt(flat(mint), e2, 0L), AttachmentRecord(e3, root, GroupId("g3"), Weight.ONE))
        val s = seatedAt(unseated, e1, e1Ticks)
        val observed = mapOf(e1 to true, e2 to false)
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
        val (cell, allocator) = joining(mint = 100L, e1Ticks = positionCeiling(Weight.ONE))
        val before = cell.current
        val memoryBefore = allocator.memory
        val outcome = allocator.schedule(root, cell, earmarked = 0L) { hungry }
        assertAll(
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

    /** [ledger] with [edge] seated at the wide position [ticks]. */
    private fun seatedAtWide(ledger: EntitlementLedger, edge: AttachmentId, ticks: Int128): EntitlementLedger =
        ledger.piece(EntitlementLedger.of(gauges = mapOf(edge to gaugeAt(ticks, folded = 0L))))

    /** `2^n` as an [Int128], for `n` in `64..126`. */
    private fun twoTo(n: Int): Int128 = Int128.fromTwosComplement(1L shl (n - 64), 0L)

    /**
     * The position limit on [FairShareAllocator.schedule], where no `apply` asks early (design
     * §7.1): `e1` at weight 1 stands at `2⁹⁴` ticks, a front of `2⁹⁰`, and a newborn `e3` at
     * `2⁴⁰` would be seated there at `2¹³⁴` ticks. The call answers `LifetimeExhausted(root)`, writes
     * nothing to the ledger, and leaves the wake memory as it was.
     */
    @Test
    fun aNewbornWhoseSeatWouldPassThePositionLimitRefusesTheCallAndWritesNothing() {
        val e3 = AttachmentId("e3")
        val start = withChildren(
            seatedAtWide(flat(mint = 100L), e1, twoTo(94)),
            AttachmentRecord(e3, root, GroupId("g3"), Weight.of(1L shl 40)),
        )
        val cell = CountingCell(start)
        val allocator = FairShareAllocator(self, policy)
        val before = allocator.memory
        val wants = { edge: AttachmentId -> if (edge == e2) Demand.NONE else hungry }
        val outcome = allocator.schedule(root, cell, earmarked = 0L, wants)
        assertAll(
            { assertEquals(null, start.gauge(e3), "the rig: e3 is a newborn") },
            { assertEquals(AllocationOutcome.LifetimeExhausted(root), outcome, "the step would pass the limit") },
            { assertEquals(0, cell.writes, "and nothing was written") },
            { assertEquals(before, allocator.memory, "nor the wake memory") },
        )
    }

    /**
     * Both sides of the limit on a newborn's landing. `e1` at weight 1 stands at `P` and `e3`, a
     * newborn at weight 1, is seated level with it, so the load after is `2P`. At `P = 2¹²⁴` the load
     * is exactly `2¹²⁵` and the landing is kept; one tick more and it is refused.
     */
    @Test
    fun aLandingThatLeavesTheLoadAtTheLimitIsKeptAndOneTickMoreIsRefused() {
        val e3 = AttachmentId("e3")
        fun outcomeAt(position: Int128): AllocationOutcome {
            // e1 and e3 alone under the root, so nothing else is seated beside them.
            val alone = withChildren(
                EntitlementLedger.bootstrap(root, mapOf(self to 100L), nonce = "genesis"),
                AttachmentRecord(e1, root, GroupId("g1"), Weight.ONE),
            )
            val start = withChildren(seatedAtWide(alone, e1, position), AttachmentRecord(e3, root, GroupId("g3"), Weight.ONE))
            val wants = { _: AttachmentId -> hungry }
            return FairShareAllocator(self, policy).schedule(root, CountingCell(start), earmarked = 0L, wants)
        }
        val atLimit = outcomeAt(twoTo(124))
        val past = outcomeAt(twoTo(124) + Int128.of(1L))
        assertAll(
            { assertTrue(atLimit is AllocationOutcome.Delegated, "a load of exactly 2^125 lands: $atLimit") },
            { assertEquals(AllocationOutcome.LifetimeExhausted(root), past, "one tick more is refused") },
        )
    }

    /**
     * Two peers that each land a child high, then merge (design §7.1): each position is one a peer
     * checked, so each fits, but their load does not. `e1` and `e2` stand at `2¹²⁴ + 2¹²³` each, a
     * load of `3 · 2¹²⁴`, past the limit and inside 128 bits. The next pick forms the load, refuses
     * typed, and writes nothing, and every read still answers.
     */
    @Test
    fun aMergedLoadPastTheLimitRefusesThePickAndEveryReadAnswers() {
        val high = twoTo(124) + twoTo(123)
        val start = seatedAtWide(seatedAtWide(flat(mint = 100L), e1, high), e2, high)
        val cell = CountingCell(start)
        val allocator = FairShareAllocator(self, policy)
        val wants = { _: AttachmentId -> hungry }
        val outcome = allocator.schedule(root, cell, earmarked = 0L, wants)
        assertAll(
            { assertEquals(AllocationOutcome.LifetimeExhausted(root), outcome, "the merged load is past the limit") },
            { assertEquals(0, cell.writes, "and nothing was written") },
            { assertNotNull(cell.current.virtualService(e1), "the virtual-service read answers") },
            { assertNotNull(allocator.virtualTime(cell.current, root, wants), "and so does the front") },
        )
    }
}
