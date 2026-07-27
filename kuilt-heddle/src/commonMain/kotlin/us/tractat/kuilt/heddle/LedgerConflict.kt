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
            is LineageCycle -> group.compareTo((other as LineageCycle).group)
            is ConservationViolation -> {
                other as ConservationViolation
                leafSpentTotal.compareTo(other.leafSpentTotal).let { if (it != 0) return it }
                mintedTotal.compareTo(other.mintedTotal)
            }
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
     * A group with **two or more live inbound generations** — two inbound edges that are
     * each [Lifecycle.ACTIVE] or [Lifecycle.CLOSING] (a still-draining closing edge counts;
     * it can still carry entitlement). This is the topology fork the design forbids
     * resolving by last-writer-wins on a parent pointer (§5.2, §10.11). It arises when two
     * replicas concurrently attach a different inbound edge for the same child — e.g. one
     * activates `e2` while another has `e1` active or closing; the lifecycle max-register
     * keeps *both* live, so every replica folds the merged state into the **same** report
     * rather than silently picking a winner. The child's whole lineage is quarantined —
     * [EntitlementLedger.holdings] returns zero at or below it — and **no new entitlement
     * may be delegated across either contested edge** ([EntitlementLedger.delegate]
     * returns `null`). This predicate is exactly the one [EntitlementLedger] quarantines
     * on, so quarantine and report always coincide (§10.11).
     *
     * **Resolution is a control-plane (H5) concern, not an in-ledger operation.** A
     * quarantined generation has holdings `0`, so it *cannot be drained* — the naive
     * "close-drain-retire all but one" recipe deadlocks (a closing edge with zero holdings
     * can neither spend nor release). Resolving a genuine fork means the control plane
     * decides which generation is canonical and **retires-and-abandons** the loser's edge
     * (accepting any entitlement stranded on it), not draining it. H2 surfaces the fork;
     * it does not resolve it.
     */
    public data class DualActiveInbound(public val group: GroupId) : LedgerConflict {
        override val order: Int get() = 3
    }

    /**
     * A [Lifecycle.RETIRED] edge across which entitlement nonetheless still stands —
     * `outstanding(e) != 0` (design §5.1, §10.10). [EntitlementLedger.retire] refuses to
     * retire an edge until it has fully drained, so on a **causally-complete** state this
     * means a **late delegation crossed a generation the cluster had already retired**: a
     * replica acting on stale [Lifecycle.ACTIVE] state delegated down an edge another
     * replica had already close-drained-retired. The max-register makes RETIRED dominate
     * the merge (closure dominance); this report surfaces the late crossing rather than
     * resolving it by arrival order, and the stranded entitlement is reconciled by the
     * control plane.
     *
     * It can **also** fire transiently on a *lagging observer* — one holding the
     * `{delegate, close, retire}` patches but not yet the draining `release`/`spend` — for
     * which `issued > returned + spent` against RETIRED. The [EntitlementLedger.retire]
     * patch carries a drain witness (the edge's counter slots) specifically to minimize
     * this transient for honest single-hop delivery; like every `validate` conflict, this
     * is a diagnostic, not a safety gate, and a lagging false-positive self-heals on
     * anti-entropy.
     */
    public data class ClosureViolation(public val edge: AttachmentId) : LedgerConflict {
        override val order: Int get() = 4
    }

    /**
     * A group that lies **on a topology cycle** — walking its live inbound edges root-ward
     * returns to the group itself instead of reaching a root. Like [DualActiveInbound] this
     * is a fork the merge deliberately retains rather than resolving by arrival order
     * (§5.2): two replicas each attached an inbound edge, and together the records close a
     * loop. The whole cycle is quarantined — [EntitlementLedger.holdings] is zero at every
     * group on it and at every group below it — so this report is what makes that quarantine
     * visible (§10.11: quarantine ⟺ explicit report).
     *
     * **Reported once per cycle member, not per descendant.** A group merely *below* a cycle
     * is quarantined too, but it is not itself a loop member and would only flood the report
     * with the whole subtree; only groups the walk re-enters at their own starting point are
     * listed.
     *
     * **Not a delivery artifact.** Records are grow-only, so a cycle observed on any state is
     * a cycle in the merged topology — a partially-delivered replica can only ever see
     * *fewer* edges, never a loop that is not really there. It can nonetheless be **transient
     * in the honest control plane**: an inverting reparent (attaching `G` under `H` while
     * `H` is still live under `G`) closes a real loop for the window between the new edge
     * activating and the old one retiring. That window is a real quarantine, so reporting it
     * is the point; it clears when one of the loop's edges reaches [Lifecycle.RETIRED].
     *
     * **Resolution is a control-plane concern**, exactly as for [DualActiveInbound]: a
     * quarantined generation has zero holdings and so cannot be drained, and the loop is
     * broken by retiring-and-abandoning one of its edges.
     */
    public data class LineageCycle(public val group: GroupId) : LedgerConflict {
        override val order: Int get() = 5
    }

    /**
     * The **global supply backstop**: total service charged has exceeded total supply ever
     * minted — `Σ_e effLeafSpent(e) > Σ mintedTotal` (design §10.1 conservation, §10.12).
     *
     * Every other check here is per-edge or per-`(group, replica)`. This one is the whole
     * ledger's books in a single line, and it exists precisely so a regression in the
     * *derivation* of [EntitlementLedger.holdings] cannot hide: the H1b divergent-child
     * re-spend (a forked child edge dropping out of the parent's delegated-out subtraction,
     * inflating spendable authority) manufactures authority that the per-lineage checks
     * would then read as legitimate — but the units it charges were never minted, and that
     * shows up here regardless.
     *
     * ## Which states this is valid on
     *
     * **Exact on a converged (causally-complete) state.** There, conservation is an identity
     * — `mintedTotal = Σ holdings + Σ leafSpent` — so this fires only when `Σ holdings` has
     * gone negative for a real reason, or when the identity itself has been broken by a bug.
     *
     * Under **partial delivery** it inherits the accepted transient the other checks have.
     * Charged service travels with the witness the mutator attached (see [EntitlementLedger]),
     * and that witness carries the *actor's own* minted supply — so a directly-funded charge
     * always arrives alongside the supply justifying it. What can transiently trip it is a
     * state observing a charge whose **root** mint has not been delivered, which is reachable
     * when the charge was funded by a transfer at a non-root path: the witness backs the donor
     * with its `issued` at that edge, not with the mint behind it.
     *
     * Where such a state also carries the **topology**, the conservation identity means the
     * same gap already strands a negative [PersistentNegativeHoldings] at the delegator — this
     * is a second voice on one fault, not a new one. On a bare delta carrying no records at
     * all it *can* be the only report, because `allGroups()` is empty there and no per-group
     * check runs. Either way it self-heals on anti-entropy, and — as for every report here —
     * consumers must not gate on `validate().isEmpty()` while rebalancing is in flight.
     *
     * @property leafSpentTotal the effective leaf spend summed over every edge
     * @property mintedTotal total supply ever minted on this state
     */
    public data class ConservationViolation(
        public val leafSpentTotal: Long,
        public val mintedTotal: Long,
    ) : LedgerConflict {
        override val order: Int get() = 6
    }
}
