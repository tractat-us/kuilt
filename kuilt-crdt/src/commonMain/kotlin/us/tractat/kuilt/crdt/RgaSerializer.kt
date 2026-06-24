package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
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
 * Ops are emitted in canonical [RgaId] ascending order so that two replicas holding the
 * same logical state (same op set, different delivery order) produce identical bytes.
 * This mirrors the fix applied to [FugueSerializer] (issue #713).
 *
 * Wire format: `{ "ops": List<RgaOp<V>> }`
 *
 * Note: the field "ops" is encoded as a List (not a Set) so that the canonical sort order
 * is preserved in the wire encoding. Decoders reconstruct the Set by reading the list —
 * the semantic meaning is unchanged (an op-log is a set of unique ops).
 *
 * The [Rga.lamport] high-water is **not** encoded on the wire. On decode it is derived
 * from the op-set as `max(op.id.lamport)` over all ops (Insert/Remove ids are real Rga
 * ids; Compact positions.keys are the ids of compacted Inserts). This makes the serialized
 * form a pure function of the logical ([equals]) value, satisfying the content-addressing
 * and Quilter delta-fingerprinting invariant (issue #779).
 */
@OptIn(ExperimentalSerializationApi::class)
internal class RgaSerializer<V>(vSerializer: KSerializer<V>) : KSerializer<Rga<V>> {

    private val opSerializer: KSerializer<RgaOp<V>> = RgaOpSerializer(vSerializer)
    private val opsSetSerializer: KSerializer<Set<RgaOp<V>>> = SetSerializer(opSerializer)
    private val opsListSerializer: KSerializer<List<RgaOp<V>>> = ListSerializer(opSerializer)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Rga") {
        element("ops", opsListSerializer.descriptor)
    }

    /**
     * Sort ops in a canonical, delivery-order-independent order before encoding:
     * - [RgaOp.Insert] and [RgaOp.Remove] sort by their [RgaId] ascending.
     * - [RgaOp.Compact] ops sort last (they carry no id of their own).
     * - Multiple [RgaOp.Compact] ops sort by the full sorted [RgaOp.Compact.positions] key-list,
     *   compared lexicographically, to achieve a deterministic order even under a malformed
     *   remote that violates the disjoint-keys invariant (see [compareCompactOps]).
     *
     * [Rga.lamport] is omitted from the wire; it is derived on decode via [deriveLamport].
     */
    override fun serialize(encoder: Encoder, value: Rga<V>): Unit = encoder.encodeStructure(descriptor) {
        val sortedOps = value.ops.sortedWith(opComparator())
        encodeSerializableElement(descriptor, 0, opsListSerializer, sortedOps)
    }

    override fun deserialize(decoder: Decoder): Rga<V> = decoder.decodeStructure(descriptor) {
        var ops: Set<RgaOp<V>>? = null

        mainLoop@ while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@mainLoop
                0 -> ops = decodeSerializableElement(descriptor, 0, opsListSerializer).toSet()
                else -> error("Unexpected index $index in Rga deserializer")
            }
        }

        val decodedOps = ops ?: emptySet()
        Rga.fromOps(decodedOps, deriveLamport(decodedOps))
    }

    companion object {
        /**
         * Derive the Lamport high-water from the op-set alone.
         *
         * - [RgaOp.Insert] and [RgaOp.Remove] carry real [RgaId]s whose [RgaId.lamport]
         *   values bound the clock.
         * - [RgaOp.Compact] carries no id of its own but its [RgaOp.Compact.positions] keys
         *   are the real [RgaId]s of compacted Inserts — those must be included so the derived
         *   clock covers compacted state.
         * - Empty op-set → 0L (same as the initial value in [Rga.empty]).
         */
        internal fun <V> deriveLamport(ops: Set<RgaOp<V>>): Long =
            ops.flatMap { op ->
                when (op) {
                    is RgaOp.Insert -> listOf(op.id.lamport)
                    is RgaOp.Remove<*> -> listOf(op.id.lamport)
                    is RgaOp.Compact -> op.positions.keys.map { it.lamport }
                }
            }.maxOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    /**
     * A [Comparator] that orders [RgaOp]s canonically:
     * Insert and Remove ops sort by their [RgaId]; Compact ops sort last, then by
     * the full sorted key-list of their [RgaOp.Compact.positions] maps.
     *
     * **Compact tiebreak rationale:** surviving [RgaOp.Compact] ops on a well-formed
     * replica have disjoint [RgaOp.Compact.positions] key-sets (each [RgaId] is
     * compacted into at most one [Compact] op).  Under that invariant,
     * `positions.keys.minOrNull()` is sufficient — no two Compact ops share the same
     * minimum key, so there are no ties.  To guard against a malformed remote that
     * violates the invariant (where `minOrNull()` could tie and fall back to
     * set-iteration order — the exact nondeterminism #713 fixed), the comparator
     * walks the full sorted key-list until it finds a difference.  This is O(N) in
     * the number of compacted ids, but compact ops are rare and small in practice.
     */
    private fun opComparator(): Comparator<RgaOp<*>> = Comparator { a, b ->
        val typeA = opTypeOrdinal(a)
        val typeB = opTypeOrdinal(b)
        if (typeA != typeB) return@Comparator typeA - typeB
        when (a) {
            is RgaOp.Insert -> a.id.compareTo((b as RgaOp.Insert).id)
            is RgaOp.Remove<*> -> a.id.compareTo((b as RgaOp.Remove<*>).id)
            is RgaOp.Compact -> compareCompactOps(a, b as RgaOp.Compact)
        }
    }

    private fun compareCompactOps(a: RgaOp.Compact, b: RgaOp.Compact): Int {
        val keysA = a.positions.keys.sorted()
        val keysB = b.positions.keys.sorted()
        val minLen = minOf(keysA.size, keysB.size)
        for (i in 0 until minLen) {
            val cmp = keysA[i].compareTo(keysB[i])
            if (cmp != 0) return cmp
        }
        return keysA.size - keysB.size
    }

    /** Ordinal used for inter-type ordering: Insert=0, Remove=1, Compact=2. */
    private fun opTypeOrdinal(op: RgaOp<*>): Int = when (op) {
        is RgaOp.Insert -> 0
        is RgaOp.Remove<*> -> 1
        is RgaOp.Compact -> 2
    }
}
