package us.tractat.kuilt.crdt.internal

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Returns a [Comparator] that orders values of type [K] by their canonical serialized form.
 *
 * Each key is serialized to a [List] of primitive leaf values via [PrimitiveLeafEncoder]
 * and the lists are compared lexicographically.  This produces a total, stable, structural
 * order for any [K] that serializes to a finite, deterministic sequence of primitives —
 * which includes every data class, value class, and primitive key used in this module.
 *
 * This is the correct replacement for a `.sortedBy { key.toString() }` comparator whose
 * correctness depended on `toString` being injective and platform-stable — a guarantee
 * that does not hold for [Double] (`-0.0`/`NaN`), [ByteArray] (identity hash), or any
 * compound type whose `toString` omits fields (issue #752).
 *
 * **Cost — prefer [serialLeaves] + [leafListComparator] when sorting.**  This comparator
 * re-serializes *both* operands on every call, so a `sortedWith` over n keys performs
 * ~2·n·log n serializations rather than n.  Callers that sort should decorate each element
 * with its [serialLeaves] once, sort on the decoration with [leafListComparator], and drop
 * it — which is what [us.tractat.kuilt.crdt.CanonicalSetSerializer] and
 * [us.tractat.kuilt.crdt.CanonicalMapSerializer] do.  This form remains for callers that
 * genuinely need a `Comparator<K>` (see `DotMapSerializer`, and issue #1964 for collapsing
 * those onto the decorated path).
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <K> serialKeyComparator(kSerializer: KSerializer<K>): Comparator<K> =
    Comparator { a, b -> leafListComparator.compare(serialLeaves(a, kSerializer), serialLeaves(b, kSerializer)) }

/**
 * Serializes [key] to its sequence of primitive leaf values — the decoration half of a
 * decorate-sort-undecorate over [leafListComparator].
 */
internal fun <K> serialLeaves(key: K, kSerializer: KSerializer<K>): List<Any?> {
    val encoder = PrimitiveLeafEncoder()
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
 * This is intentionally narrow: it is only valid for key types that serialize to a
 * finite sequence of primitives with no polymorphism.  Using it with a polymorphic
 * or nullable serializer that emits class discriminators or special null markers
 * will produce a valid (though possibly unexpected) leaf sequence.
 */
@OptIn(ExperimentalSerializationApi::class)
private class PrimitiveLeafEncoder : AbstractEncoder() {
    val leaves = mutableListOf<Any?>()

    override val serializersModule: SerializersModule = EmptySerializersModule()

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
