package us.tractat.kuilt.heddle

/**
 * Why a leaf is waiting, as [LocalHeddle.explain] answers it: the first reason, in [Kind] order,
 * that stops the next [LocalHeddle.acquire] from picking it.
 *
 * Each arm agrees with that acquire: [WouldBePickedNext] exactly when it would pick the leaf, and
 * every other arm only when it would not. [BehindAtLevel] also names the child it would pick
 * instead, at the first level where that child is not the leaf's.
 *
 * ## As a hold reason
 *
 * [kind] is the stable, ordered name of the arm, for a log line or a telemetry attribute. To tell
 * when a leaf's reason *changes*, compare [kind] and the arm's group ids, not the whole value:
 * [BehindAtLevel]'s two rationals move with every pick under that parent, so a reader that
 * compared them would see a new reason on every slot.
 */
public sealed interface WaitExplanation {
    /** The arm, by name. Declared in the order [LocalHeddle.explain] checks them. */
    public val kind: Kind

    /** The arms of a [WaitExplanation], in the order they are checked; their order is part of the API. */
    public enum class Kind { NOT_WAITING, NOT_ELIGIBLE, NO_LIVE_LINEAGE, AT_CEILING, SUPPLY_EXHAUSTED, POSITION_CEILING, BEHIND_AT_LEVEL, WOULD_BE_PICKED_NEXT }

    /** The leaf is not in the waiting set: it has no work queued. */
    public data object NotWaiting : WaitExplanation {
        override val kind: Kind get() = Kind.NOT_WAITING
    }

    /** The leaf is waiting, but this slot cannot run it: it is not in the eligible set. */
    public data object NotEligible : WaitExplanation {
        override val kind: Kind get() = Kind.NOT_ELIGIBLE
    }

    /**
     * No pick can end at the leaf [at]: it is the root, it has an ACTIVE child, the topology does not
     * declare it leasable (a group with nothing attached, not a lane), or the edges from the root
     * down to it are not all ACTIVE (a reload is removing or reshaping it). While this
     * holds its work counts as idle, so it banks no credit (see [LocalHeddle]). The same arm as
     * [AcquireOutcome.NoLiveLineage], with [at] the leaf, as from [LocalHeddle.admit].
     */
    public data class NoLiveLineage(public val at: GroupId) : WaitExplanation {
        override val kind: Kind get() = Kind.NO_LIVE_LINEAGE
    }

    /**
     * [group], on the leaf's path, has at least [limit] leases open beneath it, its concurrency
     * ceiling ([Topology.Node.maxConcurrent]), so no pick goes to the leaf until enough of them close.
     * A [limit] of `0` is a pause. When several groups on the path are at their ceilings, [group] is
     * the one nearest the root: the whole subtree waits on it.
     *
     * The next acquire skips the leaf for a sibling, or returns [AcquireOutcome.NothingEligible]
     * when every eligible leaf is capped. Checked before the supply, so a capped leaf is told this
     * even when the root is empty.
     *
     * @sample us.tractat.kuilt.heddle.sampleConcurrencyCeiling
     */
    public data class AtCeiling(public val group: GroupId, public val limit: Long) : WaitExplanation {
        override val kind: Kind get() = Kind.AT_CEILING
    }

    /** The root holds less than the reservation, so the next acquire is refused whoever is waiting. */
    public data object SupplyExhausted : WaitExplanation {
        override val kind: Kind get() = Kind.SUPPLY_EXHAUSTED
    }

    /**
     * The next acquire's pick reaches [parent], which has a child at its position ceiling, or its
     * delegation would take one there, so it is refused whoever is waiting below. The same arm as
     * [AcquireOutcome.PositionCeiling].
     */
    public data class PositionCeiling(public val parent: GroupId) : WaitExplanation {
        override val kind: Kind get() = Kind.POSITION_CEILING
    }

    /**
     * Another child goes first at [parent], the first group on the leaf's lineage where the next
     * pick leaves it: [nextChild] is the child it picks there instead.
     *
     * [leafSideVirtualService] is the effective virtual service of the leaf's own child of
     * [parent], wake clamp included. The two fronts are weighted means of the same quantity, wake
     * clamps included, over two different sets of [parent]'s children
     * ([FairShareAllocator.virtualTime]):
     * - [eligibleFront] is over the children leading to a leaf of the eligible set: the line the
     *   pick itself draws. The pick reads it on each side's own grid, rounded up to the next tick of
     *   that side's service (#3313), so a side up to one tick above it was still a candidate. Past
     *   that, the leaf's side was not a candidate this slot. At or below it, it was one, and
     *   [nextChild]'s earlier virtual deadline (or, on a tie, its smaller edge id) decided the pick.
     * - [front] is over the children leading to a leaf of the waiting set: the share line, the
     *   front a newborn is seated at and the set [LocalHeddle.entitlement] shares over. Above it,
     *   the leaf's side has had more than its share of everyone waiting; at or below it, less. It
     *   says nothing about why this pick went elsewhere: a side below [front] can still be above
     *   [eligibleFront], when the children this slot cannot run are the ones pulling [front] up.
     */
    public data class BehindAtLevel(
        public val parent: GroupId,
        public val nextChild: GroupId,
        public val leafSideVirtualService: Rational,
        public val front: Rational,
        public val eligibleFront: Rational,
    ) : WaitExplanation {
        override val kind: Kind get() = Kind.BEHIND_AT_LEVEL
    }

    /** The next acquire, on these sets and this reservation, picks this leaf. */
    public data object WouldBePickedNext : WaitExplanation {
        override val kind: Kind get() = Kind.WOULD_BE_PICKED_NEXT
    }
}
