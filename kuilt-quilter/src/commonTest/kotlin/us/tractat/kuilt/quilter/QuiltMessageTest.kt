@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals

class QuiltMessageTest {

    private val a = ReplicaId("A")
    private val msgSerializer = QuiltMessage.serializer(GCounter.serializer())

    @Test
    fun deltaRoundTripsThroughCbor() {
        val msg = QuiltMessage.Delta(sender = a, seq = 7L, delta = GCounter.of(a to 3L))
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes)
        assertEquals(msg.sender, (decoded as QuiltMessage.Delta).sender)
        assertEquals(msg.seq, decoded.seq)
        assertEquals(msg.delta, decoded.delta)
    }

    @Test
    fun ackRoundTripsThroughCbor() {
        val msg = QuiltMessage.Ack<GCounter>(acker = a, sender = ReplicaId("B"), seq = 3L)
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes)
        assertEquals(msg.acker, (decoded as QuiltMessage.Ack).acker)
        assertEquals(msg.sender, decoded.sender)
        assertEquals(msg.seq, decoded.seq)
    }

    @Test
    fun fullStateRoundTripsThroughCbor() {
        val msg = QuiltMessage.FullState(sender = a, state = GCounter.of(a to 5L), upThrough = 7L)
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes)
        assertEquals(msg.sender, (decoded as QuiltMessage.FullState).sender)
        assertEquals(msg.state, decoded.state)
        assertEquals(msg.upThrough, decoded.upThrough)
    }

    @Test
    fun resendRoundTripsThroughCbor() {
        val b = ReplicaId("B")
        val msg = QuiltMessage.Resend<GCounter>(
            requester = a,
            sender = b,
            fromSeq = 3L,
            toSeq = 5L,
        )
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes)
        val resend = decoded as QuiltMessage.Resend
        assertEquals(msg.requester, resend.requester)
        assertEquals(msg.sender, resend.sender)
        assertEquals(msg.fromSeq, resend.fromSeq)
        assertEquals(msg.toSeq, resend.toSeq)
    }

    @Test
    fun rootDigestRoundTripsThroughCbor() {
        val msg = QuiltMessage.RootDigest<GCounter>(sender = a, root = -0x0123456789ABCDEFL, upThrough = 9L)
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes) as QuiltMessage.RootDigest
        assertEquals(msg.sender, decoded.sender)
        assertEquals(msg.root, decoded.root)
        assertEquals(msg.upThrough, decoded.upThrough)
    }

    @Test
    fun fullStateRequestRoundTripsThroughCbor() {
        val msg = QuiltMessage.FullStateRequest<GCounter>(requester = a, sender = ReplicaId("B"))
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes) as QuiltMessage.FullStateRequest
        assertEquals(msg.requester, decoded.requester)
        assertEquals(msg.sender, decoded.sender)
    }

    @Test
    fun deliveredRoundTripsThroughCbor() {
        val b = ReplicaId("B")
        val msg = QuiltMessage.Delivered<GCounter>(
            sender = a,
            vector = VersionVector.of(mapOf(a to 4L, b to 2L)),
        )
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes) as QuiltMessage.Delivered
        assertEquals(msg.sender, decoded.sender)
        assertEquals(msg.vector, decoded.vector)
    }
}
