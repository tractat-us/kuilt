@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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

    /**
     * Two peers whose delivered vectors are `equal` but were merged in different orders must put
     * **identical bytes** on the wire.
     *
     * This is the wire-path half of `CanonicalSerializationTest.versionVectorIsMergeOrderIndependent`
     * and it is not redundant with it. That test encodes through `VersionVector.serializer()`
     * directly; this one goes through the *shipped* path — `QuiltMessage.serializer(…)` + [Cbor],
     * exactly what `Quilter.encode` calls — so it proves the fix reaches the enclosing sealed-class
     * serializer rather than only the standalone one. #1978 shipped an `@Serializable(with = …)`
     * annotation that a hand-written enclosing `KSerializer` ignored, fixing nothing on the wire;
     * this assertion is what makes that failure mode visible here.
     *
     * Mutation-checked: dropping `@Serializable(with = CanonicalMapSerializer::class)` from
     * `VersionVector.entries` fails this with the two author slots transposed.
     */
    @Test
    fun deliveredVectorIsMergeOrderIndependentOnTheWire() {
        val alpha = VersionVector.of(mapOf(ReplicaId("alpha") to 3L))
        val zulu = VersionVector.of(mapOf(ReplicaId("zulu") to 5L))
        val mike = VersionVector.of(mapOf(ReplicaId("mike") to 7L))

        fun frame(vector: VersionVector): List<Byte> =
            Cbor.encodeToByteArray(msgSerializer, QuiltMessage.Delivered(sender = a, vector = vector)).toList()

        val alphaFirst = alpha.ceilWith(zulu).ceilWith(mike)
        val mikeFirst = mike.ceilWith(zulu).ceilWith(alpha)

        assertEquals(3, alphaFirst.entries.size, "the probe is vacuous unless the vector is multi-entry")
        assertEquals(alphaFirst, mikeFirst, "sanity: both peers gossip the same vector")
        assertEquals(
            frame(alphaFirst),
            frame(mikeFirst),
            "a Delivered frame must be merge-order-independent on the wire",
        )
    }

    /**
     * The vector as the shipped path actually builds it: [contiguousFrontier] over a set of dots.
     *
     * `Quilter`'s `_deliveredLocal` — the field gossiped as [QuiltMessage.Delivered.vector] — is
     * `contiguousFrontier(dots)`, which is `dots.groupBy { it.replica }`: a `LinkedHashMap` in the
     * **iteration order of the dot set**, and that set is built by merging deltas, so its order is
     * delivery history. So the wire vector is order-dependent for a reason
     * `VersionVector.combine` has nothing to do with — two peers holding the same dots can gossip
     * bytes that differ purely because the dots arrived in a different order.
     *
     * That makes the canonicalisation load-bearing at the *encoder*: a fix inside `combine` would
     * not have reached this path at all.
     *
     * Mutation-checked: dropping `@Serializable(with = CanonicalMapSerializer::class)` from
     * `VersionVector.entries` fails this with the author slots transposed.
     */
    @Test
    fun deliveredVectorFromContiguousFrontierIsDotArrivalOrderIndependent() {
        val alpha = ReplicaId("alpha")
        val zulu = ReplicaId("zulu")
        val mike = ReplicaId("mike")
        val dots = listOf(Dot(alpha, 1L), Dot(zulu, 1L), Dot(mike, 1L), Dot(zulu, 2L))

        fun frame(order: List<Dot>): List<Byte> = Cbor.encodeToByteArray(
            msgSerializer,
            QuiltMessage.Delivered(sender = a, vector = contiguousFrontier(LinkedHashSet(order))),
        ).toList()

        val arrivalOrder = contiguousFrontier(LinkedHashSet(dots))
        val reverseOrder = contiguousFrontier(LinkedHashSet(dots.reversed()))

        assertEquals(3, arrivalOrder.entries.size, "the probe is vacuous unless the frontier is multi-entry")
        assertNotEquals(
            arrivalOrder.entries.keys.toList(),
            reverseOrder.entries.keys.toList(),
            "the probe is vacuous unless groupBy actually reaches the two orders differently",
        )
        assertEquals(arrivalOrder, reverseOrder, "sanity: both peers hold the same frontier")
        assertEquals(
            frame(dots),
            frame(dots.reversed()),
            "a Delivered frame must not depend on the order its dots arrived in",
        )
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
