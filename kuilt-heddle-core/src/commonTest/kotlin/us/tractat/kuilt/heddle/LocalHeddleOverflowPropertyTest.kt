package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pick never throws at any reachable state, however the waiting set moves between picks.
 *
 * Each step draws a fresh waiting set — a random, non-empty subset of the lanes — acquires one
 * lease, and completes it at a random cost. A changing waiting set is what makes children wake,
 * and each wake clamps a child to a front that is a weighted mean over a *different* set than the
 * last one. Before the fix each such mean multiplied the denominators of the stored wake offsets,
 * so a pick overflowed `Long` within tens of steps; a constant waiting set never wakes anyone and
 * cannot reach the defect, so it is not an arm here.
 *
 * Two arms, because the two paths read the policy differently: the **order** path offers every
 * waiting lane as eligible, so the pick's eligibility filter and deadline order run over the whole
 * set; the **start** path offers one lane, as a caller does when the work has already been placed,
 * so the pick has one candidate while the wake step still sees every waiter.
 *
 * The weights are mixed on purpose: whole numbers far apart (60 against 1), and fractions whose
 * denominators are coprime with each other (3/7, 2/5, 5/3), since a weight's denominator is what
 * a fractional sum carries into the mean.
 *
 * The generator walks only [List]s it built itself, in declaration order, so the same seed takes
 * the same trajectory on every target.
 */
class LocalHeddleOverflowPropertyTest {

    @Test
    fun theOrderPathNeverOverflowsAsTheWaitingSetChanges() = assertNeverOverflows(seed = 7L, startPath = false)

    @Test
    fun theStartPathNeverOverflowsAsTheWaitingSetChanges() = assertNeverOverflows(seed = 11L, startPath = true)

    /**
     * #3231's trigger, which is magnitude rather than compounding: fractional weights `3/7 : 2/5`,
     * every lease at [LocalHeddle.MAX_RESERVATION] and charged in full, with both lanes always
     * waiting. Before the fix the pick threw at cycle 4,995, about `2⁵⁶` units of service under the
     * parent, and every later acquire there threw too. Twice that many cycles run here.
     */
    @Test
    fun fractionalWeightsPickPastTheOldServiceCeiling() {
        val topology = draft("ceiling") {
            lane("a", Weight.of(3, 7))
            lane("b", Weight.of(2, 5))
        }
        val engine = LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        val lanes = listOf(topology.groupId("a"), topology.groupId("b"))
        val picked = IntArray(lanes.size)
        repeat(CEILING_CYCLES) { cycle ->
            val outcome = try {
                engine.acquire(lanes.toSet(), lanes.toSet(), startedAt = cycle.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
            } catch (e: ArithmeticException) {
                throw AssertionError("cycle ${cycle + 1} threw $e", e)
            }
            check(outcome is AcquireOutcome.Acquired) { "cycle ${cycle + 1}: expected a lease, got $outcome" }
            picked[lanes.indexOf(outcome.lease.leaf)]++
            engine.complete(outcome.lease.id, actualCost = LocalHeddle.MAX_RESERVATION)
        }
        assertAll(
            { assertEquals(CEILING_CYCLES, picked.sum(), "every cycle made one pick") },
            { assertTrue(picked.all { it > CEILING_CYCLES / 3 }, "both lanes are served near 15 : 14: ${picked.toList()}") },
        )
    }

    private fun assertNeverOverflows(seed: Long, startPath: Boolean) {
        val topology = draft("fs") {
            LANES.forEach { (name, weight) -> lane(name, weight) }
            group("nested", Weight.of(2)) {
                NESTED.forEach { (name, weight) -> lane(name, weight) }
            }
        }
        val engine = LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        val leaves = LANES.map { topology.groupId(it.first) } + NESTED.map { topology.groupId("nested", it.first) }
        val random = Random(seed)

        var picks = 0
        var samplesPastLong = 0
        repeat(PICKS) { step ->
            val size = 1 + random.nextInt(leaves.size)
            val waiting = leaves.shuffled(random).take(size)
            val eligible = if (startPath) listOf(waiting[random.nextInt(waiting.size)]) else waiting
            val outcome = try {
                engine.acquire(waiting.toSet(), eligible.toSet(), startedAt = step.toLong(), reservation = 1L)
            } catch (e: ArithmeticException) {
                throw AssertionError("seed $seed, start path $startPath: pick ${step + 1} threw $e", e)
            }
            check(outcome is AcquireOutcome.Acquired) { "seed $seed, pick ${step + 1}: expected a lease, got $outcome" }
            engine.complete(outcome.lease.id, actualCost = 1L + random.nextInt(MAX_COST))
            picks++
            if (step % SAMPLE_EVERY == 0 && engine.snapshot().allocatorMemory.wakeOffsets.values.any(::isRounded)) {
                samplesPastLong++
            }
        }
        assertAll(
            { assertEquals(PICKS, picks, "every step made one pick") },
            { assertEquals(0, engine.snapshot().leases.size, "every lease was completed") },
            // The rig: a wake offset whose exact value had no Long form, so it was narrowed. Without
            // one the run never reached the state that overflowed, and its green would prove nothing.
            { assertTrue(samplesPastLong > 0, "no sampled step held a narrowed wake offset") },
        )
    }

    /** A wake offset [ExactTime.toRational] rounded: on a power-of-two grid finer than `2⁻³¹`. */
    private fun isRounded(offset: Rational): Boolean {
        val d = offset.denominator
        return d > (1L shl 31) && d and (d - 1) == 0L
    }

    private companion object {
        const val PICKS = 100_000
        const val SAMPLE_EVERY = 100
        const val CEILING_CYCLES = 10_000
        const val MAX_COST = 3_600

        val LANES = listOf(
            "a" to Weight.of(60),
            "b" to Weight.of(30),
            "c" to Weight.of(10),
            "d" to Weight.of(1),
            "e" to Weight.of(3, 7),
            "f" to Weight.of(2, 5),
        )
        val NESTED = listOf(
            "x" to Weight.of(5, 3),
            "y" to Weight.ONE,
            "z" to Weight.of(4),
        )
    }
}
