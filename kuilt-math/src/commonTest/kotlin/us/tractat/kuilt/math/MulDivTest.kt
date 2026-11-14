package us.tractat.kuilt.math

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MulDivTest {

    @Test
    fun roundsEachWayForEverySignCombination() = assertAll(
        { assertEquals(3L, mulDiv(7L, 1L, 2L, Rounding.FLOOR)) },
        { assertEquals(4L, mulDiv(7L, 1L, 2L, Rounding.CEILING)) },
        { assertEquals(-4L, mulDiv(-7L, 1L, 2L, Rounding.FLOOR)) },
        { assertEquals(-3L, mulDiv(-7L, 1L, 2L, Rounding.CEILING)) },
        { assertEquals(-4L, mulDiv(7L, 1L, -2L, Rounding.FLOOR)) },
        { assertEquals(-3L, mulDiv(7L, 1L, -2L, Rounding.CEILING)) },
        { assertEquals(3L, mulDiv(-7L, 1L, -2L, Rounding.FLOOR)) },
        { assertEquals(4L, mulDiv(-7L, 1L, -2L, Rounding.CEILING)) },
        { assertEquals(6L, mulDiv(4L, 3L, 2L, Rounding.CEILING)) },
        { assertEquals(0L, mulDiv(0L, Long.MIN_VALUE, -1L, Rounding.CEILING)) },
    )

    @Test
    fun onlyTheFinalQuotientHasToFit() = assertAll(
        { assertEquals(1L shl 61, mulDiv(1L shl 62, 4L, 8L, Rounding.FLOOR)) },
        { assertEquals(Long.MAX_VALUE, mulDiv(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Rounding.FLOOR)) },
        { assertEquals(Long.MIN_VALUE, mulDiv(Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Rounding.FLOOR)) },
        { assertEquals(Long.MIN_VALUE, mulDiv(Long.MIN_VALUE, 1L, 1L, Rounding.CEILING)) },
    )

    @Test
    fun aQuotientThatDoesNotFitIsNull() = assertAll(
        { assertNull(mulDiv(Long.MAX_VALUE, 2L, 1L, Rounding.FLOOR)) },
        { assertNull(mulDiv(Long.MIN_VALUE, 1L, -1L, Rounding.FLOOR)) },
        { assertNull(mulDiv(Long.MIN_VALUE, -1L, 1L, Rounding.CEILING)) },
        // 2⁶³ − 1 + ½ rounds up to 2⁶³, which does not fit; rounded down it does.
        { assertNull(Long.MAX_VALUE.timesWide(2L).plus(1L).divideOrNull(2L, Rounding.CEILING)) },
        { assertEquals(Long.MAX_VALUE, Long.MAX_VALUE.timesWide(2L).plus(1L).divideOrNull(2L, Rounding.FLOOR)) },
        // −2⁶³ − ½ rounds down to −2⁶³ − 1, which does not fit; rounded up it is −2⁶³.
        { assertNull(Long.MIN_VALUE.timesWide(2L).minus(1L).divideOrNull(2L, Rounding.FLOOR)) },
        { assertEquals(Long.MIN_VALUE, Long.MIN_VALUE.timesWide(2L).minus(1L).divideOrNull(2L, Rounding.CEILING)) },
        { assertNull(Int128.MAX_VALUE.divideOrNull(Long.MAX_VALUE, Rounding.FLOOR)) },
    )

    @Test
    fun remainderTakesTheDividendsSignLikeLongRem() = assertAll(
        { assertEquals(1L, Int128.of(7L) % 2L) },
        { assertEquals(-1L, Int128.of(-7L) % 2L) },
        { assertEquals(1L, Int128.of(7L) % -2L) },
        { assertEquals(0L, Int128.of(Long.MIN_VALUE) % Long.MIN_VALUE) },
        // 2⁶⁴ mod 3 = 1, and −2⁶⁴ mod 3 = −1.
        { assertEquals(1L, Int128.fromTwosComplement(1L, 0L) % 3L) },
        { assertEquals(-1L, (Int128.ZERO - Int128.fromTwosComplement(1L, 0L)) % 3L) },
        // MAX_VALUE·MAX_VALUE mod (MAX_VALUE − 1) = 1, since MAX_VALUE ≡ 1.
        { assertEquals(1L, Long.MAX_VALUE.timesWide(Long.MAX_VALUE) % (Long.MAX_VALUE - 1L)) },
    )

    @Test
    fun aZeroDivisorIsACallerBug() {
        assertFailsWith<IllegalArgumentException> { mulDiv(1L, 1L, 0L, Rounding.FLOOR) }
    }

    /**
     * The defining inequalities, over a seeded mix of corner and random values: a floor `q` has
     * `q·d ≤ a·b < (q + 1)·d` for a positive divisor, a ceiling is the floor or one above it, and
     * `null` comes back exactly when the true quotient leaves `Long`. The JVM oracle test checks the
     * values against `BigInteger`; this one runs on every target from one seed.
     */
    @Test
    fun floorAndCeilingBracketTheExactQuotient() {
        val random = Random(3231)
        var bracketed = 0
        var refused = 0
        repeat(20_000) {
            val a = random.nextCornerLong()
            val b = random.nextCornerLong()
            val d = random.nextCornerLong().let { if (it <= 0L) (it.negateOrNull() ?: Long.MAX_VALUE).coerceAtLeast(1L) else it }
            val product = a.timesWide(b)
            val floor = mulDiv(a, b, d, Rounding.FLOOR)
            val ceiling = mulDiv(a, b, d, Rounding.CEILING)
            if (floor == null) {
                refused++
                // A refused floor means the quotient is past Long on one side: q·d would not fit
                // below a·b with q = MIN, or the product is at least (MAX + 1)·d.
                assertTrue(product < Long.MIN_VALUE.timesWide(d) || product >= Long.MAX_VALUE.timesWide(d) + d, "$a * $b / $d")
            } else {
                bracketed++
                val low = floor.timesWide(d)
                assertTrue(low <= product && product < low + d, "floor($a * $b / $d) = $floor")
                val exact = low == product
                when {
                    exact -> assertEquals(floor, ceiling)
                    ceiling != null -> assertEquals(floor + 1L, ceiling)
                    else -> assertEquals(Long.MAX_VALUE, floor)
                }
            }
        }
        assertEquals(20_000, bracketed + refused)
        assertTrue(bracketed > 5_000 && refused > 100, "both arms ran: bracketed=$bracketed refused=$refused")
    }
}
