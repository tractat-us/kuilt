package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BigInt], [ExactTime], [Int128.product] and [Rational.compareTo] against `java.math.BigInteger`,
 * an independent number type with no shared code.
 *
 * The operands are drawn to straddle the boundaries these types exist for: values near `±2⁶³`, near
 * a limb boundary (`2³²`), and small ones, so a carry, a borrow or a sign correction that is wrong
 * only at an edge shows up here rather than as a misordered pick.
 */
class ExactArithmeticOracleTest {

    private val random = Random(3231)

    private fun edgyLong(): Long = when (random.nextInt(6)) {
        0 -> Long.MAX_VALUE - random.nextInt(4)
        1 -> Long.MIN_VALUE + random.nextInt(4)
        2 -> (1L shl 32) + random.nextInt(-3, 4)
        3 -> -((1L shl 32) + random.nextInt(-3, 4))
        4 -> random.nextLong(-1000, 1000)
        else -> random.nextLong()
    }

    private fun big(v: Long) = BigInteger.valueOf(v)

    private fun BigInt.oracle(): BigInteger = BigInteger(toString())

    /** A [BigInt] built through arithmetic, so it can exceed 128 bits. */
    private fun wide(): Pair<BigInt, BigInteger> {
        var b = BigInt.of(edgyLong())
        var o = big(b.toLongOrNull() ?: 0L)
        repeat(random.nextInt(4)) {
            val v = edgyLong()
            if (random.nextBoolean()) {
                b *= BigInt.of(v)
                o *= big(v)
            } else {
                b += BigInt.of(v)
                o += big(v)
            }
        }
        return b to o
    }

    @Test
    fun bigIntAgreesWithBigInteger() {
        var checked = 0
        repeat(20_000) {
            val (a, oa) = wide()
            val (b, ob) = wide()
            assertEquals(oa, a.oracle(), "construction")
            assertEquals(oa + ob, (a + b).oracle(), "$oa + $ob")
            assertEquals(oa - ob, (a - b).oracle(), "$oa - $ob")
            assertEquals(oa * ob, (a * b).oracle(), "$oa * $ob")
            assertEquals(oa.compareTo(ob).coerceIn(-1, 1), a.compareTo(b).coerceIn(-1, 1), "$oa <=> $ob")
            assertEquals(oa.gcd(ob), a.gcd(b).oracle(), "gcd($oa, $ob)")
            assertEquals(oa.bitLength(), a.abs().bitLength(), "bitLength($oa)")
            val shift = random.nextInt(0, 130)
            assertEquals(oa.shiftLeft(shift), a.shl(shift).oracle(), "$oa << $shift")
            if (ob.signum() != 0) {
                val (q, r) = a.divRem(b)
                val expected = oa.divideAndRemainder(ob)
                assertEquals(expected[0], q.oracle(), "$oa / $ob")
                assertEquals(expected[1], r.oracle(), "$oa % $ob")
            }
            val fits = oa.bitLength() <= 63
            assertEquals(if (fits) oa.toLong() else null, a.toLongOrNull(), "toLongOrNull($oa)")
            checked++
        }
        assertEquals(20_000, checked)
    }

    @Test
    fun theFullProductAndRationalOrderAgreeWithBigInteger() {
        var checked = 0
        repeat(50_000) {
            val a = edgyLong()
            val b = edgyLong()
            val p = Int128.product(a, b)
            val expected = big(a) * big(b)
            val actual = BigInteger.valueOf(p.hi).shiftLeft(64) + (BigInteger.valueOf(p.lo) and BigInteger.ONE.shiftLeft(64) - BigInteger.ONE)
            assertEquals(expected, actual, "$a * $b")

            val x = Rational.of(edgyLong().coerceAtLeast(Long.MIN_VALUE + 1), edgyLong().let { if (it == 0L || it == Long.MIN_VALUE) 1L else it })
            val y = Rational.of(edgyLong().coerceAtLeast(Long.MIN_VALUE + 1), edgyLong().let { if (it == 0L || it == Long.MIN_VALUE) 1L else it })
            val order = (big(x.numerator) * big(y.denominator)).compareTo(big(y.numerator) * big(x.denominator))
            assertEquals(order.coerceIn(-1, 1), x.compareTo(y).coerceIn(-1, 1), "$x <=> $y")
            checked++
        }
        assertEquals(50_000, checked)
    }

    @Test
    fun narrowingIsExactWhenALongFormExistsAndRoundsUpOnlyWhenNot() {
        var exact = 0
        var rounded = 0
        repeat(20_000) {
            val n = edgyLong().coerceAtLeast(Long.MIN_VALUE + 1)
            val d = edgyLong().let { if (it == 0L || it == Long.MIN_VALUE) 7L else it }
            val m = random.nextLong(1, Long.MAX_VALUE)
            // n/d is representable; n/d · 1/m usually is not.
            val representable = ExactTime.of(Rational.of(n, d))
            assertEquals(Rational.of(n, d), representable.toRational(), "a representable value is returned unchanged")
            exact++

            val value = representable * ExactTime.of(Rational.of(1L, m))
            val narrowed = value.toRational()
            val num = BigInteger(value.numerator.toString())
            val den = BigInteger(value.denominator.toString())
            // narrowed ≥ value, and narrowed − value < 2^-k where 2^k is narrowed's denominator.
            val lhs = big(narrowed.numerator) * den
            val rhs = num * big(narrowed.denominator)
            assertTrue(lhs >= rhs, "narrowed $narrowed is below $value")
            assertTrue(lhs - rhs < den, "narrowed $narrowed is more than one grid step above $value")
            if (value.numerator.toLongOrNull() == null || value.denominator.toLongOrNull() == null) {
                assertEquals(0L, narrowed.denominator and (narrowed.denominator - 1), "a rounded value sits on a power-of-two grid")
                rounded++
            } else {
                assertEquals(Rational.of(num.toLong(), den.toLong()), narrowed)
            }
        }
        assertAll(
            { assertEquals(20_000, exact) },
            { assertTrue(rounded > 1_000, "the rounding arm barely ran: $rounded") },
        )
    }
}
