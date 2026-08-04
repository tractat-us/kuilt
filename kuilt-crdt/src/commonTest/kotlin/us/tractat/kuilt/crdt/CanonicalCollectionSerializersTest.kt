package us.tractat.kuilt.crdt

import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import us.tractat.kuilt.crdt.internal.sortedByCanonicalKey
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

/**
 * A key type deliberately carrying **no** `@Serializable` annotation, so its serializer exists
 * only inside a [SerializersModule] and is reachable only through whichever encoder is asked to
 * write it — which is what makes it a probe for #2035.
 *
 * Compound on purpose: `ordinal` 2 vs 10 discriminates a structural sort from a textual one, so
 * the order assertions below stay load-bearing rather than incidental.
 */
private data class Tag(val name: String, val ordinal: Int)

@OptIn(ExperimentalSerializationApi::class)
private object TagSerializer : KSerializer<Tag> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Tag") {
        element<String>("name")
        element<Int>("ordinal")
    }

    override fun serialize(encoder: Encoder, value: Tag) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.name)
            encodeIntElement(descriptor, 1, value.ordinal)
        }
    }

    override fun deserialize(decoder: Decoder): Tag = decoder.decodeStructure(descriptor) {
        var name = ""
        var ordinal = 0
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> name = decodeStringElement(descriptor, 0)
                1 -> ordinal = decodeIntElement(descriptor, 1)
                else -> break
            }
        }
        Tag(name, ordinal)
    }
}

@OptIn(ExperimentalSerializationApi::class)
class CanonicalCollectionSerializersTest {

    private val cbor = Cbor {}

    /** The only place [TagSerializer] is registered — exactly as a consumer's format would carry it. */
    private val contextualCbor = Cbor {
        serializersModule = SerializersModule { contextual(Tag::class, TagSerializer) }
    }

    /** Three tags in canonical order: `name` first, then `ordinal` **numerically** (2 before 10). */
    private val tagsInCanonicalOrder = listOf(Tag("a", 2), Tag("a", 10), Tag("b", 1))

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

