package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private companion object {
        const val ROUNDS: Int = 2_000
        const val QUANTUM: Long = 1L shl 42
    }
}
