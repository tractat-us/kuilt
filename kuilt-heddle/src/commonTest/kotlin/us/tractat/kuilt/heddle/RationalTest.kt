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

    @Test
    fun maxPicksLarger() {
        assertEquals(Rational.of(1, 2), Rational.max(Rational.of(1, 3), Rational.of(1, 2)))
        assertEquals(Rational.ZERO, Rational.max(Rational.of(-1, 2), Rational.ZERO))
    }

    /**
     * The exact ceiling — the rounding rule behind
     * [AttachmentRecord.neutralInitialVirtualTime]. Note the contrast with Kotlin's `Long`
     * division, which truncates toward zero: `7/2` truncates to `3` but ceils to `4`.
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
