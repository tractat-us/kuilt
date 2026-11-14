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

    private fun randomChildren(random: Random): List<Child> {
        val n = random.nextInt(2, 4)
        val weights = List(n) { Weight.of(random.nextLong(1L, 1_000L), random.nextLong(1L, 1_000L)) }
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
