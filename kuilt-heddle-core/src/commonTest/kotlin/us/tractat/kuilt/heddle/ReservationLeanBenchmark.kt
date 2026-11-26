package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.ReservationLeanRig.A
import us.tractat.kuilt.heddle.ReservationLeanRig.A1
import us.tractat.kuilt.heddle.ReservationLeanRig.A2
import us.tractat.kuilt.heddle.ReservationLeanRig.B
import us.tractat.kuilt.heddle.ReservationLeanRig.Estimate
import us.tractat.kuilt.heddle.ReservationLeanRig.Lane
import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The statistical half of #3239's pin, as a **benchmark**: it runs on demand, never in the default
 * build (#3724). `kuilt-heddle-core`'s build excludes this class from every test task unless
 * `-Pkuilt.benchmark.tests=true`:
 *
 * ```
 * ./gradlew :kuilt-heddle-core:jvmTest -Pkuilt.benchmark.tests=true --tests '*ReservationLeanBenchmark*'
 * ```
 *
 * The contract it measures: "An open lease counts at its reservation. A wrong estimate reorders
 * picks and never changes a charge. Reserve at or below the cost and `extend`: a refund is what can
 * move a sibling." The exact half, every lease's charge and a few rigs' slot times on every target,
 * is [ReservationLeanTest], in the default build.
 *
 * The pick half is a lean in share, and it is pinned on **ensembles** of rigs, never on one: a
 * single rig's share is quantised (#3239's design note found three unrelated arms all reading
 * +0.030 at think 7). Every value is a share less the same rig's honest arm, so it measures only
 * what the estimate changed. Each ensemble row pins the mean within [MEAN_TOLERANCE] and keeps
 * every rig inside the measured extremes widened by [EXTREME_TOLERANCE]; the means red when the
 * lean moves either way, the extremes only when it grows. [ReservationLeanRig.run] also checks
 * every lease's charge on every rig here.
 *
 * "A refund is what can move a sibling" holds for the means. A sibling's worst rig is another
 * matter: beside a lane reserving a quarter it loses 0.0805, about what it loses beside the refund
 * arm, 0.0844.
 *
 * Each arm also pins a fingerprint, a hash of every rig's integer slot times in ensemble order, the
 * honest arms included, since a rig moving by 0.04 moves its ensemble's mean by under 0.001. The
 * values were measured on the JVM and macosArm64 alike.
 */
class ReservationLeanBenchmark {

    @Test
    fun theHonestArmsRunTheSameSlotTimesOnEveryTarget() = assertAll(
        fingerprintIs("one level, honest", -769132613410676343L, oneLevel(Estimate.HONEST).fingerprint),
        fingerprintIs("two levels, honest", 3792682368435449249L, twoLevel(Estimate.HONEST).fingerprint),
    )

    @Test
    fun oneLevelUnderReservingLeansTheLaneUp() = pin(oneLevel(Estimate.QUARTER), Row("a, reserves a quarter", 0.0115, -0.0091, 0.0426), 8944391974842269373L)

    @Test
    fun oneLevelOverReservingLeansTheLaneDown() = pin(oneLevel(Estimate.FOUR_TIMES), Row("a, reserves 4×", -0.0115, -0.0338, 0.0), 817825462190311837L)

    @Test
    fun oneLevelAnOverReservationOnTheLastJobOfEachBurstLeavesNoLean() =
        pin(oneLevel(Estimate.FOUR_TIMES_ON_LAST), Row("a, reserves 4× on each burst's last job", -0.0005, -0.0249, 0.0190), 8326974015376676043L)

    @Test
    fun oneLevelReservingOneAndExtendingLeansTheLaneUp() =
        pin(oneLevel(Estimate.ONE_THEN_EXTEND), Row("a, reserves 1 and extends", 0.0109, 0.0, 0.0662), 3960384338718924673L)

