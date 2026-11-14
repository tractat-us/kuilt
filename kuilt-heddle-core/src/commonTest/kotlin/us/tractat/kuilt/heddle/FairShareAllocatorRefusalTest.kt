package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [FairShareAllocator.schedule]'s two refusals, driven over an in-memory ledger (#3353): a parent
 * whose children cannot be ordered answers which reason, rather than going quiet or throwing.
 *
 * - [AllocationOutcome.PositionCeiling]: a child holds a position at its ceiling (#3313), so no
 *   pick there orders exactly.
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
        ledger.piece(EntitlementLedger.of(gauges = mapOf(edge to Gauge(ticksToVirtual(ticks, Weight.ONE), folded = 0L))))

    @Test
    fun aParentWithAChildAtItsCeilingAnswersPositionCeiling() {
        val cell = CountingCell(seatedAt(flat(mint = 100L), e1, positionCeiling(Weight.ONE)))
        check(cell.current.isAtPositionCeiling(e1)) { "the rig: e1 sits at its ceiling" }
        val outcome = FairShareAllocator(self, policy).schedule(root, cell, earmarked = 0L) { hungry }
        assertAll(
            { assertEquals(AllocationOutcome.PositionCeiling(root), outcome, "the parent says why it stopped") },
            { assertEquals(0L, cell.current.edge(e2)?.issued, "and granted nothing") },
            { assertEquals(100L, cell.current.holdings(root, self), "the root still holds its supply") },
        )
    }

    /**
     * A call that grants and then meets the ceiling reports the grants: progress was made, and the
     * caller learns of the ceiling on the next call. `e1` sits 100 ticks below its ceiling, so the
     * first grant of 10 units (160 ticks) saturates it and the second pick refuses.
     */
    @Test
    fun aCeilingMetAfterAGrantAnswersDelegatedAndTheNextCallAnswersTheCeiling() {
        val cell = CountingCell(seatedAt(flat(mint = 100L), e1, positionCeiling(Weight.ONE) - 100L))
        check(!cell.current.isAtPositionCeiling(e1)) { "the rig: e1 starts below its ceiling" }
        val allocator = FairShareAllocator(self, policy)
        val wantsE1 = { edge: AttachmentId -> if (edge == e1) hungry else Demand.NONE }
        val first = allocator.schedule(root, cell, earmarked = 0L, wantsE1)
        val reachedCeiling = cell.current.isAtPositionCeiling(e1)
        val second = allocator.schedule(root, cell, earmarked = 0L, wantsE1)
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(1), first, "one grant landed before the ceiling") },
            { assertTrue(reachedCeiling, "the rig: that grant took e1 to its ceiling") },
            { assertEquals(10L, cell.current.edge(e1)?.issued, "exactly one quantum went down e1") },
            { assertEquals(AllocationOutcome.PositionCeiling(root), second, "the next call names the ceiling") },
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
}
