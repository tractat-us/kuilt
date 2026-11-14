package us.tractat.kuilt.heddle

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Model-check the production policy against an **independent exact-rational oracle**
 * (design §15 Phase 3 acceptance: "model-check agreement with a slow exact-rational
 * oracle on small sibling sets").
 *
 * The oracle re-derives EEVDF selection from scratch using `java.math.BigInteger`
 * rationals — a genuinely different number type and code path from the production
 * `Long` positions and 128-bit cross-products, with no overflow ceiling and no shared
 * reduction logic. It models the policy as design §7 now states it (#3313): each edge's
 * position is whole ticks of its own service, `G = 16` per unit, a floor is read as
 * `⌈floor · w · G⌉`, and step 3 keeps `p ≤ ⌈V · w · G⌉`. The grid constant is restated here
 * rather than read from production, so a change to it reds this test too.
 * Over a seeded, randomized sequence of demand/spend/return perturbations across
 * several small sibling sets, the two must choose the *same* grant every round. Any
 * discrepancy in candidate filtering, weighted-mean eligibility, deadline ordering, or
 * the tie-break surfaces immediately.
 *
 * This is a JVM-only correctness check (it relies on `BigInteger`); cross-platform
 * *determinism* is pinned separately by the commonTest golden vector.
 */
class HeddlePolicyOracleTest {

    /** Rounds in which the oracle reached step 3 with a non-empty candidate set (see [oraclePick]). */
    private var eligibilityRounds = 0

    @Test
    fun agreesWithBigIntegerOracleOverRandomizedSchedules() {
        for (seed in 0L until 40L) {
            runOneSchedule(seed)
        }
        // Non-vacuity: the eligibility assertion inside the oracle must have actually run.
        assertTrue(eligibilityRounds > 1_000, "the sweep barely reached step 3: only $eligibilityRounds rounds")
    }

    private fun runOneSchedule(seed: Long) {
        val rng = Random(seed)
        val n = 2 + rng.nextInt(4) // 2..5 siblings

        class C(
            val id: String,
            val weight: Weight,
            val ivt: Long,
            var issued: Long = 0L,
            var returned: Long = 0L,
            var spent: Long = 0L,
            var demanding: Boolean = true,
        ) {
            val outstanding get() = issued - returned - spent
        }

        val children = List(n) { i ->
            C(('a' + i).toString(), Weight.of((rng.nextInt(5) + 1).toLong(), (rng.nextInt(3) + 1).toLong()), rng.nextInt(5).toLong())
        }
        val config = PolicyConfig(quantum = (rng.nextInt(6) + 3).toLong(), perChildOutstandingCap = 50L)

        repeat(150) {
            for (c in children) {
                if (rng.nextInt(6) == 0) c.demanding = !c.demanding
                if (c.outstanding > 0L) c.spent += rng.nextInt((c.outstanding + 1L).toInt().coerceAtLeast(1)).toLong()
                val committedUnspent = c.issued - c.returned - c.spent
                if (committedUnspent > 0L && rng.nextInt(8) == 0) {
                    c.returned += rng.nextInt((committedUnspent + 1L).toInt().coerceAtLeast(1)).toLong()
                }
            }
            val holdings = (rng.nextInt(20) + 1).toLong()
            val edges = children.map { c ->
                PolicyEdge(
                    AttachmentRecord(AttachmentId(c.id), GroupId("root"), GroupId(c.id), c.weight),
                    EdgeSummary(AttachmentId(c.id), c.issued, c.returned, c.spent),
                    if (c.demanding) Demand(targetOutstanding = 30L, maximumUsefulGrant = 15L) else Demand.NONE,
                    gauge = Gauge(Rational.of(c.ivt), folded = 0L),
                    baseIssued = c.issued,
                )
            }

            val actual = HeddlePolicy.pick(edges, config, holdings)
            val expected = oraclePick(edges, config, holdings)
            assertEquals(expected, actual, "seed=$seed round=$it edges=$edges holdings=$holdings")

            if (actual != null) {
                children.first { it.id == actual.attachment.value }.issued += actual.amount
            }
        }
    }

    // --- Independent BigInteger rational oracle ------------------------------------

    /** A rational over arbitrary-precision integers; denominator strictly positive. */
    private data class Rat(val n: BigInteger, val d: BigInteger) : Comparable<Rat> {
        operator fun plus(o: Rat) = Rat(n * o.d + o.n * d, d * o.d)
        override fun compareTo(other: Rat): Int = (n * other.d).compareTo(other.n * d)

        /** The least integer `>= this`. */
        fun ceil(): BigInteger {
            val (q, r) = n.divideAndRemainder(d)
            return if (r.signum() > 0) q + BigInteger.ONE else q
        }
        companion object {
            fun of(v: Long) = Rat(BigInteger.valueOf(v), BigInteger.ONE)
            fun of(nn: Long, dd: Long): Rat {
                val n = BigInteger.valueOf(nn)
                val d = BigInteger.valueOf(dd)
                return if (d.signum() < 0) Rat(n.negate(), d.negate()) else Rat(n, d)
            }
        }
    }

    private fun oraclePick(edges: List<PolicyEdge>, config: PolicyConfig, holdings: Long): Grant? {
        if (holdings <= 0L) return null

        data class Cand(val id: AttachmentId, val q: Long, val p: BigInteger, val weight: Weight)

        val g = BigInteger.valueOf(16L)

        val cands = edges.mapNotNull { e ->
            val outstanding = e.summary.outstanding
            val need = e.demand.targetOutstanding - outstanding
            if (need <= 0L) return@mapNotNull null
            val capRoom = if (config.perChildOutstandingCap == Long.MAX_VALUE) Long.MAX_VALUE else config.perChildOutstandingCap - outstanding
            val q = listOf(config.quantum, need, e.demand.maximumUsefulGrant, holdings, capRoom).min()
            if (q <= 0L) return@mapNotNull null
            val w = e.record.weight
            // p = ⌈floor · w · G⌉ + G · (baseIssued − folded) − G · returned, in ticks of the edge's
            // own service (issue #1752's read, on the #3313 grid); an absent gauge means the edge
            // reads from its own origin. Offset is ZERO in this scenario. Re-derived here from the
            // gauge's own fields rather than from any kuilt-heddle helper, so the oracle stays an
            // independent second implementation of the read.
            val floorTicks = e.gauge?.let { gauge ->
                Rat(
                    BigInteger.valueOf(gauge.floor.numerator) * BigInteger.valueOf(w.numerator) * g,
                    BigInteger.valueOf(gauge.floor.denominator) * BigInteger.valueOf(w.denominator),
                ).ceil() - g * BigInteger.valueOf(gauge.folded)
            } ?: BigInteger.ZERO
            val p = floorTicks + g * BigInteger.valueOf(e.baseIssued - e.summary.returned)
            Cand(e.record.id, q, p, w)
        }
        if (cands.isEmpty()) return null

        // V = Σ w·ev / Σ w, and w·ev = p / G, so V = Σ p / (G · Σ w).
        var psum = BigInteger.ZERO
        var wsum = Rat.of(0L)
        for (c in cands) {
            psum += c.p
            wsum = wsum + Rat.of(c.weight.numerator, c.weight.denominator)
        }
        val v = Rat(psum * wsum.d, g * wsum.n)
        fun evOf(c: Cand) = Rat(c.p * BigInteger.valueOf(c.weight.denominator), g * BigInteger.valueOf(c.weight.numerator))

        // §7.3 step 3's eligible set is never empty, confirmed here in **unbounded** arithmetic:
        // `v` is the weighted mean of these same candidates over strictly positive weights, so it
        // is never below their minimum. The production policy asserts the same thing; mirroring
        // its old `ifEmpty { minBy }` fallback here would have made this oracle blind to it (#1737).
        // Eligibility at grid resolution: p ≤ ⌈V · w · G⌉, V rounded the way a seat rounds it.
        eligibilityRounds++
        val eligible = cands.filter { c ->
            val ceiling = Rat(v.n * BigInteger.valueOf(c.weight.numerator) * g, v.d * BigInteger.valueOf(c.weight.denominator)).ceil()
            c.p <= ceiling
        }
        assertTrue(eligible.isNotEmpty(), "empty eligible set at v=$v over $cands")

        // deadline = ev + q * den / num ; tie-break by id
        val winner = eligible.minWith(
            compareBy<Cand> { c -> evOf(c) + Rat.of(c.q * c.weight.denominator, c.weight.numerator) }
                .thenBy { it.id },
        )
        return Grant(winner.id, winner.q)
    }
}
