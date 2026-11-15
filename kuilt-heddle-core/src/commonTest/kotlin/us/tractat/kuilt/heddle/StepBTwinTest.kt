package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The step B twin (design §7.1, *Future: positions with no origin*, slice 1), over the shapes of
 * the #3353 weight-spread sweep, on [WindowRuleRig].
 *
 * Since step A every pick orders the full 128-bit position, so an engine with the rebase switched
 * off never raises an origin, holds every position at or above zero, and never reaches a floor arm
 * or the below-zero first-sighting arm: it is the engine step B leaves. Each row runs the rebasing
 * engine and that twin over the same calls, and the two must lease the same lane at every step.
 *
 * Each row also shows its rig fired: the rebasing side rebased, the twin never raised an origin, and
 * where the row is long enough, the twin's positions passed `2⁶³` ticks, which no position of the
 * rebasing side ever does. The design allows a row to differ only where the rebasing side landed a
 * joiner at its floor or clamped a never-observed child below zero. No row here does either, and
 * each asserts so, so every row must agree at every step.
 *
 * The sweep ran three of its rows at weights the budget now refuses (P1′), so they run here at the
 * largest round weight inside it instead: `{1, k}` always waiting at `k = 2⁵⁴` for `2⁵⁹ − 2⁴⁵`, which
 * is also inside the wide-range clause that replaces P1′, and `{1/d, 1/(d + 1)}` at `d = 30,718`
 * for `32,765`. Fine weights at `d = 2³¹ − 1` have no budget-legal form and are left out.
 * Every waiting set is a seeded draw over a [List] the rig built, so the trajectory is the same on
 * every target.
 */
class StepBTwinTest {

    private class Row(val name: String, val run: (rebase: Boolean) -> WindowRuleRig.Result)

    private val two = listOf("l1" to Weight.ONE, "l2" to Weight.ONE)
    private val four = listOf("l1" to Weight.ONE, "l2" to Weight.ONE, "l3" to Weight.ONE, "l4" to Weight.ONE)

    private fun lanes(vararg weights: Pair<String, Weight>) = weights.toList()

    /** The light lane alone for [alone] steps, then every lane on a coin flip. */
    private fun lightAloneThenChurn(random: Random, alone: Int): (Int, List<String>) -> Set<String> {
        val churn = WindowRuleRig.churn(random)
        return { step, names -> if (step < alone) setOf("light") else churn(step, names) }
    }

    private val everyLaneWaits: (Int, List<String>) -> Set<String> = { _, names -> names.toSet() }

