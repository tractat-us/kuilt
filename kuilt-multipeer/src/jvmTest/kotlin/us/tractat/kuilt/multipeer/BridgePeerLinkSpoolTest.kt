package us.tractat.kuilt.multipeer

import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.multipeer.internal.BridgePeerLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

/**
 * Verifies that [BridgePeerLink] routes inbound delivery through a bounded [us.tractat.kuilt.core.Spool]
 * rather than an unbounded [kotlinx.coroutines.channels.Channel].
 *
 * Uses a [DeliveryPolicy] with [Overflow.DROP_OLDEST] and capacity=1 to prove the buffer is bounded:
 * when two frames arrive before the receiver drains, the spool drops the oldest and retains the newest.
 * With the old `Channel.UNLIMITED` there was no bound — both frames would survive and ordering
 * within the buffer was unconstrained.
 */
class BridgePeerLinkSpoolTest {

    @Test
    fun `bounded spool drops oldest frame when capacity is exceeded`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeLib = CapturingDataCallbackLib()
        val sessionHandle = Pointer(0xCAFEBABEL)

        val policy = DeliveryPolicy(capacity = 1, overflow = Overflow.DROP_OLDEST)
        val link = BridgePeerLink(
            nativeLib = fakeLib,
            sessionHandle = sessionHandle,
            selfId = us.tractat.kuilt.core.PeerId("self"),
            policy = policy,
            dispatcher = dispatcher,
        )

        val payload1 = byteArrayOf(0x01)
        val payload2 = byteArrayOf(0x02)

        // Fire two frames via the JNA callback before the receiver drains.
        // With capacity=1 DROP_OLDEST, the first frame is evicted and only the second survives.
        fakeLib.fireData("remote-peer", payload1)
        fakeLib.fireData("remote-peer", payload2)

        // Advance virtual time so the launch { spool.deliver } coroutines run.
        testScheduler.advanceUntilIdle()

        val collected = mutableListOf<Swatch>()
        val job = launch {
            link.incoming.collect { collected += it }
        }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(1, collected.size, "bounded spool must retain exactly one frame after DROP_OLDEST eviction")
        assertContentEquals(payload2, collected.single().toByteArray())
    }
}

/**
 * [MultipeerNativeLib] fake that captures the [MultipeerNativeLib.DataCallback]
 * so tests can fire data-arrival events directly.
 */
internal class CapturingDataCallbackLib : MultipeerNativeLib by FakeMultipeerNativeLib() {
    private var capturedDataCallback: MultipeerNativeLib.DataCallback? = null

    fun fireData(peerId: String, payload: ByteArray) {
        val mem = Memory(payload.size.toLong().coerceAtLeast(1)).also { it.write(0, payload, 0, payload.size) }
        capturedDataCallback?.invoke(peerId, mem, payload.size)
    }

    override fun mc_session_set_data_callback(
        session: Pointer?,
        cb: MultipeerNativeLib.DataCallback,
    ) {
        capturedDataCallback = cb
    }
}
