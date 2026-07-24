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
 * Eight components, each already a join-semilattice, so [piece] is just their
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
        )

    /**
     * The parent-facing [EdgeSummary] for [id], or `null` if the edge is entirely
     * unknown to this ledger. `spent` is the total charged through the edge —
     * `leafSpent + rollupSpent`.
     */
    public fun edge(id: AttachmentId): EdgeSummary? {
        if (!isKnown(id)) return null
        return EdgeSummary(
            attachment = id,
            issued = counterValue(issued, id),
            returned = counterValue(returned, id),
            spent = counterValue(leafSpent, id) + counterValue(rollupSpent, id),
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
     * The single [AttachmentRecord] for [id] — its parent, child, weight, and virtual-time
     * origin — or `null` if [id] is unknown *or divergent* (two conflicting records under
     * one id, which a healthy ledger never has; see [validate]). The parent-facing read a
     * scheduler pairs with [edge]'s [EdgeSummary] to build a policy input.
     */
    public fun record(id: AttachmentId): AttachmentRecord? = recordOf(id)

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
            id in lifecycle

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
     *  - a cycle.
     */
    private fun lineageEdges(group: GroupId): List<AttachmentId>? {
        val edges = ArrayDeque<AttachmentId>()
        val seen = HashSet<GroupId>()
        var cur = group
        while (true) {
            if (!seen.add(cur)) return null // cycle
            val inboundIds = records.filter { (_, recs) -> recs.any { it.child == cur } }.keys
            if (inboundIds.isEmpty()) break // reached the root
            val liveInbound = inboundIds.filter { isLiveEdge(it) }
            if (liveInbound.isEmpty()) return null // no live path to the root → quarantine
            if (liveInbound.size > 1) return null // dual live inbound → quarantine
            val id = liveInbound.single()
            val rec = recordOf(id) ?: return null // divergent record on the lineage → quarantine
            edges.addFirst(id)
            cur = rec.parent
        }
        return edges.toList()
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
     * [r] at the root, else `issued(f)[r] − returned(f)[r]`. `leafSpent(f)[r]` is
     * subtracted **unconditionally** — no `isLeaf` test — which keeps conservation
     * topology-independent when a former leaf later gains a child (design fix 1).
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
        if (f != null) acc = checkedSub(acc, slot(leafSpent, f, r))
        return acc
    }

    /** `issued(edge)[r] − returned(edge)[r]` — [r]'s net inflow across one edge. */
    private fun netInflow(edge: AttachmentId, r: ReplicaId): Long =
        checkedSub(slot(issued, edge, r), slot(returned, edge, r))

    /** Σ minted amounts credited to [r] (the root's `creditIn`). */
    private fun mintedHeldBy(r: ReplicaId): Long =
        minted.values.fold(0L) { acc, m -> if (m.holder == r) checkedAdd(acc, m.amount) else acc }

    /** `Σ_s transfers[pathKey][s][r] − Σ_t transfers[pathKey][r][t]` — [r]'s net transfer at a path. */
    private fun transferNet(pathKey: PathKey, r: ReplicaId): Long {
        val rows = transfers[pathKey] ?: return 0L
        var inflow = 0L
        for ((_, row) in rows) inflow = checkedAdd(inflow, row.count(r))
        val outflow = rows[r]?.value ?: 0L
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
     * Bumps `issued(edge)[r]`.
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
        val bump = of(issued = mapOf(edge to bumpedSlot(issued, edge, r, amount)))
        return Patch(bump.piece(witness(r, lineage)))
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
     *  - [LedgerConflict.PerEdgeSafety] — sum-wise `leafSpent + rollupSpent + returned
     *    > issued` on an edge's aggregate values.
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
     */
    public fun validate(): List<LedgerConflict> {
        val conflicts = ArrayList<LedgerConflict>()
        for (e in allEdges()) {
            val charged = checkedAdd(
                checkedAdd(counterValue(leafSpent, e), counterValue(rollupSpent, e)),
                counterValue(returned, e),
            )
            if (charged > counterValue(issued, e)) conflicts += LedgerConflict.PerEdgeSafety(e)
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
            for (r in allReplicas()) {
                if (holdings(g, r) < 0L) conflicts += LedgerConflict.PersistentNegativeHoldings(g, r)
            }
        }
        return conflicts.sorted()
    }

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
        records.keys + issued.keys + returned.keys + leafSpent.keys + rollupSpent.keys + lifecycle.keys

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

    /** Every group named as a parent or child by any (singleton or divergent) record. */
    internal fun allGroups(): Set<GroupId> =
        records.values.flatten().flatMapTo(HashSet()) { listOf(it.parent, it.child) }

    /** Every replica that authored a slot, holds a mint, or sent/received a transfer. */
    internal fun allReplicas(): Set<ReplicaId> {
        val out = HashSet<ReplicaId>()
        for (m in minted.values) out += m.holder
        for (map in listOf(issued, returned, leafSpent, rollupSpent)) {
            for (counter in map.values) out += counter.replicas()
        }
        for (rows in transfers.values) {
            for ((donor, row) in rows) { out += donor; out += row.replicas() }
        }
        return out
    }

    /** Total minted supply. */
    internal fun mintedTotal(): Long = minted.values.fold(0L) { acc, m -> checkedAdd(acc, m.amount) }

    /** Total service charged where an edge is a path's final edge (the conservation term). */
    internal fun leafSpentTotal(): Long = leafSpent.values.fold(0L) { acc, c -> checkedAdd(acc, c.value) }

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
            lifecycle == other.lifecycle

    override fun hashCode(): Int {
        var h = records.hashCode()
        h = 31 * h + minted.hashCode()
        h = 31 * h + issued.hashCode()
        h = 31 * h + returned.hashCode()
        h = 31 * h + leafSpent.hashCode()
        h = 31 * h + rollupSpent.hashCode()
        h = 31 * h + transfers.hashCode()
        h = 31 * h + lifecycle.hashCode()
        return h
    }

    override fun toString(): String =
        "EntitlementLedger(records=$records, minted=$minted, issued=$issued, " +
            "returned=$returned, leafSpent=$leafSpent, rollupSpent=$rollupSpent, " +
            "transfers=$transfers, lifecycle=$lifecycle)"

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
        ): EntitlementLedger =
            EntitlementLedger(records, minted, issued, returned, leafSpent, rollupSpent, transfers, lifecycle)
    }
}

private fun counterValue(counters: Map<AttachmentId, GCounter>, id: AttachmentId): Long =
    counters[id]?.value ?: 0L

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

private fun Map<AttachmentId, GCounter>.mergeEdgeCounters(
    other: Map<AttachmentId, GCounter>,
): Map<AttachmentId, GCounter> = mergeValues(other) { mine, theirs -> mine.piece(theirs) }

private fun Map<ReplicaId, GCounter>.mergeRows(
    other: Map<ReplicaId, GCounter>,
): Map<ReplicaId, GCounter> = mergeValues(other) { mine, theirs -> mine.piece(theirs) }
