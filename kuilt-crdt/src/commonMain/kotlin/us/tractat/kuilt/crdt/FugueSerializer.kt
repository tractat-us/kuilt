package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Custom [KSerializer] for [Fugue]`<V>` that threads [vSerializer] correctly
 * through the op-log.
 *
 * Wire format: `{ "ops": List<FugueOp<V>> }`
 *
 * The [Fugue.lamport] high-water is **not** encoded on the wire. On decode it is
 * derived from the op-set as `max(op.id.lamport)` over all ops (Insert/Remove ids
 * are real Fugue ids; Compact positions.keys are the ids of compacted Inserts).
 * This makes the serialized form a pure function of the logical ([equals]) value:
 * two replicas with the same op-set but different clock high-waters produce identical
 * bytes, satisfying the content-addressing and Quilter delta-fingerprinting invariant
 * (issue #779 / #713).
 *
 * Use [Fugue.wireSerializer] to obtain an instance.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class FugueSerializer<V>(vSerializer: KSerializer<V>) : KSerializer<Fugue<V>> {

    private val opSerializer: KSerializer<FugueOp<V>> = FugueOpSerializer(vSerializer)
    private val opsSerializer: KSerializer<Set<FugueOp<V>>> = SetSerializer(opSerializer)
    private val opsListSerializer: KSerializer<List<FugueOp<V>>> = ListSerializer(opSerializer)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Fugue") {
        element("ops", opsSerializer.descriptor)
    }

    /**
     * Serialize ops in canonical [FugueId] ascending order so that two replicas
     * holding the same logical state (same op set, different delivery order) produce
     * identical bytes. [FugueId] is [Comparable] — ascending lamport, then replicaId.
     *
     * [FugueOp.id] is available on the sealed interface, so the sort key is uniform
     * regardless of op variant. [FugueOp.Compact] uses [FugueId.HEAD] as its sentinel
     * id and thus sorts before all real Insert/Remove ops — stable and deterministic.
     *
     * [Fugue.lamport] is omitted from the wire; it is derived on decode via
     * [deriveLamport].
     */
    override fun serialize(encoder: Encoder, value: Fugue<V>): Unit = encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, 0, opsListSerializer, value.sortedOps)
    }

    override fun deserialize(decoder: Decoder): Fugue<V> = decoder.decodeStructure(descriptor) {
        var ops: Set<FugueOp<V>>? = null

        mainLoop@ while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@mainLoop
                0 -> ops = decodeSerializableElement(descriptor, 0, opsSerializer)
                else -> error("Unexpected index $index in Fugue deserializer")
            }
        }

        val decodedOps = ops ?: emptySet()
        Fugue.fromOps(decodedOps, deriveLamport(decodedOps))
    }

    companion object {
        /**
         * Derive the Lamport high-water from the op-set alone.
         *
         * - [FugueOp.Insert] and [FugueOp.Remove] carry real [FugueId]s whose [FugueId.lamport]
         *   values bound the clock.
         * - [FugueOp.Compact] uses [FugueId.HEAD] as its own [FugueOp.id] (lamport = [Long.MIN_VALUE])
         *   but its [FugueOp.Compact.positions] keys are the real [FugueId]s of compacted Inserts —
         *   those must be included so the derived clock covers compacted state.
         * - Empty op-set → 0L (same as the initial value in [Fugue.empty]).
         */
        internal fun <V> deriveLamport(ops: Set<FugueOp<V>>): Long =
            ops.flatMap { op ->
                when (op) {
                    is FugueOp.Insert -> listOf(op.id.lamport)
                    is FugueOp.Remove -> listOf(op.id.lamport)
                    is FugueOp.Compact -> op.positions.keys.map { it.lamport }
                }
            }.maxOrNull()?.coerceAtLeast(0L) ?: 0L
    }
}

/**
 * Custom [KSerializer] for [FugueOp]`<V>` that threads [vSerializer] through
 * [FugueOp.Insert.value].
 *
 * Wire format (a map with mandatory type discriminator `t`):
 * - Insert: `{ "t": 0, "id": FugueId, "v": V, "p": FugueId, "s": FugueSide, "ro": FugueId? }`
 * - Remove: `{ "t": 1, "id": FugueId }`
 * - Compact: `{ "t": 2, "pos": Map<FugueId, FugueId> }`
 */
