package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.ReservationLeanRig.A
import us.tractat.kuilt.heddle.ReservationLeanRig.A1
import us.tractat.kuilt.heddle.ReservationLeanRig.A2
import us.tractat.kuilt.heddle.ReservationLeanRig.Estimate
import us.tractat.kuilt.heddle.ReservationLeanRig.Lane
import us.tractat.kuilt.heddle.ReservationLeanRig.ROOT
import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a wrong estimate costs on [LocalHeddle], pinned (#3239): "An open lease counts at its
 * reservation. A wrong estimate reorders picks and never changes a charge. Reserve at or below the
 * cost and `extend`: a refund is what can move a sibling."
 *
 * The charge half is exact: a leaf's spend is the costs its completions reported, here on three
 * leases and inside [ReservationLeanRig.run] on every lease of every rig below. The pick half is a
 * lean in share, and it is pinned on **ensembles** of rigs, never on one: a single rig's share is
 * quantised, and at think 7 three unrelated arms all read +0.030. Every value is a share less the
 * same rig's honest arm, so it measures only what the estimate changed. Each ensemble row pins the
 * mean within [MEAN_TOLERANCE] and keeps every rig inside the measured extremes widened by
 * [EXTREME_TOLERANCE]; the means red when the lean moves either way, the extremes only when it
 * grows.
 *
 * JVM and macosArm64 measure the same values to the last digit: the rig draws nothing at random
 * and walks no hash order, and the engine orders its outputs by id.
 */
class ReservationLeanTest {

    @Test
    fun aLeafIsChargedTheCostItReportedWhateverItReserved() {
        val h = engineOver(ReservationLeanRig.oneLevel(), ROOT, supply = 1_000L)
        val edge = AttachmentId("root>a")
        fun spent(): Long = checkNotNull(h.snapshot().ledger.edge(edge)).spent
        val waiting = setOf(A)
        val over = h.lease(waiting, reservation = 32L)
        val overDone = h.complete(over.id, 8L, waiting)
        val afterOver = spent()
        val under = h.lease(waiting, reservation = 2L)
        val underDone = h.complete(under.id, 8L, waiting)
        val afterUnder = spent()
        val extended = h.lease(waiting, reservation = 1L)
        h.extend(extended.id, 7L, waiting)
        h.complete(extended.id, 8L, waiting)
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = 8L, overrun = 0L, unrecorded = 0L), overDone, "four times the cost") },
            { assertEquals(8L, afterOver, "four times the cost, spent") },
            { assertEquals(LeaseOutcome.Done(charged = 8L, overrun = 6L, unrecorded = 0L), underDone, "a quarter of the cost") },
            { assertEquals(16L, afterUnder, "a quarter of the cost, spent") },
            { assertEquals(24L, spent(), "1, extended to the cost") },
        )
    }

    @Test
    fun oneLevelUnderReservingLeansTheLaneUp() = pin(oneLevel(Estimate.QUARTER), "a, reserves a quarter", 0.0109, -0.0093, 0.0418)

    @Test
    fun oneLevelOverReservingLeansTheLaneDown() = pin(oneLevel(Estimate.FOUR_TIMES), "a, reserves 4×", -0.0097, -0.0312, 0.0)

    @Test
    fun oneLevelAnOverReservationOnTheLastJobOfEachBurstLeavesNoLean() =
        pin(oneLevel(Estimate.FOUR_TIMES_ON_LAST), "a, reserves 4× on each burst's last job", -0.0004, -0.0262, 0.0190)

    @Test
    fun oneLevelReservingOneAndExtendingLeansTheLaneUp() =
        pin(oneLevel(Estimate.ONE_THEN_EXTEND), "a, reserves 1 and extends", 0.0107, 0.0, 0.0666)

    @Test
    fun twoLevelsUnderReserving() = twoLevel(Estimate.QUARTER).let {
        pin(it.lane, "a1, reserves a quarter", 0.0272, -0.0444, 0.0860)
        pin(it.sibling, "a2 beside it", -0.0095, -0.0826, 0.0352)
    }

    @Test
    fun twoLevelsOverReserving() = twoLevel(Estimate.FOUR_TIMES).let {
        pin(it.lane, "a1, reserves 4×", 0.0088, -0.0228, 0.0565)
        pin(it.sibling, "a2 beside it", -0.0053, -0.0432, 0.0406)
    }

    @Test
    fun twoLevelsAnOverReservationOnTheLastJobOfEachBurstMovesTheSibling() = twoLevel(Estimate.FOUR_TIMES_ON_LAST).let {
        pin(it.lane, "a1, reserves 4× on each burst's last job", 0.0449, -0.0176, 0.1210)
        pin(it.sibling, "a2 beside it", -0.0306, -0.0836, 0.0420)
    }

    @Test
    fun twoLevelsReservingOneAndExtending() = twoLevel(Estimate.ONE_THEN_EXTEND).let {
        pin(it.lane, "a1, reserves 1 and extends", 0.0070, -0.0392, 0.0935)
        pin(it.sibling, "a2 beside it", -0.0031, -0.0435, 0.0385)
    }

    /**
     * The positive control: the refund landing in a sibling's gap is an effect these ensembles can
     * see. The arm whose refund lands as its queue empties must cost the honest sibling more, on
     * average, than either arm that never refunds, by at least [CLEARANCE]. Measured, it clears
     * reserving a quarter by 0.021 and reserving 1 and extending by 0.028.
     */
    @Test
    fun aRefundInTheGapCostsTheSiblingMoreThanNeverRefunding() {
        val refunds = twoLevel(Estimate.FOUR_TIMES_ON_LAST).sibling.mean
        assertAll(
            *listOf(Estimate.QUARTER, Estimate.ONE_THEN_EXTEND).map { never ->
                {
                    val without = twoLevel(never).sibling.mean
                    assertTrue(
                        refunds <= without - CLEARANCE,
                        "a2's mean beside a refund in the gap, ${fmt(refunds)}, against $never, ${fmt(without)}: clearance ${fmt(without - refunds)} < $CLEARANCE",
                    )
                }
            }.toTypedArray(),
        )
    }

    private fun pin(spread: Spread, label: String, mean: Double, min: Double, max: Double) = assertAll(
        { assertTrue(abs(spread.mean - mean) <= MEAN_TOLERANCE, "$label: mean ${fmt(spread.mean)}, pinned ${fmt(mean)} ± $MEAN_TOLERANCE") },
        { assertTrue(spread.min >= min - EXTREME_TOLERANCE, "$label: worst loss ${fmt(spread.min)}, bound ${fmt(min - EXTREME_TOLERANCE)}") },
        { assertTrue(spread.max <= max + EXTREME_TOLERANCE, "$label: worst gain ${fmt(spread.max)}, bound ${fmt(max + EXTREME_TOLERANCE)}") },
    )

    private companion object {
        const val MEAN_TOLERANCE = 0.005
        const val EXTREME_TOLERANCE = 0.01
        const val CLEARANCE = 0.015
    }
}

