@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpoolTest {
    private fun frame(b: Byte) = Swatch(payload = byteArrayOf(b), sender = null, sequence = b.toLong())

    @Test
    fun reliableDeliversInOrder() = runTest {
        val spool = Spool(DeliveryPolicy.Reliable)
        spool.deliver(frame(1)); spool.deliver(frame(2)); spool.deliver(frame(3))
        val got = spool.incoming.take(3).toList().map { it.sequence }
        assertEquals(listOf(1L, 2L, 3L), got)
    }

    @Test
    fun suspendBlocksWhenFull() = runTest {
        val spool = Spool(DeliveryPolicy(capacity = 1, overflow = Overflow.SUSPEND))
        spool.deliver(frame(1))                       // fills the buffer
        var second = false
        val job = backgroundScope.launch { spool.deliver(frame(2)); second = true }
        runCurrent()
        assertTrue(!second, "second deliver must suspend while the buffer is full")
        assertEquals(1L, spool.incoming.first().sequence) // drain one
        runCurrent()
        assertTrue(second, "second deliver completes once space frees")
        job.cancel()
    }

    @Test
    fun dropOldestBoundsAndKeepsNewest() = runTest {
        val spool = Spool(DeliveryPolicy(capacity = 1, overflow = Overflow.DROP_OLDEST))
        spool.deliver(frame(1)); spool.deliver(frame(2)) // 1 dropped, never suspends
        assertEquals(2L, spool.incoming.first().sequence)
    }

    @Test
    fun failThrowsOnOverflow() = runTest {
        val spool = Spool(DeliveryPolicy(capacity = 1, overflow = Overflow.FAIL))
        spool.deliver(frame(1))
        assertFailsWith<FrameOverflow> { spool.deliver(frame(2)) }
    }

    @Test
    fun reliableDeliverToAClosedSpoolIsADropNotAnError() = runTest {
        // A receiver that closed concurrently (left the mesh) must not surface an error to the
        // broadcasting sender — it is a drop, matching a peer that went away.
        val spool = Spool(DeliveryPolicy.Reliable)
        spool.close()
        spool.deliver(frame(1)) // must not throw ClosedSendChannelException
    }

    @Test
    fun failDeliverToAClosedSpoolIsADropNotOverflow() = runTest {
        // A closed channel is "receiver gone", not "buffer full" — FAIL must not conflate them.
        val spool = Spool(DeliveryPolicy(capacity = 1, overflow = Overflow.FAIL))
        spool.close()
        spool.deliver(frame(1)) // must not throw FrameOverflow
    }
}
