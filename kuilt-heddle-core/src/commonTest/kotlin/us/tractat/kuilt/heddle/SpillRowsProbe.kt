package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.WakeCreditRig.A
import us.tractat.kuilt.heddle.WakeCreditRig.A1
import us.tractat.kuilt.heddle.WakeCreditRig.A2
import us.tractat.kuilt.heddle.WakeCreditRig.Arm
import us.tractat.kuilt.heddle.WakeCreditRig.LaneSpec
import us.tractat.kuilt.heddle.WakeCreditRig.Trace
import us.tractat.kuilt.heddle.WakeCreditRig.WakeConfig
import kotlin.test.Test

/**
 * TEMPORARY, print-only (#3248 review): the two review rows, the sweep's worst cell, and the
 * 192-rig staggered sweep, with a trace for the ablation and divergence study. Asserts nothing; drop
 * it once the design call on the spill is made.
 *
 * Each traced arm is bracketed by `BEGIN <label>` / `END <label>`, so lines an instrumented engine
 * prints in between are attributed to it. The ablations were run by mutating `FairShareAllocator`
 * in the working tree, never committed; the PR comment lists each mutation. `T` lines list a tick's
 * bursty-lane events; `S` lines dump each tree level before every acquire on the ticks
 * [ROW1_STATES], [WORST_STATES] and [ROW2_STATES] select.
 */
class SpillRowsProbe {
    @Test
    fun printRows() {
        for (arm in listOf(Arm.HONEST, Arm.OVER_LAST)) {
            emit(
                WakeConfig(
                    "SPILL a1=$arm(t7,b4) a2=HONEST(t5,b2,start2)",
                    WakeCreditRig.twoLevel(),
                    listOf(LaneSpec(A1, arm, 7L, 4, listOf(8L)), LaneSpec(A2, Arm.HONEST, 5L, 2, listOf(8L), firstArrival = 2L)),
                ),
                traced = arm == Arm.OVER_LAST,
                states = ROW1_STATES,
            )
        }
        // The sweep's worst honest-sibling cell under A: (t1, t2, s2, b2) = (7, 5, 7, 2).
        for (arm in listOf(Arm.HONEST, Arm.OVER_LAST)) {
            emit(staggered(7L, 5L, 7L, 2, arm).relabel("WORST"), traced = arm == Arm.OVER_LAST, states = WORST_STATES)
        }
        for (arm in listOf(Arm.HONEST, Arm.NOISE)) {
            emit(
                WakeConfig("NOISE5 a=$arm(t4,b4,[6,10,7,9,8])", WakeCreditRig.oneLevel(), listOf(LaneSpec(A, arm, 4L, 4, listOf(6L, 10L, 7L, 9L, 8L)))),
                traced = arm == Arm.NOISE,
                states = ROW2_STATES,
            )
        }
    }

    // The staggered two-level sweep from the #3240 review: a1 over-reserves (or cancels) the last
    // job of its 4-job bursts, a2 is honest. One method per a1 think, so each fits one run.
    @Test
    fun sweepThink4() = sweep(4L)

    @Test
    fun sweepThink7() = sweep(7L)

    @Test
    fun sweepThink12() = sweep(12L)

    private fun sweep(t1: Long) {
        for (t2 in listOf(4L, 5L, 7L, 9L)) {
            for (s2 in 0L..7L) {
                for (b2 in listOf(2, 4)) {
                    for (arm in listOf(Arm.HONEST, Arm.OVER_LAST, Arm.CANCEL_LAST)) {
                        println(WakeCreditRig.run(staggered(t1, t2, s2, b2, arm)).row())
                    }
                }
            }
        }
    }

    private fun staggered(t1: Long, t2: Long, s2: Long, b2: Int, arm: Arm): WakeConfig =
        WakeConfig(
            "SWEEP t1=$t1 t2=$t2 s2=$s2 b2=$b2 arm=$arm",
            WakeCreditRig.twoLevel(),
            listOf(LaneSpec(A1, arm, t1, 4, listOf(8L)), LaneSpec(A2, Arm.HONEST, t2, b2, listOf(8L), firstArrival = s2)),
        )

    private fun emit(config: WakeConfig, traced: Boolean, states: (Long) -> Boolean) {
        println("BEGIN ${config.label}")
        val run = WakeCreditRig.run(config, if (traced) Trace({ println(it) }, states) else Trace.OFF)
        println(run.row())
        println("END ${config.label}")
    }

    private fun WakeConfig.relabel(prefix: String): WakeConfig = WakeConfig("$prefix $label", ledger, lanes, slots, sleeperCredit, horizon)

    private companion object {
        val ROW1_STATES: (Long) -> Boolean = { it in 21L..23L || it in 2103L..2124L }
        val WORST_STATES: (Long) -> Boolean = { it in 12L..19L }
        val ROW2_STATES: (Long) -> Boolean = { it in 8L..9L }
    }
}
