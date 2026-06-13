package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * A node in a [JsonCrdt] document.
 *
 * The three variants compose existing CRDTs directly:
 * - [Object] — an observed-remove map keyed by [String], values are [JsonNode]s.
 *   Concurrent adds win over removes (add-wins, ORMap semantics).
 * - [Array] — an RGA sequence of [JsonNode]s.
 *   Position-stable: concurrent inserts are ordered by the RGA tiebreak.
 * - [Leaf] — a multi-value register holding one or more [JsonValue]s.
 *   Concurrent writes from different replicas are all retained until a later
 *   write observes and supersedes them.
 *
 * **Merge semantics for cross-type conflicts.** When the same key in a parent
 * [Object] holds an [Object] on one side and an [Array] or [Leaf] on the other
 * (a concurrent type-change), the richer type wins deterministically:
 * `Object > Array > Leaf`. This rule is a total order, so [piece] is commutative
 * and associative on the type dimension — convergence holds. However the losing
 * node's entire subtree is **silently and permanently discarded**: unlike scalar
 * conflicts (where [MVRegister] surfaces both values), a cross-type conflict has
 * no observable indication that data was lost. This is a v1 simplification; a
 * future version may surface these conflicts as multi-valued entries.
 *
 * **Serialization.** Use [JsonNode.serializer] to obtain a [KSerializer] that
 * handles the recursive structure correctly. The compiler-generated serializer
 * falls back to `PolymorphicSerializer(Any::class)` for the element types of the
 * inner [Rga] and [ORMap], which fails on CBOR transport.
 *
 * Wire format: `{ "t": Int, "o": ORMap?, "a": Rga?, "l": MVRegister? }` where
 * `t` is `0` for [Object], `1` for [Array], `2` for [Leaf], and only the field
 * matching the variant is present.
 */
public sealed class JsonNode : Quilted<JsonNode> {

    /**
     * An ORMap-backed JSON object: keys are observed-remove, values recurse as [JsonNode].
     */
    public class Object(
        public val map: ORMap<String, JsonNode>,
    ) : JsonNode() {

        override fun piece(other: JsonNode): JsonNode = when (other) {
            is Object -> Object(map.piece(other.map))
            is Array, is Leaf -> this  // Object wins cross-type conflict
        }

        /**
         * Unions the [Rga.causalDots] of every [Array] node reachable through this object,
         * recursing through nested [Object] maps. [Leaf] and [ORMap]/[MVRegister] dot spaces
         * do not participate in this GC path.
         */
        override fun causalDots(): Set<Dot> = map.keys
            .mapNotNull { map[it] }
            .flatMap { it.causalDots() }
            .toSet()

        override fun equals(other: Any?): Boolean = other is Object && map == other.map
        override fun hashCode(): Int = map.hashCode()
        override fun toString(): String = "JsonNode.Object($map)"
    }

    /**
     * An RGA-backed JSON array: ordered, position-stable, supports concurrent inserts.
     */
    public class Array(
        public val rga: Rga<JsonNode>,
    ) : JsonNode() {

        override fun piece(other: JsonNode): JsonNode = when (other) {
            is Object -> other           // Object wins cross-type conflict
            is Array -> Array(rga.piece(other.rga))
            is Leaf -> this              // Array wins over Leaf
        }

        /**
         * Returns this array's own [Rga.causalDots] union with the [causalDots] of all
         * [JsonNode] elements stored in the sequence — nested arrays recurse naturally.
         */
        override fun causalDots(): Set<Dot> =
            rga.causalDots() + rga.toList().flatMap { it.causalDots() }.toSet()

        override fun equals(other: Any?): Boolean = other is Array && rga == other.rga
        override fun hashCode(): Int = rga.hashCode()
        override fun toString(): String = "JsonNode.Array($rga)"
    }

    /**
     * An MVRegister-backed JSON leaf: holds the concurrent scalar values, or a
     * single resolved value in the common case.
     */
    public class Leaf(
        public val register: MVRegister<JsonValue>,
    ) : JsonNode() {

        override fun piece(other: JsonNode): JsonNode = when (other) {
            is Object -> other                        // Object wins cross-type conflict
            is Array -> other                         // Array wins over Leaf
            is Leaf -> Leaf(register.piece(other.register))
        }

        // Leaf holds an MVRegister over scalars — scalars carry no Rga dots, so
        // the default empty set from Quilted is correct here.

        override fun equals(other: Any?): Boolean = other is Leaf && register == other.register
        override fun hashCode(): Int = register.hashCode()
        override fun toString(): String = "JsonNode.Leaf($register)"
    }

