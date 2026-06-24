package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.crdt.internal.serialKeyComparator

/**
 * Custom [KSerializer] for [DotMap]`<K, S>` that emits [DotMap.entries] in a canonical
 * order, producing identical bytes for any two replicas at the same logical state
 * regardless of delivery order (issue #713).
 *
 * Wire format: `Map<K, S>` — same structural layout as the auto-generated serializer,
 * but with entries emitted in a canonical sorted order.
 *
 * **Sort order:** entries are sorted by the structural encoding of their key: each [K]
 * is serialized to a sequence of primitive leaf values via its [KSerializer], and those
 * sequences are compared lexicographically.  This produces a deterministic order for any
 * serializable key type — including data classes, inline value classes, and compound keys
 * — and is robust where a [toString]-based sort is not: [Double] (`-0.0`/`NaN`), [ByteArray]
 * (identity hash), and any type whose [toString] is not injective or platform-stable would
 * silently produce a non-canonical sort.  See [serialKeyComparator] (issue #752).
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
    private val keyComparator: Comparator<K> = serialKeyComparator(kSerializer)

    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: DotMap<K, S>) {
        val sorted = value.entries.entries
            .sortedWith(compareBy(keyComparator) { (key, _) -> key })
            .associate { (key, v) -> key to v }
        mapSerializer.serialize(encoder, sorted)
    }

    override fun deserialize(decoder: Decoder): DotMap<K, S> =
        DotMap(mapSerializer.deserialize(decoder))
}
