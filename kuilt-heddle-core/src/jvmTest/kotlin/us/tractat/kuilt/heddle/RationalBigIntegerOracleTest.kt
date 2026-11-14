package us.tractat.kuilt.heddle

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Rational]'s arithmetic against `BigInteger`, an independent construction that shares no code with
 * what it checks (#3347). For each operation the exact answer is reduced in `BigInteger`: when both
 * halves fit in `Long` the result must equal it, and otherwise the operation must throw.
 *
 * Half the operands share a large factor with the other operand, so the answer fits while the
 * straight-across products do not: the case #3347 is about. Each test counts those rows and asserts
 * it reached them, since a generator of coprime operands would pass on the old code too.
 */
class RationalBigIntegerOracleTest {

    private val longMin = BigInteger.valueOf(Long.MIN_VALUE)
    private val longMax = BigInteger.valueOf(Long.MAX_VALUE)

    private fun BigInteger.fitsLong(): Boolean = this >= longMin && this <= longMax

    private fun Long.big(): BigInteger = BigInteger.valueOf(this)

    /** The reduced `n/d` with a positive denominator, or `null` when either half leaves `Long`. */
    private fun exact(n: BigInteger, d: BigInteger): Rational? {
        val sign = d.signum()
        val g = n.gcd(d)
        val rn = n.divide(g).multiply(BigInteger.valueOf(sign.toLong()))
        val rd = d.divide(g).abs()
        return if (rn.fitsLong() && rd.fitsLong()) Rational.of(rn.toLong(), rd.toLong()) else null
    }

    @Test
    fun timesMatchesExactArithmetic() {
        val random = Random(3347)
        var fitsButCrossProductsDoNot = 0
        repeat(N) {
            val (x, y) = random.nextOperands()
            val expected = exact(x.numerator.big() * y.numerator.big(), x.denominator.big() * y.denominator.big())
            if (expected == null) {
                assertFailsWith<ArithmeticException>("$x * $y") { x * y }
            } else {
                assertEquals(expected, x * y, "$x * $y")
                if (straightAcrossOverflows(x.numerator, y.numerator, x.denominator, y.denominator)) fitsButCrossProductsDoNot++
            }
        }
        assertTrue(fitsButCrossProductsDoNot > N / 10, "the rig reached $fitsButCrossProductsDoNot rows that need cancelling")
    }

    @Test
    fun divMatchesExactArithmetic() {
        val random = Random(3348)
        var fitsButCrossProductsDoNot = 0
        repeat(N) {
            val (x, y) = random.nextOperands()
            if (y == Rational.ZERO) {
                assertFailsWith<ArithmeticException> { x / y }
                return@repeat
            }
            val expected = exact(x.numerator.big() * y.denominator.big(), x.denominator.big() * y.numerator.big())
            if (expected == null) {
                assertFailsWith<ArithmeticException>("$x / $y") { x / y }
            } else {
                assertEquals(expected, x / y, "$x / $y")
                if (straightAcrossOverflows(x.numerator, y.denominator, x.denominator, y.numerator)) fitsButCrossProductsDoNot++
            }
        }
        assertTrue(fitsButCrossProductsDoNot > N / 10, "the rig reached $fitsButCrossProductsDoNot rows that need cancelling")
    }

    private fun straightAcrossOverflows(a: Long, b: Long, c: Long, d: Long): Boolean =
        !(a.big() * b.big()).fitsLong() || !(c.big() * d.big()).fitsLong()

    /**
     * Two rationals. Half the time they are built as `(p·k)/(q·m)` and `(r·m)/(s·k)` for a large
     * shared `k` and `m`, so the answer of a product cancels both and fits; a quotient's answer
     * cancels when the second is inverted, so the pair is flipped for it half of those times.
     */
    private fun Random.nextOperands(): Pair<Rational, Rational> {
        if (nextBoolean()) return nextRational() to nextRational()
        val k = nextLong(1L, 1L shl 40)
        val m = nextLong(1L, 1L shl 40)
        val small = { nextLong(-(1L shl 20), 1L shl 20) }
        val positive = { nextLong(1L, 1L shl 20) }
        val x = Rational.of(small() * k, positive() * m)
        val y = if (nextBoolean()) Rational.of(small() * m, positive() * k) else Rational.of(positive() * k, small().takeIf { it != 0L }?.times(m) ?: m)
        return x to y
    }

    private fun Random.nextRational(): Rational {
        val n = nextCorner()
        val d = nextCorner().takeIf { it != 0L && it != Long.MIN_VALUE } ?: 1L
        return if (n == Long.MIN_VALUE && d < 0L) Rational.of(n, 1L) else Rational.of(n, d)
    }

    private fun Random.nextCorner(): Long = if (nextBoolean()) {
        CORNERS[nextInt(CORNERS.size)]
    } else {
        nextLong() shr nextInt(64)
    }

    private companion object {
        const val N = 100_000
        val CORNERS = longArrayOf(
            0L, 1L, -1L, 2L, -2L, 3L, 7L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1L,
            Long.MIN_VALUE + 1L, 1L shl 31, 1L shl 32, (1L shl 32) - 1L, 1L shl 62, (1L shl 62) + 1L,
        )
    }
}
