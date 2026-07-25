package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import kotlinx.serialization.Serializable

/**
 * The immutable fact of one **attachment generation** — a parent→child edge in the
 * fairness tree, together with the child's [weight] among its siblings and the
 * [initialVirtualTime] a fresh generation starts at (never with lifetime credit;
 * design §10.5).
 *
 * Records are immutable and grow-only. In a healthy ledger one [AttachmentId] maps
 * to exactly one record, but the merge **never collapses divergent records under
 * one id** — the ledger keeps a *set* of records per id (see
 * [EntitlementLedger]), so two conflicting records both survive the join and a
 * later phase's `validate` can report the divergence. This is deliberate: silently
 * picking a winner would be last-writer-wins on a parent pointer, which
 * `heddle-design.md` §5.2 forbids. Set union is a join-semilattice on its own, so
 * the record type needs no ordering.
 *
 * @property id this generation's identity.
 * @property parent the parent group the entitlement flows from.
 * @property child the child group the entitlement flows to.
 * @property weight the child's fairness share among its siblings; always positive.
 * @property initialVirtualTime the virtual-time origin a fresh generation starts at;
 *   for a runtime creation, derive it with [neutralInitialVirtualTime] rather than by hand.
 */
@Serializable
public data class AttachmentRecord(
    public val id: AttachmentId,
    public val parent: GroupId,
    public val child: GroupId,
    public val weight: Weight,
    public val initialVirtualTime: Long,
) {
    public companion object {
        /**
         * The one rounding rule for seating a newborn generation: **`initialVirtualTime = ⌈V⌉`**,
         * the exact ceiling of the parent's current virtual time [parentVirtualTime].
         *
         * Design §7.2 says a new generation starts at the parent's current virtual time, and
         * §10.5 makes it normative — "never with lifetime credit". But
         * `V = Σ w·ev / Σ w` is a [Rational] and almost never integral, while
         * [AttachmentRecord.initialVirtualTime] is a `Long`, so creation *must* round. The
         * direction is not a matter of taste — it carries a fairness sign:
         *
         * - **Floor** seats the newborn *behind* the front. Lower virtual service reads as
         *   "has had less than its share", so the newborn is eligible ahead of every sibling
         *   and takes the next grants outright — a sliver of unearned lifetime credit, which
         *   §10.5 forbids. A subtree that churns generations accrues the bias systematically,
         *   each newborn marginally ahead of its siblings.
         * - **Ceiling** seats it at or just ahead of the front. It can only ever *give up* a
         *   fraction of a service unit, never claim one, so §10.5 holds by construction, and
         *   the deviation is bounded: `0 <= ⌈V⌉ − V < 1` virtual unit — one quantum's worth
         *   of patience at unit weight, which the very next round erases.
         *
         * Ceiling is therefore the conservative, invariant-preserving choice, and it is
         * exact and deterministic, so every replica that re-derives a record from the same
         * `V` lands on the same `Long`.
         */
        public fun neutralInitialVirtualTime(parentVirtualTime: Rational): Long =
            parentVirtualTime.ceil()

        /**
         * A generation created **neutrally** under a parent whose current virtual time is
         * [parentVirtualTime] — the correct-by-construction alternative to computing
         * [initialVirtualTime] at the call site and rounding it the wrong way.
         *
         * @see neutralInitialVirtualTime for the rounding rule and why it is the ceiling.
         */
        public fun neutral(
            id: AttachmentId,
            parent: GroupId,
            child: GroupId,
            weight: Weight,
            parentVirtualTime: Rational,
        ): AttachmentRecord =
            AttachmentRecord(id, parent, child, weight, neutralInitialVirtualTime(parentVirtualTime))
    }
}

/**
 * One act of introducing root supply: [holder] is credited [amount] units at the
 * root path. Keyed in the ledger by a unique [MintId] so independently-recorded
 * mints union rather than collide (design fix 4).
 *
 * @property holder the replica the minted supply is credited to.
 * @property amount the units minted; non-negative.
 */
@Serializable
public data class MintRecord(
    public val holder: ReplicaId,
    public val amount: Long,
) : Comparable<MintRecord> {
    init {
        require(amount >= 0L) { "MintRecord amount must be non-negative, was $amount" }
    }

    override fun compareTo(other: MintRecord): Int {
        holder.compareTo(other.holder).let { if (it != 0) return it }
        return amount.compareTo(other.amount)
    }
}
