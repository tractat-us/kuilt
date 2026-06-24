package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Custom [KSerializer] for [DotContext] that emits [DotContext.vv] entries sorted by
 * [ReplicaId] and [DotContext.cloud] sorted by [Dot], producing identical bytes for
 * any two replicas at the same logical causal state regardless of delivery order.
 *
 * Wire format: `{ "vv": Map<ReplicaId, Long>, "cloud": List<Dot> }`
 *
 * The auto-generated serializer is delivery-order-dependent because `vv` is backed
 * by a [HashMap] (hash-order iteration) and `cloud` is a [Set] (insertion-order for
 * [LinkedHashSet], hash-order for [HashSet]). This serializer sorts both before
 * encoding, making the wire form a function of the logical value, not delivery history.
 *
 * On decode, [DotContext.fromParts] reconstructs the internal representation directly
 * from the deserialized `vv` map and `cloud` list, bypassing the public [DotContext.add]
 * path (which would be O(N·seq) for large version vectors).
 */
@OptIn(ExperimentalSerializationApi::class)
internal class DotContextSerializer : KSerializer<DotContext> {

    private val vvSerializer = MapSerializer(ReplicaId.serializer(), Long.serializer())
    private val cloudSerializer = ListSerializer(Dot.serializer())

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("DotContext") {
        element("vv", vvSerializer.descriptor)
        element("cloud", cloudSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: DotContext): Unit =
        encoder.encodeStructure(descriptor) {
            val sortedVv = value.vv.entries
                .sortedBy { (replica, _) -> replica }
                .associate { (replica, seq) -> replica to seq }
            encodeSerializableElement(descriptor, 0, vvSerializer, sortedVv)
            encodeSerializableElement(descriptor, 1, cloudSerializer, value.cloud.sorted())
        }

    override fun deserialize(decoder: Decoder): DotContext = decoder.decodeStructure(descriptor) {
        var vv: Map<ReplicaId, Long> = emptyMap()
        var cloud: List<Dot> = emptyList()

        mainLoop@ while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@mainLoop
                0 -> vv = decodeSerializableElement(descriptor, 0, vvSerializer)
                1 -> cloud = decodeSerializableElement(descriptor, 1, cloudSerializer)
                else -> error("Unexpected index $index in DotContext deserializer")
            }
        }

        DotContext.fromParts(vv, cloud.toSet())
    }
}
