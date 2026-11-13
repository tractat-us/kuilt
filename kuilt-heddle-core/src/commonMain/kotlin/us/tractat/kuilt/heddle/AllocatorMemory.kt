package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.CanonicalMapSerializer

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
 */
@Serializable
public class AllocatorMemory internal constructor(
    @Serializable(with = CanonicalMapSerializer::class)
    internal val wakeOffsets: Map<AttachmentId, @Serializable(with = RationalSerializer::class) Rational>,
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
