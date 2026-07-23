package us.tractat.kuilt.heddle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServiceUnitsTest {

    @Test
    fun rejectsNegativeAmounts() {
        assertFailsWith<IllegalArgumentException> { ServiceUnits(-1L) }
    }

    @Test
    fun addsWithOverflowChecking() {
        assertEquals(ServiceUnits(7L), ServiceUnits(3L) + ServiceUnits(4L))
        assertEquals(ServiceUnits.ZERO, ServiceUnits.ZERO + ServiceUnits.ZERO)
        assertFailsWith<ArithmeticException> { ServiceUnits(Long.MAX_VALUE) + ServiceUnits(1L) }
    }

    @Test
    fun ordersByValue() {
        assertTrue(ServiceUnits(3L) < ServiceUnits(4L))
        assertEquals(0, ServiceUnits(5L).compareTo(ServiceUnits(5L)))
    }
}