    /**
     * The [Map] half of [tiedElementsKeepInputOrder], and **not** covered by it.
     *
     * `sortedByCanonicalKey` has two overloads — one over an [Iterable], one over a [Map] — and
     * [tiedElementsKeepInputOrder] reaches only the first, through [CanonicalSetSerializer].
     * Mutation-checked: reversing the input before the sort in the [Map] overload alone leaves the
     * whole of `:kuilt-crdt` and `:kuilt-quilter` green, including all twelve golden vectors, so
     * that overload's stability was load-bearing and unpinned. Same shape of blind spot as the
     * single-entry `VersionVector` of #2010 — the property held, nothing was checking.
     *
     * It is reachable on public API: [CanonicalMapSerializer] takes any `KSerializer<K>`, and a
     * consumer key type whose serializer emits the same leaves for two distinct values — [Sparse]
     * here — makes the order decide the bytes.
     */
    @Test
    fun tiedMapKeysKeepInputOrder() {
        val ser = CanonicalMapSerializer(SparseSerializer, Long.serializer())
        val aFirst = cbor.encodeToByteArray(ser, linkedMapOf(Sparse("x", null) to 1L, Sparse(null, "x") to 2L))
        val bFirst = cbor.encodeToByteArray(ser, linkedMapOf(Sparse(null, "x") to 2L, Sparse("x", null) to 1L))

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
                    cbor.decodeFromByteArray(ser, aFirst).keys.toList(),
                    "a tied map key must retain input order",
                )
            },
            {
                assertEquals(
                    listOf(Sparse(null, "x"), Sparse("x", null)),
                    cbor.decodeFromByteArray(ser, bFirst).keys.toList(),
                    "a tied map key must retain input order under the opposite input too",
                )
            },
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

    /**
     * [Dot]'s own [Comparable] order and the canonical leaf order agree on **every** pair.
     *
     * This is the load-bearing premise of #1964's collapse: `DotSetSerializer` sorted with
     * `dots.sorted()` and `DotFunSerializer` with `sortedBy { dot }` — [Dot]'s natural order —
     * while `DotMapSerializer` and the `Canonical*Serializer` pair sorted by serialized leaves.
     * Routing all five through [sortedByCanonicalKey] is byte-neutral **only if** the two orders
     * coincide, and the golden vectors prove that for the dots they happen to contain, not in
     * general. This test is the general argument.
     *
     * The reason they coincide: [Dot] serializes to the leaf sequence `(replica, seq)` — the
     * [ReplicaId] value class inlines to its [String], `seq` to a [Long] — and [Dot.compareTo]
     * compares exactly those two, in that order, with the same [String] and [Long] `compareTo`
     * the leaf comparator dispatches to.
     *
     * The inputs are chosen to be adversarial for the ways such a coincidence usually breaks:
     * `seq` 2 vs 10 (numeric order disagrees with text order), a replica that is a strict prefix
     * of another (`"a"` vs `"ab"`), the empty [ReplicaId.Bottom], and a case difference — none of
     * which the golden vectors' four lowercase five-letter replica names exercise.
     */
    @Test
    fun dotOrderMatchesCanonicalKeyOrder() {
        val dots = listOf(
            Dot(ReplicaId("a"), 10L),
            Dot(ReplicaId(""), 1L),
            Dot(ReplicaId("ab"), 1L),
            Dot(ReplicaId("A"), 7L),
            Dot(ReplicaId("a"), 2L),
            Dot(ReplicaId("ab"), 10L),
            Dot(ReplicaId("a"), 1L),
        )
        val listSer = ListSerializer(Dot.serializer())

        assertAll(
            {
                assertEquals(
                    dots.sorted(),
                    dots.sortedByCanonicalKey(Dot.serializer()),
                    "Dot's Comparable order and the canonical leaf order must agree",
                )
            },
            {
                assertEquals(
                    cbor.encodeToByteArray(listSer, dots.sorted()).toList(),
                    cbor.encodeToByteArray(DotSetSerializer(), DotSet(dots.toSet())).toList(),
                    "DotSetSerializer must emit exactly what Dot's own order would have emitted",
                )
            },
            {
                assertEquals(
                    listOf(1L, 2L, 10L),
                    dots.sortedByCanonicalKey(Dot.serializer()).filter { it.replica == ReplicaId("a") }.map { it.seq },
                    "the probe is vacuous unless seq sorts numerically — a text sort would give [1, 10, 2]",
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

    // ── @Contextual keys: the sort must use the FORMAT's module (#2035) ───────
    //
    // Each of the three tests below reaches a distinct call site, and no one of them shadows
    // another: the two `sortedByCanonicalKey` overloads are separate functions, and each public
    // serializer that takes an arbitrary key serializer passes the module in on its own line.
    // A fix that threads the module at one site and leaves another on an empty module is caught
    // by exactly the test for the site it missed.
    //
    // Non-vacuity, in all three: the encode *itself* is the assertion — with an empty module the
    // sort raises `SerializationException` before a byte is written, so a green run proves the
    // format's module reached the leaf encoder. The order assertion beside it then proves the
    // sort still ran (and ran structurally: `ordinal` 2 before 10, which a textual sort inverts),
    // so a "fix" that skipped the sort for unresolvable keys would fail too.

    /**
     * A `@Contextual` **map key** encodes through [CanonicalMapSerializer] when the format's
     * module can resolve it.
     *
     * Before #2035 the sort ran every key through an encoder holding an *empty* module, so a key
     * type that the format resolves fine on its own became un-encodable the moment it was wrapped
     * in a canonical collection.
     */
    @Test
    fun contextualMapKeysResolveThroughTheFormatsModule() {
        val ser = CanonicalMapSerializer(ContextualSerializer(Tag::class), Long.serializer())
        val forward = linkedMapOf(Tag("a", 2) to 1L, Tag("a", 10) to 2L, Tag("b", 1) to 3L)
        val reverse = linkedMapOf(Tag("b", 1) to 3L, Tag("a", 10) to 2L, Tag("a", 2) to 1L)
        assertNotEquals(
            forward.keys.toList(),
            reverse.keys.toList(),
            "precondition: inputs must iterate differently",
        )

        val forwardBytes = contextualCbor.encodeToByteArray(ser, forward)
        assertAll(
            {
                assertEquals(
                    forwardBytes.toList(),
                    contextualCbor.encodeToByteArray(ser, reverse).toList(),
                    "a contextual map key must still sort canonically",
                )
            },
            {
                assertEquals(
                    tagsInCanonicalOrder,
                    contextualCbor.decodeFromByteArray(ser, forwardBytes).keys.toList(),
                    "the sort must run structurally over the contextually-resolved key",
                )
            },
        )
    }

    /**
     * The [Iterable] half of [contextualMapKeysResolveThroughTheFormatsModule]:
     * [CanonicalSetSerializer] reaches the other `sortedByCanonicalKey` overload, so it needs the
     * module threaded on its own line.
     */
    @Test
    fun contextualSetElementsResolveThroughTheFormatsModule() {
        val ser = CanonicalSetSerializer(ContextualSerializer(Tag::class))
        val forward = linkedSetOf(Tag("a", 2), Tag("a", 10), Tag("b", 1))
        val reverse = linkedSetOf(Tag("b", 1), Tag("a", 10), Tag("a", 2))
        assertNotEquals(
            forward.toList(),
            reverse.toList(),
            "precondition: inputs must iterate differently",
        )

        val forwardBytes = contextualCbor.encodeToByteArray(ser, forward)
        assertAll(
            {
                assertEquals(
                    forwardBytes.toList(),
                    contextualCbor.encodeToByteArray(ser, reverse).toList(),
                    "a contextual set element must still sort canonically",
                )
            },
            {
                assertEquals(
                    tagsInCanonicalOrder,
                    contextualCbor.decodeFromByteArray(ser, forwardBytes).toList(),
                    "the sort must run structurally over the contextually-resolved element",
                )
            },
        )
    }

    /**
     * [DotMapSerializer] is the third public serializer that takes an arbitrary key serializer, so
     * it is a third independent site — the `Canonical*Serializer` pair could both be fixed while
     * this one still handed the sort an empty module.
     *
     * `DotFunSerializer` and `DotSetSerializer` are not probed: both hard-code `Dot.serializer()`,
     * so no contextual key can reach them.
     */
    @Test
    fun contextualDotMapKeysResolveThroughTheFormatsModule() {
        val ser = DotMapSerializer(ContextualSerializer(Tag::class), DotSetSerializer())
        fun tagged(vararg tags: Tag): DotMap<Tag, DotSet> =
            DotMap(tags.withIndex().associate { (i, tag) -> tag to DotSet(setOf(Dot(ReplicaId("r"), i + 1L))) })

        val forward = tagged(Tag("a", 2), Tag("a", 10), Tag("b", 1))
        val reverse = tagged(Tag("b", 1), Tag("a", 10), Tag("a", 2))
        assertNotEquals(
            forward.entries.keys.toList(),
            reverse.entries.keys.toList(),
            "precondition: inputs must iterate differently",
        )

        assertEquals(
            tagsInCanonicalOrder,
            contextualCbor.decodeFromByteArray(ser, contextualCbor.encodeToByteArray(ser, forward))
                .entries.keys.toList(),
            "a contextual DotMap key must resolve through the format's module and sort structurally",
        )
    }
}
