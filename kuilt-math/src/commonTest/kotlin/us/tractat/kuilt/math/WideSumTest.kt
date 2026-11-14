package us.tractat.kuilt.math

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
            { assertEquals(Int128.of(Long.MIN_VALUE), belowMin + 1L) },
        )
    }

    @Test
    fun orderDoesNotDecideWhetherItFits() {
        // Folded through plusOrNull, the first order refuses on a partial sum and the second does not.
        val terms = listOf(Long.MAX_VALUE, 5L, -10L)
        val forward = WideSum().apply { terms.forEach { add(it) } }
        val backward = WideSum().apply { terms.asReversed().forEach { add(it) } }
        assertAll(
            { assertNull(terms.fold<Long, Long?>(0L) { sum, t -> sum?.plusOrNull(t) }) },
            { assertEquals(Long.MAX_VALUE - 5L, terms.asReversed().fold<Long, Long?>(0L) { sum, t -> sum?.plusOrNull(t) }) },
            { assertEquals(Long.MAX_VALUE - 5L, forward.toInt128().toLongOrNull()) },
            { assertEquals(Long.MAX_VALUE - 5L, backward.toInt128().toLongOrNull()) },
        )
    }

    /**
     * The widest product is 2¹²⁶, and two of them make 2¹²⁷, one past the largest `Int128` (#3366).
     * A sum of products can leave 128 bits on real data, so this must refuse, not wrap to −2¹²⁷.
     */
    @Test
    fun twoWidestProductsRefuseRatherThanWrap() {
        val widest = Long.MIN_VALUE.timesWide(Long.MIN_VALUE)
        val sum = WideSum().apply { add(widest) }
        assertFailsWith<ArithmeticException> { sum.add(widest) }
        // −2¹²⁶ − 2¹²⁶ = −2¹²⁷ is the smallest Int128, so it fits; one more does not.
        val floor = WideSum().apply {
            sub(widest)
            sub(widest)
        }
        val reached = floor.toInt128()
        assertFailsWith<ArithmeticException> { floor.sub(1L) }
        assertAll(
            { assertEquals(widest, sum.toInt128(), "a refused add leaves the sum as it was") },
            { assertEquals(Int128.MIN_VALUE, reached) },
            { assertEquals(Int128.MIN_VALUE, floor.toInt128(), "a refused sub leaves the sum as it was") },
        )
    }
}
