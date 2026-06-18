@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.serialization.cbor.Cbor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DedupKeyTest {
    @Test
    fun roundTripsThroughCbor() {
        val key = DedupKey(ClientId("client-1"), 42L)
        val bytes = Cbor.encodeToByteArray(DedupKey.serializer(), key)
        val decoded = Cbor.decodeFromByteArray(DedupKey.serializer(), bytes)
        assertEquals(key, decoded)
    }

    @Test
    fun autoClientIdIsDistinctPerDrawAndCarriesTheNodeIdPrefix() {
        val seeded = Random(7)
        val a = ClientId.auto(NodeId("a"), seeded)
        val b = ClientId.auto(NodeId("a"), seeded)   // same instance advances the RNG
        assertNotEquals(a, b)                         // per-incarnation suffix differs
        assertTrue(a.value.startsWith("auto:a-"))     // sentinel + NodeId prefix, readable in logs
    }

    @Test
    fun autoClientIdsDifferAcrossNodesEvenUnderTheSameSeed() {
        // The seeded-RNG collision the NodeId prefix exists to prevent.
        assertNotEquals(ClientId.auto(NodeId("a"), Random(7)), ClientId.auto(NodeId("b"), Random(7)))
    }

    @Test
    fun autoFamilyIdentifiesIncarnationsOfTheSameNode() {
        val a1 = ClientId.auto(NodeId("nodeA"), Random(1))
        val a2 = ClientId.auto(NodeId("nodeA"), Random(2))
        val b1 = ClientId.auto(NodeId("nodeB"), Random(1))
        assertEquals("nodeA", a1.autoFamily())
        assertEquals(a1.autoFamily(), a2.autoFamily()) // two incarnations share a family
        assertNotEquals(a1.autoFamily(), b1.autoFamily())
    }

    @Test
    fun autoFamilyIsNullForDurableAndMalformedIds() {
        assertNull(ClientId("svc-1").autoFamily())                       // durable, no sentinel
        assertNull(ClientId("auto:nodeA-nothex0123456789").autoFamily()) // sentinel but non-hex tail
        assertNull(ClientId("auto:nodeA-abc").autoFamily())             // sentinel but wrong-length tail
        assertNull(ClientId("auto:-0123456789abcdef").autoFamily())     // sentinel but empty family
        assertNull(ClientId("nodeA-0123456789abcdef").autoFamily())     // hex tail but no sentinel
    }

    @Test
    fun autoFamilyPreservesDashesInTheNodeId() {
        // The family is the nodeId verbatim — a fixed-length hex tail is stripped, not split on '-'.
        val id = ClientId.auto(NodeId("dc1-rack2-host3"), Random(5))
        assertEquals("dc1-rack2-host3", id.autoFamily())
    }
}
