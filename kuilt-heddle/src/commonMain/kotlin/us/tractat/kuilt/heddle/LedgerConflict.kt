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
            is NegativeEffectiveSpend -> edge.compareTo((other as NegativeEffectiveSpend).edge)
            is OrphanedTransferPath -> path.compareTo((other as OrphanedTransferPath).path)
            is MultipleRoots -> {
                other as MultipleRoots
                // Lexicographic over two already-sorted lists: `List` is not `Comparable`, and a
                // report carries at most one of these, so this only has to be total and stable.
                roots.zip(other.roots) { mine, theirs -> mine.compareTo(theirs) }
                    .firstOrNull { it != 0 }
                    ?: roots.size.compareTo(other.roots.size)
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

    /**
     * An edge whose **effective** spend has gone negative — `leafSpent + relocIn − relocOut < 0`
     * on either the leaf or the roll-up family (issue #1693; relocation design §5.3's lower bound,
     * §12.5).
     *
     * A relocation moves already-charged service off a retired edge by adding a *second* monotone
     * counter that cancels the first. The cancellation is only meaningful **alongside the base it
     * cancels**, so the published move republishes that base in the same delta (the drain-witness
     * idiom `retire` already uses). This report exists for the state where that pairing has
     * nonetheless come apart:
     *
     *  - **On an honest observer it is unreachable**, and that is the point — the observer-completeness
     *    rule of §6.4 is precisely what makes it so. Seeing it means the rule has been broken
     *    somewhere: a hand-built patch, a partial replay, or a regression in the derivation.
     *  - It is reported **separately from [PerEdgeSafety]** rather than folded into it because a
     *    negative effective spend *lowers* the charged total, so the upper-bound check alone would
     *    let it pass silently — the fault would be invisible in exactly the direction that matters.
     *
     * Like every report here it is a diagnostic, not a safety gate.
     */
    public data class NegativeEffectiveSpend(public val edge: AttachmentId) : LedgerConflict {
        override val order: Int get() = 7
    }

    /**
     * Transfer rows the topology has moved out from under (issue #2366) — a [PathKey] no
     * group's live lineage reads any more, still carrying a peer-to-peer hand-off that
     * [EntitlementLedger.holdings] therefore no longer counts.
     *
     * `transfers` is keyed by `PathKey.of(edge)` — the **generation's** id, not the child
     * group. So when a group's inbound generation is replaced and the rows do not travel with
     * it, the recipient's credit and the donor's debit drop out of the derivation at once, and
     * **the donor silently recovers what it gave away**.
     *
     * A [EntitlementLedger.relocationPatch] carry moves them (#2366) — cancelling the dead key
     * with `transferRelocOut` and re-opening the live one with `transferRelocIn` — so a move that
     * completes leaves nothing here to report. What still reaches this report is a generation
     * replaced **without** one: a plain reshape with no `Reconcile` behind it, a refused move —
     * including the refusal for a carried hand-off whose donor left the roster before it could ack
     * — an ack that declared no row (the shape a pre-#2377 `QuiesceAck` had), or rows keyed on a
     * generation this ledger has never seen.
     *
     * ## Candidates come from `transfers` **and** `transferRelocIn`
     *
     * A carry never writes the donor-owned base slot at the live key (#1691) — it lands in
     * `transferRelocIn` and cancels the dead key with `transferRelocOut`. So one move on, a key
     * whose entire credit arrived by carry appears in **no** `transfers` entry at all, and an
     * enumeration over the base matrix could not reach it however loud the abandonment. That is
     * the second hop of the same defect: `carol → alice → bob`, carried once onto a fresh
     * generation, and then abandoned there when the next move cannot enumerate the departed
     * `alice`. `transferRelocOut`'s keys are deliberately **not** candidates — that matrix only
     * ever cancels, so a key it alone names holds no credit to strand and would contribute only a
     * negative-`effRow` transient under partial delivery.
     *
     * ## Why this needs its own report
     *
     * Every other check here is structurally incapable of seeing it:
     *
     *  - **Conservation is blind by construction.** `Σ_r transferNet(k, r) = 0` for every
     *    key, identically — so abandoning a key's rows (or halving them, or double-moving
     *    them) is *sum-preserving*. `mintedTotal = Σ holdings + Σ effLeafSpent` still holds
     *    exactly; only the owner changed.
     *  - **[PersistentNegativeHoldings] catches only the loud half.** The recipient lands on
     *    `0`, not below, so nothing goes negative unless the recipient *also* spent or
     *    released across the dead generation.
     *  - The same asymmetry is why `EntitlementLedger.relocationPatch`'s `n < 0` precondition
     *    misses it: a recipient who merely *holds* transferred credit has no counter slot on
     *    the edge at all and is absent from every per-slot enumeration. (It is also why the
     *    fence enumerates transfer donors explicitly — see `EntitlementLedger.baseFinalsOn`.)
     *
     * ## What it takes to fire — all three, together
     *
     *  1. **The key is no longer read.** Its edge's child group has a live lineage whose
     *     final key is a *different* one.
     *  2. **The live key does not already cover the rows.** Some `(donor, recipient)` **effective**
     *     magnitude at this key exceeds the same pair's at the group's live key. Effective on both
     *     sides — base ± relocation — because that is what `EntitlementLedger.holdings` reads.
     *
     *     This is a **magnitude** test, and against a *base* row it is only a **necessary condition
     *     for abandonment**: `EntitlementLedger.transfer` accumulates onto the very same
     *     `(path, donor, recipient)` slot, so "the pair transferred at least as much again at the
     *     live key" is byte-identical to a carry. The consequence is a real, permanent blind spot —
     *     a later ordinary transfer between those two peers at the live key **masks** a report that
     *     had been firing, and rows are grow-only, so the live cumulative never falls back to
     *     unmask it.
     *
     *     A real [EntitlementLedger.relocationPatch] carry clears the report **through this very
     *     clause** — it never reaches clause 3, and saying otherwise would have retired the blind
     *     spot on an argument about a clause that does not run. The carry cancels the dead key with
     *     `transferRelocOut`, so every `(donor, recipient)` **effective** magnitude there is `0` by
     *     construction and `0 ≤ effRow(livePath, …)` holds unconditionally: the comparison
     *     short-circuits here and returns before the consequence test is asked.
     *
     *     That is still not the coincidence this clause is otherwise vulnerable to, and the
     *     difference is what makes the retirement sound. The blind spot is that a *base* pair total
     *     at the live key can match the dead one **by accident** — an unrelated later [transfer]
     *     between the same two peers — so a covered comparison says nothing about whether a carry
     *     happened. A cancelled dead side is the opposite: `transferRelocOut` is written by nothing
     *     but a carry, so `effRow(deadPath) == 0` *is* provenance the lattice carries, read through
     *     a magnitude comparison. The blind spot survives only for the base-row coincidence, which
     *     no code path produces.
     *  3. **It is consequential.** Some party to those rows still has a non-zero balance
     *     stranded on the dead generation — `netInflow + transferNet − effLeafSpent ≠ 0`,
     *     the inbound half of [EntitlementLedger.holdings] evaluated where it is no longer
     *     evaluated. Without this clause every honestly drained-and-retired generation that
     *     ever carried a hand-off would be reported forever, since the rows are grow-only
     *     and its books have already closed at zero.
     *
     * **One arm fires on two clauses, not three.** Rows keyed on a generation this ledger does
     * not know are unreadable by construction: clause 1 has no liveness question left to ask and
     * clause 2 has no live key to compare against. That arm keeps the half of clause 3 that
     * survives without an edge — some party's net at the key is non-zero — so that it agrees with
     * the three-clause arm about rows that merely *cancel*, rather than reporting there what the
     * main arm deliberately does not.
     *
     * **Deliberately silent** where the group has *no* live lineage at all: that is the
     * normal window of an honest reshape (old generation retired, new one not yet active)
     * and the standing exception to §10.11's quarantine ⟺ report correspondence, shared with
     * [EntitlementLedger.holdings]. A quarantined or divergent lineage is likewise left to
     * [RecordDivergence] / [DualActiveInbound] / [LineageCycle] rather than voiced twice.
     *
     * Like every report here it is a diagnostic, not a safety gate.
     *
     * @property path the path key whose rows are no longer reachable
     */
    public data class OrphanedTransferPath(public val path: PathKey) : LedgerConflict {
        override val order: Int get() = 8
    }

    /**
     * Supply minted at more than one root — one ledger holding two trees (issue #1751).
     *
     * A ledger describes **one** tree, seeded by **one** [EntitlementLedger.bootstrap]. Merging two
     * of them is a caller mistake, and this names it. It is defence in depth, not the safety
     * argument: a [MintRecord] is bound to the root it was minted at, so each root is credited only
     * its own supply and Σ holdings still equals `mintedTotal` — the double count this reports the
     * *risk* of is already unrepresentable.
     *
     * ## The predicate reads `minted`, never the topology — and that is the whole point
     *
     * The obvious spelling, "more than one group has no inbound edge", cannot be used: **that is
     * also the shape of an honest partially-delivered topology.** Records are grow-only and arrive
     * in no particular order, so a peer holding a child edge whose parent edge has not landed yet
     * sees a group with no inbound edge — legitimately, transiently, and on healthy traffic. A
     * report keyed on that fires on every mid-delivery state with two undelivered parent edges.
     *
     * Keyed on the distinct roots named by [MintRecord] instead, the two cases separate cleanly:
     *
     *  - **"no inbound edge yet"** contributes nothing — a group is not a mint root merely because
     *    its parent edge is late, so the mid-delivery state names exactly the one root it always did.
     *  - **"a root-scoped mint credited here"** is what this counts, and a second one can only come
     *    from a second bootstrap or a [EntitlementLedger.mint] at a second group.
     *
     * The `no inbound edge` conjunct is deliberately **dropped** rather than kept: keeping it would
     * put the topology back into the predicate in the other direction, silencing the report on a
     * two-bootstrap state carrying no records at all (the bare merge — where nothing else speaks
     * either) and on one where a root has since acquired an inbound edge.
     *
     * Unlike every other report here it is **not a delivery transient in either direction**: `minted`
     * is grow-only and a root is never removed from a record, so once two roots are on a state they
     * stay, and no anti-entropy round dissolves this. It is a standing fault, not a window.
     *
     * @property roots the distinct roots supply was minted at, sorted; always two or more.
     */
    public data class MultipleRoots(public val roots: List<GroupId>) : LedgerConflict {
        override val order: Int get() = 9
    }
}