    @Test
    fun twoLevelsUnderReserving() = pin(
        twoLevel(Estimate.QUARTER),
        Row("a1, reserves a quarter", 0.0273, -0.0444, 0.0875),
        Row("a2 beside it", -0.0093, -0.0805, 0.0340),
        -89912961515694921L,
    )

    @Test
    fun twoLevelsOverReserving() = pin(
        twoLevel(Estimate.FOUR_TIMES),
        Row("a1, reserves 4×", 0.0068, -0.0240, 0.0534),
        Row("a2 beside it", -0.0056, -0.0436, 0.0388),
        1125146888733772167L,
    )

    @Test
    fun twoLevelsAnOverReservationOnTheLastJobOfEachBurstMovesTheSibling() = pin(
        twoLevel(Estimate.FOUR_TIMES_ON_LAST),
        Row("a1, reserves 4× on each burst's last job", 0.0447, -0.0174, 0.1200),
        Row("a2 beside it", -0.0310, -0.0844, 0.0404),
        3334978947643611491L,
    )

    @Test
    fun twoLevelsReservingOneAndExtending() = pin(
        twoLevel(Estimate.ONE_THEN_EXTEND),
        Row("a1, reserves 1 and extends", 0.0070, -0.0392, 0.0914),
        Row("a2 beside it", -0.0032, -0.0425, 0.0364),
        8519634397680915583L,
    )

    /**
     * The positive control: over-reserving the last job of each burst costs the honest sibling more,
     * on average, than never over-reserving (reserving a quarter, or 1 and extending), by at least
     * [CLEARANCE]. Measured, it clears the quarter arm by 0.0217 and the extend arm by 0.0278.
     *
     * It does not isolate the refund that lands in the sibling's gap. With `complete`'s refund
     * removed, the unreturned reservation stays on `g`'s path and still costs `a2` through `g`, and
     * the clearance reads 0.0146: about two thirds of what this sees is the over-reservation, a
     * third the refund. [CLEARANCE] sits between the two, so that mutation still reds it.
     */
    @Test
    fun overReservingTheLastJobCostsTheSiblingMoreThanNeverOverReserving() {
        val refunds = twoLevel(Estimate.FOUR_TIMES_ON_LAST).sibling.mean
        assertAll(
            *listOf(Estimate.QUARTER, Estimate.ONE_THEN_EXTEND).map { never ->
                {
                    val without = twoLevel(never).sibling.mean
                    assertTrue(
                        refunds <= without - CLEARANCE,
                        "a2's mean beside 4× on the last job, ${fmt(refunds)}, against $never, ${fmt(without)}: clearance ${fmt(without - refunds)} < $CLEARANCE",
                    )
                }
            }.toTypedArray(),
        )
    }

    private class Row(val label: String, val mean: Double, val min: Double, val max: Double)

    private fun checks(spread: Spread, row: Row): List<() -> Unit> = listOf(
        { assertTrue(abs(spread.mean - row.mean) <= MEAN_TOLERANCE, "${row.label}: mean ${fmt(spread.mean)}, pinned ${fmt(row.mean)} ± $MEAN_TOLERANCE") },
        { assertTrue(spread.min >= row.min - EXTREME_TOLERANCE, "${row.label}: worst loss ${fmt(spread.min)}, bound ${fmt(row.min - EXTREME_TOLERANCE)}") },
        { assertTrue(spread.max <= row.max + EXTREME_TOLERANCE, "${row.label}: worst gain ${fmt(spread.max)}, bound ${fmt(row.max + EXTREME_TOLERANCE)}") },
    )

    private fun fingerprintIs(label: String, pinned: Long, actual: Long): () -> Unit =
        { assertEquals(pinned, actual, "$label: the hash of every rig's slot times, pinned on JVM and macosArm64") }

    private fun pin(arm: OneLevelArm, row: Row, fingerprint: Long) =
        assertAll(*(checks(arm.lane, row) + fingerprintIs(row.label, fingerprint, arm.fingerprint)).toTypedArray())

