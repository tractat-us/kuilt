package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that [CompositeSeam] uses a bounded [us.tractat.kuilt.core.Spool] for inbound delivery
 * rather than an unbounded [kotlinx.coroutines.channels.Channel.UNLIMITED] inbox.
 *
 * With [Overflow.DROP_OLDEST], a seam whose consumer is absent drops the oldest buffered frames
 * once the capacity is full. An unbounded `Channel.UNLIMITED` would never drop — only a bounded
 * `Spool` enforces the limit and produces observable drops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeSpoolBackpressureTest {

    /**
     * A [Overflow.DROP_OLDEST] composite seam with capacity=1 drops the oldest frame when a
     * second frame arrives and no consumer is draining. Only the last frame survives in the
     * buffer. An unbounded channel would accumulate both, so observing exactly one proves the
     * Spool bound is enforced.
     */
    @Test
    fun lossyPolicyDropsOldestWhenBufferFull() = runTest {
        val lossyPolicy = DeliveryPolicy(capacity = 1, overflow = Overflow.DROP_OLDEST)
        val loom = CompositeLoom(
            plies = listOf(PlyId("mem") to (InMemoryLoom() as Loom)),
            policy = lossyPolicy,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))

        host.peers.first { it.size == 2 }

        // Fill the buffer (capacity=1): first frame occupies the slot.
        host.broadcast(byteArrayOf(1))
        advanceUntilIdle()

        // Second frame arrives while the consumer is absent: DROP_OLDEST drops frame #1.
        host.broadcast(byteArrayOf(2))
        advanceUntilIdle()

        // Only one frame survives; it is the newest (frame #2).
        val received = joiner.incoming.first()
        assertEquals(listOf(2.toByte()), received.toByteArray().toList(), "expected only the newest frame to survive")

        host.close()
        joiner.close()
    }

    /**
     * A [DeliveryPolicy.Reliable] composite seam delivers all frames in order to a consuming
     * collector — backpressure does not drop or reorder.
     */
    @Test
    fun reliablePolicyDeliversAllFramesInOrder() = runTest {
        val loom = CompositeLoom(
            plies = listOf(PlyId("mem") to (InMemoryLoom() as Loom)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))

        host.peers.first { it.size == 2 }

        val frameCount = 5
        repeat(frameCount) { i -> host.broadcast(byteArrayOf(i.toByte())) }
        advanceUntilIdle()

        val received = joiner.incoming.take(frameCount).toList()
        assertEquals(frameCount, received.size)
        received.forEachIndexed { i, swatch ->
            assertEquals(listOf(i.toByte()), swatch.toByteArray().toList())
        }

        host.close()
        joiner.close()
    }
}
