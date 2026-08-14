package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import kotlinx.serialization.Serializable

/**
 * The replicated, conflict-free tally of who was granted what fairness
 * entitlement, who passed it down, and who spent it.
 *
 * It is the one genuinely-new CRDT of the fair-share layer: a
 * [`BoundedCounter`][us.tractat.kuilt.crdt.BoundedCounter] grown a *path*
 * dimension. Where a bounded counter escrows one shared budget across replicas,
 * this escrows a whole **tree** of budgets — indexed by the edge each grant
 * crossed — and every peer can merge its copy with any other and always agree,
 * with no clock and no central referee.
 *
 * ## What you can do with it
 *
 * Construct and merge ledger states (via [ZERO] and [bootstrap], or the internal
 * test factory) — the merge ([piece]) is a provable join-semilattice — and drive
 * the **economics** on top of it: [holdings] (derived spendable authority), the
 * conserving mutators [mint] / [delegate] / [release] / [transfer] / [spend] (each
 * returning a [Patch] or `null` when holdings are insufficient), and [validate]
 * (the integrity report).
 *
 * ## Safety vs. diagnostics
 *
 * **Safety is the local holdings check** each mutator runs on the actor's own complete
 * state before it emits a patch — a peer never spends beyond `holdings(P, self)`, with
 * zero coordination, because every term of that check reads a slot only that peer
 * writes. **[validate] is a diagnostic, not a safety gate.** It is eventually
 * consistent: on a fully-delivered state its report is exact, but under *partial*
 * delivery of a **multi-hop transfer-funded** charge it may transiently list a false
 * [LedgerConflict.PerEdgeSafety] / [LedgerConflict.PersistentNegativeHoldings] that a
 * later anti-entropy round dissolves. Each feasibility-consuming mutator carries a
 * witness that keeps the **direct and single-hop-transfer** cases from false-firing;
 * deeper transfer chains are the accepted transient. **Consumers must not hard-gate on
 * `validate().isEmpty()`** while rebalancing is in flight — gate on the mutator's `null`.
 *
 * ## One root per ledger (a standing invariant)
 *
 * A ledger describes **one** tree, with exactly one root: the group [bootstrap] was called
 * with. [holdings] credits a group's `creditIn` from the minted supply whenever that group
 * has no inbound edge, and [MintRecord] carries only a holder and an amount — it is not bound
 * to a root. So if two independently-[bootstrap]ped ledgers are [piece]d together, the merged
 * state has two rootless groups and **each of them is credited the full `mintedTotal`**,
 * double-counting every mint in the Σ-holdings conservation identity.
 *
 * Nothing in the representation prevents this, so it is a **caller invariant**: never merge
 * ledgers from different bootstraps. Binding a [MintRecord] to its root would make it
 * structural, but that is a wire-format change and is deliberately not taken here.
 *
 * ## Delta-state idiom: two patches from one base lose the first
 *
 * Every mutator reads the receiver and returns a patch carrying **absolute** slot values, and
 * the join is max. Two patches computed from the *same* base therefore do not compose — the
 * merge keeps the larger and silently drops the other:
 *
 * ```
 * val a = ledger.delegate(r, e, 10)!!   // issued(e)[r] = 10
 * val b = ledger.delegate(r, e, 5)!!    // issued(e)[r] = 5   ← also read from `ledger`
 * ledger.piece(a).piece(b)              // issued(e)[r] = 10, NOT 15
 * ```
 *
 * That is the price of absolute-value deltas, and it is what buys duplicate-delivery
 * idempotence — but it means a caller must **thread the state**: call each mutator on a
 * ledger that has already absorbed the previous patch, never fan several out from one
 * snapshot. `HeddleNode` does this by running each op inside its `Quilter.mutate` block, so
 * the op always sees fresh state.
 *
 * ## Lifecycle (H2)
 *
 * Each edge carries a [Lifecycle] in a per-edge **max-register**
 * (`PREPARED < ACTIVE < CLOSING < RETIRED`, join = max). The transitions
 * [prepare] / [activate] / [close] / [retire] climb that chain under strict
 * generation-and-drain discipline (design §5.3): [close] admits no new delegation,
 * [retire] finalizes only a **fully drained** edge (`outstanding == 0`). An edge
 * present without an explicit register entry defaults to [Lifecycle.ACTIVE] — the
 * H1b "present edge is ACTIVE" assumption, now made explicit rather than assumed.
 * Delegation is gated on [Lifecycle.ACTIVE], and two [Lifecycle.ACTIVE] inbound
 * generations for one child surface as [LedgerConflict.DualActiveInbound] with the
 * contested lineage quarantined (§5.2, §10.11).
 *
 * ## The representation
 *
 * Fourteen components, each already a join-semilattice, so [piece] is just their
 * componentwise join (the product-of-lattices idiom):
 *
 *  - [records] — the immutable topology (parent/child/weight per edge) as a
 *    grow-only **set of records per edge id**. A healthy id carries a singleton
 *    set; two divergent records under one id are **both retained** (never collapsed
 *    by a last-writer-wins on a parent pointer, which §5.2 forbids) so a later
 *    phase's `validate` can report the divergence.
 *  - [minted] — root supply, keyed by a unique [MintId] so mints union rather than
 *    collide.
 *  - `issued` / `returned` / `leafSpent` / `rollupSpent` — per-edge monotone
 *    [GCounter]s. Every `(edge, replica)` slot is written **exclusively** by that
 *    replica, so the merge is per-slot max and no honest concurrency can race.
 *  - `transfers` — peer-to-peer hand-offs at a path, a per-donor-row matrix keyed
 *    by [PathKey]; the row for a donor is written only by that donor.
 *  - the **relocation counters** — `issuedRelocIn`, `leafRelocIn`/`leafRelocOut`,
 *    `rollupRelocIn`/`rollupRelocOut`, described next.
 *  - `gauges` — the per-edge virtual-time seat register ([Gauge]), joined **componentwise**
 *    max. This is the only *multi-writer* per-edge component: unlike a counter slot, which
 *    exactly one replica writes, any peer may assert a floor for any edge. The pairing of each
 *    floor with the issuance its writer observed is what makes that safe under an order-free
 *    join — see [Gauge] and [grossVirtualService].
 *
 * The `spent` split ([leafSpent] vs [rollupSpent]) is the load-bearing choice: a
 * completed charge on a leaf path charges the leaf's own final edge in `leafSpent`
 * and every strict-prefix edge in `rollupSpent`, which keeps the conservation
 * identity topology-independent even when a former leaf later gains a child.
 *
 * All three lattice laws (idempotent, commutative, associative) hold by
 * construction, and duplicate or reordered delivery of any patch is absorbed
 * idempotently by the counters' max — convergence comes from the lattice, not from
 * event ids.
 *
 * ## Relocation counters — a net decrease without a decrement (#1665 slice 1)
 *
 * A generation is sometimes retired with entitlement still riding on it (the advisory-retire
 * race), and making the child whole means **moving** an already-recorded quantity from the
 * dead edge onto the live one. A grow-only [GCounter] cannot be decreased, so the move rides
 * a *second* monotone counter that cancels the first — the `PNCounter` idiom, applied
 * per-edge-per-slot:
 *
 * ```
 * effIssued(e)[r]      = issued(e)[r]      + issuedRelocIn(e)[r]
 * effLeafSpent(e)[r]   = leafSpent(e)[r]   + leafRelocIn(e)[r]   − leafRelocOut(e)[r]
 * effRollupSpent(e)[r] = rollupSpent(e)[r] + rollupRelocIn(e)[r] − rollupRelocOut(e)[r]
 * ```
 *
 * Every **stored** component still only grows; the effective value is *derived* and may fall,
 * exactly as `outstanding`/[holdings] already do. There is no `issuedRelocOut` — issuance is
 * never net-decreased. Because the five new families are ordinary [GCounter] maps joined
 * componentwise, [piece] stays idempotent/commutative/associative by the **same**
 * product-of-lattices argument that already covers the other eight; adding them makes the
 * CRDT strictly larger, not structurally different.
 *
 * **Slot ownership is what makes this sound.** The base counters on a *live* edge belong to
 * the data plane — replica `r` writes its own slot, and only ever a value it derived locally.
 * The relocation counters belong to the **control plane** exclusively (log apply); the data
 * plane never touches them. So a re-home adds its credit to `issuedRelocIn(t)[r]` rather than
 * fabricating an absolute on the contended base `issued(t)[r]` that `r`'s own [delegate]
 * writes concurrently — two writers on one max-joined slot would silently erase one side,
 * with conservation *and* per-edge safety blind to the loss.
 *
 * **Spend relocation rides the quiesce fence** (#1693). Moving an already-charged spend drains the
 * dead edge to zero headroom, so one straggler charge afterwards would leave a permanently
 * unclearable per-edge-safety violation. What makes it safe is that the move's magnitude is derived
 * from **log-recorded per-peer promises** rather than any peer's gossip view — see
 * [relocationPatch] and `ControlCommand.Quiesce`.
 *
 * @sample us.tractat.kuilt.heddle.sampleEntitlementLedgerMerge
 * @sample us.tractat.kuilt.heddle.sampleEntitlementLedgerLifecycle
 */
