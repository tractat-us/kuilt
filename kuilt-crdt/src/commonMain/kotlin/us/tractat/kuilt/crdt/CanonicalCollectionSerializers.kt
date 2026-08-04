package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.crdt.internal.sortedByCanonicalKey

/**
 * [KSerializer] for a [Map] that emits entries in **key** order, so that — subject to the
 * precondition below — two replicas at the same logical state produce identical bytes
 * regardless of merge history or host platform.
 *
 * The zoo's map-backed states are merged through `MapMerge`, which builds a [HashMap], whose
 * iteration order is unspecified and differs by platform in kind, not merely in detail: on the
 * JVM it is hash-bucket order, so it tracks the key set and the map's capacity; on Kotlin/Native
 * it is insertion order, so it tracks merge history directly. Neither is a function of the
 * logical value alone, so the same `GCounter` could encode to different bytes on two replicas —
 * or on one replica and its own peer (issue #1957).
 *
 * **Sort order:** by the structural encoding of each key — every [K] is serialized to a
 * sequence of primitive leaves and those sequences compared lexicographically. This works for
 * data classes, inline value classes and compound keys, and is correct where a [toString]-based
 * sort is not: `seq` 2 and 10 order numerically, not as text. See `sortedByCanonicalKey`, the one
 * canonical order shared with the dot family (#752, #1964).
 *
 * It is a total **preorder**, not a total order: two distinct keys whose leaf sequences are
 * identical compare equal, and [sortedWith] is stable, so they retain their input order — for
 * those keys the encoding is history-dependent again.
 *
 * **Precondition — this class canonicalises the key ORDER, nothing else.** The bytes are
 * canonical only if [K] and [V] each serialize canonically in their own right. Two traps:
 *
 * - A key or value reaching an unordered [Set] or [Map] field through a non-canonical
 *   serializer is not canonical, and neither is the whole. Two *equal* keys of a type like
 *   `data class Key(val tags: Set<String>)` encode to different bytes.
 * - **Values are passed through `vSerializer` untouched.** A `Map<String, GCounter>` is
 *   canonical here only once `GCounter` itself encodes canonically; wrapping the outer map is
 *   not sufficient.
 *
 * **[K] may be `@Contextual` or polymorphic.** The sort reads the *format's* `SerializersModule`
 * off the encoder and hands it to the internal leaf encoder, so a key resolves in the sort exactly
 * as it resolves in the format (#2035). It contributes its class discriminator as an ordinary leaf,
 * so polymorphic keys order by discriminator first and payload second.
 *
 * Wire format is unchanged — the same map layout, with entries reordered.
 */
@OptIn(ExperimentalSerializationApi::class)
public class CanonicalMapSerializer<K, V>(
    private val kSerializer: KSerializer<K>,
    vSerializer: KSerializer<V>,
) : KSerializer<Map<K, V>> {

    private val mapSerializer = MapSerializer(kSerializer, vSerializer)

    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Map<K, V>) {
        mapSerializer.serialize(encoder, value.sortedByCanonicalKey(kSerializer, encoder.serializersModule))
    }

    override fun deserialize(decoder: Decoder): Map<K, V> = mapSerializer.deserialize(decoder)
}

/**
 * [KSerializer] for a [Set] that emits elements in **element** order, so that — subject to the
 * precondition below — two replicas at the same logical state produce identical bytes regardless
 * of merge order.
 *
 * `GSet.piece` is `elements + other.elements`, which yields a `LinkedHashSet` in merge order —
 * so the auto-generated serializer encoded the same logical set differently depending on which
 * side of the join a replica saw first (issue #1957).
 *
 * Wire format: a **list**, matching `DotSetSerializer` — [ListSerializer] preserves the sorted
 * order and every format encodes a list as an array.
 *
 * Sort order, and its total-preorder caveat, are as described on [CanonicalMapSerializer].
 *
 * **Precondition — this class canonicalises the element ORDER, nothing else.** The bytes are
 * canonical only if [E] itself serializes canonically: an [E] that reaches an unordered [Set] or
 * [Map] field through a non-canonical serializer makes the whole encoding non-canonical, however
 * this class orders the elements around it.
 *
 * **[E] may be `@Contextual` or polymorphic**, on the same terms as [CanonicalMapSerializer]'s
 * key (#2035).
 */
@OptIn(ExperimentalSerializationApi::class)
public class CanonicalSetSerializer<E>(
    private val eSerializer: KSerializer<E>,
) : KSerializer<Set<E>> {

    private val listSerializer = ListSerializer(eSerializer)

    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Set<E>) {
        listSerializer.serialize(encoder, value.sortedByCanonicalKey(eSerializer, encoder.serializersModule))
    }

    override fun deserialize(decoder: Decoder): Set<E> =
        listSerializer.deserialize(decoder).toSet()
}
