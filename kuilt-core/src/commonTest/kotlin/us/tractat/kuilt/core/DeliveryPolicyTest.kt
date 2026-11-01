package us.tractat.kuilt.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    /** `Int.MAX_VALUE` is `Channel.UNLIMITED`, so a policy holding it would build a channel with no bound. */
    @Test
    fun theChannelUnlimitedSentinelIsRefusedAtEveryOverflow() {
        assertAll(
            { assertEquals(Int.MAX_VALUE, Channel.UNLIMITED, "the value this test refuses must be the sentinel") },
            *Overflow.entries.map { overflow ->
                { assertFailsWith<IllegalArgumentException>("$overflow") { DeliveryPolicy(Int.MAX_VALUE, overflow) }; Unit }
            }.toTypedArray(),
            { assertFailsWith<IllegalArgumentException>("copy") { DeliveryPolicy.Reliable.copy(capacity = Int.MAX_VALUE) }; Unit },
        )
    }

    @Test
    fun aCapacityBelowOneIsStillRefused() {
        assertFailsWith<IllegalArgumentException> { DeliveryPolicy(capacity = 0) }
    }

    /**
     * The largest capacity left builds a working [Spool] at every overflow.
     *
     * **What this cannot detect: a missing bound.** Showing one takes 2^31 - 1 buffered frames, and
     * [Spool] exposes no capacity. That half rests on the channel factory, which special-cases only
     * the exact sentinel and documents any positive capacity below it as a buffer of that size.
     */
    @Test
    fun theLargestAllowedCapacityBuildsASpoolThatDelivers() = runTest {
        for (overflow in Overflow.entries) {
            val spool = Spool<Int>(DeliveryPolicy(Int.MAX_VALUE - 1, overflow))
            spool.deliver(7)
            assertEquals(7, spool.incoming.first(), "$overflow")
        }
    }
}
