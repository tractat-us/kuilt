/**
 * Tests for [MuxSeam] — round-trip and channel-isolation properties.
 *
 * Uses [UnconfinedTestDispatcher] so coroutine launches are eager inside [runTest].
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuxSeamTest {

    // ── Round-trip: payload survives tag + strip ─────────────────────────────

    /**
     * A frame broadcast on channel 0x00 arrives at the peer's 0x00 view with
     * the original payload (tag byte is stripped).
     */
    @Test
    fun broadcastRoundTripOnChannelA() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("mux-round-trip"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = MuxSeam(rawA, backgroundScope)
        val muxB = MuxSeam(rawB, backgroundScope)

        val tag = 0x00.toByte()
        val payload = byteArrayOf(1, 2, 3)

        val received = async { muxB.channel(tag).incoming.first() }
        muxA.channel(tag).broadcast(payload)

        val swatch = received.await()
        assertTrue(swatch.payload.contentEquals(payload), "payload must survive round-trip stripped of tag")
    }

    /**
     * A frame sent via sendTo on channel 0x01 arrives at the peer's 0x01 view
     * with the original payload.
     */
    @Test
    fun sendToRoundTripOnChannelB() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("mux-sendto-round-trip"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = MuxSeam(rawA, backgroundScope)
        val muxB = MuxSeam(rawB, backgroundScope)

        val tag = 0x01.toByte()
        val payload = byteArrayOf(42, 43)

        val received = async { muxB.channel(tag).incoming.first() }
        muxA.channel(tag).sendTo(rawB.selfId, payload)

        val swatch = received.await()
        assertTrue(swatch.payload.contentEquals(payload), "payload must survive sendTo round-trip stripped of tag")
    }

    // ── Channel isolation: frames don't leak across tags ──────────────────────

    /**
     * A frame sent on tag 0x00 must NOT appear on tag 0x01's incoming flow.
     */
    @Test
    fun channelAFrameDoesNotReachChannelB() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("mux-isolation"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = MuxSeam(rawA, backgroundScope)
        val muxB = MuxSeam(rawB, backgroundScope)

        // Collect channel 0x01 on B into a buffered channel so we can check it
        // without blocking after sending on 0x00.
        val channelBIncoming = muxB.channel(0x01).incoming.produceIn(this)

        // Send on channel 0x00 and wait for it to reach channel 0x00 on B
        val receivedOnA = async { muxB.channel(0x00).incoming.first() }
        muxA.channel(0x00.toByte()).broadcast(byteArrayOf(7))
        receivedOnA.await() // 0x00 frame delivered

        // Channel 0x01 must have received nothing
        assertTrue(
            channelBIncoming.tryReceive().isFailure,
            "frame sent on tag 0x00 must not appear on tag 0x01 incoming",
        )

        channelBIncoming.cancel()
    }

    /**
     * A frame sent on tag 0x01 must NOT appear on tag 0x00's incoming flow.
     */
    @Test
    fun channelBFrameDoesNotReachChannelA() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("mux-isolation-b"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = MuxSeam(rawA, backgroundScope)
        val muxB = MuxSeam(rawB, backgroundScope)

        val channelAIncoming = muxB.channel(0x00).incoming.produceIn(this)

        val receivedOnB = async { muxB.channel(0x01).incoming.first() }
        muxA.channel(0x01.toByte()).broadcast(byteArrayOf(9))
        receivedOnB.await()

        assertTrue(
            channelAIncoming.tryReceive().isFailure,
            "frame sent on tag 0x01 must not appear on tag 0x00 incoming",
        )

        channelAIncoming.cancel()
    }

    // ── N-way: more than two channels ────────────────────────────────────────

    /**
     * Three distinct tags on the same MuxSeam each deliver independently; a
     * frame on tag X reaches only tag X's view.
     */
    @Test
    fun threeChannelsAllDeliverIndependently() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("mux-three-channels"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = MuxSeam(rawA, backgroundScope)
        val muxB = MuxSeam(rawB, backgroundScope)

        val tagX = 0x10.toByte()
        val tagY = 0x20.toByte()
        val tagZ = 0x30.toByte()

        val recvX = async { muxB.channel(tagX).incoming.first() }
        val recvY = async { muxB.channel(tagY).incoming.first() }
        val recvZ = async { muxB.channel(tagZ).incoming.first() }

        muxA.channel(tagX).broadcast(byteArrayOf(1))
        muxA.channel(tagY).broadcast(byteArrayOf(2))
        muxA.channel(tagZ).broadcast(byteArrayOf(3))

        val swatchX = recvX.await()
        val swatchY = recvY.await()
        val swatchZ = recvZ.await()

        assertAll(
            { assertTrue(swatchX.payload.contentEquals(byteArrayOf(1)), "tagX payload") },
            { assertTrue(swatchY.payload.contentEquals(byteArrayOf(2)), "tagY payload") },
            { assertTrue(swatchZ.payload.contentEquals(byteArrayOf(3)), "tagZ payload") },
        )
    }

    // ── channel() is idempotent: same tag → same Seam instance ───────────────

    @Test
    fun sameTagReturnsSameSeamInstance() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("mux-idempotent"))
        val muxA = MuxSeam(rawA, backgroundScope)

        val first = muxA.channel(0x00)
        val second = muxA.channel(0x00)

        assertTrue(first === second, "channel(tag) must return the same Seam instance each call")
    }

    // ── Delegates: peers and state forward to underlying Seam ────────────────

    @Test
    fun channelViewForwardsPeers() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("mux-peers"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = MuxSeam(rawA, backgroundScope)
        val channelA = muxA.channel(0x00)

        assertEquals(rawA.peers.value, channelA.peers.value, "channel peers must match delegate peers")
    }

    @Test
    fun channelViewForwardsState() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("mux-state"))

        val muxA = MuxSeam(rawA, backgroundScope)
        val channelA = muxA.channel(0x00)

        assertEquals(rawA.state.value, channelA.state.value, "channel state must match delegate state")
    }

    // ── Multiple frames in order ──────────────────────────────────────────────

    @Test
    fun multipleFramesDeliveredInOrderOnChannel() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("mux-order"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = MuxSeam(rawA, backgroundScope)
        val muxB = MuxSeam(rawB, backgroundScope)

        val tag = 0x05.toByte()
        val frames = async { muxB.channel(tag).incoming.take(3).toList() }

        muxA.channel(tag).broadcast(byteArrayOf(10))
        muxA.channel(tag).broadcast(byteArrayOf(20))
        muxA.channel(tag).broadcast(byteArrayOf(30))

        val received = frames.await()
        assertAll(
            { assertTrue(received[0].payload.contentEquals(byteArrayOf(10)), "first frame") },
            { assertTrue(received[1].payload.contentEquals(byteArrayOf(20)), "second frame") },
            { assertTrue(received[2].payload.contentEquals(byteArrayOf(30)), "third frame") },
        )
    }

    // ── sender is preserved through the channel view ──────────────────────────

    @Test
    fun senderIsPreservedThroughChannelView() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("mux-sender"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = MuxSeam(rawA, backgroundScope)
        val muxB = MuxSeam(rawB, backgroundScope)

        val tag = 0x02.toByte()
        val received = async { muxB.channel(tag).incoming.first() }
        muxA.channel(tag).broadcast(byteArrayOf(99))

        val swatch = received.await()
        assertTrue(swatch.sender == rawA.selfId, "sender PeerId must be preserved through MuxSeam")
    }
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
