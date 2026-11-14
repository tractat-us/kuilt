package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WideSumTest {

    @Test
    fun carriesAndBorrowsAcrossTheLowWord() {
        val pastMax = WideSum().apply {
            add(Long.MAX_VALUE)
            add(Long.MAX_VALUE)
            add(2L)
        }.toInt128()
        val belowMin = WideSum().apply {
            add(Long.MIN_VALUE)
            sub(1L)
        }.toInt128()
        assertAll(
            // 2·(2⁶³ − 1) + 2 = 2⁶⁴: one carry into the high word, a zero low word.
            { assertEquals(Int128.fromTwosComplement(1L, 0L), pastMax) },
            { assertEquals(Int128.fromTwosComplement(-1L, Long.MAX_VALUE), belowMin) },
            { assertEquals(Int128.ZERO, pastMax - Int128.fromTwosComplement(1L, 0L)) },
            { assertEquals(Int128.of(-1L), Int128.ZERO - 1L) },
            { assertEquals(Int128.of(Long.MIN_VALUE), belowMin + 1L) },
        )
    }

    @Test
    fun orderDoesNotDecideWhetherItFits() {
        // Folded through checkedAdd, the first order throws on a partial sum and the second does not.
        val terms = listOf(Long.MAX_VALUE, 5L, -10L)
        val forward = WideSum().apply { terms.forEach(::add) }
        val backward = WideSum().apply { terms.asReversed().forEach(::add) }
        assertAll(
            { assertFailsWith<ArithmeticException> { terms.fold(0L, ::checkedAdd) } },
            { assertEquals(Long.MAX_VALUE - 5L, forward.toLongExact()) },
            { assertEquals(Long.MAX_VALUE - 5L, backward.toLongExact()) },
        )
    }

    @Test
    fun narrowingThrowsRatherThanWraps() {
        assertAll(
            { assertFailsWith<ArithmeticException> { (Int128.of(Long.MAX_VALUE) + 1L).toLongExact() } },
            { assertFailsWith<ArithmeticException> { (Int128.of(Long.MIN_VALUE) - 1L).toLongExact() } },
            { assertEquals(Long.MAX_VALUE, Int128.of(Long.MAX_VALUE).toLongExact()) },
            { assertEquals(Long.MIN_VALUE, Int128.of(Long.MIN_VALUE).toLongExact()) },
            { assertEquals(-1L, Int128.of(-1L).toLongExact()) },
        )
    }
}