    private val rows: List<Row> = listOf(
        Row("{1, k} light alone, then churn, k = 2⁴⁰") { r ->
            WindowRuleRig(lanes("light" to Weight.ONE, "heavy" to Weight.of(1L shl 40)), rebase = r)
                .run(STEPS, waiting = lightAloneThenChurn(Random(3353), alone = STEPS / 10))
        },
        Row("{k, k/2, k/8, 1} churn, k = 2⁴⁰") { r ->
            WindowRuleRig(lanes("k" to Weight.of(1L shl 40), "k2" to Weight.of(1L shl 39), "k8" to Weight.of(1L shl 37), "light" to Weight.ONE), rebase = r)
                .run(STEPS, waiting = WindowRuleRig.churn(Random(3353)))
        },
        Row("{1, k} heavy waker, k = 2⁴⁰") { r ->
            WindowRuleRig(lanes("light" to Weight.ONE, "heavy" to Weight.of(1L shl 40)), rebase = r)
                .run(STEPS, waiting = WindowRuleRig.randomWaker(Random(3353), idle = 200, burst = 50))
        },
        Row("{1, 1, k} heavy waker, k = 61,440") { r ->
            WindowRuleRig(two + ("heavy" to Weight.of(61_440L)), rebase = r)
                .run(STEPS, waiting = WindowRuleRig.randomWaker(Random(3353), idle = 200, burst = 50))
        },
        Row("{1, 1, k} heavy waker, k = 65,532") { r ->
            WindowRuleRig(two + ("heavy" to Weight.of(65_532L)), rebase = r)
                .run(STEPS, waiting = WindowRuleRig.randomWaker(Random(3353), idle = 200, burst = 50))
        },
        Row("{1, 1, 1, 1, k} heavy waker, k = 49,152") { r ->
            WindowRuleRig(four + ("heavy" to Weight.of(49_152L)), rebase = r)
                .run(STEPS, waiting = WindowRuleRig.randomWaker(Random(3354), idle = 200, burst = 50))
        },
        Row("{1, 1, 1, 1, k} heavy waker, k = 57,344") { r ->
            WindowRuleRig(four + ("heavy" to Weight.of(57_344L)), rebase = r)
                .run(STEPS, waiting = WindowRuleRig.randomWaker(Random(3354), idle = 200, burst = 50))
        },
        Row("{1, 1} + newborn, k = 61,440") { r ->
            WindowRuleRig.newborns(Weight.of(61_440L), two, Random(3354), every = 300, burst = 50, steps = STEPS, rebase = r)
        },
        Row("{1, 1} + newborn, k = 65,532") { r ->
            WindowRuleRig.newborns(Weight.of(65_532L), two, Random(3354), every = 300, burst = 50, steps = STEPS, rebase = r)
        },
        Row("{1, 1, 1, 1} + newborn, k = 49,152") { r ->
            WindowRuleRig.newborns(Weight.of(49_152L), four, Random(3354), every = 300, burst = 50, steps = STEPS, rebase = r)
        },
        Row("{1, 1, 1, 1} + newborn, k = 57,344") { r ->
            WindowRuleRig.newborns(Weight.of(57_344L), four, Random(3354), every = 300, burst = 50, steps = STEPS, rebase = r)
        },
        Row("{1, k} both always waiting, k = 2⁵⁴") { r ->
            WindowRuleRig(lanes("light" to Weight.ONE, "heavy" to Weight.of(1L shl 54)), rebase = r)
                .run(LONG_STEPS, waiting = everyLaneWaits)
        },
        Row("{1/d, 1/(d + 1)} churn, d = 30,718") { r ->
            WindowRuleRig(lanes("a" to Weight.of(1L, 30_718L), "b" to Weight.of(1L, 30_719L)), rebase = r)
                .run(FINE_STEPS, waiting = WindowRuleRig.churn(Random(3353)))
        },
        Row("fine {7/13, 5/11, 1/3} churn") { r ->
            WindowRuleRig(lanes("a" to Weight.of(7L, 13L), "b" to Weight.of(5L, 11L), "c" to Weight.of(1L, 3L)), rebase = r)
                .run(FINE_STEPS, waiting = WindowRuleRig.churn(Random(3353)))
        },
        // Off the sweep: fine weights a lease apart in thirds, so two lanes' positions meet exactly
        // and the id breaks the tie. A translation that is out by under a tick breaks such a tie the
        // other way, which no other row here is sensitive enough to see.
        Row("ties {1/3, 2/3, 1} churn") { r ->
            WindowRuleRig(lanes("a" to Weight.of(1L, 3L), "b" to Weight.of(2L, 3L), "c" to Weight.ONE), rebase = r)
                .run(FINE_STEPS, waiting = WindowRuleRig.churn(Random(3353)))
        },
    )

    @Test
    fun theRebasingEngineAndItsNoOriginTwinLeaseTheSameLaneAtEveryStep() {
        val runs = rows.map { row -> Triple(row, row.run(true), row.run(false)) }
        runs.forEach { (row, rebasing, twin) -> println("twin row ${row.name}: rebasing[$rebasing] twin[$twin]") }
        assertAll(
            { assertEquals(rows.size, runs.size, "every row ran") },
            *runs.flatMap { (row, rebasing, twin) ->
                val name = row.name
                listOf(
                    { assertEquals(0, rebasing.refusals + twin.refusals, "$name: neither side refused") },
                    { assertEquals(rebasing.steps, rebasing.picks, "$name: every step leased") },
                    { assertTrue(rebasing.rebases > 0, "$name: the rig, the rebasing side rebased ($rebasing)") },
                    { assertEquals(0, twin.rebases, "$name: the rig, the twin never rebased") },
                    { assertEquals(Int128.ZERO, twin.origin, "$name: the rig, the twin never raised an origin") },
                    { assertEquals(0, twin.floorLandings + twin.unobservedBelowZero, "$name: the twin reached no floor arm and no below-zero arm") },
                    { assertTrue(twin.peakTicks >= LONG_LIMIT_TICKS, "$name: the rig, the twin's positions passed 2⁶³ ticks (${twin.peakTicks})") },
                    { assertTrue(rebasing.peakTicks < LONG_LIMIT_TICKS, "$name: the rebasing side stayed inside a Long (${rebasing.peakTicks})") },
                    {
                        assertEquals(
                            0,
                            rebasing.floorLandings + rebasing.unobservedBelowZero,
                            "$name: the rebasing side landed no joiner at its floor and clamped no never-observed child below zero, so the row may not differ",
                        )
                    },
                    { assertEquals(null, firstDifference(rebasing.leaves, twin.leaves), "$name: the same lane at every step") },
                )
            }.toTypedArray(),
        )
    }

