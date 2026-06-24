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
    fun defaultCapacityIsTheDocumentedBoundedSize() {
        assertEquals(256, DeliveryPolicy.DEFAULT_CAPACITY, "the bounded default — never UNLIMITED")
        assertEquals(256, DeliveryPolicy.Reliable.capacity)
    }
}
