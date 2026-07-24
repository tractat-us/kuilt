package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId

/**
 * An integrity fault [EntitlementLedger.validate] derives from merged state.
 *
 * Conflicts are **surfaced, never resolved by timestamp** (design §4.6): every
 * replica folds the same merged state into the same, deterministically-sorted
 * report. The affected lineage contributes no spendable holdings (quarantine is
 * transitive down the path), and nothing here is ever silently resolved by arrival
 * order or a clock.
 *
 * `validate()` is an **eventually-consistent diagnostic, not a safety gate.** Safety
 * comes from the local holdings check each mutator runs on the actor's own complete
 * state — never from a conflict report. Under partial delivery a report may transiently
 * list a conflict that a later anti-entropy round dissolves (see [EntitlementLedger]);
 * consumers must not hard-gate on `validate().isEmpty()` while rebalancing is in flight.
 *
 * [Comparable] so a report is emitted in one canonical order on every peer — the
 * ordering is derived from the typed identities below (no stringified keys).
 */
public sealed interface LedgerConflict : Comparable<LedgerConflict> {

    /** The stable ordering rank of this conflict kind (kinds are grouped in the sorted report). */
    public val order: Int

    override fun compareTo(other: LedgerConflict): Int {
        order.compareTo(other.order).let { if (it != 0) return it }
        // Equal order ⇒ same kind (each kind owns a distinct `order`), so these casts hold.
        return when (this) {
            is PerEdgeSafety -> edge.compareTo((other as PerEdgeSafety).edge)
            is RecordDivergence -> id.compareTo((other as RecordDivergence).id)
            is PersistentNegativeHoldings -> {
                other as PersistentNegativeHoldings
                group.compareTo(other.group).let { if (it != 0) return it }
                replica.compareTo(other.replica)
            }
            is DualActiveInbound -> group.compareTo((other as DualActiveInbound).group)
            is ClosureViolation -> edge.compareTo((other as ClosureViolation).edge)
        }
    }

    /**
     * An edge whose aggregate charged-plus-returned exceeds what was ever issued
     * down it: `leafSpent(e) + rollupSpent(e) + returned(e) > issued(e)`. Checked
     * **sum-wise on aggregate values**, never per slot — a peer may legitimately
     * return entitlement it received by transfer, so a per-slot `returned > issued`
     * is fine; only the edge total crossing `issued` is a fault (design §4.6).
     */
    public data class PerEdgeSafety(public val edge: AttachmentId) : LedgerConflict {
        override val order: Int get() = 0
    }

    /**
     * A `(group, replica)` whose derived [EntitlementLedger.holdings] is negative —
     * the real overspend net (design §4.6): a debit beyond a peer's pocket that
     * nonetheless stays within the edge *sum* passes [PerEdgeSafety] yet strands
     * holdings persistently negative. On a fully-delivered state this is a genuine
     * overspend; under partial delivery of a multi-hop transfer-funded charge it may
     * surface transiently and self-heal on anti-entropy.
     */
    public data class PersistentNegativeHoldings(
        public val group: GroupId,
        public val replica: ReplicaId,
    ) : LedgerConflict {
        override val order: Int get() = 1
    }

    /**
     * Two distinct immutable [AttachmentRecord]s under one [AttachmentId] — a
     * topology fork the merge deliberately retained rather than resolving by
     * last-writer-wins on a parent pointer (design §5.2). The whole lineage is
     * quarantined: [EntitlementLedger.holdings] returns zero for any group at or
     * below the divergent edge.
     */
    public data class RecordDivergence(public val id: AttachmentId) : LedgerConflict {
        override val order: Int get() = 2
    }

    /**
     * A group with **two or more [Lifecycle.ACTIVE] inbound generations** — the topology
     * fork the design forbids resolving by last-writer-wins on a parent pointer (§5.2,
     * §10.11). It arises when two replicas concurrently activate a different inbound edge
     * for the same child; the lifecycle max-register keeps *both* ACTIVE, so every replica
     * folds the merged state into the **same** report rather than silently picking a
     * winner. The child's whole lineage is quarantined — [EntitlementLedger.holdings]
     * returns zero at or below it — and **no new entitlement may be delegated across
     * either contested edge** ([EntitlementLedger.delegate] returns `null`) until the
     * control plane close-drains all but one generation.
     */
    public data class DualActiveInbound(public val group: GroupId) : LedgerConflict {
        override val order: Int get() = 3
    }

    /**
     * A [Lifecycle.RETIRED] edge across which entitlement nonetheless still stands —
     * `outstanding(e) != 0` (design §5.1, §10.10). [EntitlementLedger.retire] refuses
     * to retire an edge until it has fully drained, so a retired edge that later shows
     * outstanding entitlement can only mean a **late delegation crossed a generation the
     * cluster had already retired**: a replica acting on stale [Lifecycle.ACTIVE] state
     * delegated down an edge that another replica had already close-drained-retired. The
     * max-register makes RETIRED dominate the merge (closure dominance); this report
     * surfaces the late crossing rather than resolving it by arrival order. The
     * entitlement is stranded on a dead generation and must be reconciled by the control
     * plane.
     */
    public data class ClosureViolation(public val edge: AttachmentId) : LedgerConflict {
        override val order: Int get() = 4
    }
}
