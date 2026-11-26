package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.WakeCreditRig.A
import us.tractat.kuilt.heddle.WakeCreditRig.A1
import us.tractat.kuilt.heddle.WakeCreditRig.A2
import us.tractat.kuilt.heddle.WakeCreditRig.Arm
import us.tractat.kuilt.heddle.WakeCreditRig.LaneSpec
import us.tractat.kuilt.heddle.WakeCreditRig.WakeConfig
import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A lane's lease that straddles its idle gap (#3238), noisy honest costs, and a cancel of work
 * that ran, on [WakeCreditRig] through [LocalHeddle] (#3376).
 *
 * Every number is a share **less the same rig's honest arm**, so the arms measure only what the
 * reservation, the refund or the cancel changed. One lane `a` against an always-backlogged `b`,
 * `think` units between bursts: at a think shorter than a job (8) the lane is seen idle with its
 * lease still open and wakes while it runs, which is the straddle.
 *
 * With the wake clamp, an over-reserved straddling lease was forgiven at each wake (+0.136 at t7),
 * and an under-reserved one was charged twice. With the net-service clock the over-reservation is
 * the lane's own position, kept across the gap, and its refund lowers it, so both read as honest.
 */
class WakeCreditSlotTest {

    private fun oneLevel(arm: Arm, think: Long, costs: List<Long> = listOf(8L), burst: Int = BURST): WakeCreditRig.Run =
        WakeCreditRig.run(WakeConfig(WakeCreditRig.oneLevel(), listOf(LaneSpec(A, arm, think, burst, costs)), horizon = HORIZON))

    private fun twoLevel(arm: Arm, think: Long): WakeCreditRig.Run = WakeCreditRig.run(
        WakeConfig(
            WakeCreditRig.twoLevel(),
            listOf(LaneSpec(A1, arm, think, BURST, listOf(8L)), LaneSpec(A2, Arm.HONEST, 11L - think, BURST, listOf(8L))),
            horizon = HORIZON,
        ),
    )

    /**
     * The engine saw the lane wake, and, at a think shorter than a job, a burst queued while the lane
     * still held a lease: the straddle. At a think past the longest job the lease has always ended first.
     */
    private fun fired(label: String, think: Long, run: WakeCreditRig.Run, longestJob: Long = 8L): () -> Unit = {
        val straddles = think < longestJob
        assertTrue(run.wakes > 0 && (run.wakesWithOpenLease > 0) == straddles, "$label: wakes=${run.wakes} openWakes=${run.wakesWithOpenLease}")
    }

    private fun within(label: String, measured: Double, expected: Double, tolerance: Double): () -> Unit = {
        assertTrue(abs(measured - expected) <= tolerance, "$label: ${fmt(measured)}, expected ${fmt(expected)} ± $tolerance")
    }

    /** One level: reserving four times the cost, or a quarter of it, on the job that ends a burst changes nothing. */
    @Test
    fun aStraddlingLeaseIsChargedWhatItUsed() {
        val checks = THINKS.flatMap { think ->
            val honest = oneLevel(Arm.HONEST, think)
            val over = oneLevel(Arm.OVER_LAST, think)
            val under = oneLevel(Arm.UNDER_LAST, think)
            listOf(
                fired("t$think over", think, over),
                fired("t$think under", think, under),
                within("t$think over-last", over.share(A) - honest.share(A), 0.0, STRADDLE_TOLERANCE),
                within("t$think under-last", under.share(A) - honest.share(A), 0.0, STRADDLE_TOLERANCE),
            )
        }
        assertAll(*checks.toTypedArray())
    }

    /** Honest estimates of noisy costs take what honest exact estimates of the same costs take. */
    @Test
    fun noisyHonestCostsCostNothing() {
        val cases = listOf(listOf(2L, 14L) to THINKS, listOf(4L, 4L, 4L, 20L) to listOf(4L, 12L))
        val checks = cases.flatMap { (costs, thinks) ->
            thinks.flatMap { think ->
                val noise = oneLevel(Arm.NOISE, think, costs)
                val honest = oneLevel(Arm.HONEST, think, costs)
                listOf(fired("noise$costs t$think", think, noise, costs.max()), within("noise$costs t$think", noise.share(A) - honest.share(A), 0.0, NOISE_TOLERANCE))
            }
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * Two levels, `a1` over-reserving its last job beside an honest sibling `a2` under one group: the
     * spill. A waker's shift counts a sibling's outstanding reservation as clock it ran, and that
     * sibling's later refund then lands while the waker is away (#3239). The clock roughly halves
     * the spill the clamp left at t7 (+0.111 / −0.100); what is left is pinned, not claimed fixed.
     */
    @Test
    fun theTwoLevelSpillIsPinnedAtItsKnownSize() {
        val checks = listOf(7L to (0.045 to -0.029), 4L to (0.010 to -0.026)).flatMap { (think, expected) ->
            val honest = twoLevel(Arm.HONEST, think)
            val over = twoLevel(Arm.OVER_LAST, think)
            listOf(
                fired("2L t$think", think, over),
                within("2L t$think a1", over.share(A1) - honest.share(A1), expected.first, PIN_TOLERANCE),
                within("2L t$think a2", over.share(A2) - honest.share(A2), expected.second, PIN_TOLERANCE),
            )
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * A cancelled job that held its slot is charged nothing, which is cancel's free ride, with an
     * idle gap as without one ([aCancelledJobThatRanIsAFreeRideOfExactlyItsCost]). None of it is wake
     * credit: a cancel charged its reservation, which here is the job's cost, takes every row below
     * to 0.0. It is the contract, not a leak (#3238): `cancel` is for work that never ran, and work
     * that held its slot settles with `complete(lease, elapsed)`. The sizes are pinned because they
     * follow how much of the lane's slot time went uncharged, which the gap changes.
     */
    @Test
    fun theCancelResidualIsPinnedAtItsKnownSize() {
        val checks = listOf(7L to 0.030, 4L to 0.044, 12L to 0.015).flatMap { (think, expected) ->
            val honest = oneLevel(Arm.HONEST, think)
            val cancel = oneLevel(Arm.CANCEL_LAST, think)
            listOf(fired("t$think cancel", think, cancel), within("t$think cancel-last", cancel.share(A) - honest.share(A), expected, PIN_TOLERANCE))
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * The no-gap control: what `cancel` on work that ran costs everyone else, with no idle gap for
     * wake credit to hide in (#3238). A lane that never idles cancels the last of every four jobs
     * against an always-backlogged sibling at equal weight, all of its jobs costing 8.
     *
     * The oracle, from the charges alone. Both lanes ask at every call, so fair share levels what
     * each was **charged**. Let `s_a` and `s_b` be the slot time each lane held. `b` is charged all
     * of `s_b`; `a` is charged three jobs of every four, all the same size, so `3/4 · s_a`. Level
     * charges give `s_b = 3/4 · s_a`, so `a` holds `s_a / (s_a + 3/4 · s_a) = 4/7` of the slots,
     * where settling every job at its cost holds `1/2`. The cancels gain `a` `4/7 − 1/2 = 1/14`.
     *
     * The same rig settling those jobs with `complete(lease, elapsed)` gains nothing: that is the
     * honest arm, and its share is a half. The rig is deterministic and draws nothing at random, so
     * both measured shares are pinned exactly beside the oracle and the half.
     */
    @Test
    fun aCancelledJobThatRanIsAFreeRideOfExactlyItsCost() {
        val honest = oneLevel(Arm.HONEST, WakeCreditRig.NEVER_IDLES, burst = 4)
        val cancel = oneLevel(Arm.CANCEL_LAST, WakeCreditRig.NEVER_IDLES, burst = 4)
        val oracle = 4.0 / 7.0 - 1.0 / 2.0
        assertAll(
            { assertEquals(0 to 0, cancel.wakes to cancel.wakesWithOpenLease, "the cancelling lane was never seen idle") },
            { assertEquals(0 to 0, honest.wakes to honest.wakesWithOpenLease, "the honest lane was never seen idle") },
            within("honest share against a half", honest.share(A), 0.5, NO_GAP_ORACLE_TOLERANCE),
            within("cancel gain against the oracle", cancel.share(A) - honest.share(A), oracle, NO_GAP_ORACLE_TOLERANCE),
            within("honest share as measured", honest.share(A), NO_GAP_HONEST_SHARE, EXACT),
            within("cancel share as measured", cancel.share(A), NO_GAP_CANCEL_SHARE, EXACT),
        )
    }

    private fun fmt(d: Double): String = (kotlin.math.round(d * 10_000) / 10_000).toString()

    private companion object {
        const val HORIZON = 4_000L
        const val BURST = 4
        val THINKS = listOf(7L, 4L, 12L)
        const val STRADDLE_TOLERANCE = 0.005
        const val NOISE_TOLERANCE = 0.015
        const val PIN_TOLERANCE = 0.003
        const val NO_GAP_ORACLE_TOLERANCE = 0.001

        /** 15,996 of the 32,000 slot-time units: the horizon's edge, not a lean. */
        const val NO_GAP_HONEST_SHARE = 0.499875

        /** 18,274 of the 32,000 slot-time units, a gain of 0.0712 against the oracle's 0.0714. */
        const val NO_GAP_CANCEL_SHARE = 0.5710625
        const val EXACT = 1e-9
    }
}
