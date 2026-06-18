/**
 * Tests for [NamedMux] — string-keyed sibling of [MuxSeam]. Round-trip,
 * channel-isolation, name-validation, and single-collection properties.
 *
 * Uses [UnconfinedTestDispatcher] so coroutine launches are eager inside [runTest].
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NamedMuxTest {

    // ── Round-trip: payload survives name framing + strip ────────────────────

    @Test
    fun broadcastRoundTripOnNamedChannel() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-round-trip"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = NamedMux(rawA, backgroundScope)
        val muxB = NamedMux(rawB, backgroundScope)

        val payload = byteArrayOf(1, 2, 3)

        val received = async { muxB.channel("chat").incoming.first() }
        muxA.channel("chat").broadcast(payload)

        val swatch = received.await()
        assertTrue(swatch.payload.contentEquals(payload), "payload must survive round-trip stripped of name header")
    }

    @Test
    fun sendToRoundTripOnNamedChannel() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-sendto"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = NamedMux(rawA, backgroundScope)
        val muxB = NamedMux(rawB, backgroundScope)

        val payload = byteArrayOf(42, 43)

        val received = async { muxB.channel("cursors").incoming.first() }
        muxA.channel("cursors").sendTo(rawB.selfId, payload)

        val swatch = received.await()
        assertTrue(swatch.payload.contentEquals(payload), "payload must survive sendTo round-trip stripped of name header")
    }

    /** Delivered payload must start at offset 0 — a fresh array, not a view into the framed buffer. */
    @Test
    fun deliveredPayloadIsZeroOffset() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-zero-offset"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = NamedMux(rawA, backgroundScope)
        val muxB = NamedMux(rawB, backgroundScope)

        val payload = byteArrayOf(7, 8, 9, 10)
        val received = async { muxB.channel("data").incoming.first() }
        muxA.channel("data").broadcast(payload)

        val swatch = received.await()
        assertAll(
            { assertEquals(payload.size, swatch.payload.size, "delivered payload must be exactly the original length") },
            { assertEquals(7.toByte(), swatch.payload[0], "delivered payload must start at offset 0") },
        )
    }

    // ── Channel isolation: distinct names don't cross-talk ────────────────────

    @Test
    fun chatFrameDoesNotReachCursors() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-isolation"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = NamedMux(rawA, backgroundScope)
        val muxB = NamedMux(rawB, backgroundScope)

        val cursorsIncoming = muxB.channel("cursors").incoming.produceIn(this)

        val receivedChat = async { muxB.channel("chat").incoming.first() }
        muxA.channel("chat").broadcast(byteArrayOf(7))
        receivedChat.await()

        assertTrue(
            cursorsIncoming.tryReceive().isFailure,
            "frame sent on \"chat\" must not appear on \"cursors\" incoming",
        )
        cursorsIncoming.cancel()
    }

    /** Names that share a byte prefix must not be confused (length-prefixed, not delimiter-based). */
    @Test
    fun prefixSharingNamesDoNotCrossTalk() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-prefix"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = NamedMux(rawA, backgroundScope)
        val muxB = NamedMux(rawB, backgroundScope)

        val chatRoomIncoming = muxB.channel("chatroom").incoming.produceIn(this)

        val receivedChat = async { muxB.channel("chat").incoming.first() }
        muxA.channel("chat").broadcast(byteArrayOf(1))
        receivedChat.await()

        assertTrue(
            chatRoomIncoming.tryReceive().isFailure,
            "\"chat\" frame must not leak into \"chatroom\" — names are length-prefixed",
        )
        chatRoomIncoming.cancel()
    }

    // ── N-way independence ────────────────────────────────────────────────────

    @Test
    fun threeNamedChannelsDeliverIndependently() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-three"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = NamedMux(rawA, backgroundScope)
        val muxB = NamedMux(rawB, backgroundScope)

        val recvX = async { muxB.channel("chat").incoming.first() }
        val recvY = async { muxB.channel("cursors").incoming.first() }
        val recvZ = async { muxB.channel("voice").incoming.first() }

        muxA.channel("chat").broadcast(byteArrayOf(1))
        muxA.channel("cursors").broadcast(byteArrayOf(2))
        muxA.channel("voice").broadcast(byteArrayOf(3))

        assertAll(
            { assertTrue(recvX.await().payload.contentEquals(byteArrayOf(1)), "chat payload") },
            { assertTrue(recvY.await().payload.contentEquals(byteArrayOf(2)), "cursors payload") },
            { assertTrue(recvZ.await().payload.contentEquals(byteArrayOf(3)), "voice payload") },
        )
    }

    // ── channel() idempotency ─────────────────────────────────────────────────

    @Test
    fun sameNameReturnsSameSeamInstance() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-idempotent"))
        val muxA = NamedMux(rawA, backgroundScope)

        assertSame(muxA.channel("chat"), muxA.channel("chat"), "channel(name) must return the same Seam instance each call")
    }

    @Test
    fun concurrentChannelCallsAreSafe() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-concurrent"))
        val muxA = NamedMux(rawA, backgroundScope)

        val results = (1..16).map { async { muxA.channel("chat") } }.awaitAll()

        assertAll(
            { assertEquals(1, results.toSet().size, "all 16 concurrent channel(name) calls must return the same instance") },
            { assertSame(results[0], results[15], "first and last must be identical") },
            { assertTrue(muxA.channel("a") !== muxA.channel("b"), "different names must produce different channel views") },
        )
    }

    // ── Name validation ───────────────────────────────────────────────────────

    @Test
    fun emptyNameRejected() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-empty"))
        val muxA = NamedMux(rawA, backgroundScope)

        assertFailsWith<IllegalArgumentException> { muxA.channel("") }
    }

    @Test
    fun oversizeNameRejected() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-oversize"))
        val muxA = NamedMux(rawA, backgroundScope)

        assertAll(
            { muxA.channel("a".repeat(255)) }, // exactly 255 bytes is allowed
            { assertFailsWith<IllegalArgumentException> { muxA.channel("a".repeat(256)) } },
        )
    }

    /** A name whose UTF-8 encoding exceeds 255 bytes (though its char count is smaller) is rejected. */
    @Test
    fun multibyteNameMeasuredInBytes() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-multibyte"))
        val muxA = NamedMux(rawA, backgroundScope)

        // "é" encodes to 2 UTF-8 bytes; 128 of them = 256 bytes > 255.
        assertFailsWith<IllegalArgumentException> { muxA.channel("é".repeat(128)) }
    }

    // ── Unknown-name inbound frames are silently discarded ────────────────────

    @Test
    fun unknownNameFramesDiscarded() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-unknown"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = NamedMux(rawA, backgroundScope)
        val muxB = NamedMux(rawB, backgroundScope)

        // B only subscribes to "chat"; A sends on "voice" then on "chat".
        val chatIncoming = muxB.channel("chat").incoming.produceIn(this)

        muxA.channel("voice").broadcast(byteArrayOf(99))
        val received = async { muxB.channel("chat").incoming.first() }
        muxA.channel("chat").broadcast(byteArrayOf(1))

        assertTrue(received.await().payload.contentEquals(byteArrayOf(1)), "chat frame must arrive")
        // The "voice" frame must not have leaked into "chat".
        assertTrue(chatIncoming.tryReceive().let { it.isFailure || it.getOrNull()?.payload?.contentEquals(byteArrayOf(1)) == true })
        chatIncoming.cancel()
    }

    // ── Delegation: peers/state forward; sender preserved ────────────────────

    @Test
    fun channelViewForwardsPeersAndState() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-delegation"))
        val muxA = NamedMux(rawA, backgroundScope)
        val channel = muxA.channel("chat")

        assertAll(
            { assertEquals(rawA.peers.value, channel.peers.value, "channel peers must match delegate peers") },
            { assertEquals(rawA.state.value, channel.state.value, "channel state must match delegate state") },
            { assertEquals(rawA.selfId, channel.selfId, "channel selfId must match delegate selfId") },
        )
    }

    @Test
    fun senderIsPreservedThroughChannelView() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-sender"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = NamedMux(rawA, backgroundScope)
        val muxB = NamedMux(rawB, backgroundScope)

        val received = async { muxB.channel("chat").incoming.first() }
        muxA.channel("chat").broadcast(byteArrayOf(99))

        assertEquals(rawA.selfId, received.await().sender, "sender PeerId must be preserved through NamedMux")
    }

    // ── Multiple frames in order on one name ──────────────────────────────────

    @Test
    fun multipleFramesDeliveredInOrder() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("named-order"))
        val rawB = loom.join(InMemoryTag("b"))

        val muxA = NamedMux(rawA, backgroundScope)
        val muxB = NamedMux(rawB, backgroundScope)

        val frames = async { muxB.channel("chat").incoming.take(3).toList() }
        muxA.channel("chat").broadcast(byteArrayOf(10))
        muxA.channel("chat").broadcast(byteArrayOf(20))
        muxA.channel("chat").broadcast(byteArrayOf(30))

        val received = frames.await()
        assertAll(
            { assertTrue(received[0].payload.contentEquals(byteArrayOf(10)), "first frame") },
            { assertTrue(received[1].payload.contentEquals(byteArrayOf(20)), "second frame") },
            { assertTrue(received[2].payload.contentEquals(byteArrayOf(30)), "third frame") },
        )
    }
}
