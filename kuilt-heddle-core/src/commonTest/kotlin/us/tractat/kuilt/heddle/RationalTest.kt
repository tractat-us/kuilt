package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RationalTest {

    @Test
    fun reducesToLowestTermsAndNormalizesSign() {
        assertAll(
            { assertEquals(Rational.of(1, 2), Rational.of(2, 4)) },
            { assertEquals(Rational.of(1, 2), Rational.of(-1, -2)) },
            { assertEquals(Rational.of(-1, 2), Rational.of(1, -2)) },
            { assertEquals(Rational.ZERO, Rational.of(0, 5)) },
            { assertEquals(Rational.ONE, Rational.of(7, 7)) },
        )
    }

    @Test
    fun arithmeticIsExact() {
        assertAll(
            { assertEquals(Rational.of(5, 6), Rational.of(1, 2) + Rational.of(1, 3)) },
            { assertEquals(Rational.of(1, 6), Rational.of(1, 2) - Rational.of(1, 3)) },
            { assertEquals(Rational.of(1, 6), Rational.of(1, 2) * Rational.of(1, 3)) },
            { assertEquals(Rational.of(3, 2), Rational.of(1, 2) / Rational.of(1, 3)) },
        )
    }

    @Test
    fun ordersByExactCrossMultiplication() {
        assertAll(
            { assertTrue(Rational.of(1, 3) < Rational.of(1, 2)) },
            { assertTrue(Rational.of(2, 3) > Rational.of(1, 2)) },
            { assertTrue(Rational.of(-1, 2) < Rational.ZERO) },
            { assertEquals(0, Rational.of(2, 4).compareTo(Rational.of(1, 2))) },
        )
    }

    /**
     * The cross-products of an order are formed in 128 bits (#3313), so two rationals whose
     * products leave `Long` still order exactly. Before, the compare threw, and `Gauge.join`, which
     * orders floors, threw with it.
     */
    @Test
    fun ordersExactlyWhereTheCrossProductsLeaveLong() {
        val a = Rational.of(Long.MAX_VALUE / 3, 7)
        val b = Rational.of(Long.MAX_VALUE / 5, 11)
        val c = Rational.of(Long.MAX_VALUE - 2, Long.MAX_VALUE - 1)
        val d = Rational.of(Long.MAX_VALUE - 1, Long.MAX_VALUE)
        assertAll(
            { assertTrue(a > b) },
            { assertTrue(b < a) },
            // (M−2)/(M−1) < (M−1)/M: both are just under one, and only an exact compare sees which.
            { assertTrue(c < d) },
            { assertEquals(Rational.max(c, d), d) },
            { assertEquals(Gauge(d, 0L), Gauge(c, 0L).join(Gauge(d, 0L))) },
        )
    }

    /**
     * A sum or difference whose answer fits is returned, even when the textbook formula's
     * intermediates do not (#3313). `plus` and `minus` divide out the denominators' common factor
     * before multiplying and form the numerator in 128 bits.
     */
    @Test
    fun aSumThatFitsIsReturnedWhateverItsIntermediates() {
        val twoTo32 = 1L shl 32
        val big = (1L shl 62) + 1L // odd, so (big)/2^40 is in lowest terms
        assertAll(
            // 1/2³² + 3/2³² = 1/2³⁰. Forming d1·d2 = 2⁶⁴ first used to throw.
            { assertEquals(Rational.of(1, 1L shl 30), Rational.of(1, twoTo32) + Rational.of(3, twoTo32)) },
            { assertEquals(Rational.of(1, 1L shl 31), Rational.of(3, twoTo32) - Rational.of(1, twoTo32)) },
            // The numerators sum to 2⁶³ + 2, past Long, but the answer reduces by 2.
            { assertEquals(Rational.of(big, 1L shl 39), Rational.of(big, 1L shl 40) + Rational.of(big, 1L shl 40)) },
            { assertEquals(Rational.ZERO, Rational.of(big, 1L shl 40) - Rational.of(big, 1L shl 40)) },
            { assertEquals(Rational.of(-1, 1L shl 30), Rational.of(-3, twoTo32) + Rational.of(-1, twoTo32)) },
            // Coprime denominators still go through the general path.
            { assertEquals(Rational.of(5, 6), Rational.of(1, 2) + Rational.of(1, 3)) },
        )
    }

    /** The answer itself not fitting is still a throw: `plus` and `minus` keep their contract. */
    @Test
    fun aSumThatDoesNotFitStillThrows() {
        assertAll(
            { assertFailsWith<ArithmeticException> { Rational.of(Long.MAX_VALUE) + Rational.ONE } },
            { assertFailsWith<ArithmeticException> { Rational.of(Long.MIN_VALUE) - Rational.ONE } },
            { assertFailsWith<ArithmeticException> { Rational.of(1, Long.MAX_VALUE) + Rational.of(1, Long.MAX_VALUE - 1) } },
        )
    }

    @Test
    fun maxPicksLarger() {
        assertEquals(Rational.of(1, 2), Rational.max(Rational.of(1, 3), Rational.of(1, 2)))
        assertEquals(Rational.ZERO, Rational.max(Rational.of(-1, 2), Rational.ZERO))
    }

    /**
     * The exact ceiling — [Rational.ceil]. Note the contrast with Kotlin's `Long` division,
     * which truncates toward zero: `7/2` truncates to `3` but ceils to `4`. It has no
     * production caller since the seat moved into [Gauge.floor], which is a [Rational] and
     * needs no rounding at all (#1752); the direction is pinned here because it is the part a
     * future `Long` virtual-time field would get wrong.
     */
    @Test
    fun ceilRoundsUpToTheLeastEnclosingWhole() {
        assertAll(
            { assertEquals(4L, Rational.of(7, 2).ceil()) },
            { assertEquals(11L, Rational.of(109, 10).ceil()) },
            { assertEquals(1L, Rational.of(1, 1_000_000).ceil()) },
            // Whole numbers are already their own ceiling.
            { assertEquals(3L, Rational.of(6, 2).ceil()) },
            { assertEquals(0L, Rational.ZERO.ceil()) },
            { assertEquals(1L, Rational.ONE.ceil()) },
            { assertEquals(Long.MAX_VALUE, Rational.of(Long.MAX_VALUE).ceil()) },
            // Negatives ceil toward zero, not away from it.
            { assertEquals(-3L, Rational.of(-7, 2).ceil()) },
            { assertEquals(0L, Rational.of(-1, 2).ceil()) },
            { assertEquals(Long.MIN_VALUE, Rational.of(Long.MIN_VALUE).ceil()) },
        )
    }

    @Test
    fun overflowThrowsRatherThanWraps() {
        assertAll(
            { assertFailsWith<ArithmeticException> { Rational.of(Long.MAX_VALUE, 1) + Rational.of(1, 1) } },
            { assertFailsWith<ArithmeticException> { Rational.of(Long.MAX_VALUE, 2) * Rational.of(3, 1) } },
            { assertFailsWith<ArithmeticException> { Rational.of(1, 0) } },
            { assertFailsWith<ArithmeticException> { Rational.of(1, 1) / Rational.ZERO } },
        )
    }
}
