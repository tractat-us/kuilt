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
 * where `T` is their committed total. Draws stay inside what the ledger can hold (see
 * `BoundMetricsTest`): an edge carrying service has a weight below 1,000 and `c·b ≤ 2⁵⁸`, with that
 * cap itself drawn often. Each arm counts the draws where the old arithmetic, the reduced share times
 * `T` as a `Long` rational, would have overflowed, so neither can pass on draws that never reached it.
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
     * A weight near `Long.MAX_VALUE` (#3347) on a demanding child nobody has served yet: no edge can
     * carry service at that weight, but an unseated one holds it. One child's weight is `a/b` with
     * `a` past `MAX / b`; the rest are whole numbers small enough that the weight sum
     * `(a + b·Σc)/b` still fits, and they carry the service.
     */
    @Test
    fun observedDeviationMatchesExactArithmeticWithAnUnservedWeightNearLongMax() {
        val random = Random(3347)
        var overflowedBefore = 0
        repeat(N) {
            val children = randomChildrenWithAnUnservedHeavyWeight(random)
            if (oldProductOverflows(children)) overflowedBefore++
            assertEquals(oracle(children), observedDeviationOf(children), "children $children")
        }
        // The rig: most draws are ones the old `share × T` threw on.
        assertTrue(overflowedBefore > N / 2, "only $overflowedBefore of $N draws overflowed the old product")
    }

    private fun randomChildren(random: Random): List<Child> {
        val n = random.nextInt(2, 4)
        val weights = List(n) { Weight.of(random.nextLong(1L, 1_000L), random.nextLong(1L, 1_000L)) }
        val committed = weights.map { committedUpToTheCap(random, it) }
        val demanding = List(n) { random.nextInt(4) != 0 }.let { d -> if (d.none { it }) d.mapIndexed { i, x -> x || i == 0 } else d }
        return List(n) { Child(weights[it], committed[it], demanding[it]) }
    }

    /** One weight `a/b` with `a + b·Σc ≤ MAX`, so the sum fits, and `a > MAX / b`, held by a demanding child with no service. */
    private fun randomChildrenWithAnUnservedHeavyWeight(random: Random): List<Child> {
        val n = random.nextInt(2, 4)
        val light = List(n - 1) { random.nextLong(1L, 1_000L) }
        val b = random.nextLong(2L, 1_000L)
        val room = Long.MAX_VALUE - b * light.sum()
        var a: Long
        do a = random.nextLong(Long.MAX_VALUE / b + 1, room + 1) while (gcd(a, b) != 1L)
        val heavyAt = random.nextInt(n)
        return List(n) { i ->
            when {
                i == heavyAt -> Child(Weight.of(a, b), 0L)
                else -> {
                    val w = Weight.of(light[if (i < heavyAt) i else i - 1])
                    Child(w, committedUpToTheCap(random, w), demanding = random.nextInt(4) != 0)
                }
            }
        }
    }

    /** Up to `2⁵⁸ / b`, the cap itself one draw in four. */
    private fun committedUpToTheCap(random: Random, w: Weight): Long {
        val cap = CAP / w.denominator
        return if (random.nextInt(4) == 0) cap else random.nextLong(0L, cap + 1)
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

    /**
     * Whether the old `share × T` left `Long` for some demanding child: with the share reduced to
     * `p/q`, the product cancels `gcd(T, q)` before it multiplies, so it overflows when
     * `p · T / gcd(T, q)` does.
     */
    private fun oldProductOverflows(children: List<Child>): Boolean {
        val (_, scaled, sum, total) = scaledWeights(children)
        if (total.signum() == 0) return false
        return scaled.any { w ->
            val g = w.gcd(sum)
            val p = w / g
            val q = sum / g
            p * (total / total.gcd(q)) > Long.MAX_VALUE.big()
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

        /** The rows' margin: `c·b ≤ 2⁵⁸` on every edge that carries service. */
        const val CAP = 1L shl 58
    }
}
