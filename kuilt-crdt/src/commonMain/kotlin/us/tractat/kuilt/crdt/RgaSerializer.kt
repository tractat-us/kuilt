package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Custom [KSerializer] for [Rga]`<V>` that uses [RgaOpSerializer] for the op-log field.
 *
 * The compiler-generated `Rga$$serializer` uses the generated `RgaOp$$serializer` which
 * defaults to `PolymorphicSerializer(Any::class)` for the element type [V]. This causes
 * CBOR wire serialization to fail unless the [V] type is explicitly registered in a
 * `SerializersModule`.
 *
 * [RgaSerializer] bypasses this by using [RgaOpSerializer], which correctly threads
 * [vSerializer] through to [RgaOp.Insert.value]. Use [Rga.wireSerializer] to obtain
 * an instance for a given [V].
 *
 * Wire format: `{ "ops": Set<RgaOp<V>>, "lamport": Long }`
 */
@OptIn(ExperimentalSerializationApi::class)
internal class RgaSerializer<V>(vSerializer: KSerializer<V>) : KSerializer<Rga<V>> {

    private val opSerializer: KSerializer<RgaOp<V>> = RgaOpSerializer(vSerializer)
    private val opsSerializer: KSerializer<Set<RgaOp<V>>> = SetSerializer(opSerializer)
    private val longSerializer: KSerializer<Long> = Long.serializer()

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Rga") {
        element("ops", opsSerializer.descriptor)
        element("lamport", longSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: Rga<V>): Unit = encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, 0, opsSerializer, value.ops)
        encodeLongElement(descriptor, 1, value.lamport)
    }

    override fun deserialize(decoder: Decoder): Rga<V> = decoder.decodeStructure(descriptor) {
        var ops: Set<RgaOp<V>>? = null
        var lamport = 0L

        mainLoop@ while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@mainLoop
                0 -> ops = decodeSerializableElement(descriptor, 0, opsSerializer)
                1 -> lamport = decodeLongElement(descriptor, 1)
                else -> error("Unexpected index $index in Rga deserializer")
            }
        }

        Rga.fromOps(ops ?: emptySet(), lamport)
    }
}