@Serializable
public class EntitlementLedger private constructor(
    private val records: Map<AttachmentId, Set<AttachmentRecord>>,
    private val minted: Map<MintId, MintRecord>,
    private val issued: Map<AttachmentId, GCounter>,
    private val returned: Map<AttachmentId, GCounter>,
    private val leafSpent: Map<AttachmentId, GCounter>,
    private val rollupSpent: Map<AttachmentId, GCounter>,
    private val transfers: Map<PathKey, Map<ReplicaId, GCounter>>,
    private val lifecycle: Map<AttachmentId, Lifecycle> = emptyMap(),
    private val issuedRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
    private val leafRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
    private val leafRelocOut: Map<AttachmentId, GCounter> = emptyMap(),
    private val rollupRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
    private val rollupRelocOut: Map<AttachmentId, GCounter> = emptyMap(),
    private val gauges: Map<AttachmentId, Gauge> = emptyMap(),
) : Quilted<EntitlementLedger> {

    /** The join: the componentwise least-upper-bound of `this` and [other]. */
    override fun piece(other: EntitlementLedger): EntitlementLedger =
        EntitlementLedger(
            records = records.mergeValues(other.records) { mine, theirs -> mine + theirs },
            minted = minted.mergeValues(other.minted) { mine, theirs -> maxOf(mine, theirs) },
            issued = issued.mergeEdgeCounters(other.issued),
            returned = returned.mergeEdgeCounters(other.returned),
            leafSpent = leafSpent.mergeEdgeCounters(other.leafSpent),
            rollupSpent = rollupSpent.mergeEdgeCounters(other.rollupSpent),
            transfers = transfers.mergeValues(other.transfers) { mine, theirs ->
                mine.mergeRows(theirs)
            },
            // The lifecycle max-register: join = max, so CLOSING/RETIRED dominates a
            // laggard's ACTIVE regardless of merge order (design §5.1, §10.10).
            lifecycle = lifecycle.mergeValues(other.lifecycle) { mine, theirs -> maxOf(mine, theirs) },
            // The relocation families are ordinary GCounter maps, so they join exactly as the
            // base counters do — the product-of-lattices argument is unchanged in shape.
            issuedRelocIn = issuedRelocIn.mergeEdgeCounters(other.issuedRelocIn),
            leafRelocIn = leafRelocIn.mergeEdgeCounters(other.leafRelocIn),
            leafRelocOut = leafRelocOut.mergeEdgeCounters(other.leafRelocOut),
            rollupRelocIn = rollupRelocIn.mergeEdgeCounters(other.rollupRelocIn),
            rollupRelocOut = rollupRelocOut.mergeEdgeCounters(other.rollupRelocOut),
            // The virtual-time gauge: a per-edge register joined COMPONENTWISE (max floor, max
            // fold) — never lexicographically by floor, which would keep a stale writer's pair
            // whole and reintroduce the double count (#1752, see [Gauge]).
            gauges = gauges.mergeValues(other.gauges) { mine, theirs -> mine.join(theirs) },
        )

    // ─────────────────────────────────────────────────────────────────────────
    // Effective counter reads — base ± relocation. Derived, never stored; the only
    // values the economics and diagnostics ever look at (see the class KDoc).
    // ─────────────────────────────────────────────────────────────────────────

    /** `issued(e)[r] + issuedRelocIn(e)[r]` — [r]'s effective issuance across [e]. */
    private fun effIssuedSlot(e: AttachmentId, r: ReplicaId): Long =
        checkedAdd(slot(issued, e, r), slot(issuedRelocIn, e, r))

    /** `leafSpent(e)[r] + leafRelocIn(e)[r] − leafRelocOut(e)[r]`. */
    private fun effLeafSpentSlot(e: AttachmentId, r: ReplicaId): Long =
        checkedSub(checkedAdd(slot(leafSpent, e, r), slot(leafRelocIn, e, r)), slot(leafRelocOut, e, r))

    /** The edge-wide (all-replica) effective issuance across [e]. */
    private fun effIssuedTotal(e: AttachmentId): Long =
        checkedAdd(counterValue(issued, e), counterValue(issuedRelocIn, e))

    /** The edge-wide effective leaf spend on [e] — the conservation-identity term. */
    private fun effLeafSpentTotal(e: AttachmentId): Long =
        checkedSub(checkedAdd(counterValue(leafSpent, e), counterValue(leafRelocIn, e)), counterValue(leafRelocOut, e))

    /** The edge-wide effective roll-up spend through [e]. */
    private fun effRollupSpentTotal(e: AttachmentId): Long =
        checkedSub(checkedAdd(counterValue(rollupSpent, e), counterValue(rollupRelocIn, e)), counterValue(rollupRelocOut, e))

    /**
     * The parent-facing [EdgeSummary] for [id], or `null` if the edge is entirely
     * unknown to this ledger. Reported at **effective** values (base ± relocation, see the
     * class KDoc), so an edge that received a re-homed generation reads the credit it now
     * carries and a drained one reads zero outstanding. `spent` is the total charged through
     * the edge — effective `leafSpent + rollupSpent`.
     */
    public fun edge(id: AttachmentId): EdgeSummary? {
        if (!isKnown(id)) return null
        return EdgeSummary(
            attachment = id,
            issued = effIssuedTotal(id),
            returned = counterValue(returned, id),
            spent = checkedAdd(effLeafSpentTotal(id), effRollupSpentTotal(id)),
        )
    }

    /**
     * Summaries of every **[Lifecycle.ACTIVE]** edge whose parent is [parent], in a
     * deterministic order (by [AttachmentId]). Non-active generations (prepared,
     * closing, retired) are excluded — this is the parent-facing scheduling view, and
     * only active edges are candidates. An edge counts if **any** record under its id
     * names [parent] *and* its lifecycle is active — divergent records are retained,
     * not collapsed, and their reconciliation is a later concern.
     */
    public fun activeChildren(parent: GroupId): List<EdgeSummary> =
        records
            .filter { (id, recs) -> recs.any { it.parent == parent } && lifecycleOf(id) == Lifecycle.ACTIVE }
            .keys
            .sorted()
            .mapNotNull { edge(it) }

    /**
     * The single [AttachmentRecord] for [id] — its parent, child and weight — or `null` if
     * [id] is unknown *or divergent* (two conflicting records under
     * one id, which a healthy ledger never has; see [validate]). The parent-facing read a
     * scheduler pairs with [edge]'s [EdgeSummary] to build a policy input.
     */
    public fun record(id: AttachmentId): AttachmentRecord? = recordOf(id)

    // ─────────────────────────────────────────────────────────────────────────
    // The virtual-time gauge (issue #1752) — the replicated seat register and the
    // read path over it. Absent ⇒ the edge reads from zero, exactly as an unseated
    // edge should.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * [id]'s stored virtual-time [Gauge], or `null` when the edge carries none — either it has
     * never been seated, or this view is simply missing the patch that seated it. The two are
     * indistinguishable here **on purpose**: absence is the seat-bump predicate (see [Gauge]),
     * and it is a *local* read, so a peer that has not seen the seat may re-seat from its own
     * front. That is safe precisely because its bump folds its own observed issuance.
     */
    public fun gauge(id: AttachmentId): Gauge? = gauges[id]

    /**
     * [id]'s **base** issuance — `Σ_r issued(id)[r]`, excluding relocation credit. This is the
     * [Gauge]'s fold axis, and it is published because a consumer holding a [gauge] cannot
     * interpret the register without it: the read is `floor + (baseIssuance − folded) / w`, and
     * [edge]'s [EdgeSummary.issued] is the *effective* value, which deliberately is not this.
     */
    public fun baseIssuance(id: AttachmentId): Long = counterValue(issued, id)

    /**
     * [id]'s **gross** virtual service — `floor + (baseIssued − folded) / w`, or `baseIssued / w`
     * when no [Gauge] is stored. `null` if [id] is unknown *or divergent* (no single record ⇒ no
     * single weight to divide by).
     *
     * ## Base issuance, not effective (issue #1752, F2)
     *
     * The fold axis is the **base** `issued` counter — deliberately *not* `effIssued`, which adds
     * `issuedRelocIn`. A relocated magnitude is *economics*: it restores a re-homed child's
     * spendable holdings, and it must not also advance the child's virtual clock, because the
     * service it accounts for was already charged against the strand's own virtual time on the
     * dead generation. Reading it here would make a relocation-receiving edge appear to have
     * consumed its entire re-homed strand the instant it received it — permanent starvation sized
     * by the strand. It is also what makes the register **arrival-order independent**: a
     * `reconcileStranded` patch touches no term of this read, so it cannot matter whether it lands
     * before or after the edge is seated.
     *
     * @throws ArithmeticException if the exact arithmetic would exceed `Long` (§10.12 — a
     *   deterministic throw, never a silent wrap; see [CheckedMath]).
     */
    public fun grossVirtualService(id: AttachmentId): Rational? =
        recordOf(id)?.let { grossVirtualService(id, it.weight) }

    /**
     * [id]'s virtual service net of returns — `grossVirtualService − returned / w` (design §7.1's
     * `b + committedService / weight`, with the gauge supplying `b` and the fold). `null` if [id]
     * is unknown or divergent.
     *
     * @throws ArithmeticException if the exact arithmetic would exceed `Long`.
     */
    public fun virtualService(id: AttachmentId): Rational? {
        val w = recordOf(id)?.weight ?: return null
        return grossVirtualService(id, w) - perWeight(counterValue(returned, id), w)
    }

    /** [grossVirtualService] once the edge's single weight is in hand. */
    private fun grossVirtualService(id: AttachmentId, w: Weight): Rational =
        grossVirtualServiceAt(id, w, counterValue(issued, id))

    /**
     * The gross virtual service [id] would read at base issuance [issuance] — the same arithmetic
     * as [grossVirtualService], evaluated at a *hypothetical* issuance rather than the stored one.
     * This is what a checkpoint's floor is: the value the writer asserts *after* its own grant
     * lands. Sharing the arithmetic is deliberate — a checkpoint computed by a second copy of this
     * expression could drift from the read that has to agree with it.
     */
    private fun grossVirtualServiceAt(id: AttachmentId, w: Weight, issuance: Long): Rational =
        gauges[id].grossVirtualServiceAt(issuance, w)

    /**
     * The [Lifecycle] of [id], or `null` if [id] is entirely unknown to this ledger.
     * A known edge with no explicit register entry reads as [Lifecycle.ACTIVE] (the
     * H1b default); the transitions [prepare] / [activate] / [close] / [retire] write
     * the register explicitly.
     *
     * **This derived read is not the register value and is not monotone.** An observer
     * that has seen only a counter patch (e.g. `delegate`) but not the edge's `prepare`
     * reads the default [Lifecycle.ACTIVE]; when the lagging `prepare` (carrying
     * [Lifecycle.PREPARED]) later merges, the read *regresses* ACTIVE→PREPARED. The
     * stored register itself is a monotone max-register (a real promotion never regresses);
     * only the default-for-absent read is transient, and it self-heals once the edge's
     * own lifecycle entry has arrived. Do not treat a single derived read as authoritative
     * mid-convergence.
     */
    public fun lifecycle(id: AttachmentId): Lifecycle? = if (isKnown(id)) lifecycleOf(id) else null

    /** Whether [id] is mentioned by any component (records or any counter). */
    private fun isKnown(id: AttachmentId): Boolean =
        id in records || id in issued || id in returned || id in leafSpent || id in rollupSpent ||
            id in lifecycle || id in issuedRelocIn || id in leafRelocIn || id in leafRelocOut ||
            id in rollupRelocIn || id in rollupRelocOut || id in gauges

    /** The lifecycle of a **known** edge: the stored value, or [Lifecycle.ACTIVE] by default. */
    private fun lifecycleOf(id: AttachmentId): Lifecycle = lifecycle[id] ?: Lifecycle.ACTIVE

    /** True when [id] can still carry entitlement (active or draining) — used for lineage. */
    private fun isLiveEdge(id: AttachmentId): Boolean =
        lifecycleOf(id) == Lifecycle.ACTIVE || lifecycleOf(id) == Lifecycle.CLOSING

    // ─────────────────────────────────────────────────────────────────────────
    // Topology helpers (H1b treats every present, singleton-recorded edge as ACTIVE)
    // ─────────────────────────────────────────────────────────────────────────

    /** The single record for [id], or `null` if [id] is unknown *or divergent* (`size > 1`). */
    private fun recordOf(id: AttachmentId): AttachmentRecord? = records[id]?.singleOrNull()

    /**
     * The **live** edges from the root down to [group]'s inbound edge, in root→group
     * order (empty when [group] is the root). Only [Lifecycle.ACTIVE]/[Lifecycle.CLOSING]
     * edges count as a live path — a closing edge still drains, but prepared and retired
     * edges carry nothing. `null` signals the lineage is **quarantined** and no holdings
     * may be derived:
     *  - a divergent record on the path ([LedgerConflict.RecordDivergence]);
     *  - two live inbound edges into one group — the [LedgerConflict.DualActiveInbound]
     *    topology fork, now reported (§5.2, §10.11) — so delegation across either is refused;
     *  - a group whose only inbound edges are all prepared/retired (no live path to the
     *    root — e.g. mid-reparent, before the new generation activates);
     *  - a cycle ([LedgerConflict.LineageCycle]).
     *
     * Of those four, three are reported by [validate]; the **no-live-path** case deliberately
     * is not. It is the one quarantine that is a *normal* step of an honest reshape — the
     * window after the old generation retires and before the new one activates — so reporting
     * it would flag healthy traffic. It is the standing exception to §10.11's
     * quarantine ⟺ report correspondence, and it clears as soon as the new generation
     * activates.
     */
    private fun lineageEdges(group: GroupId): List<AttachmentId>? = walkLineage(group).edges

    /**
     * One root-ward walk of [group]'s live lineage — the shared implementation behind
     * [lineageEdges] and [validate]'s cycle report, so the two can never disagree about what
     * "quarantined" means.
     *
     * [LineageWalk.onCycle] is `true` only when the walk re-entered **[group] itself**, i.e.
     * [group] lies *on* a cycle rather than merely below one. A group below a cycle is
     * quarantined too, but listing it would flood the report with the entire subtree; each
     * loop member re-enters at its own starting point, so every cycle is reported exactly
     * once per member and never for a descendant.
     */
    private fun walkLineage(group: GroupId): LineageWalk {
        val edges = ArrayDeque<AttachmentId>()
        val seen = HashSet<GroupId>()
        var cur = group
        while (true) {
            if (!seen.add(cur)) return LineageWalk(edges = null, onCycle = cur == group) // cycle
            val inboundIds = records.filter { (_, recs) -> recs.any { it.child == cur } }.keys
            if (inboundIds.isEmpty()) break // reached the root
            val liveInbound = inboundIds.filter { isLiveEdge(it) }
            if (liveInbound.isEmpty()) return LineageWalk.QUARANTINED // no live path to the root
            if (liveInbound.size > 1) return LineageWalk.QUARANTINED // dual live inbound
            val id = liveInbound.single()
            val rec = recordOf(id) ?: return LineageWalk.QUARANTINED // divergent record on the lineage
            edges.addFirst(id)
            cur = rec.parent
        }
        return LineageWalk(edges = edges.toList(), onCycle = false)
    }

    /**
     * The child edges of [group] — every edge **any** of whose records names [group] as
     * parent, sorted. Uses `any` (matching [activeChildren]/[lineageEdges]), deliberately
     * *not* `singleOrNull`: were a child edge to go divergent (`size > 1`) and drop out of
     * this set, its `issued(c)[r] − returned(c)[r]` would stop being subtracted and the
     * parent's [holdings] would **inflate** — re-spendable authority manufactured from a
     * conflict. Keeping the divergent child in the subtraction converts that into safe
     * *deflation*: the authority is frozen, never created (a double-spend the sum-wise
     * check alone would miss).
     */
    private fun childEdges(group: GroupId): List<AttachmentId> =
        records.filter { (_, recs) -> recs.any { it.parent == group } }.keys.sorted()

    /** True when [group] has no child edges — the only groups at which service may be spent. */
    public fun isLeaf(group: GroupId): Boolean = childEdges(group).isEmpty()

    // ─────────────────────────────────────────────────────────────────────────
    // holdings — spendable authority, derived, never stored (design §4.2)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The units [r] may spend at [group], derived from the merged state:
     *
     * ```
     * holdings = creditIn + transferNet(f, r)
     *          − Σ_{c ∈ childEdges(group)} (issued(c)[r] − returned(c)[r])
     *          − leafSpent(f)[r]
     * ```
     *
     * where `f = inbound(group)`; `creditIn` is the sum of minted amounts held by
     * [r] at the root, else `effIssued(f)[r] − returned(f)[r]`. `effLeafSpent(f)[r]` is
     * subtracted **unconditionally** — no `isLeaf` test — which keeps conservation
     * topology-independent when a former leaf later gains a child (design fix 1). Both the
     * issuance and the leaf-spend terms read **effective** values (base ± relocation), so a
     * re-homed generation credits the child that now owns it.
     *
     * Every subtracted term reads a slot only [r] writes, so a peer's local
     * feasibility check is sound with zero coordination. If [group]'s lineage is
     * quarantined (see [lineageEdges]) this returns `0` — quarantine is transitive
     * down the path (design §4.6). May be negative (a real overspell net; see
     * [LedgerConflict.PersistentNegativeHoldings]).
     */
    public fun holdings(group: GroupId, r: ReplicaId): Long {
        val lineage = lineageEdges(group) ?: return 0L
        val f = lineage.lastOrNull()
        val pathKey = if (f == null) PathKey.ROOT else PathKey.of(f)
        val creditIn = if (f == null) mintedHeldBy(r) else netInflow(f, r)
        var acc = checkedAdd(creditIn, transferNet(pathKey, r))
        for (c in childEdges(group)) {
            acc = checkedSub(acc, netInflow(c, r))
        }
        if (f != null) acc = checkedSub(acc, effLeafSpentSlot(f, r))
        return acc
    }

    /** `effIssued(edge)[r] − returned(edge)[r]` — [r]'s net inflow across one edge. */
    private fun netInflow(edge: AttachmentId, r: ReplicaId): Long =
        checkedSub(effIssuedSlot(edge, r), slot(returned, edge, r))

    /**
     * Σ minted amounts credited to [r] (the root's `creditIn`). Every mint counts, whatever
     * root it was bootstrapped for — see the class KDoc's one-root-per-ledger invariant.
     */
    private fun mintedHeldBy(r: ReplicaId): Long =
        minted.values.fold(0L) { acc, m -> if (m.holder == r) checkedAdd(acc, m.amount) else acc }

    /** `Σ_s transfers[pathKey][s][r] − Σ_t transfers[pathKey][r][t]` — [r]'s net transfer at a path. */
    private fun transferNet(pathKey: PathKey, r: ReplicaId): Long {
        val rows = transfers[pathKey] ?: return 0L
        var inflow = 0L
        for ((_, row) in rows) inflow = checkedAdd(inflow, row.count(r))
        val outflow = rows[r]?.let(::checkedCounterValue) ?: 0L
        return checkedSub(inflow, outflow)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mutators — house idiom: check feasibility on `this`, return Patch?/null. The
    // `null` (insufficient holdings on the actor's own complete state) is the SAFETY
    // gate. Each feasibility-consuming patch also carries a witness (design fix 2,
    // narrowed): the observed credit slots its holdings check read along the lineage,
    // plus a depth-1 backing of any donor who transferred into the actor — at their
    // absolute values (max-safe). This keeps `validate` (a diagnostic, not a gate)
    // from false-firing on the direct and single-hop-transfer cases under partial
    // delivery; a multi-hop transfer-funded charge is an accepted transient.
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // Topology transitions — climb the lifecycle chain under strict generation-and-
    // drain discipline (design §5.1–5.3). Same house idiom: check on `this`, return a
    // Patch?/null. The register merges by max, so a promotion never regresses under any
    // merge order (closure dominance, §10.10).
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Introduce a new attachment generation: record [record] and mark its edge
     * [Lifecycle.PREPARED] (design §5.3 *create*). `null` if the edge id is **already
     * known** to this ledger — each generation is prepared exactly once; changing a
     * weight or parent mints a *new* id, never re-preparing an old one. No entitlement
     * may cross a prepared edge until [activate].
     */
    public fun prepare(record: AttachmentRecord): Patch<EntitlementLedger>? {
        if (isKnown(record.id)) return null
        return Patch(
            of(
                records = mapOf(record.id to setOf(record)),
                lifecycle = mapOf(record.id to Lifecycle.PREPARED),
            ),
        )
    }

    /**
     * Promote [edge] to [Lifecycle.ACTIVE] — delegation across it is now admitted
     * (design §5.3). `null` if [edge] is unknown/divergent, or already
     * [Lifecycle.CLOSING]/[Lifecycle.RETIRED] (a closing edge cannot be resurrected —
     * closure dominance, §10.10). Idempotent from prepared/active.
     */
    public fun activate(edge: AttachmentId): Patch<EntitlementLedger>? {
        recordOf(edge) ?: return null
        if (lifecycleOf(edge) >= Lifecycle.CLOSING) return null
        return Patch(of(lifecycle = mapOf(edge to Lifecycle.ACTIVE)))
    }

    /**
     * Promote [edge] to [Lifecycle.CLOSING] — no new delegation is admitted, but spend
     * and release still drain it (design §5.3). `null` if [edge] is unknown/divergent,
     * or already [Lifecycle.RETIRED]. Idempotent from closing.
     */
    public fun close(edge: AttachmentId): Patch<EntitlementLedger>? {
        recordOf(edge) ?: return null
        if (lifecycleOf(edge) == Lifecycle.RETIRED) return null
        return Patch(of(lifecycle = mapOf(edge to Lifecycle.CLOSING)))
    }

    /**
     * Finalize a **drained** edge: promote [edge] to [Lifecycle.RETIRED] (design §5.3).
     * `null` unless [edge] is currently [Lifecycle.CLOSING] **and** fully drained
     * ([EdgeSummary.outstanding] `== 0`, i.e. every delegated unit has been returned or
     * spent). This is the drain gate: a retire is refused while entitlement is still
     * outstanding across the edge. Once retired, nothing crosses again and its history
     * stays queryable via [edge] forever.
     *
     * The patch carries a **drain witness** — the edge's observed `issued`/`returned`/
     * `leafSpent`/`rollupSpent` counter slots at their absolute values (max-safe, same
     * house idiom as the feasibility mutators' [witness]). Retirement is causally after
     * the drain, but that causality crosses writer streams; without the witness a laggard
     * holding `{delegate, close, retire}` but not the draining `release`/`spend` patch
     * would compute `outstanding != 0` against RETIRED and false-fire
     * [LedgerConflict.ClosureViolation] on honest single-hop delivery. The witness ships
     * the drained counters alongside RETIRED so the retired-and-drained state travels as one.
     */
    public fun retire(edge: AttachmentId): Patch<EntitlementLedger>? {
        recordOf(edge) ?: return null
        if (lifecycleOf(edge) != Lifecycle.CLOSING) return null
        val summary = edge(edge) ?: return null
        if (summary.outstanding != 0L) return null
        val retired = of(lifecycle = mapOf(edge to Lifecycle.RETIRED))
        return Patch(retired.piece(drainWitness(edge)))
    }

    /**
     * The counter slots of [edge] at their observed absolute values — the witness a
     * [retire] patch carries so its RETIRED marker never outruns the drain that justified
     * it. Absolute values are max-safe: re-delivery is absorbed idempotently.
     */
    private fun drainWitness(edge: AttachmentId): EntitlementLedger = of(
        issued = issued[edge]?.let { mapOf(edge to it) } ?: emptyMap(),
        returned = returned[edge]?.let { mapOf(edge to it) } ?: emptyMap(),
        leafSpent = leafSpent[edge]?.let { mapOf(edge to it) } ?: emptyMap(),
        rollupSpent = rollupSpent[edge]?.let { mapOf(edge to it) } ?: emptyMap(),
        // Any relocation already recorded on the edge travels with the drain: the witness is a
        // republish of *observed* values, so max-join absorbs it, and shipping the base without
        // its cancelling relocation would let a laggard read a spend the edge no longer carries.
        issuedRelocIn = issuedRelocIn[edge]?.let { mapOf(edge to it) } ?: emptyMap(),
        leafRelocIn = leafRelocIn[edge]?.let { mapOf(edge to it) } ?: emptyMap(),
        leafRelocOut = leafRelocOut[edge]?.let { mapOf(edge to it) } ?: emptyMap(),
        rollupRelocIn = rollupRelocIn[edge]?.let { mapOf(edge to it) } ?: emptyMap(),
        rollupRelocOut = rollupRelocOut[edge]?.let { mapOf(edge to it) } ?: emptyMap(),
        // The gauge travels with any republished base `issued`, always (#1752). This witness is
        // the one other place in the ledger that republishes an edge's base issuance, and a
        // republish that shipped counters without their checkpoint is *precisely* the patch-split
        // the negative control shows reintroducing F1's double count — a receiver would hold the
        // advanced counter against an absent gauge and its next seat bump would count the service
        // twice. Harmless today only because a witnessed edge is RETIRED and so never a scheduling
        // candidate; co-carrying it costs one line and does not depend on that staying true.
        gauges = gauges[edge]?.let { mapOf(edge to it) } ?: emptyMap(),
    )

    /**
     * [edge]'s drain witness — its counter slots at their observed absolute values — read from **this**
     * (the proposer's) state so the **H5 control plane** can carry it in a governed [Retire] command.
     * Governed retire applies against a topology-only projection whose counters are empty, so without
     * this the published RETIRED patch would carry no witness and a laggard that received RETIRED before
     * the draining `release`/`spend` deltas would transiently false-fire [LedgerConflict.ClosureViolation].
     * Carrying the proposer's observed witness ships the drained counters alongside RETIRED (§5.1, §10.10).
     * `internal` — control-plane support.
     */
    internal fun drainWitnessFor(edge: AttachmentId): EntitlementLedger = drainWitness(edge)

    /**
     * The **§4 generation-move**: drain each fenced retired edge in [finals] and re-home everything it
     * carried — net inflow *and* already-charged service — onto the child's live inbound [liveEdge]
     * (relocation design §4, issue #1665 slice 3 / #1693). This is the conserving recovery for the
     * advisory-retire race, and it is **pure**: acked finals in, patch out.
     *
     * ## Why the child needs its FULL net inflow re-homed
     *
     * When a gossip-lagged peer retires an edge whose `delegate` it had not yet merged, the edge is
     * RETIRED with `outstanding != 0` and its budget is stranded on a generation no longer on the live
     * lineage (`GovernedHeddleNode.retire`). Once the child is legally reparented onto a fresh inbound
     * edge with `issued = 0`, [holdings] at the child derive **permanently negative** — a persistent
     * [LedgerConflict.PersistentNegativeHoldings] / [LedgerConflict.PerEdgeSafety] with zero real
     * overspend. `release` refuses a retired edge, so the data plane cannot recover it.
     *
     * The child's holdings under the old edge `s` were credited by its **full net inflow**
     * `n = effIssued(s)[r] − returned(s)[r]` — *not* its `outstanding` (which nets out `spent(s)`). So
     * making the child whole means re-homing `n`, and relocating the `sp = effLeafSpent + effRollupSpent`
     * that rode with it so `s` stays per-edge-safe.
     *
     * ## The patch, per fenced edge `s` and replica `r`
     *
     * ```
     * returned(s)[r]        → iss           # drain: netInflow(s)[r] → 0, outstanding(s) → 0
     * issued/leafSpent/rollupSpent(s)[r] → the acked finals   # republished (observer completeness, §6.4)
     * leafRelocOut(s)[r]    += lsp          leafRelocIn(t)[r]   += lsp
     * rollupRelocOut(s)[r]  += rsp          rollupRelocIn(t)[r] += rsp
     * issuedRelocIn(t)[r]   += n            # NEVER the live edge's base `issued` slot (#1691)
     * ```
     *
     * Every move sends equal and opposite relocation credits, so `Σ effLeafSpent` — the conservation
     * term — is invariant; the net-inflow half telescopes (the parent's subtraction of `netInflow(s)`
     * drops by `n` exactly as the child's `creditIn` rises by `n`). `mintedTotal` is untouched.
     *
     * ## The two inputs, and why neither is read from a gossip view
     *
     *  - **[finals]** — per fenced edge, each replica's log-recorded [SlotFinals] from its
     *    [ControlCommand.QuiesceAck]. Base slots are single-writer, and the acking replica has
     *    marked the edge locally unwritable, so these values are *final* and consensus-recorded.
     *  - **`this`** — the control plane's own accumulated relocation state ([FenceState.relocations]).
     *    The relocation families and a fenced edge's base slots are control-plane-owned exclusively
     *    (§6.3), so `this` holds their true values with no data-plane read at all.
     *
     * Both are functions of the committed log prefix, so **every peer derives the identical patch**
     * (Raft §5.4.3). That is what closes the Wall-A magnitude residual #1669 documented and what
     * removes §12.3's magnitude-freshness residual: a second move onto one live edge accumulates on
     * `this` rather than max-colliding with the first.
     *
     * ## Preconditions, checked per replica against the acked finals
     *
     *  - `n ≥ 0` — a replica left **net-negative** on `s` (the transfer-tangle case, PR #1669
     *    `break4`) would need its transfer rows moved too. Out of scope ⇒ [Relocation.Refused].
     *  - `n ≥ sp` ⟺ `outstanding(s)[r] ≥ 0`. Holds for a healthy strand; refused otherwise.
     *  - `lsp ≥ 0`, `rsp ≥ 0` — an acked base below what has already been relocated out is
     *    protocol-impossible, so it fails closed rather than moving a negative quantity.
     *
     * ## …and one checked on the whole strand, against `this` rather than the acks (#2366)
     *
     * `transfers` is keyed by `PathKey.of(edge)`, so a move would leave the rows on the dead
     * generation's key where [holdings] no longer reads them — silently reassigning a recipient's
     * entitlement to the donor, with conservation intact and [validate] empty. The `n < 0`
     * precondition above is the *loud* half of that tangle: it needs the recipient to have also
     * spent or released across `s`. So a fenced edge carrying **any** transfer row is refused,
     * which is what the first bullet's "out of scope" has always promised.
     *
     * This one reads `this`, and `this` is the caller's choice — so it fires wherever the receiver
     * carries the rows and **not** on the H5 control-plane path, whose receiver ([FenceState.relocations])
     * is log-pure and never carries a transfer at all. That path stays blind until the rows become a
     * consensus fact carried in the [ControlCommand.QuiesceAck] (issue #2377). Determinism is
     * untouched either way: every peer passes the same log-pure receiver, so every peer still derives
     * the identical outcome.
     *
     * The live edge needs **no** data-plane read: the move adds `sp` to `t`'s effective spend and
     * `n ≥ sp` to its effective issuance, so per-edge safety on `t` survives by increment.
     *
     * `Relocation.Nothing` when every fenced edge is already drained (`n = 0, sp = 0`) — the
     * deterministic idempotence guard of §5.4 (iii): a second `Reconcile` for one child moves nothing.
     *
     * A replica that authored slots on `s` but is **absent from [finals]** (never acked) is left
     * untouched: its strand stays stranded on the same terms as a crashed peer's holdings
     * (`heddle-design.md` §8.1). Draining a slot whose owner never promised is precisely the
     * fabricate-beyond-owner violation the fence exists to prevent.
     *
     * `internal` — the caller is [HeddleControlPlane]'s apply loop, which alone holds log-pure inputs.
     */
    internal fun relocationPatch(
        liveEdge: AttachmentId,
        finals: Map<AttachmentId, Map<ReplicaId, SlotFinals>>,
    ): Relocation {
        // ── pass 1: derive every fenced slot, and total each replica's cover and charge.
        val drainable = ArrayList<DrainedSlot>()
        val coverByReplica = HashMap<ReplicaId, Long>()
        val chargeByReplica = HashMap<ReplicaId, Long>()

        for ((s, perReplica) in finals.entries.sortedBy { it.key }) {
            for ((r, acked) in perReplica.entries.sortedBy { it.key }) {
                val relIssIn = slot(issuedRelocIn, s, r)
                val relLeafIn = slot(leafRelocIn, s, r)
                val relLeafOut = slot(leafRelocOut, s, r)
                val relRollIn = slot(rollupRelocIn, s, r)
                val relRollOut = slot(rollupRelocOut, s, r)
                // §12.1: the drain target is effIssued(s)[r], not the base — s may itself have
                // received an earlier relocation, and draining against the base under-drains it.
                val iss = checkedAdd(acked.issued, relIssIn)
                // The control plane may already have drained this slot; max keeps the join honest.
                val ret = maxOf(acked.returned, slot(returned, s, r))
                val lsp = checkedSub(checkedAdd(acked.leafSpent, relLeafIn), relLeafOut)
                val rsp = checkedSub(checkedAdd(acked.rollupSpent, relRollIn), relRollOut)
                if (lsp < 0L || rsp < 0L) {
                    return Relocation.Refused(
                        "relocate refused: effective spend on ${s.value} for ${r.value} is negative " +
                            "(leaf=$lsp, rollup=$rsp) — an acked base below what was already relocated out",
                    )
                }
                val n = checkedSub(iss, ret)
                val sp = checkedAdd(lsp, rsp)
                if (n < 0L) {
                    return Relocation.Refused(
                        "relocate refused: ${r.value} is net-negative on ${s.value} (n=$n) — a " +
                            "transfer-tangled strand needs its transfer rows moved too (out of scope)",
                    )
                }
                if (n == 0L && sp == 0L) continue // already drained: contribute nothing
                coverByReplica[r] = checkedAdd(coverByReplica[r] ?: 0L, n)
                chargeByReplica[r] = checkedAdd(chargeByReplica[r] ?: 0L, sp)
                drainable += DrainedSlot(
                    edge = s,
                    replica = r,
                    acked = acked,
                    relIssIn = relIssIn,
                    relLeafIn = relLeafIn,
                    relRollIn = relRollIn,
                    relLeafOut = relLeafOut,
                    relRollOut = relRollOut,
                    iss = iss,
                    n = n,
                    lsp = lsp,
                    rsp = rsp,
                )
            }
        }
        if (drainable.isEmpty()) return Relocation.Nothing

        // ── the transfer-row precondition (#2366), checked once the move is known to be non-empty.
        //
        // `transfers` is keyed by `PathKey.of(edge)` — the GENERATION's id, not the child group — so a
        // move re-homes the counter families onto `t` and leaves the rows on `s`, where `holdings` no
        // longer reads them. The recipient's credit and the donor's debit both vanish and the donor
        // silently recovers what it gave away, with NOTHING objecting: `Σ_r transferNet(pathKey, r) = 0`,
        // so abandoning a whole path key is sum-preserving and conservation is structurally blind;
        // `validate()` is silent because the recipient lands on 0, not below.
        //
        // The `n < 0` guard above is the same tangle's LOUD half — it needs the recipient to have also
        // spent or released across `s`. A recipient who merely HOLDS transferred credit has no slot on
        // the edge at all, is absent from `replicasOnEdge`/`baseFinalsOn`, and reaches here unseen. So
        // this widens the refusal the KDoc already promises rather than adding a new one.
        //
        // Checked AFTER the per-slot preconditions so each keeps its own pin: an edge that is both
        // net-negative and transfer-tangled still refuses on `n < 0`, the guard that owns that case.
        // Checked after the `drainable.isEmpty()` return so an already-drained strand still reads
        // `Nothing` — nothing moves there, so there is no abandonment to prevent.
        for (s in finals.keys.sorted()) {
            val donors = transfers[PathKey.of(s)]?.keys.orEmpty()
            if (donors.isNotEmpty()) {
                return Relocation.Refused(
                    "relocate refused: ${s.value} carries transfer rows (donors " +
                        "${donors.map { it.value }.sorted()}) that the move would abandon on its path key — " +
                        "a transfer-tangled strand needs its transfer rows moved too (out of scope)",
                )
            }
        }

        // ── the `n ≥ sp` precondition, quantified per (child, replica) over the WHOLE derivation
        // rather than per edge (#1895). The re-home lands a charge on the live edge at charge time
        // but its covering issuance only arrives with this very Reconcile, so if that edge is itself
        // fenced before the recovery runs it reads spend-with-no-cover and a per-edge test refused
        // the whole child forever. The cover is genuinely there — on the sibling fenced edge the
        // entitlement came from before the re-home split charge from credit — so the honest question
        // is whether this replica's fenced edges TOGETHER cover what was charged through them.
        //
        // Aggregated per replica and NEVER across replicas: entitlement is per-replica, so funding
        // one replica's spend from another's surplus would be a real conservation break, not a fix.
        for (r in chargeByReplica.keys.sortedBy { it.value }) {
            val charge = chargeByReplica.getValue(r)
            // getValue, not `?: 0L`: the two maps are written in lockstep under the same key two
            // statements apart, so their key sets are identical by construction. A silent zero here
            // would refuse undiagnosably if that ever stopped being true.
            val cover = coverByReplica.getValue(r)
            if (cover < charge) {
                return Relocation.Refused(
                    "relocate refused: ${r.value}'s fenced edges cannot cover what was charged " +
                        "through them (cover=$cover < spent=$charge)",
                )
            }
        }

        // ── pass 2: drain each fenced slot, and accumulate the live edge's credit.
        val drain = EdgePatchBuilder()
        val creditIssued = HashMap<ReplicaId, Long>()
        val creditLeaf = HashMap<ReplicaId, Long>()
        val creditRollup = HashMap<ReplicaId, Long>()
        for (d in drainable) {
            // ── the fenced edge: drain it, and republish every premise of that conclusion so no
            // observer can hold `relocOut` without the base it cancels (§6.4 / §5.3 proviso).
            drain.put(CounterFamily.RETURNED, d.edge, d.replica, d.iss)
            drain.put(CounterFamily.ISSUED, d.edge, d.replica, d.acked.issued)
            drain.put(CounterFamily.LEAF_SPENT, d.edge, d.replica, d.acked.leafSpent)
            drain.put(CounterFamily.ROLLUP_SPENT, d.edge, d.replica, d.acked.rollupSpent)
            drain.put(CounterFamily.ISSUED_RELOC_IN, d.edge, d.replica, d.relIssIn)
            drain.put(CounterFamily.LEAF_RELOC_IN, d.edge, d.replica, d.relLeafIn)
            drain.put(CounterFamily.ROLLUP_RELOC_IN, d.edge, d.replica, d.relRollIn)
            drain.put(CounterFamily.LEAF_RELOC_OUT, d.edge, d.replica, checkedAdd(d.relLeafOut, d.lsp))
            drain.put(CounterFamily.ROLLUP_RELOC_OUT, d.edge, d.replica, checkedAdd(d.relRollOut, d.rsp))

            // ── the live edge: accumulate across every fenced edge in THIS move before adding
            // the standing control-plane total, so two edges re-homing onto one `t` sum.
            creditIssued[d.replica] = checkedAdd(creditIssued[d.replica] ?: 0L, d.n)
            creditLeaf[d.replica] = checkedAdd(creditLeaf[d.replica] ?: 0L, d.lsp)
            creditRollup[d.replica] = checkedAdd(creditRollup[d.replica] ?: 0L, d.rsp)
        }

        for ((r, add) in creditIssued) {
            drain.put(CounterFamily.ISSUED_RELOC_IN, liveEdge, r, checkedAdd(slot(issuedRelocIn, liveEdge, r), add))
        }
        for ((r, add) in creditLeaf) {
            drain.put(CounterFamily.LEAF_RELOC_IN, liveEdge, r, checkedAdd(slot(leafRelocIn, liveEdge, r), add))
        }
        for ((r, add) in creditRollup) {
            drain.put(CounterFamily.ROLLUP_RELOC_IN, liveEdge, r, checkedAdd(slot(rollupRelocIn, liveEdge, r), add))
        }
        return Relocation.Moved(drain.build())
    }

    /**
     * The **base** counter slots of [edge], by replica — the shape a [ControlCommand.QuiesceAck]
     * declares. Read on the acking peer's own complete state (for its own slot) at the moment it
     * marks [edge] locally unwritable, or over every replica for a test that models a converged view.
     * `internal` — barrier + test support.
     */
    internal fun baseFinalsOn(edge: AttachmentId): Map<ReplicaId, SlotFinals> =
        replicasOnEdge(edge).associateWith { r -> baseFinalsOn(edge, r) }

    /** [r]'s own base counter slots on [edge] — its [ControlCommand.QuiesceAck] payload. */
    internal fun baseFinalsOn(edge: AttachmentId, r: ReplicaId): SlotFinals = SlotFinals(
        issued = slot(issued, edge, r),
        returned = slot(returned, edge, r),
        leafSpent = slot(leafSpent, edge, r),
        rollupSpent = slot(rollupSpent, edge, r),
    )

    /** The replicas that authored any counter slot on [edge] — base or relocation. */
    private fun replicasOnEdge(edge: AttachmentId): Set<ReplicaId> {
        val out = HashSet<ReplicaId>()
        for (counters in allEdgeCounters()) {
            counters[edge]?.let { out += it.replicas() }
        }
        return out
    }

    /** Every per-edge [GCounter] family — base and relocation — in one list. */
    private fun allEdgeCounters(): List<Map<AttachmentId, GCounter>> = listOf(
        issued, returned, leafSpent, rollupSpent,
        issuedRelocIn, leafRelocIn, leafRelocOut, rollupRelocIn, rollupRelocOut,
    )

    /**
     * Introduce root supply: credit [holder] with [amount] units under [mintId].
     * Control-plane only (design §9); the one non-conserving op and the only mutator
     * with no feasibility gate, so it never returns `null`. [mintId] MUST be unique
     * per mint act so distinct acts union rather than max-collide (design fix 4).
     */
    public fun mint(mintId: MintId, holder: ReplicaId, amount: Long): Patch<EntitlementLedger> {
        require(amount >= 0L) { "mint amount must be non-negative, was $amount" }
        return Patch(of(minted = mapOf(mintId to MintRecord(holder, amount))))
    }

    /**
     * [r] delegates [amount] down [edge], moving authority from the parent group into
     * the child. `null` if:
     *  - [edge] is unknown or divergent;
     *  - [edge] is not [Lifecycle.ACTIVE] (prepared/closing/retired admit no new
     *    delegation — design §5.1);
     *  - the child's inbound topology is ambiguous (two live inbound edges — a
     *    [LedgerConflict.DualActiveInbound] — quarantines the contested lineage, §10.11);
     *  - [r]'s holdings at the parent are insufficient.
     *
     * Bumps `issued(edge)[r]`, and — **in this same patch** — writes the [Gauge] checkpoint for
     * the issuance that bump produces.
     *
     * ## The checkpoint is patch-atomic with the counter it folds, and that is load-bearing
     *
     * The checkpoint asserts *"at base issuance `issuedAfter`, this edge's gross virtual service
     * was `grossEv(issuedAfter)`"* — computed in this peer's own view, which is the only view it
     * can honestly speak for. Riding the **same** [Patch] as the `issued` bump is what puts F1's
     * precondition outside the reachable sublattice: every reachable view is a join of published
     * patches, so no view can hold this edge's advanced counter while missing the checkpoint that
     * accounts for it. Unrepresentable, rather than merely unlikely.
     *
     * **Do not split this into two patches, and do not republish base `issued` for a schedulable
     * edge without its gauge.** Either move restores exactly the state the negative control in
     * `GaugeWriteRulesTest` shows reintroducing the full double count. That is why [drainWitness]
     * co-carries the gauge, and it is the standing constraint on any future boot-time republish of
     * authored base slots (see #1783).
     *
     * ## Seat before you delegate — this method will not do it for you
     *
     * Delegating down an edge that carries **no** gauge silently seats it at its own origin: the
     * checkpoint written here is `grossEv(issuedAfter)`, which for an unseated edge is
     * `issuedAfter / w`, and [seat] refuses forever afterwards because a gauge now exists. The join
     * cannot repair it either — `max` on a floor that is already the minimum is a no-op. So a
     * caller driving this must seat first; `HeddleNode.settleJoiners` does, and `HeddleNode.pickOne`
     * additionally refuses a gauge-absent edge as a candidate so a mid-round `Activate` cannot slip
     * through the gap.
     */
    public fun delegate(r: ReplicaId, edge: AttachmentId, amount: Long): Patch<EntitlementLedger>? {
        require(amount >= 1L) { "delegate amount must be positive, was $amount" }
        val rec = recordOf(edge) ?: return null
        if (lifecycleOf(edge) != Lifecycle.ACTIVE) return null
        // The child's live inbound must be unambiguous (exactly this edge); a contested
        // child (two active inbound) has a null lineage, so delegation across either is refused.
        if (lineageEdges(rec.child) == null) return null
        if (amount > holdings(rec.parent, r)) return null
        val lineage = lineageEdges(rec.parent) ?: return null
        val issuedAfter = checkedAdd(counterValue(issued, edge), amount)
        val bump = of(
            issued = mapOf(edge to bumpedSlot(issued, edge, r, amount)),
            gauges = mapOf(edge to Gauge(grossVirtualServiceAt(edge, rec.weight, issuedAfter), issuedAfter)),
        )
        return Patch(bump.piece(witness(r, lineage)))
    }

    /**
     * Seat [edge] at [front] — write its first [Gauge], keyed on **gauge absence**.
     *
     * `null` if [edge] is unknown or divergent, or if it **already carries a gauge**. That
     * predicate is the whole point of the revision. The refuted §5.2 keyed seating on
     * `effIssued == 0`, which was stale-readable (F1: a peer missing only this edge's own
     * issuance slots reads `0` and re-seats an already-served edge) *and* permanently false for a
     * relocation-receiving edge (F2: `effIssued = issued + issuedRelocIn`, so once
     * `reconcileStranded` re-homes onto a fresh edge the predicate never holds again, in any merge
     * order). Gauge absence has neither defect: it is the exact question "has anybody seated this
     * edge yet", and the relocation counters are not a term of it.
     *
     * The floor written is `max(front, baseIssued / w)`, and the fold is this peer's **own
     * observed** base issuance. Both halves matter:
     *  - the `max` means a bump can never seat an edge *behind* what its own observed service
     *    already implies, so this write has no lifetime-credit direction (§10.5);
     *  - folding the observed issuance is what makes a *stale* bump self-limiting. A peer that has
     *    seen none of this edge's service writes `(itsFront, 0)`, and the componentwise join then
     *    pairs that floor with a better-informed fold and deflates it. A restarted peer's
     *    `(0, 0)` is absorbed with no effect at all.
     *
     * [front] is the parent's virtual time over the set [edge] is joining — see
     * [HeddlePolicy.front], which excludes the joiner. It is a scheduler-level read and therefore
     * passed in: the ledger holds no demand and no wake clamps, so it cannot compute a front, and
     * two peers legitimately compute different ones.
     */
    public fun seat(edge: AttachmentId, front: Rational): Patch<EntitlementLedger>? {
        val w = recordOf(edge)?.weight ?: return null
        if (edge in gauges) return null
        val observed = counterValue(issued, edge)
        return Patch(of(gauges = mapOf(edge to Gauge(Rational.max(front, perWeight(observed, w)), observed))))
    }

    /**
     * [r] returns [amount] of unused entitlement up [edge], restoring the parent's
     * holdings. `null` if:
     *  - [edge] is unknown or divergent;
     *  - [edge] is **not the child's live inbound edge** — the pocket credited
     *    (`returned(edge)`) must be the same pocket the feasibility check reads
     *    (`holdings(child)`, funded by the live inbound). Without this tie, a caller
     *    could gate on a child's live pocket yet credit an unrelated (prepared/retired)
     *    edge and mint holdings from nothing (breaks conservation, design §4.3). Because
     *    [lineageEdges] follows only live (active|closing) edges, this one check also
     *    refuses release across a prepared or retired edge — they are never the live
     *    inbound — while still admitting release across a **closing** edge so it can drain;
     *  - [r]'s holdings at the child are insufficient.
     *
     * Bumps `returned(edge)[r]`.
     */
    public fun release(r: ReplicaId, edge: AttachmentId, amount: Long): Patch<EntitlementLedger>? {
        require(amount >= 1L) { "release amount must be positive, was $amount" }
        val child = recordOf(edge)?.child ?: return null
        val lineage = lineageEdges(child) ?: return null
        if (lineage.lastOrNull() != edge) return null // credited pocket must be the checked live pocket
        if (amount > holdings(child, r)) return null
        val bump = of(returned = mapOf(edge to bumpedSlot(returned, edge, r, amount)))
        return Patch(bump.piece(witness(r, lineage)))
    }

    /**
     * Move [amount] of holdings at [group] from peer [from] to peer [to] — same
     * lineage, different pocket (design §4.3, verbatim `BoundedCounter.transfer`).
     * `null` if [from]'s holdings at [group] are insufficient. Appends to [from]'s own
     * transfer row.
     */
    public fun transfer(group: GroupId, from: ReplicaId, to: ReplicaId, amount: Long): Patch<EntitlementLedger>? {
        require(from != to) { "transfer from and to must differ, both were $from" }
        require(amount >= 1L) { "transfer amount must be positive, was $amount" }
        if (amount > holdings(group, from)) return null
        val lineage = lineageEdges(group) ?: return null
        val pathKey = lineage.lastOrNull()?.let { PathKey.of(it) } ?: PathKey.ROOT
        val current = transfers[pathKey]?.get(from)?.count(to) ?: 0L
        val bump = of(transfers = mapOf(pathKey to mapOf(from to GCounter.of(to to checkedAdd(current, amount)))))
        return Patch(bump.piece(witness(from, lineage)))
    }

    /**
     * Charge [amount] of completed service by [r] at leaf [group]. `require`s [group]
     * is a leaf; `null` if [r]'s holdings there are insufficient. Charges
     * `leafSpent(inbound(group))[r]` **and** `rollupSpent(e)[r]` for every strict-prefix
     * edge `e` — one atomic patch keeping per-edge `outstanding` correct at every level
     * (design fix 1). [amount] `0` is a no-op cancel.
     */
    public fun spend(r: ReplicaId, group: GroupId, amount: Long): Patch<EntitlementLedger>? {
        require(amount >= 0L) { "spend amount must be non-negative, was $amount" }
        require(isLeaf(group)) { "spend requires a leaf group, $group has active children" }
        if (amount == 0L) return Patch(of()) // cancel: a no-op delta
        val lineage = lineageEdges(group) ?: return null
        val f = lineage.lastOrNull() ?: return null // a root leaf has no edge to charge
        if (amount > holdings(group, r)) return null
        val prefix = lineage.dropLast(1)
        val bump = of(
            leafSpent = mapOf(f to bumpedSlot(leafSpent, f, r, amount)),
            rollupSpent = prefix.associateWith { e -> bumpedSlot(rollupSpent, e, r, amount) },
        )
        return Patch(bump.piece(witness(r, lineage)))
    }

    /**
     * The live entitlement path from the root down to [group]'s inbound edge, in root→group
     * order (empty when [group] is the root), or `null` if the lineage is **quarantined**
     * (a divergent record, two live inbound edges, no live path to the root, or a cycle —
     * see [holdings]/[validate]). A caller that must charge service against a *captured*
     * path (design §4.4) reads this **at reservation time**, while the topology is valid,
     * and later hands the captured list to [spendCaptured].
     */
    public fun lineageOf(group: GroupId): List<AttachmentId>? = lineageEdges(group)

    /**
     * Charge [amount] of completed service by [r] against a **path captured earlier**
     * (design §4.4 / §10.4: "charge every edge of the path captured at reservation; history
     * never moves to a newer generation"). Unlike [spend], this does **not** recompute the
     * lineage or re-check `isLeaf`/holdings from the *current* topology — it charges the
     * exact [capturedPath] edges directly: `leafSpent` on the captured final edge and
     * `rollupSpent` on every captured strict-prefix edge.
     *
     * This is always valid because records are immutable and the spend counters are
     * monotone: the historical generation the work was admitted under still exists and can
     * still be charged, even if the child's lineage has since been reparented, quarantined
     * ([LedgerConflict.DualActiveInbound]), or gained a child (so the former leaf is no
     * longer `isLeaf`). Those concurrent reshapes make [spend] return `null` or throw; a
     * completion must **never** be silently dropped, so the node charges the captured path.
     *
     * `null` only for a structurally-impossible [capturedPath] (empty — a root leaf has no
     * edge to charge); the caller must surface that, never swallow it. [amount] `0` is a
     * no-op cancel; negative is rejected.
     */
    public fun spendCaptured(r: ReplicaId, capturedPath: List<AttachmentId>, amount: Long): Patch<EntitlementLedger>? {
        require(amount >= 0L) { "spendCaptured amount must be non-negative, was $amount" }
        if (amount == 0L) return Patch(of()) // cancel: a no-op delta
        val f = capturedPath.lastOrNull() ?: return null // a root leaf has no edge to charge
        val prefix = capturedPath.dropLast(1)
        val bump = of(
            leafSpent = mapOf(f to bumpedSlot(leafSpent, f, r, amount)),
            rollupSpent = prefix.associateWith { e -> bumpedSlot(rollupSpent, e, r, amount) },
        )
        return Patch(bump.piece(witness(r, capturedPath)))
    }

    /**
     * The witness a feasibility-consuming patch carries: the credit slots [actor]'s
     * holdings check read along [lineage], at their observed absolute values (max-safe,
     * so over-inclusion is harmless).
     *
     * **Scope — the honest boundary.** Safety is the *local* holdings check the mutator
     * already ran on [actor]'s own complete state; the witness only keeps [validate]
     * (a diagnostic) from false-firing under partial delivery. It covers:
     *  - [actor]'s own credit read directly by the check — `issued`/`returned`[actor] on
     *    every lineage edge, [actor]'s minted supply, and the transfer slots crediting
     *    [actor];
     *  - a **depth-1 backing** of each donor who transferred into [actor] at a level: the
     *    donor's own `issued`/`returned`[donor] at that edge (or minted, at the root), so
     *    a *single-hop* transfer-then-charge does not false-fire on a lagging replica.
     *
     * It deliberately does **not** chase a transfer's funding transitively (Iain's call):
     * a *multi-hop* transfer-funded charge may transiently surface a false
     * [LedgerConflict.PerEdgeSafety] / [LedgerConflict.PersistentNegativeHoldings] on a
     * partially-delivered replica. That is an eventually-consistent diagnostic artifact
     * that self-heals on anti-entropy — never an authorized overspend.
     */
    private fun witness(actor: ReplicaId, lineage: List<AttachmentId>): EntitlementLedger {
        val wIssued = HashMap<AttachmentId, GCounter>()
        val wReturned = HashMap<AttachmentId, GCounter>()
        val wMinted = HashMap<MintId, MintRecord>()
        val wTransfers = HashMap<PathKey, Map<ReplicaId, GCounter>>()

        // The actor's own credit read directly by the holdings check.
        for (e in lineage) {
            addSlot(wIssued, e, actor, slot(issued, e, actor))
            addSlot(wReturned, e, actor, slot(returned, e, actor))
        }
        for ((id, m) in minted) if (m.holder == actor) wMinted[id] = m

        // Each level names the path ending at it; the root has no edge.
        for (edge in listOf<AttachmentId?>(null) + lineage) {
            val pathKey = edge?.let { PathKey.of(it) } ?: PathKey.ROOT
            val rows = transfers[pathKey] ?: continue
            val kept = HashMap<ReplicaId, GCounter>()
            for ((donor, row) in rows) {
                if (donor == actor) {
                    kept[donor] = row // the actor's own outflow row — actor-authored, max-safe
                    continue
                }
                val credited = row.count(actor)
                if (credited <= 0L) continue
                kept[donor] = GCounter.of(actor to credited)
                // Depth-1 donor backing (see KDoc): the donor could only transfer what it
                // held, so carry the donor's own backing at this edge / root.
                if (edge == null) {
                    for ((id, m) in minted) if (m.holder == donor) wMinted[id] = m
                } else {
                    addSlot(wIssued, edge, donor, slot(issued, edge, donor))
                    addSlot(wReturned, edge, donor, slot(returned, edge, donor))
                }
            }
            if (kept.isNotEmpty()) wTransfers[pathKey] = kept
        }
        return of(minted = wMinted, issued = wIssued, returned = wReturned, transfers = wTransfers)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // validate — integrity faults surfaced from merged state (design §4.6)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The integrity faults derivable from this merged state, in one canonical order
     * (identical on every replica). This is an **eventually-consistent diagnostic, not
     * a safety gate** — safety is the local holdings check in the mutators. On a
     * fully-delivered state the report is exact; the per-patch witness keeps the direct
     * and single-hop-transfer cases honest under partial delivery, but a partially-
     * delivered **multi-hop transfer-funded** charge may transiently list a false
     * conflict that self-heals on anti-entropy. The checks:
     *
     *  - [LedgerConflict.PerEdgeSafety] — sum-wise `effLeafSpent + effRollupSpent + returned
     *    > effIssued` on an edge's aggregate **effective** values (base ± relocation).
     *  - [LedgerConflict.PersistentNegativeHoldings] — a `(group, replica)` with
     *    negative derived [holdings]: the real overspend net. Quarantined lineages
     *    (holdings `0`) are excluded — they surface as [LedgerConflict.RecordDivergence].
     *  - [LedgerConflict.RecordDivergence] — two distinct records under one id.
     *  - [LedgerConflict.DualActiveInbound] — a group with two or more
     *    [Lifecycle.ACTIVE] inbound generations (§5.2, §10.11). Its lineage is
     *    quarantined (holdings `0`), so it is *excluded* from the negative-holdings
     *    check above — it surfaces here instead.
     *  - [LedgerConflict.ClosureViolation] — a [Lifecycle.RETIRED] edge with non-zero
     *    outstanding entitlement: a late delegation crossed a generation the cluster had
     *    already retired (closure dominance, §10.10).
     *  - [LedgerConflict.LineageCycle] — a group whose live inbound edges loop back to it
     *    instead of reaching a root (§5.2, §10.11). Reported once per loop member, never for
     *    a mere descendant; records are grow-only, so a loop seen on any state is real.
     *  - [LedgerConflict.ConservationViolation] — the **global** backstop:
     *    `Σ effLeafSpent > mintedTotal`, i.e. more service charged than supply ever minted
     *    (§10.1). Exact on a converged state; it never fires alone on honest partially-
     *    delivered traffic (a state missing a charge's root mint already strands a
     *    [LedgerConflict.PersistentNegativeHoldings] at the delegator). Its value is that it
     *    is derived from the raw totals, so it still catches manufactured authority when the
     *    per-lineage derivation of [holdings] is itself the thing that regressed.
     *  - [LedgerConflict.OrphanedTransferPath] — transfer rows the topology moved out from under
     *    (#2366): a [PathKey] no group's live lineage reads, whose rows a generation move did not
     *    carry across, still holding a non-zero balance for one of the parties. The one fault here
     *    that conservation is *structurally* blind to — `Σ_r transferNet(k, r) = 0` on every key, so
     *    abandoning a whole key is sum-preserving — and that the negative-holdings check misses
     *    because the recipient lands on `0` rather than below it.
     *
     * **What is deliberately not reported:** a group whose only inbound edges are all
     * prepared/retired is quarantined (holdings `0`) but *silent* — that is the normal window
     * of an honest reshape, and flagging it would fire on healthy traffic. It is the standing
     * exception to §10.11's quarantine ⟺ report correspondence (see [lineageEdges]).
     */
    public fun validate(): List<LedgerConflict> {
        val conflicts = ArrayList<LedgerConflict>()
        for (e in allEdges()) {
            val effLeaf = effLeafSpentTotal(e)
            val effRollup = effRollupSpentTotal(e)
            val charged = checkedAdd(checkedAdd(effLeaf, effRollup), counterValue(returned, e))
            if (charged > effIssuedTotal(e)) conflicts += LedgerConflict.PerEdgeSafety(e)
            // §5.3's lower bound, reported rather than assumed (#1693 / relocation design §12.5). A
            // negative effective spend means an edge carries a relocation-out whose covering base has
            // not arrived — and it also *lowers* `charged` above, so the per-edge upper bound alone
            // would let it pass silently.
            if (effLeaf < 0L || effRollup < 0L) conflicts += LedgerConflict.NegativeEffectiveSpend(e)
            // A retired edge must have drained before retiring; non-zero outstanding
            // means entitlement crossed a generation already retired elsewhere.
            if (lifecycleOf(e) == Lifecycle.RETIRED) {
                val summary = edge(e)
                if (summary != null && summary.outstanding != 0L) conflicts += LedgerConflict.ClosureViolation(e)
            }
        }
        for ((id, recs) in records) {
            if (recs.size > 1) conflicts += LedgerConflict.RecordDivergence(id)
        }
        for (g in allGroups()) {
            if (liveInboundCount(g) >= 2) conflicts += LedgerConflict.DualActiveInbound(g)
            if (walkLineage(g).onCycle) conflicts += LedgerConflict.LineageCycle(g)
            for (r in allReplicas()) {
                if (holdings(g, r) < 0L) conflicts += LedgerConflict.PersistentNegativeHoldings(g, r)
            }
        }
        for (path in orphanedTransferPaths()) conflicts += LedgerConflict.OrphanedTransferPath(path)
        // The global backstop, last: totals read straight off the components, so it survives a
        // regression in the per-lineage derivation the checks above all depend on.
        val spentTotal = leafSpentTotal()
        val supplyTotal = mintedTotal()
        if (spentTotal > supplyTotal) {
            conflicts += LedgerConflict.ConservationViolation(spentTotal, supplyTotal)
        }
        return conflicts.sorted()
    }

    /**
     * The path keys carrying transfer rows that no group's live lineage reads any more, sorted —
     * the derivation behind [LedgerConflict.OrphanedTransferPath] (issue #2366). Its three clauses,
     * and why each is load-bearing, are in that type's KDoc; this is where they are evaluated.
     *
     * **Enumerated over the rows themselves, never over an edge's counter slots.** The whole point
     * of the defect is a recipient who *only* holds transferred credit: it has no slot on the edge,
     * so [replicasOnEdge] — which walks the counter families — does not contain it, and an
     * enumeration built from that set would silently drop exactly the party the report exists for.
     */
    private fun orphanedTransferPaths(): List<PathKey> {
        if (transfers.isEmpty()) return emptyList()
        val edgeByPath = allEdges().associateBy { PathKey.of(it) }
        val out = ArrayList<PathKey>()
        for ((path, rows) in transfers) {
            // The root path has no final edge, so no reshape can move it: it is live on every state.
            if (path == PathKey.ROOT) continue
            val edge = edgeByPath[path]
            if (edge == null) {
                out += path // names no generation this ledger knows — unreadable by construction
                continue
            }
            // Unknown or divergent record ⇒ the lineage is quarantined and [RecordDivergence] owns
            // it; `null` lineage ⇒ the honest-reshape window, the standing silent exception.
            val child = recordOf(edge)?.child ?: continue
            val livePath = lineageEdges(child)?.let { it.lastOrNull()?.let(PathKey::of) ?: PathKey.ROOT } ?: continue
            if (livePath == path) continue // still the key `holdings` reads at this group
            if (rowsCarriedAcross(rows, livePath)) continue // a move took them with it: nothing lost
            val parties = HashSet<ReplicaId>()
            for ((donor, row) in rows) { parties += donor; parties += row.replicas() }
            if (parties.any { r -> transferNet(path, r) != 0L && strandedOn(edge, r) != 0L }) out += path
        }
        return out.sorted()
    }

    /**
     * True when every `(donor, recipient)` cumulative in [rows] is matched or exceeded at [livePath]
     * — i.e. a generation move carried the hand-offs across with the counter families it re-homed.
     * Compared **per pair** rather than on the net, so a move that carried only some of the rows
     * still reports.
     */
    private fun rowsCarriedAcross(rows: Map<ReplicaId, GCounter>, livePath: PathKey): Boolean {
        val live = transfers[livePath] ?: emptyMap()
        return rows.all { (donor, row) ->
            val there = live[donor]
            row.replicas().all { recipient -> row.count(recipient) <= (there?.count(recipient) ?: 0L) }
        }
    }

    /**
     * What [r] would still hold at [edge] if it were live — the **inbound half** of the [holdings]
     * derivation, `netInflow + transferNet − effLeafSpent`, evaluated on a generation nothing reads
     * any more. The group-level `childEdges` subtraction is deliberately absent: it does not move
     * when the inbound generation is replaced, so it is not part of what a move must carry.
     *
     * Zero on a generation whose books closed honestly before it died, whatever it once carried.
     */
    private fun strandedOn(edge: AttachmentId, r: ReplicaId): Long = checkedSub(
        checkedAdd(netInflow(edge, r), transferNet(PathKey.of(edge), r)),
        effLeafSpentSlot(edge, r),
    )

    /**
     * The number of **live** ([Lifecycle.ACTIVE] or [Lifecycle.CLOSING]) inbound edges
     * into [group] — `≥ 2` is a fork. This is deliberately the *same* predicate
     * [lineageEdges] quarantines on, so a quarantined child (holdings `0`, undrainable)
     * always has a matching [LedgerConflict.DualActiveInbound] report (invariant §10.11:
     * quarantine ⟺ explicit report). Counting only ACTIVE would leave an ACTIVE+CLOSING
     * fork silently quarantined with an empty `validate()`.
     */
    private fun liveInboundCount(group: GroupId): Int =
        records.count { (id, recs) -> recs.any { it.child == group } && isLiveEdge(id) }

    // ─────────────────────────────────────────────────────────────────────────
    // Enumeration + test-support accessors (internal — used by validate and tests)
    // ─────────────────────────────────────────────────────────────────────────

    /** The record set under [id] (empty if unknown) — `internal`, test support. */
    internal fun recordsOf(id: AttachmentId): Set<AttachmentRecord> = records[id] ?: emptySet()

    /** Every edge id mentioned by any component. */
    internal fun allEdges(): Set<AttachmentId> =
        records.keys + lifecycle.keys + allEdgeCounters().flatMapTo(HashSet()) { it.keys }

    /**
     * The **live inbound** edges of [child] — every non-divergent edge whose record targets [child]
     * and whose lifecycle is [Lifecycle.ACTIVE] or [Lifecycle.CLOSING] (a still-draining closing edge
     * counts; it can carry entitlement). Sorted by id so every peer folds the same order.
     *
     * This is the exact predicate [LedgerConflict.DualActiveInbound] fires on. The **H5 control plane**
     * reads it *before* applying an `activate` proposal so the log's serialization can refuse the
     * loser of two overlapping reshapes as a structured conflict — instead of letting both apply and
     * quarantine the lineage (design §9, §5.2, §10.11). `internal` — control-plane + test support.
     */
    internal fun liveInboundEdges(child: GroupId): List<AttachmentId> =
        allEdges()
            .filter { e ->
                recordOf(e)?.child == child &&
                    lifecycleOf(e).let { it == Lifecycle.ACTIVE || it == Lifecycle.CLOSING }
            }
            .sorted()

    /**
     * The **RETIRED inbound** edges of [child] — every non-divergent edge whose record targets [child]
     * and whose lifecycle is [Lifecycle.RETIRED]. These are the edges a raced advisory-retire may have
     * stranded budget on ([reconcileStranded]); the **H5 control plane** reads it on its log-pure
     * projection to gate a reconciliation witness (§9 #3, §5.4). `internal` — control-plane + test support.
     */
    internal fun retiredInboundEdges(child: GroupId): List<AttachmentId> =
        allEdges().filter { recordOf(it)?.child == child && lifecycleOf(it) == Lifecycle.RETIRED }.sorted()

    /** The edge ids carrying a base `issued` slot — the control plane's reconciliation witness-shape gate. */
    internal fun issuedEdges(): Set<AttachmentId> = issued.keys

    /** The edge ids carrying a `returned` slot — the control plane's reconciliation witness-shape gate. */
    internal fun returnedEdges(): Set<AttachmentId> = returned.keys

    /** The edge ids carrying an `issuedRelocIn` slot — where a re-home's credit legally lands. */
    internal fun issuedRelocInEdges(): Set<AttachmentId> = issuedRelocIn.keys

    /**
     * The donors holding a transfer row at [edge]'s path key — the rows a generation move off [edge]
     * would abandon (#2366). Empty for an edge nobody has handed entitlement across.
     * `internal` — the [relocationPatch] precondition reads the same map; test support.
     */
    internal fun transferDonorsOn(edge: AttachmentId): Set<ReplicaId> =
        transfers[PathKey.of(edge)]?.keys ?: emptySet()

    /** The donor rows recorded at [path] (empty if none) — `internal`, test support. */
    internal fun transfersAt(path: PathKey): Map<ReplicaId, GCounter> = transfers[path] ?: emptyMap()

    /** Every group named as a parent or child by any (singleton or divergent) record. */
    internal fun allGroups(): Set<GroupId> =
        records.values.flatten().flatMapTo(HashSet()) { listOf(it.parent, it.child) }

    /** Every replica that authored a slot, holds a mint, or sent/received a transfer. */
    internal fun allReplicas(): Set<ReplicaId> {
        val out = HashSet<ReplicaId>()
        for (m in minted.values) out += m.holder
        for (map in allEdgeCounters()) {
            for (counter in map.values) out += counter.replicas()
        }
        for (rows in transfers.values) {
            for ((donor, row) in rows) { out += donor; out += row.replicas() }
        }
        return out
    }

    /** Total minted supply. */
    internal fun mintedTotal(): Long = minted.values.fold(0L) { acc, m -> checkedAdd(acc, m.amount) }

    /**
     * Total service charged where an edge is a path's final edge (the conservation term), at
     * **effective** values — a relocation moves leaf spend between edges in equal and opposite
     * amounts, so this sum is invariant under any move.
     */
    internal fun leafSpentTotal(): Long =
        allEdges().fold(0L) { acc, e -> checkedAdd(acc, effLeafSpentTotal(e)) }

    /** [r]'s effective issuance across [edge] — `issued + issuedRelocIn`. `internal`, test support. */
    internal fun effectiveIssued(edge: AttachmentId, r: ReplicaId): Long = effIssuedSlot(edge, r)

    /**
     * The raw **stored** slot value of one counter [family] at `(edge, r)`. Every family is grow-only,
     * so this value never falls under [piece] — the property the lattice rests on, and the one the
     * derived effective reads deliberately do not have. `internal`, test support.
     */
    internal fun storedSlot(family: CounterFamily, edge: AttachmentId, r: ReplicaId): Long =
        slot(counterMap(family), edge, r)

    private fun counterMap(family: CounterFamily): Map<AttachmentId, GCounter> = when (family) {
        CounterFamily.ISSUED -> issued
        CounterFamily.RETURNED -> returned
        CounterFamily.LEAF_SPENT -> leafSpent
        CounterFamily.ROLLUP_SPENT -> rollupSpent
        CounterFamily.ISSUED_RELOC_IN -> issuedRelocIn
        CounterFamily.LEAF_RELOC_IN -> leafRelocIn
        CounterFamily.LEAF_RELOC_OUT -> leafRelocOut
        CounterFamily.ROLLUP_RELOC_IN -> rollupRelocIn
        CounterFamily.ROLLUP_RELOC_OUT -> rollupRelocOut
    }

    /**
     * The sub-ledger restricted to [id]'s own components (plus its path's transfer
     * rows). Pins the projection homomorphism (design §10.8): restriction to an
     * edge's components commutes with [piece]. `internal` — test support.
     */
    internal fun projectEdge(id: AttachmentId): EntitlementLedger = of(
        records = records[id]?.let { mapOf(id to it) } ?: emptyMap(),
        issued = issued[id]?.let { mapOf(id to it) } ?: emptyMap(),
        returned = returned[id]?.let { mapOf(id to it) } ?: emptyMap(),
        leafSpent = leafSpent[id]?.let { mapOf(id to it) } ?: emptyMap(),
        rollupSpent = rollupSpent[id]?.let { mapOf(id to it) } ?: emptyMap(),
        transfers = transfers[PathKey.of(id)]?.let { mapOf(PathKey.of(id) to it) } ?: emptyMap(),
        lifecycle = lifecycle[id]?.let { mapOf(id to it) } ?: emptyMap(),
        issuedRelocIn = issuedRelocIn[id]?.let { mapOf(id to it) } ?: emptyMap(),
        leafRelocIn = leafRelocIn[id]?.let { mapOf(id to it) } ?: emptyMap(),
        leafRelocOut = leafRelocOut[id]?.let { mapOf(id to it) } ?: emptyMap(),
        rollupRelocIn = rollupRelocIn[id]?.let { mapOf(id to it) } ?: emptyMap(),
        rollupRelocOut = rollupRelocOut[id]?.let { mapOf(id to it) } ?: emptyMap(),
        gauges = gauges[id]?.let { mapOf(id to it) } ?: emptyMap(),
    )

    /**
     * This state with every [Gauge] entry removed and all thirteen other components untouched.
     *
     * `internal` — and it exists for exactly one caller: the **negative control** that splits a
     * [delegate] patch so its counters travel without their checkpoint, and thereby demonstrates
     * that the same-patch rule in [delegate] is load-bearing rather than stylistic. Without a way
     * to express that split the rule would be an unenforced convention, and the next refactor
     * would delete it with a green suite.
     *
     * Nothing in production may call this. Publishing a patch built this way is precisely the
     * F1 double count.
     */
    internal fun withoutGauges(): EntitlementLedger = EntitlementLedger(
        records, minted, issued, returned, leafSpent, rollupSpent, transfers, lifecycle,
        issuedRelocIn, leafRelocIn, leafRelocOut, rollupRelocIn, rollupRelocOut, gauges = emptyMap(),
    )

    override fun equals(other: Any?): Boolean =
        other is EntitlementLedger &&
            records == other.records &&
            minted == other.minted &&
            issued == other.issued &&
            returned == other.returned &&
            leafSpent == other.leafSpent &&
            rollupSpent == other.rollupSpent &&
            transfers == other.transfers &&
            lifecycle == other.lifecycle &&
            issuedRelocIn == other.issuedRelocIn &&
            leafRelocIn == other.leafRelocIn &&
            leafRelocOut == other.leafRelocOut &&
            rollupRelocIn == other.rollupRelocIn &&
            rollupRelocOut == other.rollupRelocOut &&
            gauges == other.gauges

    override fun hashCode(): Int {
        var h = records.hashCode()
        h = 31 * h + minted.hashCode()
        h = 31 * h + issued.hashCode()
        h = 31 * h + returned.hashCode()
        h = 31 * h + leafSpent.hashCode()
        h = 31 * h + rollupSpent.hashCode()
        h = 31 * h + transfers.hashCode()
        h = 31 * h + lifecycle.hashCode()
        h = 31 * h + issuedRelocIn.hashCode()
        h = 31 * h + leafRelocIn.hashCode()
        h = 31 * h + leafRelocOut.hashCode()
        h = 31 * h + rollupRelocIn.hashCode()
        h = 31 * h + rollupRelocOut.hashCode()
        h = 31 * h + gauges.hashCode()
        return h
    }

    override fun toString(): String =
        "EntitlementLedger(records=$records, minted=$minted, issued=$issued, " +
            "returned=$returned, leafSpent=$leafSpent, rollupSpent=$rollupSpent, " +
            "transfers=$transfers, lifecycle=$lifecycle, issuedRelocIn=$issuedRelocIn, " +
            "leafRelocIn=$leafRelocIn, leafRelocOut=$leafRelocOut, " +
            "rollupRelocIn=$rollupRelocIn, rollupRelocOut=$rollupRelocOut, gauges=$gauges)"

    public companion object {
        /** The empty ledger: no topology, no supply, no accounting. The lattice bottom. */
        public val ZERO: EntitlementLedger =
            EntitlementLedger(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

        /**
         * A ledger seeded with root supply for one **mint act**: each
         * `(replica, amount)` in [mint] becomes a [MintRecord] credited to that
         * replica, keyed by a [MintId] unique to this act.
         *
         * [nonce] identifies the mint act and MUST be distinct across independent
         * acts (a fresh value per commit; a sequence number, a Raft log index, a
         * UUID). This is load-bearing, not decoration: mints are keyed so that two
         * *distinct* acts crediting the same holder **union** rather than
         * max-collide into one lost mint (design fix 4). The same act observed on
         * two peers carries the same [nonce], so it converges to one entry. No edges
         * yet — the topology is grown by the mutators (a later phase). Every amount
         * must be non-negative.
         *
         * [root] names the tree this supply belongs to, but it reaches the state only inside
         * the generated [MintId] — a [MintRecord] is *not* structurally bound to a root. So a
         * ledger must have **exactly one bootstrap**: merging two independently-bootstrapped
         * ledgers leaves two rootless groups, each credited the whole minted supply. See the
         * "One root per ledger" section of the class KDoc.
         */
        public fun bootstrap(root: GroupId, mint: Map<ReplicaId, Long>, nonce: String): EntitlementLedger {
            val minted = mint.entries.associate { (holder, amount) ->
                MintId("${root.value}#$nonce#${holder.value}") to MintRecord(holder, amount)
            }
            return EntitlementLedger(
                records = emptyMap(),
                minted = minted,
                issued = emptyMap(),
                returned = emptyMap(),
                leafSpent = emptyMap(),
                rollupSpent = emptyMap(),
                transfers = emptyMap(),
            )
        }

        /**
         * Directly assemble a ledger from its components. `internal` — the public
         * way to build a non-trivial ledger is via the mutators (a later phase);
         * this exists so this phase's lattice-law tests can construct arbitrary
         * states of the still-inert lattice.
         */
        internal fun of(
            records: Map<AttachmentId, Set<AttachmentRecord>> = emptyMap(),
            minted: Map<MintId, MintRecord> = emptyMap(),
            issued: Map<AttachmentId, GCounter> = emptyMap(),
            returned: Map<AttachmentId, GCounter> = emptyMap(),
            leafSpent: Map<AttachmentId, GCounter> = emptyMap(),
            rollupSpent: Map<AttachmentId, GCounter> = emptyMap(),
            transfers: Map<PathKey, Map<ReplicaId, GCounter>> = emptyMap(),
            lifecycle: Map<AttachmentId, Lifecycle> = emptyMap(),
            issuedRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
            leafRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
            leafRelocOut: Map<AttachmentId, GCounter> = emptyMap(),
            rollupRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
            rollupRelocOut: Map<AttachmentId, GCounter> = emptyMap(),
            gauges: Map<AttachmentId, Gauge> = emptyMap(),
        ): EntitlementLedger =
            EntitlementLedger(
                records, minted, issued, returned, leafSpent, rollupSpent, transfers, lifecycle,
                issuedRelocIn, leafRelocIn, leafRelocOut, rollupRelocIn, rollupRelocOut, gauges,
            )
    }
}

/**
 * The outcome of one [EntitlementLedger] lineage walk: the root→group edge list, or `null`
 * when the lineage is quarantined, plus whether the walked group lies **on** a topology cycle.
 */
private class LineageWalk(val edges: List<AttachmentId>?, val onCycle: Boolean) {
    companion object {
        /** Quarantined for a non-cycle reason (divergent record, dual live inbound, no live path). */
        val QUARANTINED: LineageWalk = LineageWalk(edges = null, onCycle = false)
    }
}

/**
 * The nine per-edge [GCounter] families of [EntitlementLedger] — four base, five relocation.
 * Names one family for [EntitlementLedger.storedSlot], whose contract is that **every** family is
 * grow-only: no `piece` on any state may lower a stored slot. `internal` — test + derivation support.
 */
internal enum class CounterFamily {
    ISSUED,
    RETURNED,
    LEAF_SPENT,
    ROLLUP_SPENT,
    ISSUED_RELOC_IN,
    LEAF_RELOC_IN,
    LEAF_RELOC_OUT,
    ROLLUP_RELOC_IN,
    ROLLUP_RELOC_OUT,
}

/**
 * The overflow-checked aggregate of one [GCounter]: `Σ_r counter[r]`, but folded through
 * [checkedAdd] instead of [GCounter.value]'s plain `sum()`.
 *
 * [GCounter.value] is a bare `Long` sum and **wraps** — the zoo's counters are unconstrained
 * by design and their `inc` is unchecked (see [CheckedMath]). Every *honest* state reaching
 * this module is mint-bounded, so no sum of real slots can approach [Long.MAX_VALUE]; but an
 * adversarial or corrupted **deserialized** state carries whatever slot values the wire said,
 * and a silent wrap there would hand [EntitlementLedger.validate] and [EntitlementLedger.edge]
 * a negative aggregate that reads as *less* charged than nothing. §10.12 says arithmetic that
 * would exceed `Long` fails deterministically and never wraps; routing every aggregate read
 * through here is what makes that true of a state this module did not itself construct.
 */
private fun checkedCounterValue(counter: GCounter): Long =
    counter.replicas().fold(0L) { acc, r -> checkedAdd(acc, counter.count(r)) }

private fun counterValue(counters: Map<AttachmentId, GCounter>, id: AttachmentId): Long =
    counters[id]?.let(::checkedCounterValue) ?: 0L

/** The single `(id, r)` slot value, or 0. */
private fun slot(counters: Map<AttachmentId, GCounter>, id: AttachmentId, r: ReplicaId): Long =
    counters[id]?.count(r) ?: 0L

/** A `GCounter` carrying just the `(id, r)` slot bumped to its new absolute value (overflow-checked). */
private fun bumpedSlot(counters: Map<AttachmentId, GCounter>, id: AttachmentId, r: ReplicaId, by: Long): GCounter =
    GCounter.of(r to checkedAdd(slot(counters, id, r), by))

/** Merge a single `(edge, r) = value` slot into a witness accumulator (skips non-positive). */
private fun addSlot(acc: HashMap<AttachmentId, GCounter>, edge: AttachmentId, r: ReplicaId, value: Long) {
    if (value <= 0L) return
    val one = GCounter.of(r to value)
    acc[edge] = acc[edge]?.piece(one) ?: one
}

/**
 * One `(fenced edge, replica)` slot of a relocation derivation, carried from the deriving pass to
 * the building pass so the two agree by construction.
 *
 * The split exists because the `n ≥ sp` precondition is a property of **all** of one replica's
 * fenced edges together, not of each edge alone (#1895) — so every slot has to be derived before
 * any of them can be drained. Holding the derived values rather than recomputing them in the second
 * pass is deliberate: two copies of this arithmetic could drift, and it is conservation-critical.
 *
 * Construct it with **named arguments**: nine of the twelve parameters are bare `Long`s, so a
 * transposed `relLeafOut`/`relRollOut` or `lsp`/`rsp` compiles clean and silently mis-drains — the
 * one drift vector carrying the values instead of recomputing them does not already close.
 *
 * @property n `iss - ret`, this slot's cover — what the edge can fund.
 * @property lsp the slot's effective leaf spend.
 * @property rsp the slot's effective roll-up spend.
 */
private data class DrainedSlot(
    val edge: AttachmentId,
    val replica: ReplicaId,
    val acked: SlotFinals,
    val relIssIn: Long,
    val relLeafIn: Long,
    val relRollIn: Long,
    val relLeafOut: Long,
    val relRollOut: Long,
    val iss: Long,
    val n: Long,
    val lsp: Long,
    val rsp: Long,
)

/**
 * Accumulates `(family, edge, replica) → absolute value` writes into one [EntitlementLedger] delta.
 *
 * The relocation derivation writes across all nine per-edge counter families at once, and a
 * hand-rolled nine-map accumulator at each call site is where a family gets silently forgotten.
 * Non-positive values are skipped (a zero slot is the lattice bottom — shipping it says nothing);
 * repeated writes to one slot join by max, matching the wire semantics exactly.
 */
private class EdgePatchBuilder {
    private val families = HashMap<CounterFamily, HashMap<AttachmentId, GCounter>>()

    fun put(family: CounterFamily, edge: AttachmentId, r: ReplicaId, value: Long) {
        if (value <= 0L) return
        addSlot(families.getOrPut(family) { HashMap() }, edge, r, value)
    }

    private fun of(family: CounterFamily): Map<AttachmentId, GCounter> = families[family] ?: emptyMap()

    fun build(): EntitlementLedger = EntitlementLedger.of(
        issued = of(CounterFamily.ISSUED),
        returned = of(CounterFamily.RETURNED),
        leafSpent = of(CounterFamily.LEAF_SPENT),
        rollupSpent = of(CounterFamily.ROLLUP_SPENT),
        issuedRelocIn = of(CounterFamily.ISSUED_RELOC_IN),
        leafRelocIn = of(CounterFamily.LEAF_RELOC_IN),
        leafRelocOut = of(CounterFamily.LEAF_RELOC_OUT),
        rollupRelocIn = of(CounterFamily.ROLLUP_RELOC_IN),
        rollupRelocOut = of(CounterFamily.ROLLUP_RELOC_OUT),
    )
}

private fun Map<AttachmentId, GCounter>.mergeEdgeCounters(
    other: Map<AttachmentId, GCounter>,
): Map<AttachmentId, GCounter> = mergeValues(other) { mine, theirs -> mine.piece(theirs) }

private fun Map<ReplicaId, GCounter>.mergeRows(
    other: Map<ReplicaId, GCounter>,
): Map<ReplicaId, GCounter> = mergeValues(other) { mine, theirs -> mine.piece(theirs) }
