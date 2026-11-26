package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.ReservationLeanRig.A
import us.tractat.kuilt.heddle.ReservationLeanRig.A1
import us.tractat.kuilt.heddle.ReservationLeanRig.A2
import us.tractat.kuilt.heddle.ReservationLeanRig.Estimate
import us.tractat.kuilt.heddle.ReservationLeanRig.Lane
import kotlin.test.Test

// Scratch measurement, removed before ready.
class ReservationLeanMeasure {
    private val arms = listOf(Estimate.QUARTER, Estimate.FOUR_TIMES, Estimate.FOUR_TIMES_ON_LAST, Estimate.ONE_THEN_EXTEND)
    private fun f(d: Double) = (kotlin.math.round(d * 10_000) / 10_000).toString()
    private fun s(v: List<Double>) = "mean ${f(v.sum() / v.size)} min ${f(v.min())} max ${f(v.max())}"

    @Test
    fun measure() {
        for (horizon in (System_horizons())) {
            println("H=$horizon ONE")
            val d = arms.associateWith { mutableListOf<Double>() }
            for (think in 3L..14L) for (burst in listOf(2, 3, 4, 8)) {
                fun sh(e: Estimate) = ReservationLeanRig.run(ReservationLeanRig.oneLevel(), listOf(Lane(A, e, think, burst)), horizon).share(A)
                val h = sh(Estimate.HONEST)
                for (a in arms) d.getValue(a) += sh(a) - h
            }
            for (a in arms) println("  $a ${s(d.getValue(a))}")
            println("H=$horizon TWO")
            val l = arms.associateWith { mutableListOf<Double>() }
            val sb = arms.associateWith { mutableListOf<Double>() }
            for (t1 in listOf(4L, 7L, 12L)) for (t2 in listOf(4L, 7L, 12L)) for (s2 in listOf(0L, 2L, 5L)) for (b2 in listOf(2, 4)) {
                fun r(e: Estimate) = ReservationLeanRig.run(ReservationLeanRig.twoLevel(), listOf(Lane(A1, e, t1, burst = 4), Lane(A2, Estimate.HONEST, t2, burst = b2, start = s2)), horizon)
                val h = r(Estimate.HONEST)
                for (a in arms) {
                    val x = r(a)
                    l.getValue(a) += x.share(A1) - h.share(A1)
                    sb.getValue(a) += x.share(A2) - h.share(A2)
                }
            }
            for (a in arms) println("  $a a1 ${s(l.getValue(a))} | a2 ${s(sb.getValue(a))}")
        }
    }

    private fun System_horizons() = listOf(1000L, 2000L)
}
