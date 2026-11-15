package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A weight set too fine for one full lease (#3353, P1′). At `d ≥ 30,720` a child's ceiling `C` is
 * so close to one lease of [LocalHeddle.MAX_RESERVATION] that a child between `C − H` and its
 * rebase threshold `R` is never rebased, yet its next lease passes `C`, and the parent refuses every
 * pick there for good. [LocalHeddle.apply] refuses such a set before an edge of it is active.
 */
class LocalHeddleFineWeightTest {

    private val policy = LocalPolicy(defaultReservation = 1L)
    private val full = LocalHeddle.MAX_RESERVATION

    /**
     * Each set is refused when it is applied, typed, and the engine keeps what it had: `1/30,721`
     * and `1/30,722`, the pair the review wedged; `1/32,767`, whose one lease alone passes its
     * ceiling; and `1/3` beside a heavy lane, which fits alone and triples the heavy lane's grid step.
     */
    @Test
    fun aWeightSetTooFineForOneFullLeaseIsRefusedWhenItIsApplied() {
        // Two parents, so the heavy lane's sums do not leave `Long` beside the fine lanes first.
        val base = draft("fine") {
            group("thin", Weight.ONE) { lane("c", Weight.ONE) }
            group("thick", Weight.ONE) { lane("heavy", HEAVY) }
        }
        val finer = draft("fine") {
            group("thin", Weight.ONE) {
                lane("c", Weight.ONE)
                lane("a", Weight.of(1, 30_721))
                lane("b", Weight.of(1, 30_722))
                lane("z", Weight.of(1, 32_767))
            }
            group("thick", Weight.ONE) {
                lane("heavy", HEAVY)
                lane("third", Weight.of(1, 3))
            }
        }
        val engine = LocalHeddle.bootWhole(base, policy, epoch = 1L)
        val result = engine.apply(finer, generation = 1L)
        val refused = result.refusals.associateBy { it.child }
        val names = listOf(finer.groupId("thin", "a"), finer.groupId("thin", "b"), finer.groupId("thin", "z"), finer.groupId("thick", "third"))
        assertAll(
            { assertEquals(names.toSet(), refused.keys, "every finer lane, and only those: ${result.refusals}") },
            {
                for (name in names) {
                    val why = assertIs<StepRefusal.Refused>(refused[name], "refused, not draining: ${name.value}")
                    assertTrue(why.reason.contains("lease"), "refused for one lease past its ceiling: $why")
                }
            },
            {
                val ledger = engine.snapshot().ledger
                val active = listOf("thin", "thick").map { ledger.activeChildren(finer.groupId(it)).size }
                assertEquals(listOf(1, 1), active, "c and heavy alone are active")
            },
        )
    }

    /**
     * The control, at the limit: `{1/30,718, 1/30,719}` runs at full leases whatever a lease is
     * charged, the band cost included, with every one of 200 acquires leased.
     */
    @Test
    fun theFinestPairThatFitsRunsAtFullLeases() {
        val topology = draft("limit") {
            lane("a", Weight.of(1, 30_718))
            lane("b", Weight.of(1, 30_719))
        }
        val a = topology.groupId("a")
        val b = topology.groupId("b")
        val runs = listOf(1L, BAND_COST, full).map { cost ->
            val engine = LocalHeddle.bootWhole(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
            run(engine, setOf(a, b), cost)
        }
        assertEquals(listOf(Run(200, 0), Run(200, 0), Run(200, 0)), runs, "every acquire leased, at each cost")
    }

    /**
     * Why the set was refused, on an engine handed it past [LocalHeddle.apply]: one lease charged
     * [BAND_COST] parked `a` between `C − H` and `R`, where no rebase was due, and the next full
     * lease carried `a` past `C`, where the public reads saturate. It used to refuse every acquire
     * after the first. Nothing rebases now (#3472), so a cost a hair lower carries `a` past its
     * ceiling too, and the band no longer separates the two: both run every acquire, since a pick
     * orders the wide position. P1′ is what still refuses the set, until W1 replaces it.
     */
    @Test
    fun aBandSetHandedPastApplyRunsPastItsCeilingAfterOneUnderChargedLease() {
        val root = GroupId("band")
        val a = GroupId("band/a")
        val b = GroupId("band/b")
        val edge = AttachmentId("a")
        fun engine(): LocalHeddle {
            val ledger = shapedLedger(root, listOf(AttachmentRecord(edge, root, a, Weight.of(1, 30_721)), AttachmentRecord(AttachmentId("b"), root, b, Weight.of(1, 30_722))))
            return engineOver(ledger, root, supply = 0L).apply { reconcileCapacity(1L) }
        }
        val banded = engine()
        val belowBand = engine()
        var pastCeiling = 0
        var belowPast = 0
        val crossed = run(banded, setOf(a), BAND_COST) { if (banded.snapshot().ledger.isAtPositionCeiling(edge)) pastCeiling++ }
        val clear = run(belowBand, setOf(a), BELOW_THE_BAND) { if (belowBand.snapshot().ledger.isAtPositionCeiling(edge)) belowPast++ }
        assertAll(
            { assertTrue(!weightsFitTheBudget(listOf(Weight.of(1, 30_721), Weight.of(1, 30_722))), "rig: apply would refuse this set") },
            { assertEquals(Run(acquired = 200, refused = 0), crossed, "every acquire leased, past the ceiling too") },
            { assertTrue(pastCeiling > 0, "the rig: a lease carried a past its ceiling") },
            { assertEquals(Run(acquired = 200, refused = 0), clear, "a cost below the band runs") },
            { assertTrue(belowPast > 0, "and, with no rebase, passes its ceiling too") },
        )
    }

    private data class Run(val acquired: Int, val refused: Int)

    /** 200 full-size acquires over [waiting], each completed at [cost], with [afterAcquire] run after each acquire. */
    private fun run(engine: LocalHeddle, waiting: Set<GroupId>, cost: Long, afterAcquire: () -> Unit = {}): Run {
        var acquired = 0
        var refused = 0
        for (step in 0 until 200) {
            when (val out = engine.acquire(waiting, waiting, startedAt = step.toLong(), reservation = full).also { afterAcquire() }) {
                is AcquireOutcome.Acquired -> {
                    acquired++
                    engine.complete(out.lease.id, actualCost = cost, waiting = emptySet())
                }
                is AcquireOutcome.PositionCeiling -> refused++
                else -> error("unexpected $out")
            }
        }
        return Run(acquired, refused)
    }

    private companion object {
        /** The cost the review measured parking `1/30,721` in the band: `1.172 · 10¹²`. */
        const val BAND_COST: Long = 1_172_000_000_000L

        /** A lane that fits alone, and whose grid step a `1/3` beside it triples past its window. */
        val HEAVY: Weight = Weight.of(185_000_000_000_000_000L)

        /** A hair lower, `1.1715 · 10¹²`, which does not reach it. */
        const val BELOW_THE_BAND: Long = 1_171_500_000_000L
    }
}
