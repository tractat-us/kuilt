@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplicatorMessageTest {

    private val a = ReplicaId("A")
    private val msgSerializer = ReplicatorMessage.serializer(GCounter.serializer())

    @Test
    fun deltaRoundTripsThroughCbor() {
        val msg = ReplicatorMessage.Delta(sender = a, seq = 7L, delta = GCounter.of(a to 3L))
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes)
        assertEquals(msg.sender, (decoded as ReplicatorMessage.Delta).sender)
        assertEquals(msg.seq, decoded.seq)
        assertEquals(msg.delta, decoded.delta)
    }

    @Test
    fun ackRoundTripsThroughCbor() {
        val msg = ReplicatorMessage.Ack<GCounter>(acker = a, sender = ReplicaId("B"), seq = 3L)
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes)
        assertEquals(msg.acker, (decoded as ReplicatorMessage.Ack).acker)
        assertEquals(msg.sender, decoded.sender)
        assertEquals(msg.seq, decoded.seq)
    }

    @Test
    fun fullStateRoundTripsThroughCbor() {
        val msg = ReplicatorMessage.FullState(sender = a, state = GCounter.of(a to 5L))
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes)
        assertEquals(msg.sender, (decoded as ReplicatorMessage.FullState).sender)
        assertEquals(msg.state, decoded.state)
    }

    @Test
    fun resendRoundTripsThroughCbor() {
        val b = ReplicaId("B")
        val msg = ReplicatorMessage.Resend<GCounter>(
            requester = a,
            sender = b,
            fromSeq = 3L,
            toSeq = 5L,
        )
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes)
        val resend = decoded as ReplicatorMessage.Resend
        assertEquals(msg.requester, resend.requester)
        assertEquals(msg.sender, resend.sender)
        assertEquals(msg.fromSeq, resend.fromSeq)
        assertEquals(msg.toSeq, resend.toSeq)
    }

    @Test
    fun deliveredRoundTripsThroughCbor() {
        val b = ReplicaId("B")
        val msg = ReplicatorMessage.Delivered<GCounter>(
            sender = a,
            vector = VersionVector.of(mapOf(a to 4L, b to 2L)),
        )
        val bytes = Cbor.encodeToByteArray(msgSerializer, msg)
        val decoded = Cbor.decodeFromByteArray(msgSerializer, bytes) as ReplicatorMessage.Delivered
        assertEquals(msg.sender, decoded.sender)
        assertEquals(msg.vector, decoded.vector)
    }
}
