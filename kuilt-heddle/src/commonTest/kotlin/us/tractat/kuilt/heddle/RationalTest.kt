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