    public companion object {
        /**
         * Returns a [KSerializer] for [JsonNode] that correctly handles the recursive
         * [ORMap] and [Rga] element types.
         */
        public fun serializer(): KSerializer<JsonNode> = JsonNodeSerializer
    }
}

// ---- Serializer ----

/**
 * Custom [KSerializer] for [JsonNode] following the same discriminated-union pattern
 * as [RgaOpSerializer]: a mandatory integer type tag (`t`) followed by optional
 * variant-specific fields. Only one data field is written per serialized value.
 *
 * Wire fields:
 * - `t` (index 0) — type discriminator: 0 = Object, 1 = Array, 2 = Leaf
 * - `o` (index 1) — [ORMap]`<String, JsonNode>` (Object only)
 * - `a` (index 2) — [Rga]`<JsonNode>` (Array only)
 * - `l` (index 3) — [MVRegister]`<JsonValue>` (Leaf only)
 */
@OptIn(ExperimentalSerializationApi::class)
private object JsonNodeSerializer : KSerializer<JsonNode> {

    private const val TYPE_OBJECT = 0
    private const val TYPE_ARRAY = 1
    private const val TYPE_LEAF = 2

    // Lazy to break the initialization cycle:
    // JsonNodeSerializer → ORMap.serializer(..., JsonNodeSerializer)
    // JsonNodeSerializer → Rga.wireSerializer(JsonNodeSerializer)
    private val omSerializer: KSerializer<ORMap<String, JsonNode>> by lazy {
        ORMap.serializer(String.serializer(), JsonNodeSerializer)
    }
    private val rgaSerializer: KSerializer<Rga<JsonNode>> by lazy {
        Rga.wireSerializer(JsonNodeSerializer)
    }
    private val mvSerializer: KSerializer<MVRegister<JsonValue>> =
        MVRegister.serializer(JsonValue.serializer())

    // A harmless placeholder descriptor used for the three optional variant fields.
    // The real encoding shape is controlled by the serialize/deserialize methods below;
    // the descriptor is used only for field-name/index registration.
    private val placeholder: SerialDescriptor =
        buildClassSerialDescriptor("us.tractat.kuilt.crdt.JsonNode.Data")

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("us.tractat.kuilt.crdt.JsonNode") {
        element("t", Int.serializer().descriptor)
        // The optional variant fields carry placeholder descriptors — the actual codec
        // is threaded through the lazy serializer fields below.
        element("o", placeholder, isOptional = true)
        element("a", placeholder, isOptional = true)
        element("l", placeholder, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: JsonNode): Unit = encoder.encodeStructure(descriptor) {
        when (value) {
            is JsonNode.Object -> {
                encodeIntElement(descriptor, 0, TYPE_OBJECT)
                encodeSerializableElement(descriptor, 1, omSerializer, value.map)
            }
            is JsonNode.Array -> {
                encodeIntElement(descriptor, 0, TYPE_ARRAY)
                encodeSerializableElement(descriptor, 2, rgaSerializer, value.rga)
            }
            is JsonNode.Leaf -> {
                encodeIntElement(descriptor, 0, TYPE_LEAF)
                encodeSerializableElement(descriptor, 3, mvSerializer, value.register)
            }
        }
    }

    override fun deserialize(decoder: Decoder): JsonNode = decoder.decodeStructure(descriptor) {
        var type = -1
        var node: JsonNode? = null

        mainLoop@ while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@mainLoop
                0 -> type = decodeIntElement(descriptor, 0)
                1 -> node = JsonNode.Object(decodeSerializableElement(descriptor, 1, omSerializer))
                2 -> node = JsonNode.Array(decodeSerializableElement(descriptor, 2, rgaSerializer))
                3 -> node = JsonNode.Leaf(decodeSerializableElement(descriptor, 3, mvSerializer))
                else -> throw SerializationException("Unexpected index $index in JsonNode deserializer")
            }
        }

        if (type == -1) throw SerializationException("JsonNode missing type tag 't'")
        node ?: throw SerializationException("JsonNode missing data field for type $type")
    }
}
