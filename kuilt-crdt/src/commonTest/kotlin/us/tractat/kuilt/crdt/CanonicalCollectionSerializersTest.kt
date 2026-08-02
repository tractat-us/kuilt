package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A tie: [SparseSerializer] emits the *same primitive leaf* for both `Sparse("x", null)` and
 * `Sparse(null, "x")` — only the element **index** differs, and the leaf encoder behind the
 * canonical sort records primitives, not indices. So the two compare equal and the sort's
 * stability decides their order, while CBOR writes the field name and so makes that decision
 * visible in the bytes.
 */
private data class Sparse(val a: String?, val b: String?)

@OptIn(ExperimentalSerializationApi::class)
private object SparseSerializer : KSerializer<Sparse> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Sparse") {
        element<String>("a", isOptional = true)
        element<String>("b", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: Sparse) {
        encoder.encodeStructure(descriptor) {
            if (value.a != null) encodeStringElement(descriptor, 0, value.a)
            if (value.b != null) encodeStringElement(descriptor, 1, value.b)
        }
    }

    override fun deserialize(decoder: Decoder): Sparse = decoder.decodeStructure(descriptor) {
        var a: String? = null
        var b: String? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> a = decodeStringElement(descriptor, 0)
                1 -> b = decodeStringElement(descriptor, 1)
                else -> break
            }
        }
        Sparse(a, b)
    }
}

@OptIn(ExperimentalSerializationApi::class)
class CanonicalCollectionSerializersTest {

    private val cbor = Cbor {}

    @Test
    fun mapEncodingIsInsertionOrderIndependent() {
        val ser = CanonicalMapSerializer(String.serializer(), Long.serializer())
        // linkedMapOf, not HashMap: a LinkedHashMap iterates in insertion order on EVERY target,
        // so the two inputs are guaranteed to differ. A HashMap does not work here — on the JVM
        // its iteration order is a function of the key set and capacity, not of insertion, so
        // both maps iterate identically and this test passes even with the sort removed.
        val forward = linkedMapOf("a" to 1L, "b" to 2L, "c" to 3L)
        val reverse = linkedMapOf("c" to 3L, "b" to 2L, "a" to 1L)
        assertNotEquals(
            forward.keys.toList(),
            reverse.keys.toList(),
            "precondition: inputs must iterate differently",
        )

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
        // linkedSetOf iterates in insertion order on every target — see the note in
        // mapEncodingIsInsertionOrderIndependent for why a plain HashSet would be unsound here.
        val forward = linkedSetOf("alpha", "beta", "gamma")
        val reverse = linkedSetOf("gamma", "beta", "alpha")
        assertNotEquals(
            forward.toList(),
            reverse.toList(),
            "precondition: inputs must iterate differently",
        )

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
    fun tiedElementsKeepInputOrder() {
        // The sort is a total PREORDER, so tied elements are ordered by the sort's stability
        // alone. That makes stability load-bearing for the bytes, not an implementation detail:
        // the serializers sort by a pre-computed leaf list rather than by a comparator that
        // re-serializes each operand, and only a stable sort keeps those two byte-identical.
        val ser = CanonicalSetSerializer(SparseSerializer)
        val aFirst = cbor.encodeToByteArray(ser, linkedSetOf(Sparse("x", null), Sparse(null, "x")))
        val bFirst = cbor.encodeToByteArray(ser, linkedSetOf(Sparse(null, "x"), Sparse("x", null)))

        assertAll(
            {
                assertNotEquals(
                    aFirst.toList(),
                    bFirst.toList(),
                    "precondition: the tie must be observable in the bytes, else this proves nothing",
                )
            },
            {
                assertEquals(
                    listOf(Sparse("x", null), Sparse(null, "x")),
                    cbor.decodeFromByteArray(ser, aFirst).toList(),
                    "a tie must retain input order",
                )
            },
            {
                assertEquals(
                    listOf(Sparse(null, "x"), Sparse("x", null)),
                    cbor.decodeFromByteArray(ser, bFirst).toList(),
                    "a tie must retain input order under the opposite input too",
                )
            },
        )
    }

    @Test
    fun compoundKeysSortStructurallyNotByToString() {
        // The two keys differ only in the numeric `seq` leaf, and differ there in the one way that
        // discriminates the two candidate orders: structurally 2 < 10, but as text "10" < "2".
        // A toString-based comparator therefore emits [10, 2] and fails the order assertion below.
        // That is what pins serialKeyComparator as load-bearing rather than incidental — keys whose
        // toString order happens to agree with their structural order cannot tell the two apart.
        val ser = CanonicalMapSerializer(Dot.serializer(), Long.serializer())
        val low = Dot(ReplicaId("A"), 2L)
        val high = Dot(ReplicaId("A"), 10L)
        val forward = linkedMapOf(low to 1L, high to 2L)
        val reverse = linkedMapOf(high to 2L, low to 1L)
        assertNotEquals(
            forward.keys.toList(),
            reverse.keys.toList(),
            "precondition: inputs must iterate differently",
        )

        val decoded = cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, forward))
        assertAll(
            {
                assertEquals(
                    cbor.encodeToByteArray(ser, forward).toList(),
                    cbor.encodeToByteArray(ser, reverse).toList(),
                    "compound keys must sort structurally",
                )
            },
            {
                assertEquals(
                    listOf(2L, 10L),
                    decoded.keys.map { it.seq },
                    "seq must sort numerically, not by toString",
                )
            },
        )
    }
}
