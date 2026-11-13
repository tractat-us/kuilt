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
    public enum class Kind { NOT_WAITING, NOT_ELIGIBLE, NO_LIVE_LINEAGE, SUPPLY_EXHAUSTED, BEHIND_AT_LEVEL, WOULD_BE_PICKED_NEXT }

    /** The leaf is not in the waiting set: it has no work queued. */
    public data object NotWaiting : WaitExplanation {
        override val kind: Kind get() = Kind.NOT_WAITING
    }

    /** The leaf is waiting, but this slot cannot run it: it is not in the eligible set. */
    public data object NotEligible : WaitExplanation {
        override val kind: Kind get() = Kind.NOT_ELIGIBLE
    }

    /**
     * No pick can end at the leaf [at]: it is the root, it has an ACTIVE child, or the edges from
     * the root down to it are not all ACTIVE (a reload is removing or reshaping it). While this
     * holds its work counts as idle, so it banks no credit (see [LocalHeddle]). The same arm as
     * [AcquireOutcome.NoLiveLineage], with [at] the leaf, as from [LocalHeddle.admit].
     */
    public data class NoLiveLineage(public val at: GroupId) : WaitExplanation {
        override val kind: Kind get() = Kind.NO_LIVE_LINEAGE
    }

    /** The root holds less than the reservation, so the next acquire is refused whoever is waiting. */
    public data object SupplyExhausted : WaitExplanation {
        override val kind: Kind get() = Kind.SUPPLY_EXHAUSTED
    }

    /**
     * Another child goes first at [parent], the first group on the leaf's lineage where the next
     * pick leaves it: [nextChild] is the child it picks there instead.
     *
     * [leafSideVirtualService] is the effective virtual service of the leaf's own child of
     * [parent], wake clamp included, and [front] is [parent]'s current virtual time over the
     * children competing there ([FairShareAllocator.virtualTime]). Above the front, that side is
     * running ahead of its share. At or below it, it is within its share, and [nextChild]'s
     * earlier virtual deadline decided the pick.
     */
    public data class BehindAtLevel(
        public val parent: GroupId,
        public val nextChild: GroupId,
        public val leafSideVirtualService: Rational,
        public val front: Rational,
    ) : WaitExplanation {
        override val kind: Kind get() = Kind.BEHIND_AT_LEVEL
    }

    /** The next acquire, on these sets and this reservation, picks this leaf. */
    public data object WouldBePickedNext : WaitExplanation {
        override val kind: Kind get() = Kind.WOULD_BE_PICKED_NEXT
    }
}
