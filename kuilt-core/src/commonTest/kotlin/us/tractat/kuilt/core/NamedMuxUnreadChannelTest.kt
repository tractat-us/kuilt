package us.tractat.kuilt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Known failure found while checking #2862: an unread view backpressures the whole mux.
 * This also fails with main's dispatched subscription once its subscribers have started.
 * Keep the desired isolation assertion as an explicit expected failure until a separate
 * overflow-policy change resolves it. An unexpected pass must make this test fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NamedMuxUnreadChannelTest {
    @Test
    fun unreadChannelIsolationIsAKnownFailure() = runTest(StandardTestDispatcher()) {
        // shareIn uses this default for a plain flow with no fused buffer operator.
        // Measure it so a JVM defaultBuffer property override cannot invalidate the probe.
        val bufferProbe = Channel<Unit>(Channel.BUFFERED)
        var sharedCapacity = 0
        while (bufferProbe.trySend(Unit).isSuccess) sharedCapacity++
        bufferProbe.cancel()

        val spoolCapacity = DeliveryPolicy.Reliable.capacity
        // Include the frame held by the suspended per-view deliver(), then overflow sharing.
        val floodSize = spoolCapacity + sharedCapacity + 2
        val start = CompletableDeferred<Unit>()
        val peer = PeerId("sender")
        var emitted = 0
        val base = object : Seam by FakeSeam() {
            override val incoming = flow {
                start.await()
                repeat(floodSize) {
                    emit(Swatch(NamedFrame.encode("a".encodeToByteArray(), byteArrayOf(1)), sender = peer))
                    emitted++
                }
                emit(Swatch(NamedFrame.encode("b".encodeToByteArray(), byteArrayOf(55)), sender = peer))
                emitted++
            }
        }
        val mux = NamedMux(base, backgroundScope)
        val a = mux.channel("a") // Never collected during the flood.
        val b = mux.channel("b")
        val received = mutableListOf<List<Byte>>()
        backgroundScope.launch { b.incoming.collect { received += it.toByteArray().toList() } }
        runCurrent() // Settle subscriptions on main too; do not hide the stall with startup loss.
        start.complete(Unit)
        runCurrent()
        val emittedWhileUnread = emitted
        val receivedWhileUnread = received.toList()
        println("unread-channel probe: spool=$spoolCapacity shared=$sharedCapacity " +
            "flood=$floodSize emitted=$emittedWhileUnread received=$receivedWhileUnread")

        // Recovery control, after observing the unread phase: consuming A must release B.
        // Verify every A payload as well, so neither startup loss nor dropping can hide the stall.
        val receivedA = mutableListOf<List<Byte>>()
        backgroundScope.launch { a.incoming.collect { receivedA += it.toByteArray().toList() } }
        runCurrent()

        assertAll(
            { assertEquals(spoolCapacity + sharedCapacity + 1, emittedWhileUnread,
                "upstream stops after the spool, in-flight delivery and shared buffer fill") },
            {
                assertFailsWith<AssertionError>("known mux isolation failure; remove this expectation when fixed") {
                    assertEquals(listOf(listOf<Byte>(55)), receivedWhileUnread,
                        "an unread channel must not stall delivery to another channel")
                }
            },
            { assertEquals(List(floodSize) { listOf<Byte>(1) }, receivedA,
                "draining A releases every buffered frame without loss") },
            { assertEquals(floodSize + 1, emitted, "draining A lets upstream finish") },
            { assertEquals(listOf(listOf<Byte>(55)), received,
                "B receives its exact frame once A is drained") },
        )
    }
}
