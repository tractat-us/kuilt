package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.crdt.internal.serialKeyComparator

/**
 * [KSerializer] for a [Map] that emits entries in a canonical order, so two replicas at the
 * same logical state produce identical bytes regardless of merge history or host platform.
 *
 * The zoo's map-backed states are merged through `MapMerge`, which builds a [HashMap] — its
 * iteration order is hash-bucket order, a per-platform implementation detail. A `GCounter`
 * holding `{r1:1, r2:1, r3:1}` encoded its keys `r2, r3, r1` on the JVM and `r1, r2, r3` on
 * Kotlin/Native before this serializer existed (issue #1957).
 *
 * **Sort order:** by the structural encoding of each key — every [K] is serialized to a
 * sequence of primitive leaves and those sequences compared lexicographically. That is a total
 * order for any serializable key, including data classes, inline value classes and compound
 * keys, and is correct where a [toString]-based sort is not. See `serialKeyComparator` (#752).
 *
 * Wire format is unchanged — the same map layout, with entries reordered.
 */
@OptIn(ExperimentalSerializationApi::class)
public class CanonicalMapSerializer<K, V>(
    kSerializer: KSerializer<K>,
    vSerializer: KSerializer<V>,
) : KSerializer<Map<K, V>> {

    private val mapSerializer = MapSerializer(kSerializer, vSerializer)
    private val keyComparator: Comparator<K> = serialKeyComparator(kSerializer)

    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Map<K, V>) {
        val sorted = LinkedHashMap<K, V>(value.size)
        for (key in value.keys.sortedWith(keyComparator)) {
            sorted[key] = value.getValue(key)
        }
        mapSerializer.serialize(encoder, sorted)
    }

    override fun deserialize(decoder: Decoder): Map<K, V> = mapSerializer.deserialize(decoder)
}

/**
 * [KSerializer] for a [Set] that emits elements in a canonical order, so two replicas at the
 * same logical state produce identical bytes regardless of merge order.
 *
 * `GSet.piece` is `elements + other.elements`, which yields a `LinkedHashSet` in merge order —
 * so the auto-generated serializer encoded the same logical set differently depending on which
 * side of the join a replica saw first (issue #1957).
 *
 * Wire format: a **list**, matching `DotSetSerializer` — [ListSerializer] preserves the sorted
 * order and every format encodes a list as an array.
 *
 * Sort order is the structural key order described on [CanonicalMapSerializer].
 */
@OptIn(ExperimentalSerializationApi::class)
public class CanonicalSetSerializer<E>(
    eSerializer: KSerializer<E>,
) : KSerializer<Set<E>> {

    private val listSerializer = ListSerializer(eSerializer)
    private val elementComparator: Comparator<E> = serialKeyComparator(eSerializer)

    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Set<E>) {
        listSerializer.serialize(encoder, value.sortedWith(elementComparator))
    }

    override fun deserialize(decoder: Decoder): Set<E> =
        listSerializer.deserialize(decoder).toSet()
}
