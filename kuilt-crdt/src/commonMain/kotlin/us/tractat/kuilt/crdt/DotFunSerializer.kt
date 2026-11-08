package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom [KSerializer] for [DotFun]`<V>` that emits [DotFun.values] entries sorted by
 * [Dot] key (lexicographically by replica, then by seq), producing identical bytes for
 * any two replicas at the same logical state regardless of delivery order.
 *
 * Wire format: `Map<Dot, V>` — same structural layout as the auto-generated serializer,
 * but with entries emitted in a canonical sorted order.
 *
 * The auto-generated serializer is delivery-order-dependent because [DotFun.join] builds
 * a [LinkedHashMap] whose iteration order depends on which side is `self` vs `other` in
 * the merge. Sorting before encoding makes the wire form a function of the logical value.
 *
 * This is the serializer selected by `@Serializable(with = DotFunSerializer::class)` on
 * [DotFun]. Callers obtain an instance via `DotFun.serializer(valueSerializer)`.
 *
 * **Sort order:** the one canonical order shared with the rest of the dot family (#1964): the
 * encoding is [CanonicalMapSerializer]'s over `Dot` keys, delegated to rather than re-spelled, as
 * [ORMapEntrySerializer] and [LatticeDotFunSerializer] do. It is byte-identical to the
 * `sortedBy { dot }` it replaced — see [DotSetSerializer].
 */
@OptIn(ExperimentalSerializationApi::class)
public class DotFunSerializer<V>(
    private val vSerializer: KSerializer<V>,
) : KSerializer<DotFun<V>> {

    private val mapSerializer = CanonicalMapSerializer(Dot.serializer(), vSerializer)

    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: DotFun<V>) {
        mapSerializer.serialize(encoder, value.values)
    }

    override fun deserialize(decoder: Decoder): DotFun<V> =
        DotFun(mapSerializer.deserialize(decoder))
}
