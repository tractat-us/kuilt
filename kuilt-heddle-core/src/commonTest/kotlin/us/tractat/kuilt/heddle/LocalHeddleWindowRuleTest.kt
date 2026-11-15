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

    /** `{1, 1, 1, 1}` and a weight-2²⁰ lane, every lane on a coin flip each step: three refusals, from step 15. */
    @Test
    fun aHeavyLaneInAChurningSetIsNeverRefused() {
        val rig = WindowRuleRig(four + ("heavy" to Weight.of(1L shl 20)))
        assertNeverRefused(rig.run(2_000, waiting = WindowRuleRig.churn(Random(3353))), heavyPicked = "heavy")
    }

    /**
     * A rebase moves every child of a parent by the same virtual time, so the order a pick sees is
     * the same whichever origin it is measured from: the window rule changes **where** the origin
     * goes, never **who** is picked. Each row runs twice, on the rule before it and on the window
     * rule, over shapes the rule before never refused, and the two runs pick the same lane at every
     * step. The rig fired: no joiner landed at its floor, which is the one place the rules may part,
     * and the two runs ended at different origins, so each row did measure a different rebase.
     */
    @Test
    fun theWindowRulePicksAsTheRuleBeforeWhereverThatNeverRefused() {
        val rows: List<Pair<String, (Boolean) -> WindowRuleRig.Result>> = listOf(
            "churn {1, 1, 65,536}" to { w -> WindowRuleRig(two + ("heavy" to Weight.of(65_536L)), w).run(STEPS, waiting = WindowRuleRig.churn(Random(3353))) },
            "churn {1, 1, 1, 1, 16,384}" to { w -> WindowRuleRig(four + ("heavy" to Weight.of(16_384L)), w).run(STEPS, waiting = WindowRuleRig.churn(Random(3353))) },
            "waker {1, 1} + 61,440" to { w ->
                WindowRuleRig(two + ("heavy" to Weight.of(61_440L)), w).run(STEPS, waiting = WindowRuleRig.randomWaker(Random(3353), idle = 200, burst = 50))
            },
            "newborns {1, 1} + 61,440" to { w -> WindowRuleRig.newborns(Weight.of(61_440L), two, Random(3354), every = 300, burst = 50, steps = STEPS, windowRule = w) },
            "newborns {1, 1, 1, 1} + 49,152" to { w -> WindowRuleRig.newborns(Weight.of(49_152L), four, Random(3354), every = 300, burst = 50, steps = STEPS, windowRule = w) },
            "fine {7/13, 5/11, 1/3}" to { w ->
                WindowRuleRig(listOf("a" to Weight.of(7L, 13L), "b" to Weight.of(5L, 11L), "c" to Weight.of(1L, 3L)), w).run(STEPS, waiting = WindowRuleRig.churn(Random(3353)))
            },
            "mixed {1, 4,096}" to { w -> WindowRuleRig(listOf("light" to Weight.ONE, "heavy" to Weight.of(4_096L)), w).run(STEPS, waiting = WindowRuleRig.churn(Random(3353))) },
        )
        val twins = rows.map { (name, make) -> Triple(name, make(false), make(true)) }
        assertAll(
            { assertEquals(rows.size, twins.size, "every row ran") },
            *twins.flatMap { (name, before, window) ->
                listOf(
                    { assertEquals(0, before.refusals + window.refusals, "$name: neither rule refused, before[$before] window[$window]") },
                    { assertEquals(before.leaves, window.leaves, "$name: the same lane at every step") },
                    { assertEquals(0, window.floorLandings, "$name: the rig, no joiner landed at its floor") },
                    { assertTrue(window.rebases > 0 && before.rebases > 0, "$name: the rig, both rebased") },
                )
            }.toTypedArray(),
            { assertEquals(rows.size, twins.count { (_, before, window) -> before.origin != window.origin }, "the rig: on every row the rules moved the origin differently") },
        )
    }

    private companion object {
        /** Steps a twin row runs on each rule. */
        const val STEPS: Int = 1_000
    }
}