/** One arm's deltas over an ensemble: the mean and both extremes. */
internal class Spread(values: List<Double>) {
    val mean: Double = values.sum() / values.size
    val min: Double = values.min()
    val max: Double = values.max()
}

internal class LaneAndSibling(val lane: Spread, val sibling: Spread)

private fun fmt(d: Double): String = (round(d * 10_000) / 10_000).toString()

/**
 * Ensemble runs, each computed once per test process and shared by the tests that read it, so the
 * honest arm runs once per level. Not thread-safe, and need not be: tests in one process run one at
 * a time on every target.
 */
private val oneLevelHonest = mutableMapOf<Pair<Long, Int>, Double>()
private val oneLevelArms = mutableMapOf<Estimate, Spread>()
private val twoLevelHonest = mutableMapOf<List<Long>, ReservationLeanRig.Run>()
private val twoLevelArms = mutableMapOf<Estimate, LaneAndSibling>()

private fun oneLevelShare(estimate: Estimate, think: Long, burst: Int): Double =
    ReservationLeanRig.run(ReservationLeanRig.oneLevel(), listOf(Lane(A, estimate, think, burst))).share(A)

/** `a` against an always-backlogged `b`, at think 3..14 × burst {2, 3, 4, 8}, cost 8: 48 rigs. */
private fun oneLevel(estimate: Estimate): Spread = oneLevelArms.getOrPut(estimate) {
    val deltas = (3L..14L).flatMap { think ->
        listOf(2, 3, 4, 8).map { burst ->
            val honest = oneLevelHonest.getOrPut(think to burst) { oneLevelShare(Estimate.HONEST, think, burst) }
            oneLevelShare(estimate, think, burst) - honest
        }
    }
    check(deltas.size == 48) { "one level: ${deltas.size} rigs" }
    Spread(deltas)
}

private fun twoLevelRun(estimate: Estimate, t1: Long, t2: Long, s2: Long, b2: Int): ReservationLeanRig.Run = ReservationLeanRig.run(
    ReservationLeanRig.twoLevel(),
    listOf(Lane(A1, estimate, t1, burst = 4), Lane(A2, Estimate.HONEST, t2, burst = b2, start = s2)),
)

/**
 * `root → {g, b}`, `g → {a1, a2}`, `b` always backlogged: `a1` (burst 4) takes the arm and `a2` is
 * honest, at `a1`'s think {4, 7, 12} × `a2`'s think {4, 7, 12} × `a2`'s start {0, 2, 5} × `a2`'s
 * burst {2, 4}, cost 8: 54 rigs.
 */
private fun twoLevel(estimate: Estimate): LaneAndSibling = twoLevelArms.getOrPut(estimate) {
    val lane = mutableListOf<Double>()
    val sibling = mutableListOf<Double>()
    for (t1 in listOf(4L, 7L, 12L)) for (t2 in listOf(4L, 7L, 12L)) for (s2 in listOf(0L, 2L, 5L)) for (b2 in listOf(2, 4)) {
        val honest = twoLevelHonest.getOrPut(listOf(t1, t2, s2, b2.toLong())) { twoLevelRun(Estimate.HONEST, t1, t2, s2, b2) }
        val run = twoLevelRun(estimate, t1, t2, s2, b2)
        lane += run.share(A1) - honest.share(A1)
        sibling += run.share(A2) - honest.share(A2)
    }
    check(lane.size == 54) { "two levels: ${lane.size} rigs" }
    LaneAndSibling(Spread(lane), Spread(sibling))
}
