package us.tractat.kuilt.math

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every operation against `BigInteger`, the JVM's arbitrary-precision integer, as an independent
 * construction: nothing here shares code with what it checks. The common tests prove the algebra on
 * every target; this proves the values.
 */
class BigIntegerOracleTest {

    private val longMin = BigInteger.valueOf(Long.MIN_VALUE)
    private val longMax = BigInteger.valueOf(Long.MAX_VALUE)
    private val int128Min = BigInteger.ONE.shiftLeft(127).negate()
    private val int128Max = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)

    private fun Int128.big(): BigInteger = BigInteger(toString())

    private fun Long.big(): BigInteger = BigInteger.valueOf(this)

    private fun BigInteger.fitsLong(): Boolean = this >= longMin && this <= longMax

    @Test
    fun checkedLongMatchesExactArithmetic() {
        val random = Random(1)
        repeat(N) {
            val a = random.nextCornerLong()
            val b = random.nextCornerLong()
            assertEquals(a.big().add(b.big()).takeIf { it.fitsLong() }?.toLong(), a.plusOrNull(b), "$a + $b")
            assertEquals(a.big().subtract(b.big()).takeIf { it.fitsLong() }?.toLong(), a.minusOrNull(b), "$a - $b")
            assertEquals(a.big().multiply(b.big()).takeIf { it.fitsLong() }?.toLong(), a.timesOrNull(b), "$a * $b")
            assertEquals(a.big().negate().takeIf { it.fitsLong() }?.toLong(), a.negateOrNull(), "-$a")
        }
    }

    @Test
    fun int128MatchesExactArithmetic() {
        val random = Random(2)
        repeat(N) {
            val x = random.nextCornerLong().timesWide(random.nextCornerLong())
            val y = random.nextCornerLong().timesWide(random.nextCornerLong())
            val c = random.nextCornerLong()
            assertEquals(x.big(), BigInteger.valueOf(x.high).shiftLeft(64).add(BigInteger(java.lang.Long.toUnsignedString(x.low))), "words of $x")
            assertEquals(x.big().add(y.big()), (x + y).big(), "$x + $y")
            assertEquals(x.big().subtract(y.big()), (x - y).big(), "$x - $y")
            assertEquals(x.big().add(c.big()), (x + c).big(), "$x + $c")
            assertEquals(x.big().compareTo(y.big()), x.compareTo(y).coerceIn(-1, 1), "$x <=> $y")
            assertEquals(x.big().signum(), x.sign, "sign of $x")
            assertEquals(x.big().takeIf { it.fitsLong() }?.toLong(), x.toLongOrNull(), "$x narrowed")
        }
    }

    @Test
    fun int128SumsBeyondProductsMatchOrThrow() {
        val random = Random(3)
        var threw = 0
        repeat(N) {
            val x = Int128.fromTwosComplement(random.nextLong(), random.nextLong())
            val y = Int128.fromTwosComplement(random.nextLong(), random.nextLong())
            val exact = x.big().add(y.big())
            if (exact < int128Min || exact > int128Max) {
                threw++
                assertFailsWith<ArithmeticException>("$x + $y") { x + y }
            } else {
                assertEquals(exact, (x + y).big(), "$x + $y")
            }
        }
        // Two uniform 128-bit values overflow about a quarter of the time; both arms must have run.
        assertTrue(threw in N / 8..N / 2, "overflow arm ran $threw of $N times")
    }

    @Test
    fun mulDivMatchesExactArithmetic() {
        val random = Random(4)
        var refused = 0
        repeat(N) {
            val a = random.nextCornerLong()
            val b = random.nextCornerLong()
            val d = random.nextCornerLong().takeIf { it != 0L } ?: 1L
            for ((rounding, mode) in listOf(Rounding.FLOOR to RoundingMode.FLOOR, Rounding.CEILING to RoundingMode.CEILING)) {
                val exact = BigDecimal(a.big().multiply(b.big())).divide(BigDecimal(d.big()), 0, mode).toBigIntegerExact()
                val expected = exact.takeIf { it.fitsLong() }?.toLong()
                if (expected == null) refused++
                assertEquals(expected, mulDiv(a, b, d, rounding), "$a * $b / $d, $rounding")
            }
        }
        assertTrue(refused > N / 100, "the refusal arm ran only $refused times")
    }

    private companion object {
        const val N = 200_000
    }
}
