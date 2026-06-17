@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.serialization.cbor.Cbor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        assertTrue(a.value.startsWith("a-"))          // NodeId prefix, readable in logs
    }

    @Test
    fun autoClientIdsDifferAcrossNodesEvenUnderTheSameSeed() {
        // The seeded-RNG collision the NodeId prefix exists to prevent.
        assertNotEquals(ClientId.auto(NodeId("a"), Random(7)), ClientId.auto(NodeId("b"), Random(7)))
    }
}
