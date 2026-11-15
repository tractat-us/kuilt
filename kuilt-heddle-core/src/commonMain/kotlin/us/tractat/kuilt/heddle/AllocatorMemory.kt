package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.crdt.CanonicalMapSerializer
import us.tractat.kuilt.math.Int128

/**
 * What a [FairShareAllocator] remembers between calls, as a value: the wake clamps it has
 * computed and the demanding state each edge was last observed in (design §7.2, issues #1695,
 * #1714).
 *
 * A scheduler that runs in one process and outlives a restart needs this. Without it a restarted
 * allocator has observed nothing, so it treats no child as a waker: a group that went idle just
 * before the restart and is busy just after keeps the idle credit the clamp exists to deny. So the
 * allocator exports its memory through [FairShareAllocator.memory] and takes it back through its
 * constructor, and a caller that persists its ledger persists this beside it.
 *
 * It is also how a caller asks a question without changing the answer: a throwaway allocator
 * built over a copy of this value makes the same picks the real one would, and the real one has
 * not moved.
 *
 * It is **scheduler-local, never replicated**. Two peers may hold different memories, and that only
 * reorders their grants: a wake offset can give a turn up, never create entitlement. Nothing here
 * merges.
 *
 * Both maps are keyed by attachment id across the whole tree. They encode through
 * [CanonicalMapSerializer], so two equal memories encode to the same bytes whatever order the
 * allocator's own hash maps happen to hold them in. The contents are deliberately not public: the
 * only things a caller does with a memory are to keep it and to hand it back.
 *
 * Each wake clamp is kept in ticks of its edge's own grid, wide (#3353): a waker idle since long
 * before its parent's origin was raised is clamped the whole way up, and that can be past `Long`.
 * It encodes as its two words, like a [Gauge]'s ticks ([WideTicksSerializer]).
 *
 * Its encoded shape is part of [LocalHeddleSnapshot]'s format 2, which changed it in place for
 * the wide clamps; format 1 reads through a frozen surrogate of the virtual-time form it wrote. A
 * field added here takes a default, and any other change needs a frozen surrogate first.
 */
@Serializable
public class AllocatorMemory internal constructor(
    @Serializable(with = CanonicalMapSerializer::class)
    internal val wakeOffsets: Map<AttachmentId, @Serializable(with = WideTicksSerializer::class) Int128>,
    @Serializable(with = CanonicalMapSerializer::class)
    internal val demandingObserved: Map<AttachmentId, Boolean>,
) {
    override fun equals(other: Any?): Boolean =
        other is AllocatorMemory && wakeOffsets == other.wakeOffsets && demandingObserved == other.demandingObserved

    override fun hashCode(): Int = 31 * wakeOffsets.hashCode() + demandingObserved.hashCode()

    override fun toString(): String =
        "AllocatorMemory(wakeOffsets=${wakeOffsets.size}, demandingObserved=${demandingObserved.size})"

    public companion object {
        /** A memory that has observed nothing: what a brand-new allocator starts from. */
        public val EMPTY: AllocatorMemory = AllocatorMemory(emptyMap(), emptyMap())
    }
}

/** A non-negative wide count in its two words, each elided at zero: [nonNegativeWide]'s wire shape. */
@Serializable
private class WideTicksWords(val high: Long = 0L, val low: Long = 0L)

/**
 * An [Int128] that is never negative, as its two words `{high, low}`, the low one unsigned and each
 * elided at zero. Decoding a negative one throws, the read-path guard ([nonNegativeWide]).
 */
internal object WideTicksSerializer : KSerializer<Int128> {
    override val descriptor: SerialDescriptor = WideTicksWords.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Int128) {
        require(value >= Int128.ZERO) { "a wide count must be non-negative, was $value" }
        encoder.encodeSerializableValue(WideTicksWords.serializer(), WideTicksWords(value.high, value.low))
    }

    override fun deserialize(decoder: Decoder): Int128 {
        val words = decoder.decodeSerializableValue(WideTicksWords.serializer())
        return nonNegativeWide(words.high, words.low, "a wake clamp")
    }
}
