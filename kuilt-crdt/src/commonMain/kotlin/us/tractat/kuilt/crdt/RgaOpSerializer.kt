package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Custom [KSerializer] for [RgaOp]`<V>` that correctly threads [vSerializer] through to
 * [RgaOp.Insert.value], bypassing the limitation in the compiler-generated sealed-class
 * serializer where the `V` type parameter defaults to `PolymorphicSerializer(Any::class)`.
 *
 * Use [Rga.serializer] (which wraps this) rather than constructing this directly.
 *
 * Wire format: a CBOR/JSON map with a mandatory `t` (type discriminator) integer field
 * and variant-specific additional fields:
 *
 * - Insert: `{ "t": 0, "id": RgaId, "v": V, "a": RgaId }`
 * - Remove: `{ "t": 1, "id": RgaId }`
 * - Compact: `{ "t": 2, "pos": Map<RgaId, RgaId> }`
 *
 * @param vSerializer the serializer for the element type [V].
 */
@OptIn(ExperimentalSerializationApi::class)
public class RgaOpSerializer<V>(
    private val vSerializer: KSerializer<V>,
) : KSerializer<RgaOp<V>> {

    private val rgaIdSerializer: KSerializer<RgaId> = RgaId.serializer()
    private val positionsSerializer: KSerializer<Map<RgaId, RgaId>> = MapSerializer(rgaIdSerializer, rgaIdSerializer)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("us.tractat.kuilt.crdt.RgaOp") {
        element<Int>("t")         // 0 = Insert, 1 = Remove, 2 = Compact
        element("id", rgaIdSerializer.descriptor, isOptional = true)          // Insert + Remove
        element("v", vSerializer.descriptor, isOptional = true)               // Insert only
        element("a", rgaIdSerializer.descriptor, isOptional = true)           // Insert only
        element("pos", positionsSerializer.descriptor, isOptional = true)    // Compact only
    }

    override fun serialize(encoder: Encoder, value: RgaOp<V>): Unit = encoder.encodeStructure(descriptor) {
        when (value) {
            is RgaOp.Insert -> {
                encodeIntElement(descriptor, 0, TYPE_INSERT)
                encodeSerializableElement(descriptor, 1, rgaIdSerializer, value.id)
                encodeSerializableElement(descriptor, 2, vSerializer, value.value)
                encodeSerializableElement(descriptor, 3, rgaIdSerializer, value.after)
            }
            is RgaOp.Remove -> {
                encodeIntElement(descriptor, 0, TYPE_REMOVE)
                encodeSerializableElement(descriptor, 1, rgaIdSerializer, value.id)
            }
            is RgaOp.Compact -> {
                encodeIntElement(descriptor, 0, TYPE_COMPACT)
                encodeSerializableElement(descriptor, 4, positionsSerializer, value.positions)
            }
        }
    }

    override fun deserialize(decoder: Decoder): RgaOp<V> = decoder.decodeStructure(descriptor) {
        var type = -1
        var id: RgaId? = null
        @Suppress("UNCHECKED_CAST")
        var value: V = null as V
        var hasValue = false
        var after: RgaId? = null
        var positions: Map<RgaId, RgaId>? = null

        mainLoop@ while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@mainLoop
                0 -> type = decodeIntElement(descriptor, 0)
                1 -> id = decodeSerializableElement(descriptor, 1, rgaIdSerializer)
                2 -> { value = decodeSerializableElement(descriptor, 2, vSerializer); hasValue = true }
                3 -> after = decodeSerializableElement(descriptor, 3, rgaIdSerializer)
                4 -> positions = decodeSerializableElement(descriptor, 4, positionsSerializer)
                else -> throw SerializationException("Unexpected index: $index")
            }
        }

        when (type) {
            TYPE_INSERT -> RgaOp.Insert(
                id = id ?: missingField("Insert", "id"),
                value = if (hasValue) value else missingField("Insert", "value"),
                after = after ?: missingField("Insert", "after"),
            )
            TYPE_REMOVE -> RgaOp.Remove(id = id ?: missingField("Remove", "id"))
            TYPE_COMPACT -> RgaOp.Compact(positions = positions ?: missingField("Compact", "pos"))
            else -> throw SerializationException("Unknown RgaOp type discriminator: $type")
        }
    }

    private fun <T> missingField(variant: String, field: String): T =
        throw SerializationException("$variant missing required field '$field'")

    private companion object {
        const val TYPE_INSERT = 0
        const val TYPE_REMOVE = 1
        const val TYPE_COMPACT = 2
    }
}
