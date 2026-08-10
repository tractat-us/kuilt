package us.tractat.kuilt.bolt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.FugueId
import us.tractat.kuilt.crdt.FugueOp
import us.tractat.kuilt.crdt.LogOp
import us.tractat.kuilt.crdt.OpLogCrdt
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp

/**
 * How one op-log CRDT's operations are classified and encoded into an archive.
 *
 * Every [Bolt] takes one of these. It binds three things a bolt cannot know for itself: which ops
 * are content and which are records of forgetting ([classifyOp]), which causal dot an id belongs to
 * ([dotOf]), and how an op turns into bytes ([encode] / [decode]).
 *
 * ### Why it holds a CRDT instance
 *
 * `OpLogCrdt.classify` and `dotOf` are instance methods, so classification needs a live CRDT in
 * hand. [classifier] is that hand — a **witness**, used only for its three contract methods and
 * never read for content. The companion factories construct an empty one, so there is no state to
 * leak even in principle. (If a future replay-side validator wants free-standing classification,
 * `OpLogCrdt` can grow it source-compatibly; this parameter is why that is worth doing.)
 *
 * ### Why the serializer is not a parameter
 *
 * You pass the **element** serializer; the op serializer comes from
 * `OpLogCrdt.opSerializer(vSerializer)` and cannot be overridden. That is deliberate. The
 * compiler-generated sealed serializer for `RgaOp`/`FugueOp` writes a *different* wire format —
 * class-discriminator polymorphism instead of the canonical leading `t` tag — and defaults its
 * element type to `PolymorphicSerializer(Any::class)`, which CBOR cannot encode at all. Bytes
 * written that way sit outside the golden vectors that pin this format across versions, and an
 * archive exists precisely to be read by a later build. `FugueOpSerializer` is `internal` besides,
 * so `opSerializer` is the only way to canonically encode a `FugueOp`.
 *
 * @param Id the element-identity type (`RgaId` / `FugueId`).
 * @param V the element type carried by inserts.
 * @param Op the operation type (`RgaOp<V>` / `FugueOp<V>`).
 */
@OptIn(ExperimentalSerializationApi::class)
public class BoltArchiveFormat<Id : Any, V, Op : Any>(
    private val classifier: OpLogCrdt<Id, V, Op>,
    vSerializer: KSerializer<V>,
) {
    private val opSerializer: KSerializer<Op> = classifier.opSerializer(vSerializer)
    private val cbor = Cbor

    /**
     * The `serialName` of the canonical op serializer — written into every segment header so an
     * archive says what it holds rather than relying on the reader guessing.
     */
    public val opFormat: String = opSerializer.descriptor.serialName

    /** The `serialName` of the element serializer, likewise written into the segment header. */
    public val elementType: String = vSerializer.descriptor.serialName

    /** Classify [op] — content (`Insert`/`Remove`) or a record of forgetting (`Compact`). */
    internal fun classifyOp(op: Op): LogOp<Id> = classifier.classify(op)

    /** The causal dot [id] belongs to. */
    internal fun dotOf(id: Id): Dot = classifier.dotOf(id)

    /** [op] as canonical CBOR. */
    internal fun encode(op: Op): ByteArray = cbor.encodeToByteArray(opSerializer, op)

    /** The op [bytes] encode. */
    internal fun decode(bytes: ByteArray): Op = cbor.decodeFromByteArray(opSerializer, bytes)

    /**
     * The dots the `Insert`s among [ops] mint — **insert-only**, which is the whole promise of the
     * frame's dot field. A `Remove` reuses its target insert's id and so contributes nothing, and a
     * `Compact` never reaches a frame at all.
     */
    internal fun insertDotsOf(ops: List<Op>): Set<Dot> =
        ops.mapNotNullTo(mutableSetOf()) { op -> (classifyOp(op) as? LogOp.Insert)?.let { dotOf(it.id) } }

    /** Content ops only: everything [classifyOp] does not call a `Compact`. */
    internal fun contentOnly(ops: List<Op>): List<Op> = ops.filter { classifyOp(it) !is LogOp.Compact }

    public companion object {

        /** The archive format for an [Rga] of [vSerializer]'s element type. */
        public fun <V> rga(vSerializer: KSerializer<V>): BoltArchiveFormat<RgaId, V, RgaOp<V>> =
            BoltArchiveFormat(Rga.empty(), vSerializer)

        /** The archive format for a [Fugue] of [vSerializer]'s element type. */
        public fun <V> fugue(vSerializer: KSerializer<V>): BoltArchiveFormat<FugueId, V, FugueOp<V>> =
            BoltArchiveFormat(Fugue.empty(), vSerializer)
    }
}
