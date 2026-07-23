package us.tractat.kuilt.heddle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckedMathTest {

    @Test
    fun addComputesNormalSums() {
        assertEquals(7L, checkedAdd(3L, 4L))
        assertEquals(-1L, checkedAdd(2L, -3L))
        assertEquals(Long.MAX_VALUE, checkedAdd(Long.MAX_VALUE - 1L, 1L))
    }

    @Test
    fun addThrowsOnOverflow() {
        assertFailsWith<ArithmeticException> { checkedAdd(Long.MAX_VALUE, 1L) }
        assertFailsWith<ArithmeticException> { checkedAdd(Long.MIN_VALUE, -1L) }
    }

    @Test
    fun mulComputesNormalProducts() {
        assertEquals(12L, checkedMul(3L, 4L))
        assertEquals(0L, checkedMul(0L, Long.MAX_VALUE))
        assertEquals(-6L, checkedMul(-2L, 3L))
        assertEquals(Long.MAX_VALUE, checkedMul(Long.MAX_VALUE, 1L))
    }

    @Test
    fun mulThrowsOnOverflow() {
        assertFailsWith<ArithmeticException> { checkedMul(Long.MAX_VALUE, 2L) }
        assertFailsWith<ArithmeticException> { checkedMul(Long.MIN_VALUE, -1L) }
        assertFailsWith<ArithmeticException> { checkedMul(Long.MAX_VALUE / 2L + 1L, 3L) }
    }
}
