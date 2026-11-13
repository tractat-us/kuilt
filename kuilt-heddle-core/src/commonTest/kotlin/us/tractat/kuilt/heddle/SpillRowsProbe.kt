package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.WakeCreditRig.A
import us.tractat.kuilt.heddle.WakeCreditRig.A1
import us.tractat.kuilt.heddle.WakeCreditRig.A2
import us.tractat.kuilt.heddle.WakeCreditRig.Arm
import us.tractat.kuilt.heddle.WakeCreditRig.LaneSpec
import us.tractat.kuilt.heddle.WakeCreditRig.WakeConfig
import kotlin.test.Test

/** TEMPORARY (#3248 review rows): prints the two rows the 05:07Z comment asks for. */
class SpillRowsProbe {
    @Test
    fun printRows() {
        for (horizon in listOf(4_000L, 20_000L)) {
            for (arm in listOf(Arm.HONEST, Arm.OVER_LAST)) {
                val run = WakeCreditRig.run(
                    WakeConfig(
                        "SPILL h=$horizon a1=$arm(t7,b4) a2=HONEST(t5,b2,start2)",
                        WakeCreditRig.twoLevel(),
                        listOf(LaneSpec(A1, arm, 7L, 4, listOf(8L)), LaneSpec(A2, Arm.HONEST, 5L, 2, listOf(8L), firstArrival = 2L)),
                        horizon = horizon,
                    ),
                )
                println(run.row())
            }
            for (arm in listOf(Arm.HONEST, Arm.NOISE)) {
                val run = WakeCreditRig.run(
                    WakeConfig("NOISE5 h=$horizon a=$arm(t4,b4,[6,10,7,9,8])", WakeCreditRig.oneLevel(), listOf(LaneSpec(A, arm, 4L, 4, listOf(6L, 10L, 7L, 9L, 8L))), horizon = horizon),
                )
                println(run.row())
            }
        }
    }
}
