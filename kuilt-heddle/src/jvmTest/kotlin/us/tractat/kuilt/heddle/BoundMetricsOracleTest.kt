package us.tractat.kuilt.heddle

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BoundMetrics.observedDeviation] against `BigInteger`, as an independent construction (#3319).
 *
 * The oracle never reduces a fraction and never forms a share: it scales every weight `aᵢ/bᵢ` to the
 * integer `Wᵢ = aᵢ · Πb / bᵢ`, then takes `⌈|Wᵢ · T − cᵢ · ΣW| / ΣW⌉` over the demanding children,
 * where `T` is their committed total. Committed service is drawn up to the largest each edge can
 * reach — the gauge `delegate` writes forms `committed · bᵢ`, which has to fit, and the root has to
 * fund the sum — with the cap itself drawn often. The rig counts the draws where the old arithmetic,
 * the reduced share's numerator times `T` as a `Long`, would have overflowed.
 */
class BoundMetricsOracleTest {

    @Test
    fun observedDeviationMatchesExactArithmetic() {
        val random = Random(3319)
        var overflowedBefore = 0
        repeat(N) {
            val children = randomChildren(random)
            if (oldProductOverflows(children)) overflowedBefore++
            assertEquals(oracle(children), observedDeviationOf(children), "children $children")
        }
        // The rig: most draws are ones the old `share × T` threw on.
        assertTrue(overflowedBefore > N / 2, "only $overflowedBefore of $N draws overflowed the old product")
    }

    /**
     * Weights near `Long.MAX_VALUE` (#3347): one child's weight is `a/b` with `a` past `MAX / b`, so
     * the straight-across `Rational.div` that forms its share overflowed. The rest are whole numbers
     * small enough that the weight sum `(a + b·Σc)/b` still fits. Two overflows remain real ones and
     * are documented on `observedDeviation`: the weight sum itself, and a reduced share `w_i/W`
     * whose parts both pass `Long` (pinned in `BoundMetricsTest`). The rig counts the draws where some demanding child's share overflowed
     * the old quotient, so the arm cannot pass on draws that never formed one.
     */
    @Test
    fun observedDeviationMatchesExactArithmeticAtWeightsNearLongMax() {
        val random = Random(3347)
        var oldQuotientThrew = 0
        repeat(N) {
            val children = randomChildrenWithAHeavyWeight(random)
            if (oldQuotientOverflows(children)) oldQuotientThrew++
            assertEquals(oracle(children), observedDeviationOf(children), "children $children")
        }
        // The rig: most draws form a share the old quotient threw on.
        assertTrue(oldQuotientThrew > N / 2, "only $oldQuotientThrew of $N draws formed a share the old quotient threw on")
    }

    private fun randomChildren(random: Random): List<Child> {
        val n = random.nextInt(2, 4)
        val weights = List(n) { Weight.of(random.nextLong(1L, 1_000L), random.nextLong(1L, 1_000L)) }
        return withCommitted(random, weights)
    }

    /** One weight `a/b` with `a + b·Σc ≤ MAX`, so the sum fits, and `a > MAX / b`, so `a · b` does not. */
    private fun randomChildrenWithAHeavyWeight(random: Random): List<Child> {
        val n = random.nextInt(2, 4)
        val light = List(n - 1) { random.nextLong(1L, 1_000L) }
        val b = random.nextLong(2L, 1_000L)
        val room = Long.MAX_VALUE - b * light.sum()
        var a: Long
        do a = random.nextLong(Long.MAX_VALUE / b + 1, room + 1) while (gcd(a, b) != 1L)
        val heavyAt = random.nextInt(n)
        val weights = List(n) { i ->
            when {
                i == heavyAt -> Weight.of(a, b)
                i < heavyAt -> Weight.of(light[i])
                else -> Weight.of(light[i - 1])
            }
        }
        return withCommitted(random, weights)
    }

    private fun withCommitted(random: Random, weights: List<Weight>): List<Child> {
        val n = weights.size
        // Each edge's gauge forms `committed · b`, and the root must fund the sum, so cap each child.
        val committed = weights.map { w ->
            val cap = Long.MAX_VALUE / n / w.denominator
            if (random.nextInt(4) == 0) cap else random.nextLong(0L, cap + 1)
        }
        val demanding = List(n) { random.nextInt(4) != 0 }.let { d -> if (d.none { it }) d.mapIndexed { i, x -> x || i == 0 } else d }
        return List(n) { Child(weights[it], committed[it], demanding[it]) }
    }

    private fun oracle(children: List<Child>): Long {
        val (live, scaled, sum, total) = scaledWeights(children)
        if (total.signum() == 0) return 0L
        return live.indices.maxOf { i ->
            val gap = (scaled[i] * total - live[i].committed.big() * sum).abs()
            val (q, r) = gap.divideAndRemainder(sum)
            (if (r.signum() == 0) q else q + BigInteger.ONE).longValueExact()
        }
    }

    /** Whether some demanding child's reduced share numerator times the committed total passes `Long`. */
    private fun oldProductOverflows(children: List<Child>): Boolean {
        val (_, scaled, sum, total) = scaledWeights(children)
        return scaled.any { w -> (w / w.gcd(sum)) * total > Long.MAX_VALUE.big() }
    }

    /** Whether forming some demanding child's share `wᵢ / W` by multiplying straight across leaves `Long`. */
    private fun oldQuotientOverflows(children: List<Child>): Boolean {
        val live = children.filter { it.demanding }
        val sum = live.fold(Rational.ZERO) { acc, c -> acc + Rational.of(c.weight.numerator, c.weight.denominator) }
        val max = Long.MAX_VALUE.big()
        return live.any { c ->
            c.weight.numerator.big() * sum.denominator.big() > max || c.weight.denominator.big() * sum.numerator.big() > max
        }
    }

    private tailrec fun gcd(x: Long, y: Long): Long = if (y == 0L) x else gcd(y, x % y)

    private data class Scaled(val live: List<Child>, val scaled: List<BigInteger>, val sum: BigInteger, val total: BigInteger)

    private fun scaledWeights(children: List<Child>): Scaled {
        val live = children.filter { it.demanding }
        val scale = live.fold(BigInteger.ONE) { acc, c -> acc * c.weight.denominator.big() }
        val scaled = live.map { it.weight.numerator.big() * scale / it.weight.denominator.big() }
        val total = live.fold(BigInteger.ZERO) { acc, c -> acc + c.committed.big() }
        return Scaled(live, scaled, scaled.fold(BigInteger.ZERO, BigInteger::add), total)
    }

    private fun Long.big(): BigInteger = BigInteger.valueOf(this)

    private companion object {
        const val N = 2_000
    }
}
