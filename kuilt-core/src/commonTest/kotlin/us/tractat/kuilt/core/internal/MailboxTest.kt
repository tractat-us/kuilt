@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.internal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FrameOverflow
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.Swatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MailboxTest {
    private fun frame(b: Byte) = Swatch(payload = byteArrayOf(b), sender = null, sequence = b.toLong())

    @Test
    fun reliableDeliversInOrder() = runTest {
        val box = Mailbox(DeliveryPolicy.Reliable)
        box.deliver(frame(1)); box.deliver(frame(2)); box.deliver(frame(3))
        val got = box.incoming.take(3).toList().map { it.sequence }
        assertEquals(listOf(1L, 2L, 3L), got)
    }

    @Test
    fun suspendBlocksWhenFull() = runTest {
        val box = Mailbox(DeliveryPolicy(capacity = 1, overflow = Overflow.SUSPEND))
        box.deliver(frame(1))                       // fills the buffer
        var second = false
        val job = backgroundScope.launch { box.deliver(frame(2)); second = true }
        runCurrent()
        assertTrue(!second, "second deliver must suspend while the buffer is full")
        assertEquals(1L, box.incoming.first().sequence) // drain one
        runCurrent()
        assertTrue(second, "second deliver completes once space frees")
        job.cancel()
    }

    @Test
    fun dropOldestBoundsAndKeepsNewest() = runTest {
        val box = Mailbox(DeliveryPolicy(capacity = 1, overflow = Overflow.DROP_OLDEST))
        box.deliver(frame(1)); box.deliver(frame(2)) // 1 dropped, never suspends
        assertEquals(2L, box.incoming.first().sequence)
    }

    @Test
    fun failThrowsOnOverflow() = runTest {
        val box = Mailbox(DeliveryPolicy(capacity = 1, overflow = Overflow.FAIL))
        box.deliver(frame(1))
        assertFailsWith<FrameOverflow> { box.deliver(frame(2)) }
    }

    @Test
    fun reliableDeliverToAClosedMailboxIsADropNotAnError() = runTest {
        // A receiver that closed concurrently (left the mesh) must not surface an error to the
        // broadcasting sender — it is a drop, matching a peer that went away.
        val box = Mailbox(DeliveryPolicy.Reliable)
        box.close()
        box.deliver(frame(1)) // must not throw ClosedSendChannelException
    }

    @Test
    fun failDeliverToAClosedMailboxIsADropNotOverflow() = runTest {
        // A closed channel is "receiver gone", not "buffer full" — FAIL must not conflate them.
        val box = Mailbox(DeliveryPolicy(capacity = 1, overflow = Overflow.FAIL))
        box.close()
        box.deliver(frame(1)) // must not throw FrameOverflow
    }
}
