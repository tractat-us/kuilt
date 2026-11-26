package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.ReservationLeanRig.A
import us.tractat.kuilt.heddle.ReservationLeanRig.A1
import us.tractat.kuilt.heddle.ReservationLeanRig.A2
import us.tractat.kuilt.heddle.ReservationLeanRig.Estimate
import us.tractat.kuilt.heddle.ReservationLeanRig.Lane
import kotlin.test.Test

class ReservationLeanTest {

    @Test
    fun measure() {
        println("ONE LEVEL")
        for ((arm, s) in oneLevel) println("  $arm: $s")
        println("TWO LEVEL")
        for ((arm, s) in twoLevel) println("  $arm: a1 ${s.lane} | a2 ${s.sibling}")
    }
}

/** Mean and both extremes of one arm's deltas over an ensemble. */
internal class Spread(values: List<Double>) {
    val mean: Double = values.sum() / values.size
    val min: Double = values.min()
    val max: Double = values.max()
    val n: Int = values.size
    override fun toString(): String = "n=$n mean ${fmt(mean)} min ${fmt(min)} max ${fmt(max)}"
}

internal class Pair2(val lane: Spread, val sibling: Spread)

private fun fmt(d: Double): String = (kotlin.math.round(d * 10_000) / 10_000).toString()

private val ARMS = listOf(Estimate.QUARTER, Estimate.FOUR_TIMES, Estimate.FOUR_TIMES_ON_LAST, Estimate.ONE_THEN_EXTEND)

/** think 3..14 × burst {2, 3, 4, 8}, cost 8: 48 rigs of `a` against an always-backlogged `b`. */
private val oneLevel: Map<Estimate, Spread> by lazy {
    val deltas = ARMS.associateWith { mutableListOf<Double>() }
    for (think in 3L..14L) for (burst in listOf(2, 3, 4, 8)) {
        fun share(e: Estimate) = ReservationLeanRig.run(ReservationLeanRig.oneLevel(), listOf(Lane(A, e, think, burst))).share(A)
        val honest = share(Estimate.HONEST)
        for (arm in ARMS) deltas.getValue(arm) += share(arm) - honest
    }
    deltas.mapValues { Spread(it.value) }
}

/** a1's think {4, 7, 12} × a2's think {4, 7, 12} × a2's start {0, 2, 5} × a2's burst {2, 4}: 54 rigs under `g`. */
private val twoLevel: Map<Estimate, Pair2> by lazy {
    val lane = ARMS.associateWith { mutableListOf<Double>() }
    val sibling = ARMS.associateWith { mutableListOf<Double>() }
    for (t1 in listOf(4L, 7L, 12L)) for (t2 in listOf(4L, 7L, 12L)) for (s2 in listOf(0L, 2L, 5L)) for (b2 in listOf(2, 4)) {
        fun run(e: Estimate) = ReservationLeanRig.run(
            ReservationLeanRig.twoLevel(),
            listOf(Lane(A1, e, t1, burst = 4), Lane(A2, Estimate.HONEST, t2, burst = b2, start = s2)),
        )
        val honest = run(Estimate.HONEST)
        for (arm in ARMS) {
            val r = run(arm)
            lane.getValue(arm) += r.share(A1) - honest.share(A1)
            sibling.getValue(arm) += r.share(A2) - honest.share(A2)
        }
    }
    ARMS.associateWith { Pair2(Spread(lane.getValue(it)), Spread(sibling.getValue(it))) }
}
