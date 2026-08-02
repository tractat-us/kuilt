package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class CanonicalCollectionSerializersTest {

    private val cbor = Cbor {}

    @Test
    fun mapEncodingIsInsertionOrderIndependent() {
        val ser = CanonicalMapSerializer(String.serializer(), Long.serializer())
        // HashMap so iteration order is neither insertion nor sorted — the real defect shape.
        val forward = HashMap<String, Long>().apply { put("a", 1L); put("b", 2L); put("c", 3L) }
        val reverse = HashMap<String, Long>().apply { put("c", 3L); put("b", 2L); put("a", 1L) }

        assertEquals(
            cbor.encodeToByteArray(ser, forward).toList(),
            cbor.encodeToByteArray(ser, reverse).toList(),
            "canonical map encoding must not depend on insertion order",
        )
    }

    @Test
    fun mapRoundTripsAndSorts() {
        val ser = CanonicalMapSerializer(String.serializer(), Long.serializer())
        val value = mapOf("c" to 3L, "a" to 1L, "b" to 2L)
        val decoded = cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, value))
        assertAll(
            { assertEquals(value, decoded, "round-trip must preserve the map") },
            { assertEquals(listOf("a", "b", "c"), decoded.keys.toList(), "decoded order must be sorted") },
        )
    }

    @Test
    fun setEncodingIsInsertionOrderIndependent() {
        val ser = CanonicalSetSerializer(String.serializer())
        val forward = linkedSetOf("alpha", "beta", "gamma")
        val reverse = linkedSetOf("gamma", "beta", "alpha")

        assertEquals(
            cbor.encodeToByteArray(ser, forward).toList(),
            cbor.encodeToByteArray(ser, reverse).toList(),
            "canonical set encoding must not depend on insertion order",
        )
    }

    @Test
    fun setRoundTripsAndSorts() {
        val ser = CanonicalSetSerializer(String.serializer())
        val value = linkedSetOf("gamma", "alpha", "beta")
        val decoded = cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, value))
        assertAll(
            { assertEquals(value, decoded, "round-trip must preserve the set") },
            { assertEquals(listOf("alpha", "beta", "gamma"), decoded.toList(), "decoded order must be sorted") },
        )
    }

    @Test
    fun compoundKeysSortStructurallyNotByToString() {
        // Dot is a data class with (replica, seq) — serialKeyComparator must order it by
        // serialized leaves, so this works without any Comparable bound on the key.
        val ser = CanonicalMapSerializer(Dot.serializer(), Long.serializer())
        val a = Dot(ReplicaId("A"), 2L)
        val b = Dot(ReplicaId("B"), 1L)
        val forward = HashMap<Dot, Long>().apply { put(a, 1L); put(b, 2L) }
        val reverse = HashMap<Dot, Long>().apply { put(b, 2L); put(a, 1L) }

        assertEquals(
            cbor.encodeToByteArray(ser, forward).toList(),
            cbor.encodeToByteArray(ser, reverse).toList(),
            "compound keys must sort structurally",
        )
    }
}
