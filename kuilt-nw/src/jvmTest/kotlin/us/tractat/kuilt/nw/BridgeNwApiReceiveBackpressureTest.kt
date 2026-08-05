// deliberate: the JNA callback is a FOREIGN OS thread that the dylib invokes synchronously, and the
// property under test is precisely what happens when it outruns the JVM-side drain. A virtual-time
// dispatcher cannot express that — under `runTest` the producer and the drain are the same thread, so
// the callback can never actually outrun anything and the bug is unreachable.
@file:Suppress("ForbiddenImport")

package us.tractat.kuilt.nw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The JVM bridge's receive path is **lossless under burst** (#2134).
 *
 * `BridgeNwApi` used to stage received bytes through a 64-slot `DROP_OLDEST` channel, matching the
 * lossy `tryEmit` on the appleMain side. That is defensible for the three lifecycle event flows —
 * a dropped `endpointFound` is re-discovered, a dropped close is backstopped by the drop-tolerant
 * `connectionStates` STATE — and indefensible for a byte stream: nothing above reconstructs a gap,
 * and a length-prefixed reader cannot resynchronize after one, so a single dropped chunk misparses
 * **every subsequent byte on that connection**. The transport delivers at most 64 KiB per receive,
 * so a 16 MiB frame is 256+ chunks against 64 slots — which is how `payloadOfExactlyTheBudgetIsCarried`
 * came to wedge roughly one run in five.
 *
 * ## Why this test uses real threads
 * The defect only exists across a thread boundary. In production the callback is invoked
 * *synchronously* by the dylib's forwarding collector on its own thread, and the fix works by
 * **blocking that thread** — returning from the callback is the ack the native re-arm waits on. Under
 * a single-threaded test dispatcher the producer and the drain interleave cooperatively, so the
 * callback can never outrun the drain and there is nothing to drop. Hence [Dispatchers.Default] and a
 * deliberately slow collector, with real-time bounds that are wedge backstops rather than assertions.
 */
class BridgeNwApiReceiveBackpressureTest {

    /**
     * A burst several times the staging buffer, against a collector slow enough that the producer must
     * outrun it. Pre-fix the producer never waited, so only about a buffer's worth of the burst survived;
     * the rest vanished with no error anywhere. Now the producer is made to wait, and every chunk lands —
     * in order, which is the other half of what a framer needs.
     */
    @Test
    fun aBurstLargerThanTheStagingBufferLosesNoChunks() = runBlocking {
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, Dispatchers.Default)
        val joiner = BridgeNwApi(fake, FakeNwNativeLib.JOINER, Dispatchers.Default)

        // Concurrent, because it is written from the collector's thread and read from this one.
        val received = ConcurrentLinkedQueue<Int>()
        val collector = launch(Dispatchers.Default) {
            joiner.bytesReceived.collect { event ->
                // Slower than the producer on purpose: this is the "consumer falls behind" condition, and
                // the only question the test asks is whether falling behind costs bytes.
                delay(COLLECTOR_LAG_MS)
                received += decodeIndex(event.bytes)
            }
        }
        // Subscribed, not merely launched: `bytesReceived` has no replay, so a chunk published before the
        // first subscriber attaches is gone for a reason that has nothing to do with the defect.
        withTimeout(BURST_BACKSTOP) {
            while (joiner.bytesSubscriberCountForTest() == 0) delay(POLL_MS)
        }

        val conn = NwConnectionId("c")
        withTimeout(BURST_BACKSTOP) {
            repeat(BURST) { i -> host.send(conn, encodeIndex(i)) }
        }
        // Wait for the burst to LAND OR GO QUIET — not for it to land. A lossy stage never reaches
        // [BURST], so waiting on the count alone turns the interesting failure ("192 chunks vanished")
        // into an uninformative timeout. Quiescing instead lets the assertion below report the real number.
        withTimeout(BURST_BACKSTOP) {
            var lastSeen = -1
            var quietFor = 0L
            while (received.size < BURST && quietFor < QUIESCE_MS) {
                delay(POLL_MS)
                quietFor = if (received.size == lastSeen) quietFor + POLL_MS else 0L
                lastSeen = received.size
            }
        }
        collector.cancel()

        val arrived = received.toList()
        assertAll(
            { assertEquals(BURST, arrived.size, "a burst larger than the staging buffer must not lose chunks") },
            {
                assertEquals(
                    List(BURST) { it },
                    arrived,
                    "chunks must arrive in wire order — a framer has no other way to reassemble",
                )
            },
        )
    }

    /**
     * After [BridgeNwApi.close] there is no drain left, so a chunk that arrives late must be DROPPED —
     * promptly. Pinned because the fix's blocking stage would otherwise park a dylib forwarding collector
     * on a consumer that is never coming back: a permanent native-thread hang, strictly worse than the
     * lost bytes it replaced. [BridgeNwApi.close] cancelling the staging channel is what keeps it bounded.
     */
    @Test
    fun aBurstAfterCloseIsDroppedRatherThanBlockingForever() = runBlocking {
        val fake = FakeNwNativeLib()
        val host = BridgeNwApi(fake, FakeNwNativeLib.HOST, Dispatchers.Default)
        val joiner = BridgeNwApi(fake, FakeNwNativeLib.JOINER, Dispatchers.Default)

        joiner.close()

        // More than the staging buffer holds, with nothing draining it: pre-`cancel()` the first send past
        // the buffer parks the caller for good.
        withTimeout(BURST_BACKSTOP) {
            repeat(BURST) { i -> host.send(NwConnectionId("c"), encodeIndex(i)) }
        }
    }

    private fun encodeIndex(i: Int): ByteArray = byteArrayOf((i ushr 8).toByte(), i.toByte())

    private fun decodeIndex(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)

    private companion object {
        /** Several times `BridgeNwApi.BYTES_BUFFER` (64), so a lossy stage sheds most of it. */
        private const val BURST = 512
        private const val COLLECTOR_LAG_MS = 1L
        private const val POLL_MS = 1L

        /**
         * How long the arrival count must stand still before the burst counts as finished. Two orders of
         * magnitude above [COLLECTOR_LAG_MS], so it can only be reached by chunks that are never coming.
         */
        private const val QUIESCE_MS = 500L

        /**
         * A wedge backstop, not an assertion: the burst is `BURST * COLLECTOR_LAG_MS` ≈ 0.5 s of deliberate
         * consumer lag, and this is wall-clock over real threads, so it is sized for a contended box.
         */
        private val BURST_BACKSTOP = 60.seconds
    }
}
