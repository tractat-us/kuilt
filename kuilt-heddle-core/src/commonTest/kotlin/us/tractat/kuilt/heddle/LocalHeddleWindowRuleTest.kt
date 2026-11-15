package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A heavy child joining lighter siblings that are a lease apart (#3353, the window rule).
 *
 * Every integer weight has the same ceiling in ticks, but a heavy child's ticks climb `k` times as
 * fast per unit of virtual time. When the rebase may lower the origin only as far as the least-served
 * demanding light child, two lights a lease apart hold the front half a lease above the origin, and a
 * weight-`k` child joining there lands `k` times that far up its own grid: past its ceiling for `k`
 * near `2¹⁶`. Every row here was measured refusing on the base before the window rule; each asserts
 * that its rig fired as well as that it never refused.
 */
class LocalHeddleWindowRuleTest {

    private val two = listOf("l1" to Weight.ONE, "l2" to Weight.ONE)
    private val four = listOf("l1" to Weight.ONE, "l2" to Weight.ONE, "l3" to Weight.ONE, "l4" to Weight.ONE)

    private fun assertNeverRefused(row: WindowRuleRig.Result, reloadsAtLeast: Int = 0, heavyPicked: String? = null) = assertAll(
        { assertEquals(0, row.refusals, "no pick refused at the ceiling: $row") },
        { assertEquals(row.steps, row.picks, "every step leased: $row") },
        { assertTrue(row.rebases > 0, "the rig: the parent rebased, $row") },
        { assertTrue(row.reloads >= reloadsAtLeast, "the rig: at least $reloadsAtLeast newborns joined, $row") },
        { heavyPicked?.let { h -> assertTrue(row.leaves.any { it.startsWith(h) }, "the rig: a heavy lane was picked, $row") } },
    )

    /** `{1, 1}` and a weight-65,534 newborn every few hundred steps: refused for good from step 1,263. */
    @Test
    fun aHeavyNewbornBesideTwoLightsIsNeverRefused() =
        assertNeverRefused(
            WindowRuleRig.newborns(Weight.of(65_534L), two, Random(3354), every = 500, burst = 50, steps = 2_000),
            reloadsAtLeast = 3,
            heavyPicked = "h",
        )

    /** `{1, 1, 1, 1}` and a weight-57,344 newborn: refused for good from step 5,777. */
    @Test
    fun aHeavyNewbornBesideFourLightsIsNeverRefused() =
        assertNeverRefused(
            WindowRuleRig.newborns(Weight.of(57_344L), four, Random(3354), every = 500, burst = 50, steps = 6_500),
            reloadsAtLeast = 8,
            heavyPicked = "h",
        )

    /** `{1, 1}` and a weight-65,532 waker that comes and goes at random: refused for good from step 40. */
    @Test
    fun aHeavyWakerBesideTwoLightsIsNeverRefused() {
        val rig = WindowRuleRig(two + ("heavy" to Weight.of(65_532L)))
        assertNeverRefused(rig.run(500, waiting = WindowRuleRig.randomWaker(Random(3353), idle = 1_000, burst = 50)), heavyPicked = "heavy")
    }

    /** `{1, 1, 1, 1}` and a weight-2²⁰ lane, every lane on a coin flip each step: six refusals, from step 15. */
    @Test
    fun aHeavyLaneInAChurningSetIsNeverRefused() {
        val rig = WindowRuleRig(four + ("heavy" to Weight.of(1L shl 20)))
        assertNeverRefused(rig.run(2_000, waiting = WindowRuleRig.churn(Random(3353))), heavyPicked = "heavy")
    }
}