    /** The first step at which [a] and [b] lease different lanes, as `step: a against b`, or `null`. */
    /**
     * The positive control for the below-zero witness every twin row reads ([neverObservedBelowZero]):
     * it names a child exactly when the allocator's own first-sighting rule clamps it. a and b are
     * seated 20 ticks apart either side of a raised origin; a fresh allocator has observed neither.
     */
    @Test
    fun theBelowZeroWitnessNamesTheChildTheFirstSightingRuleClamps() {
        val root = GroupId("p")
        val (a, b) = listOf(edgeOf(root, GroupId("p/a")), edgeOf(root, GroupId("p/b")))
        val seated = shapedLedger(root, listOf(a, b))
            .piece(EntitlementLedger.of(gauges = mapOf(a.id to gaugeAt(Int128.of(160L), 0L), b.id to gaugeAt(Int128.of(480L), 0L))))
        // 20 units is 320 ticks on a weight-1 grid: a reads −160, b +160.
        val raised = seated.piece(checkNotNull(seated.raiseOrigin(root, Int128.of(20L))) { "the rig: the origin rose" }.delta)
        fun fresh() = FairShareAllocator(LOCAL, PolicyConfig(quantum = 1L))
        val watched = FairShareAllocator(LOCAL, PolicyConfig(quantum = 1L), AllocatorMemory(emptyMap(), mapOf(a.id to true, b.id to true)))
        val clamping = fresh()
        val named = neverObservedBelowZero(raised, clamping) { true }
        clamping.settle(root, raised) { Demand(targetOutstanding = 1L, maximumUsefulGrant = 1L) }
        assertAll(
            { assertEquals(listOf(a.id), named, "below zero, never observed and demanding: a, and only a") },
            { assertEquals(Int128.of(320L), clamping.memory.wakeOffsets[a.id], "and the allocator's own rule clamps a to b, 320 ticks up") },
            { assertEquals(null, clamping.memory.wakeOffsets[b.id], "and leaves b, which it does not name, alone") },
            { assertEquals(emptyList(), neverObservedBelowZero(seated, fresh()) { true }, "at or above zero: not the arm") },
            { assertEquals(emptyList(), neverObservedBelowZero(raised, fresh()) { false }, "not demanding: not the arm") },
            { assertEquals(emptyList(), neverObservedBelowZero(raised, watched) { true }, "observed: not the arm") },
        )
    }

    private fun firstDifference(a: List<String>, b: List<String>): String? {
        val at = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: return if (b.size > a.size) "step ${a.size}: nothing against ${b[a.size]}" else null
        return "step $at: ${a[at]} against ${b.getOrNull(at)}"
    }

    private companion object {
        /** The sweep's row length. */
        const val STEPS: Int = 20_000

        /** The sweep's long row: the heavy lane passes `2⁶³` ticks of service at about 52,000 steps. */
        const val LONG_STEPS: Int = 80_000

        /**
         * The fine rows. No heavy grid carries a position past `2⁶³` early, so the twin gets there on
         * service alone: about 3.1 · 10¹⁸ ticks at 20,000 steps and 9.23 · 10¹⁸ at 60,000.
         */
        const val FINE_STEPS: Int = 64_000
    }
}