    /** Both lanes' rows and the fingerprint in one [assertAll], so a red lane row cannot hide the sibling's. */
    private fun pin(arm: TwoLevelArm, lane: Row, sibling: Row, fingerprint: Long) =
        assertAll(*(checks(arm.lane, lane) + checks(arm.sibling, sibling) + fingerprintIs(lane.label, fingerprint, arm.fingerprint)).toTypedArray())

    private companion object {
        const val MEAN_TOLERANCE = 0.005
        const val EXTREME_TOLERANCE = 0.01
        const val CLEARANCE = 0.018
    }
}

/** One arm's deltas over an ensemble: the mean and both extremes. */
internal class Spread(val values: List<Double>) {
    val mean: Double = values.sum() / values.size
    val min: Double = values.min()
    val max: Double = values.max()
}

/**
 * One arm over an ensemble: its spreads, and a [fingerprint] of every rig's integer slot times, in
 * ensemble order. The spreads are tolerant, so they cannot see one rig take a different trajectory on
 * one target; the fingerprint is exact, so it can.
 */
internal class OneLevelArm(val lane: Spread, val fingerprint: Long)

internal class TwoLevelArm(val lane: Spread, val sibling: Spread, val fingerprint: Long)

private fun fmt(d: Double): String = (round(d * 10_000) / 10_000).toString()

/**
 * Ensemble runs, each computed once per test process and shared by the tests that read it, so the
 * honest arm runs once per level. Not thread-safe, and need not be: tests in one process run one at
 * a time on every target.
 */
private val oneLevelHonest = mutableMapOf<Pair<Long, Int>, ReservationLeanRig.Run>()
private val oneLevelArms = mutableMapOf<Estimate, OneLevelArm>()
private val twoLevelHonest = mutableMapOf<List<Long>, ReservationLeanRig.Run>()
private val twoLevelArms = mutableMapOf<Estimate, TwoLevelArm>()

private fun oneLevelRun(estimate: Estimate, think: Long, burst: Int): ReservationLeanRig.Run =
    ReservationLeanRig.run(ReservationLeanRig.oneLevel(), listOf(Lane(A, estimate, think, burst)))

/** `a` against an always-backlogged `b`, at think 3..14 × burst {2, 3, 4, 8}, cost 8: 48 rigs. */
private fun oneLevel(estimate: Estimate): OneLevelArm = oneLevelArms.getOrPut(estimate) {
    val deltas = mutableListOf<Double>()
    var print = 17L
    for (think in 3L..14L) for (burst in listOf(2, 3, 4, 8)) {
        val honest = oneLevelHonest.getOrPut(think to burst) { oneLevelRun(Estimate.HONEST, think, burst) }
        val run = if (estimate == Estimate.HONEST) honest else oneLevelRun(estimate, think, burst)
        deltas += run.share(A) - honest.share(A)
        print = run.fingerprint(print, listOf(A, B))
    }
    check(deltas.size == 48) { "one level: ${deltas.size} rigs" }
    OneLevelArm(Spread(deltas), print)
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
private fun twoLevel(estimate: Estimate): TwoLevelArm = twoLevelArms.getOrPut(estimate) {
    val lane = mutableListOf<Double>()
    val sibling = mutableListOf<Double>()
    var print = 17L
    for (t1 in listOf(4L, 7L, 12L)) for (t2 in listOf(4L, 7L, 12L)) for (s2 in listOf(0L, 2L, 5L)) for (b2 in listOf(2, 4)) {
        val honest = twoLevelHonest.getOrPut(listOf(t1, t2, s2, b2.toLong())) { twoLevelRun(Estimate.HONEST, t1, t2, s2, b2) }
        val run = if (estimate == Estimate.HONEST) honest else twoLevelRun(estimate, t1, t2, s2, b2)
        lane += run.share(A1) - honest.share(A1)
        sibling += run.share(A2) - honest.share(A2)
        print = run.fingerprint(print, listOf(A1, A2, B))
    }
    check(lane.size == 54) { "two levels: ${lane.size} rigs" }
    TwoLevelArm(Spread(lane), Spread(sibling), print)
}
