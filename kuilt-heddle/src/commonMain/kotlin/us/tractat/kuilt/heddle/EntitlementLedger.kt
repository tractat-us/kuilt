package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
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
 * ## This phase is inert
 *
 * This is the data layer only: you can construct ledger states (via [ZERO] and
 * [bootstrap], or the internal test factory) and merge them with [piece], and the
 * merge is a provable join-semilattice. The operations that *change* entitlement
 * (grant, return, transfer, spend) and the integrity checks are added in the next
 * phase; a ledger you can only construct and merge is the correct intermediate.
 *
 * ## The representation
 *
 * Seven components, each already a join-semilattice, so [piece] is just their
 * componentwise join (the product-of-lattices idiom):
 *
 *  - [records] — the immutable topology (parent/child/weight per edge), grow-only
 *    union; one [AttachmentId] ⇒ one [AttachmentRecord].
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
    private val records: Map<AttachmentId, AttachmentRecord>,
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
            records = records.mergeValues(other.records) { mine, theirs -> maxOf(mine, theirs) },
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
     * lifecycle filtering arrives with the lifecycle register in a later phase.
     */
    public fun activeChildren(parent: GroupId): List<EdgeSummary> =
        records.values
            .filter { it.parent == parent }
            .map { it.id }
            .sorted()
            .mapNotNull { edge(it) }

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
         * A ledger seeded with root supply: each `(replica, amount)` in [mint]
         * becomes a [MintRecord] credited to that replica, keyed by a deterministic
         * [MintId] namespaced to [root]. No edges yet — the topology is grown by the
         * mutators (a later phase). Every amount must be non-negative.
         */
        public fun bootstrap(root: GroupId, mint: Map<ReplicaId, Long>): EntitlementLedger {
            val minted = mint.entries.associate { (holder, amount) ->
                MintId("${root.value}#${holder.value}") to MintRecord(holder, amount)
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
            records: Map<AttachmentId, AttachmentRecord> = emptyMap(),
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

private fun Map<AttachmentId, GCounter>.mergeEdgeCounters(
    other: Map<AttachmentId, GCounter>,
): Map<AttachmentId, GCounter> = mergeValues(other) { mine, theirs -> mine.piece(theirs) }

private fun Map<ReplicaId, GCounter>.mergeRows(
    other: Map<ReplicaId, GCounter>,
): Map<ReplicaId, GCounter> = mergeValues(other) { mine, theirs -> mine.piece(theirs) }
