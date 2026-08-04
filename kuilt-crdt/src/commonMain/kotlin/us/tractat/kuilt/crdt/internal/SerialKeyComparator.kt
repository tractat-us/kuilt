package us.tractat.kuilt.crdt.internal

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule

/**
 * Sorts by canonical serialized form — the ordering **every** canonical serializer in this
 * module uses, so the sort lives in one place rather than being re-derived per type (#1964).
 *
 * Each element is serialized to a [List] of primitive leaf values via [PrimitiveLeafEncoder]
 * and the lists are compared lexicographically.  This produces a structural total **preorder**
 * — not a total order; see [leafListComparator] — for any [T] that serializes to a finite,
 * deterministic sequence of primitives, which includes every data class, value class and
 * primitive key used in this module.
 *
 * It is the correct replacement for a `.sortedBy { it.toString() }` sort whose correctness
 * depended on `toString` being injective and platform-stable — a guarantee that does not hold
 * for [Double] (`-0.0`/`NaN`), [ByteArray] (identity hash), or any compound type whose
 * `toString` omits fields (issue #752).
 *
 * [module] is the **format's** [SerializersModule], read off the encoder at `serialize` time and
 * handed to [PrimitiveLeafEncoder] so a `@Contextual` or open-polymorphic key resolves here exactly
 * as it would in the format itself (#2035).  It is a required parameter rather than one defaulting
 * to an empty module: a default would let a new call site silently reintroduce that gap, and every
 * caller already holds an [kotlinx.serialization.encoding.Encoder].
 *
 * **Decorate-sort-undecorate: each element is serialized exactly once.**  A comparator that
 * re-serializes both operands on every call costs ~2·n·log n serializations for n elements,
 * each allocating a fresh encoder and leaf list; decorating up front makes it n.  The two are
 * byte-identical because this is the same [leafListComparator] over the same [serialLeaves]
 * and [sortedWith] is stable, so elements with identical leaf sequences keep their input order
 * — pinned by `tiedElementsKeepInputOrder`.
 */
internal fun <T> Iterable<T>.sortedByCanonicalKey(
    serializer: KSerializer<T>,
    module: SerializersModule,
): List<T> =
    map { it to serialLeaves(it, serializer, module) }
        .sortedWith(compareBy(leafListComparator) { it.second })
        .map { it.first }

/**
 * The [Map] form of [sortedByCanonicalKey]: the same entries in canonical **key** order, in a
 * [LinkedHashMap] so that order survives to the encoder.
 *
 * Key and value are read out of each entry eagerly, before the sort, so no assumption is made
 * about whether a given platform's `entries` iterator hands back distinct entry objects.
 */
internal fun <K, V> Map<K, V>.sortedByCanonicalKey(
    kSerializer: KSerializer<K>,
    module: SerializersModule,
): Map<K, V> {
    val decorated = map { (key, value) -> Triple(serialLeaves(key, kSerializer, module), key, value) }
        .sortedWith(compareBy(leafListComparator) { it.first })
    val sorted = LinkedHashMap<K, V>(size)
    decorated.forEach { (_, key, value) -> sorted[key] = value }
    return sorted
}

/**
 * Serializes [key] to its sequence of primitive leaf values — the decoration half of a
 * decorate-sort-undecorate over [leafListComparator].
 *
 * [module] is the format's own [SerializersModule]; see [sortedByCanonicalKey].
 */
internal fun <K> serialLeaves(key: K, kSerializer: KSerializer<K>, module: SerializersModule): List<Any?> {
    val encoder = PrimitiveLeafEncoder(module)
    kSerializer.serialize(encoder, key)
    return encoder.leaves
}

/**
 * Lexicographic order over the leaf sequences produced by [serialLeaves]; shorter sequences
 * sort first when one is a prefix of the other.
 *
 * A total **preorder**, not a total order: two distinct keys with identical leaf sequences
 * compare equal.  Both `sortedWith` and `sortedBy` are stable, so such keys retain their
 * input order — the property that keeps the decorated sort byte-identical to the
 * comparator-based one it replaced.
 */
internal val leafListComparator: Comparator<List<Any?>> = Comparator(::compareSerialKeys)

@Suppress("UNCHECKED_CAST")
private fun compareSerialKeys(a: List<Any?>, b: List<Any?>): Int {
    val minLen = minOf(a.size, b.size)
    for (i in 0 until minLen) {
        val cmp = compareLeaves(a[i], b[i])
        if (cmp != 0) return cmp
    }
    return a.size - b.size
}

private fun compareLeaves(a: Any?, b: Any?): Int = when {
    a == null && b == null -> 0
    a == null -> -1
    b == null -> 1
    a is Boolean && b is Boolean -> a.compareTo(b)
    a is Byte && b is Byte -> a.compareTo(b)
    a is Short && b is Short -> a.compareTo(b)
    a is Int && b is Int -> a.compareTo(b)
    a is Long && b is Long -> a.compareTo(b)
    a is Float && b is Float -> a.compareTo(b)
    a is Double && b is Double -> a.compareTo(b)
    a is Char && b is Char -> a.compareTo(b)
    a is String && b is String -> a.compareTo(b)
    else -> a.toString().compareTo(b.toString())
}

/**
 * A minimal [AbstractEncoder] that captures every primitive leaf value emitted by
 * a [KSerializer] into [leaves].  Structural delimiters (begin/end class, list, map)
 * are accepted silently — only the scalar payload values are collected.
 *
 * [serializersModule] is the **format's** module, not an empty one (#2035).  A
 * `ContextualSerializer` or `PolymorphicSerializer` resolves its delegate by reading
 * `encoder.serializersModule`, so with an empty module a key the format itself encodes
 * perfectly well threw here instead — the sort was strictly narrower than the format
 * wrapped around it.
 *
 * What stays narrow is the *shape* of the leaf sequence, not which serializers resolve:
 * a polymorphic key contributes its class-discriminator string as an ordinary leaf, so
 * such keys order by discriminator first and payload second.  That is a well-defined
 * order, merely not the one a reader assuming "sorted by payload" would guess.
 */
@OptIn(ExperimentalSerializationApi::class)
private class PrimitiveLeafEncoder(
    override val serializersModule: SerializersModule,
) : AbstractEncoder() {
    val leaves = mutableListOf<Any?>()

    override fun encodeBoolean(value: Boolean) { leaves += value }
    override fun encodeByte(value: Byte) { leaves += value }
    override fun encodeShort(value: Short) { leaves += value }
    override fun encodeInt(value: Int) { leaves += value }
    override fun encodeLong(value: Long) { leaves += value }
    override fun encodeFloat(value: Float) { leaves += value }
    override fun encodeDouble(value: Double) { leaves += value }
    override fun encodeChar(value: Char) { leaves += value }
    override fun encodeString(value: String) { leaves += value }
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) { leaves += index }
    override fun encodeNull() { leaves += null }

    override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
        leaves += collectionSize
        return this
    }
}
