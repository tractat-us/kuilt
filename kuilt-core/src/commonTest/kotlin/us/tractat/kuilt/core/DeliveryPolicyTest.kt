package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryPolicyTest {
    @Test
    fun presetsSelectTheExpectedOverflow() {
        assertEquals(Overflow.SUSPEND, DeliveryPolicy.Reliable.overflow)
        assertEquals(Overflow.DROP_OLDEST, DeliveryPolicy.Lossy.overflow)
        assertEquals(Overflow.FAIL, DeliveryPolicy.Strict.overflow)
    }

    @Test
    fun defaultCapacityIsBoundedAndPositive() {
        assertEquals(true, DeliveryPolicy.Reliable.capacity in 1..1_000_000)
    }
}
