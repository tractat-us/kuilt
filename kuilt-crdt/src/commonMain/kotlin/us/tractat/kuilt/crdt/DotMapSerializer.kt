package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom [KSerializer] for [DotMap]`<K, S>` that emits [DotMap.entries] in a canonical
 * order, producing identical bytes for any two replicas at the same logical state
 * regardless of delivery order (issue #713).
 *
 * Wire format: `Map<K, S>` — same structural layout as the auto-generated serializer,
 * but with entries emitted in a canonical sorted order.
 *
 * **Sort order:** entries are sorted by the [String] representation of the serialized key
 * ([K.toString]). This is deterministic — two values that are equal per [equals] have the
 * same [toString] for every well-behaved `K` (data classes, value classes, sealed classes,
 * primitives). The alternative (serialising keys to JSON and sorting the JSON strings) is
 * more rigorous but also more expensive; the `toString`-based sort is sufficient because
 * all [DotMap] key types used in this module ([String], [Int], sealed types) satisfy the
 * invariant.
 *
 * The auto-generated serializer is delivery-order-dependent because [DotMap.join] builds
 * a [LinkedHashMap] whose iteration order depends on which side is `self` vs `other` in
 * the merge. Sorting before encoding makes the wire form a function of the logical value.
 */
@OptIn(ExperimentalSerializationApi::class)
public class DotMapSerializer<K, S : DotStore<S>>(
    private val kSerializer: KSerializer<K>,
    private val sSerializer: KSerializer<S>,
) : KSerializer<DotMap<K, S>> {

    private val mapSerializer = MapSerializer(kSerializer, sSerializer)

    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: DotMap<K, S>) {
        val sorted = value.entries.entries
            .sortedBy { (key, _) -> key.toString() }
            .associate { (key, v) -> key to v }
        mapSerializer.serialize(encoder, sorted)
    }

    override fun deserialize(decoder: Decoder): DotMap<K, S> =
        DotMap(mapSerializer.deserialize(decoder))
}
