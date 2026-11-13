package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.CanonicalMapSerializer

/**
 * What a [FairShareAllocator] remembers between calls, as a value: the wake clamps it has
 * computed, the demanding state each edge was last observed in (design §7.2, issues #1695,
 * #1714), and, for a lease engine, what each clamp's open leases could still hand back (#3238).
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
 * Every map is keyed by attachment id across the whole tree. They encode through
 * [CanonicalMapSerializer], so two equal memories encode to the same bytes whatever order the
 * allocator's own hash maps happen to hold them in. The contents are deliberately not public: the
 * only things a caller does with a memory are to keep it and to hand it back.
 *
 * Its encoded shape is part of [LocalHeddleSnapshot]'s format 1: a field added here takes a
 * default, and any other change needs a frozen surrogate there first.
 */
@Serializable
public class AllocatorMemory internal constructor(
    @Serializable(with = CanonicalMapSerializer::class)
    internal val wakeOffsets: Map<AttachmentId, @Serializable(with = RationalSerializer::class) Rational>,
    @Serializable(with = CanonicalMapSerializer::class)
    internal val demandingObserved: Map<AttachmentId, Boolean>,
    @Serializable(with = CanonicalMapSerializer::class)
    internal val wakeExposures: Map<AttachmentId, WakeExposure> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean =
        other is AllocatorMemory && wakeOffsets == other.wakeOffsets && demandingObserved == other.demandingObserved &&
            wakeExposures == other.wakeExposures

    override fun hashCode(): Int = 31 * (31 * wakeOffsets.hashCode() + demandingObserved.hashCode()) + wakeExposures.hashCode()

    override fun toString(): String =
        "AllocatorMemory(wakeOffsets=${wakeOffsets.size}, demandingObserved=${demandingObserved.size}, " +
            "wakeExposures=${wakeExposures.size})"

    public companion object {
        /** A memory that has observed nothing: what a brand-new allocator starts from. */
        public val EMPTY: AllocatorMemory = AllocatorMemory(emptyMap(), emptyMap())
    }
}

/**
 * What the wake clamps of one edge left at risk: how much a later release, by a lease that was
 * already open at a clamp, could still hand back (#3238).
 *
 * A clamp lifts a waking edge to its parent's front, reading the edge's virtual service with every
 * open lease counted at its reservation. When one of those leases later releases part of its
 * reservation, at `complete` or `cancel`, the edge's virtual service falls by the release and lands
 * behind the front the clamp put it on: idle credit after all. So the allocator adds back to the
 * wake offset whatever part of such a release the clamp actually removed.
 *
 * - [slack] is how far ahead of the clamp's floor the edge already sat, in cost units, rounded
 *   down. A release that only uses up that lead hands back nothing the clamp denied, so it is
 *   consumed first and not added back.
 * - [budget] is the edge's outstanding at the clamp, less what releases have counted against it
 *   since. Together the leases open at the clamp cannot hand back more than they then held.
 * - [openedBefore] is the first lease id issued after the clamp: a lease at or past it was not
 *   open when the clamp read the edge, so its releases are never counted.
 *
 * Both amounts are in cost units, and [slack] is below [budget]: an exposure whose slack covers its
 * budget can add nothing back, so the allocator forgets it rather than keep it.
 */
@Serializable
internal data class WakeExposure(
    val slack: Long,
    val budget: Long,
    val openedBeforeEpoch: Long,
    val openedBeforeSeq: Long,
) {
    init {
        require(slack >= 0L) { "slack must be non-negative, was $slack" }
        require(budget > slack) { "budget must exceed slack $slack, was $budget" }
    }

    /** The first lease id issued after the clamp. */
    val openedBefore: LeaseId get() = LeaseId(openedBeforeEpoch, openedBeforeSeq)
}
