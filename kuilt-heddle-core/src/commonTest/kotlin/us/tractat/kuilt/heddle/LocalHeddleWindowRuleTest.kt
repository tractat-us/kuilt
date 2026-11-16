package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A heavy child joining lighter siblings that are a lease apart: never refused, and fair (#3353,
 * #3472).
 *
 * Every integer weight has the same ceiling in ticks, but a heavy child's ticks climb `k` times as
 * fast per unit of virtual time, so a weight-`k` child joining lights lands `k` times as far up its
 * own grid. These rows were written for the window rule, which kept the rebase from seating such a
 * child past its ceiling; every one was measured refusing on the base before it. Nothing rebases
 * now, and a pick orders the wide position, so each row asserts what is left to promise: every step
 * leases, the heavy lane takes its share while it waits, and the always-waiting lights stay level
 * with each other. Each asserts its rig fired as well.
 */
class LocalHeddleWindowRuleTest {

    private val two = listOf("l1" to Weight.ONE, "l2" to Weight.ONE)
    private val four = listOf("l1" to Weight.ONE, "l2" to Weight.ONE, "l3" to Weight.ONE, "l4" to Weight.ONE)

    /**
     * Never refused, and fair. A heavy lane of weight `k` beside `n` lights is owed `k / (k + n)` of
     * the service while it waits, which for every `k` here is all but a few picks, so it must take
     * at least [HEAVY_SHARE] of its waiting steps. With [lightsAlwaysWait], the lights wait every
     * step at weight 1, so what each was charged ends within [LIGHT_SPREAD] leases of the others.
     */
    private fun assertNeverRefusedAndFair(
        row: WindowRuleRig.Result,
        reloadsAtLeast: Int = 0,
        lightsAlwaysWait: Boolean = true,
    ) {
        val lights = row.served.filterKeys { it.startsWith("l") }.values
        assertAll(
            { assertEquals(0, row.refusals, "no pick refused: $row") },
            { assertEquals(row.steps, row.picks, "every step leased: $row") },
            { assertTrue(row.reloads >= reloadsAtLeast, "the rig: at least $reloadsAtLeast newborns joined, $row") },
            { assertTrue(row.heavyPicks > 0, "the rig: a heavy lane was picked, $row") },
            { assertTrue(row.heavyPicks >= HEAVY_SHARE * row.heavyWaiting, "the heavy lane took its share while it waited, $row") },
            {
                if (lightsAlwaysWait) {
                    assertTrue(lights.size >= 2 && lights.all { it > 0L }, "the rig: every light was served, $row")
                    val spread = lights.max() - lights.min()
                    assertTrue(spread <= LIGHT_SPREAD * LocalHeddle.MAX_RESERVATION, "the lights stayed level: $spread apart, $row")
                }
            },
        )
    }

    /** `{1, 1}` and a weight-65,534 newborn every few hundred steps: refused for good from step 1,263 before the window rule. */
    @Test
    fun aHeavyNewbornBesideTwoLightsIsNeverRefused() =
        assertNeverRefusedAndFair(
            WindowRuleRig.newborns(Weight.of(65_534L), two, Random(3354), every = 500, burst = 50, steps = 2_000),
            reloadsAtLeast = 3,
        )

    /** `{1, 1, 1, 1}` and a weight-57,344 newborn: refused for good from step 5,777 before the window rule. */
    @Test
    fun aHeavyNewbornBesideFourLightsIsNeverRefused() =
        assertNeverRefusedAndFair(
            WindowRuleRig.newborns(Weight.of(57_344L), four, Random(3354), every = 500, burst = 50, steps = 6_500),
            reloadsAtLeast = 8,
        )

    /** `{1, 1}` and a weight-65,532 waker that comes and goes at random: refused for good from step 40 before the window rule. */
    @Test
    fun aHeavyWakerBesideTwoLightsIsNeverRefused() {
        val rig = WindowRuleRig(two + ("heavy" to Weight.of(65_532L)))
        assertNeverRefusedAndFair(rig.run(500, waiting = WindowRuleRig.randomWaker(Random(3353), idle = 1_000, burst = 50)))
    }

    /**
     * `{1, 1, 1, 1}` and a weight-57,344 waker. With no rebase a position passes its ceiling within
     * one waking burst, which is the regime the window rule kept the parent out of, and the row
     * asserts it gets there: past the ceiling only the public reads saturate, and the picks stay
     * fair.
     */
    @Test
    fun aHeavyWakerBesideFourLightsIsNeverRefused() {
        val row = WindowRuleRig(four + ("heavy" to Weight.of(57_344L)))
            .run(500, waiting = WindowRuleRig.randomWaker(Random(3354), idle = 200, burst = 50))
        assertAll(
            { assertTrue(row.peakShare >= 1.0, "the rig: a position passed its ceiling, $row") },
            { assertNeverRefusedAndFair(row) },
        )
    }

    /** `{1, 1, 1, 1}` and a weight-2²⁰ lane, every lane on a coin flip each step: three refusals, from step 15, before the window rule. */
    @Test
    fun aHeavyLaneInAChurningSetIsNeverRefused() {
        val rig = WindowRuleRig(four + ("heavy" to Weight.of(1L shl 20)))
        assertNeverRefusedAndFair(rig.run(2_000, waiting = WindowRuleRig.churn(Random(3353))), lightsAlwaysWait = false)
    }

    private companion object {
        /** The share of its waiting steps a heavy lane must take: it is owed all but a few of them. */
        const val HEAVY_SHARE: Double = 0.9

        /** How many leases apart, in what they were charged, the always-waiting lights may end. */
        const val LIGHT_SPREAD: Long = 2L
    }
}
