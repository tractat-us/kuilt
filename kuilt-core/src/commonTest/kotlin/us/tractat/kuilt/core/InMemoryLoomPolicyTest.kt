@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryLoomPolicyTest {
    @Test
    fun reliableLoomAppliesBackpressureInsteadOfUnboundedBuffering() = runTest {
        // A loom with capacity 1: a sender that outruns the receiver must SUSPEND, not pile up.
        val loom = InMemoryLoom(DeliveryPolicy(capacity = 1, overflow = Overflow.SUSPEND))
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("a"))
        b.peers.first { a.selfId in it }            // links established

        var sentBoth = false
        val job = backgroundScope.launch {
            a.broadcast(byteArrayOf(1))
            a.broadcast(byteArrayOf(2))             // must suspend: b's mailbox (cap 1) is full
            sentBoth = true
        }
        runCurrent()
        assertTrue(!sentBoth, "second broadcast must suspend under backpressure")

        assertEquals(byteArrayOf(1).toList(), b.incoming.first().toByteArray().toList()) // drain one
        runCurrent()
        assertTrue(sentBoth, "second broadcast completes once the receiver drains")
        job.cancel()
    }
}