@OptIn(ExperimentalSerializationApi::class)
internal class FugueOpSerializer<V>(
    private val vSerializer: KSerializer<V>,
) : KSerializer<FugueOp<V>> {

    private val idSerializer: KSerializer<FugueId> = FugueId.serializer()
    private val sideSerializer: KSerializer<FugueSide> = FugueSide.serializer()
    private val positionsSerializer: KSerializer<Map<FugueId, FugueId>> =
        MapSerializer(idSerializer, idSerializer)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("us.tractat.kuilt.crdt.FugueOp") {
        element<Int>("t")                                                            // 0=Insert, 1=Remove, 2=Compact
        element("id", idSerializer.descriptor, isOptional = true)                    // Insert + Remove
        element("v", vSerializer.descriptor, isOptional = true)                      // Insert only
        element("p", idSerializer.descriptor, isOptional = true)                     // Insert: parent
        element("s", sideSerializer.descriptor, isOptional = true)                   // Insert: side
        element("ro", idSerializer.descriptor, isOptional = true)                    // Insert: rightOrigin (R-side only)
        element("pos", positionsSerializer.descriptor, isOptional = true)            // Compact only
    }

    override fun serialize(encoder: Encoder, value: FugueOp<V>): Unit = encoder.encodeStructure(descriptor) {
        when (value) {
            is FugueOp.Insert -> {
                encodeIntElement(descriptor, 0, TYPE_INSERT)
                encodeSerializableElement(descriptor, 1, idSerializer, value.id)
                encodeSerializableElement(descriptor, 2, vSerializer, value.value)
                encodeSerializableElement(descriptor, 3, idSerializer, value.parent)
                encodeSerializableElement(descriptor, 4, sideSerializer, value.side)
                if (value.rightOrigin != null) {
                    encodeSerializableElement(descriptor, 5, idSerializer, value.rightOrigin)
                }
            }
            is FugueOp.Remove -> {
                encodeIntElement(descriptor, 0, TYPE_REMOVE)
                encodeSerializableElement(descriptor, 1, idSerializer, value.id)
            }
            is FugueOp.Compact -> {
                encodeIntElement(descriptor, 0, TYPE_COMPACT)
                encodeSerializableElement(descriptor, 6, positionsSerializer, value.positions)
            }
        }
    }

    override fun deserialize(decoder: Decoder): FugueOp<V> = decoder.decodeStructure(descriptor) {
        var type = -1
        var id: FugueId? = null
        @Suppress("UNCHECKED_CAST")
        var value: V = null as V
        var hasValue = false
        var parent: FugueId? = null
        var side: FugueSide? = null
        var rightOrigin: FugueId? = null
        var positions: Map<FugueId, FugueId>? = null

        mainLoop@ while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@mainLoop
                0 -> type = decodeIntElement(descriptor, 0)
                1 -> id = decodeSerializableElement(descriptor, 1, idSerializer)
                2 -> { value = decodeSerializableElement(descriptor, 2, vSerializer); hasValue = true }
                3 -> parent = decodeSerializableElement(descriptor, 3, idSerializer)
                4 -> side = decodeSerializableElement(descriptor, 4, sideSerializer)
                5 -> rightOrigin = decodeSerializableElement(descriptor, 5, idSerializer)
                6 -> positions = decodeSerializableElement(descriptor, 6, positionsSerializer)
                else -> throw SerializationException("Unexpected index: $index")
            }
        }

        when (type) {
            TYPE_INSERT -> FugueOp.Insert(
                id = id ?: missingField("Insert", "id"),
                value = if (hasValue) value else missingField("Insert", "value"),
                parent = parent ?: missingField("Insert", "p"),
                side = side ?: missingField("Insert", "s"),
                rightOrigin = rightOrigin,  // nullable, may be absent
            )
            TYPE_REMOVE -> FugueOp.Remove(id = id ?: missingField("Remove", "id"))
            TYPE_COMPACT -> FugueOp.Compact(positions = positions ?: missingField("Compact", "pos"))
            else -> throw SerializationException("Unknown FugueOp type discriminator: $type")
        }
    }

    private fun <T> missingField(variant: String, field: String): T =
        throw SerializationException("$variant missing required field '$field'")

    private companion object {
        const val TYPE_INSERT = 0
        const val TYPE_REMOVE = 1
        const val TYPE_COMPACT = 2
    }
}
