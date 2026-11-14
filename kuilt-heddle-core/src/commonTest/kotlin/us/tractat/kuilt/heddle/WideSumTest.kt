package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.WideSum
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What this module builds on `:kuilt-math`'s [WideSum]: a sum that stays exact whatever the order
 * of its terms, then narrows to a `Long` with a throw. The accumulator's own arithmetic, carries and
 * refusals included, is tested in `:kuilt-math`.
 */
class WideSumTest {

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
