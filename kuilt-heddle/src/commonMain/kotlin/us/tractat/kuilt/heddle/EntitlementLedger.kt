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
 * (the integrity report). Each feasibility-consuming mutator carries a
 * self-justifying witness so [validate] never false-fires under honest partial
 * delivery.
 *
 * Every present edge is treated as **ACTIVE** here; the lifecycle lattice (PREPARED
 * / ACTIVE / CLOSING / RETIRED) and its `DualActiveInbound` / `ClosureViolation`
 * conflicts are a later phase.
 *
 * ## The representation
 *
 * Seven components, each already a join-semilattice, so [piece] is just their
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
        )

    /**
     * The parent-facing [EdgeSummary] for [id], or `null` if the edge is entirely
     * unknown to this ledger. `spent` is the total charged through the edge —
     * `leafSpent + rollupSpent`.
     */
    public fun edge(id: AttachmentId): EdgeSummary? {
        val known = id in records ||
            id in issued || id in returned || id in leafSpent || id in rollupSpent
        if (!known) return null
        return EdgeSummary(
            attachment = id,
            issued = counterValue(issued, id),
            returned = counterValue(returned, id),
            spent = counterValue(leafSpent, id) + counterValue(rollupSpent, id),
        )
    }

    /**
     * Summaries of every edge whose parent is [parent], in a deterministic order
     * (by [AttachmentId]). In this phase every present edge is treated as active;
     * lifecycle filtering arrives with the lifecycle register in a later phase. An
     * edge counts if **any** record under its id names [parent] — divergent records
     * are retained, not collapsed, and their reconciliation is a later phase's job.
     */
    public fun activeChildren(parent: GroupId): List<EdgeSummary> =
        records
            .filter { (_, recs) -> recs.any { it.parent == parent } }
            .keys
            .sorted()
            .mapNotNull { edge(it) }

    // ─────────────────────────────────────────────────────────────────────────
    // Topology helpers (H1b treats every present, singleton-recorded edge as ACTIVE)
    // ─────────────────────────────────────────────────────────────────────────

    /** The single record for [id], or `null` if [id] is unknown *or divergent* (`size > 1`). */
    private fun recordOf(id: AttachmentId): AttachmentRecord? = records[id]?.singleOrNull()

    /**
     * The edges from the root down to [group]'s inbound edge, in root→group order
     * (empty when [group] is the root). `null` signals the lineage is **quarantined**
     * and no holdings may be derived: a divergent record on the path
     * ([LedgerConflict.RecordDivergence]), a group with two inbound edges (a
     * [LedgerConflict.PersistentNegativeHoldings]-adjacent H2 `DualActiveInbound`,
     * treated conservatively here as a quarantine), or a cycle.
     */
    private fun lineageEdges(group: GroupId): List<AttachmentId>? {
        val edges = ArrayDeque<AttachmentId>()
        val seen = HashSet<GroupId>()
        var cur = group
        while (true) {
            if (!seen.add(cur)) return null // cycle
            val inboundIds = records.filter { (_, recs) -> recs.any { it.child == cur } }.keys
            if (inboundIds.isEmpty()) break // reached the root
            if (inboundIds.size > 1) return null // dual inbound (H2) → quarantine
            val id = inboundIds.single()
            val rec = recordOf(id) ?: return null // divergent record on the lineage → quarantine
            edges.addFirst(id)
            cur = rec.parent
        }
        return edges.toList()
    }

    /** The child edges of [group] (singleton records whose parent is [group]), sorted. */
    private fun childEdges(group: GroupId): List<AttachmentId> =
        records.filter { (_, recs) -> recs.singleOrNull()?.parent == group }.keys.sorted()

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
    // Mutators — house idiom: check feasibility on `this`, return Patch?/null.
    // Each feasibility-consuming patch also carries a self-justifying witness
    // (design fix 2): the observed credit slots its holdings check read along the
    // lineage, at their absolute values (max-safe), so a state that contains the
    // debit always contains its justification and `validate` never false-fires
    // under honest partial delivery.
    // ─────────────────────────────────────────────────────────────────────────

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
     * the child. `null` if [r]'s holdings at the parent are insufficient, or [edge] is
     * unknown/divergent. Bumps `issued(edge)[r]`.
     */
    public fun delegate(r: ReplicaId, edge: AttachmentId, amount: Long): Patch<EntitlementLedger>? {
        require(amount >= 1L) { "delegate amount must be positive, was $amount" }
        val parent = recordOf(edge)?.parent ?: return null
        if (amount > holdings(parent, r)) return null
        val lineage = lineageEdges(parent) ?: return null
        val bump = of(issued = mapOf(edge to bumpedSlot(issued, edge, r, amount)))
        return Patch(bump.piece(witness(r, lineage)))
    }

    /**
     * [r] returns [amount] of unused entitlement up [edge], restoring the parent's
     * holdings. `null` if [r]'s holdings at the child are insufficient, or [edge] is
     * unknown/divergent. Bumps `returned(edge)[r]`.
     */
    public fun release(r: ReplicaId, edge: AttachmentId, amount: Long): Patch<EntitlementLedger>? {
        require(amount >= 1L) { "release amount must be positive, was $amount" }
        val child = recordOf(edge)?.child ?: return null
        if (amount > holdings(child, r)) return null
        val lineage = lineageEdges(child) ?: return null
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
     * The self-justifying witness (design fix 2): the credit slots [actor]'s holdings
     * check read along [lineage], at their observed absolute values. Carrying them in
     * the patch guarantees any state holding the debit also holds a credit ≥ its
     * justification, so [validate]'s [LedgerConflict.PerEdgeSafety] /
     * [LedgerConflict.PersistentNegativeHoldings] cannot false-fire under honest
     * partial delivery. Absolute values are max-safe, so over-inclusion is harmless.
     */
    private fun witness(actor: ReplicaId, lineage: List<AttachmentId>): EntitlementLedger {
        val wIssued = HashMap<AttachmentId, GCounter>()
        val wReturned = HashMap<AttachmentId, GCounter>()
        for (e in lineage) {
            slot(issued, e, actor).takeIf { it > 0L }?.let { wIssued[e] = GCounter.of(actor to it) }
            slot(returned, e, actor).takeIf { it > 0L }?.let { wReturned[e] = GCounter.of(actor to it) }
        }
        val wMinted = minted.filterValues { it.holder == actor }
        val wTransfers = HashMap<PathKey, Map<ReplicaId, GCounter>>()
        for (pathKey in listOf(PathKey.ROOT) + lineage.map { PathKey.of(it) }) {
            val rows = transfers[pathKey] ?: continue
            val kept = HashMap<ReplicaId, GCounter>()
            for ((donor, row) in rows) {
                if (donor == actor) {
                    kept[donor] = row // actor's own outflow row — actor-authored, max-safe
                } else {
                    row.count(actor).takeIf { it > 0L }?.let { kept[donor] = GCounter.of(actor to it) }
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
     * (identical on every replica). With the self-justifying witness these fire only
     * on genuine faults, never honest lag:
     *
     *  - [LedgerConflict.PerEdgeSafety] — sum-wise `leafSpent + rollupSpent + returned
     *    > issued` on an edge's aggregate values.
     *  - [LedgerConflict.PersistentNegativeHoldings] — a `(group, replica)` with
     *    negative derived [holdings]: the real overspend net. Quarantined lineages
     *    (holdings `0`) are excluded — they surface as [LedgerConflict.RecordDivergence].
     *  - [LedgerConflict.RecordDivergence] — two distinct records under one id.
     *
     * `DualActiveInbound` / `ClosureViolation` are the lifecycle phase (H2) and are
     * deliberately not reported here.
     */
    public fun validate(): List<LedgerConflict> {
        val conflicts = ArrayList<LedgerConflict>()
        for (e in allEdges()) {
            val charged = checkedAdd(
                checkedAdd(counterValue(leafSpent, e), counterValue(rollupSpent, e)),
                counterValue(returned, e),
            )
            if (charged > counterValue(issued, e)) conflicts += LedgerConflict.PerEdgeSafety(e)
        }
        for ((id, recs) in records) {
            if (recs.size > 1) conflicts += LedgerConflict.RecordDivergence(id)
        }
        for (g in allGroups()) {
            for (r in allReplicas()) {
                if (holdings(g, r) < 0L) conflicts += LedgerConflict.PersistentNegativeHoldings(g, r)
            }
        }
        return conflicts.sorted()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Enumeration + test-support accessors (internal — used by validate and tests)
    // ─────────────────────────────────────────────────────────────────────────

    /** Every edge id mentioned by any component. */
    internal fun allEdges(): Set<AttachmentId> =
        records.keys + issued.keys + returned.keys + leafSpent.keys + rollupSpent.keys

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
    )

    override fun equals(other: Any?): Boolean =
        other is EntitlementLedger &&
            records == other.records &&
            minted == other.minted &&
            issued == other.issued &&
            returned == other.returned &&
            leafSpent == other.leafSpent &&
            rollupSpent == other.rollupSpent &&
            transfers == other.transfers

    override fun hashCode(): Int {
        var h = records.hashCode()
        h = 31 * h + minted.hashCode()
        h = 31 * h + issued.hashCode()
        h = 31 * h + returned.hashCode()
        h = 31 * h + leafSpent.hashCode()
        h = 31 * h + rollupSpent.hashCode()
        h = 31 * h + transfers.hashCode()
        return h
    }

    override fun toString(): String =
        "EntitlementLedger(records=$records, minted=$minted, issued=$issued, " +
            "returned=$returned, leafSpent=$leafSpent, rollupSpent=$rollupSpent, transfers=$transfers)"

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
        ): EntitlementLedger =
            EntitlementLedger(records, minted, issued, returned, leafSpent, rollupSpent, transfers)
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

private fun Map<AttachmentId, GCounter>.mergeEdgeCounters(
    other: Map<AttachmentId, GCounter>,
): Map<AttachmentId, GCounter> = mergeValues(other) { mine, theirs -> mine.piece(theirs) }

private fun Map<ReplicaId, GCounter>.mergeRows(
    other: Map<ReplicaId, GCounter>,
): Map<ReplicaId, GCounter> = mergeValues(other) { mine, theirs -> mine.piece(theirs) }
