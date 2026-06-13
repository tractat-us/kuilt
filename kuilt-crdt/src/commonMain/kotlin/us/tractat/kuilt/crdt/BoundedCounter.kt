package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A **bounded / escrow counter**: a shared budget pre-divided into per-replica
 * **quotas**, with the invariant `quota(r) >= 0` enforced *locally* and the
 * derived global invariant *"total spent <= total received"* held by
 * construction. No coordination is required for the local check — only for
 * **redistributing** quota via [transfer].
 *
 * Internally, three per-replica state components — every one a join-semilattice,
 * so [piece] is just the per-component merge:
 *
 *  - `initial[r]`              — `r`'s seed quota (set by [init], immutable after).
 *  - `transfers[s].count(r)`   — total quota `s` has ever transferred to `r`;
 *                                **each (s, r) slot is owned exclusively by `s`**,
 *                                so concurrent transfers from different donors to
 *                                the same receiver compose without collision.
 *  - `spent[r]`                — total `r` has ever **consumed**. Transfers out
 *                                do NOT bump `spent` — they move through
 *                                `transfers` instead — so `totalSpent` reads as
 *                                pure consumption.
 *
 * Derived:
 *
 *  - `quota(r)    = initial[r] + Σ_s transfers[s].count(r) − Σ_t transfers[r].count(t) − spent[r]`
 *  - `totalBudget = sum(initial) − sum(spent)`   *(transfers conserved)*
 *  - `totalSpent  = sum(spent)`
 *
 * **Rung 5a scope:** the data type and local-decrement safety. The
 * transfer-request protocol — how a low replica *asks* a peer to invoke
 * [transfer] over a Seam, and rebalancing policy — is deferred to Rung 5b once
 * the Seam replicator (Rung 12) exists.
 *
 * @sample us.tractat.kuilt.crdt.sampleBoundedCounter
 */
@Serializable
public class BoundedCounter private constructor(
    private val initial: GCounter,
    private val transfers: Map<ReplicaId, GCounter>,
    private val spent: GCounter,
) : Quilted<BoundedCounter> {

    /** The total budget remaining across the whole system. */
    public val totalBudget: Long get() = initial.value - spent.value

    /** Total **consumed** across the whole system (does not include transfers). */
    public val totalSpent: Long get() = spent.value

    /** [replica]'s remaining quota — what it may still spend locally. */
    public fun quota(replica: ReplicaId): Long {
        val received = transfers.values.sumOf { it.count(replica) }
        val given = transfers[replica]?.value ?: 0L
        return initial.count(replica) + received - given - spent.count(replica)
    }

    /**
     * Try to spend [amount] on behalf of [replica]. Returns a [Patch] when the
     * spend is within [replica]'s current quota; `null` otherwise. The caller's
     * state is unchanged in either case — apply the patch with [piece] to
     * commit. [amount] must be positive.
     */
    public fun trySpend(replica: ReplicaId, amount: Long = 1L): Patch<BoundedCounter>? {
        require(amount >= 1L) { "BoundedCounter spend must be positive, was $amount" }
        if (amount > quota(replica)) return null
        val spentDelta = spent.inc(replica, amount).delta
        return Patch(BoundedCounter(initial = GCounter.ZERO, transfers = emptyMap(), spent = spentDelta))
    }

    /**
     * Move [amount] of quota from [from] to [to] by appending to **`from`'s own
     * row** of the transfers matrix. Returns a [Patch] when [from] has quota to
     * release; `null` otherwise. The caller's state is unchanged either way.
     * [amount] must be positive and [from] must differ from [to].
     *
     * Two concurrent donors transferring to the same receiver compose
     * correctly: each writes its own row, and the per-row merge keeps both.
     *
     * This is the sender-side primitive of the transfer. Rung 5b will add the
     * request/grant protocol over a Seam; without it, peers degrade
     * gracefully to "deny when low".
     */
    public fun transfer(from: ReplicaId, to: ReplicaId, amount: Long): Patch<BoundedCounter>? {
        require(amount >= 1L) { "BoundedCounter transfer must be positive, was $amount" }
        require(from != to) { "BoundedCounter transfer requires from != to" }
        if (amount > quota(from)) return null
        val fromRow = transfers[from] ?: GCounter.ZERO
        val rowDelta = fromRow.inc(to, amount).delta
        return Patch(
            BoundedCounter(
                initial = GCounter.ZERO,
                transfers = mapOf(from to rowDelta),
                spent = GCounter.ZERO,
            ),
        )
    }

    /** Per-component join — every component is a join-semilattice. */
    override fun piece(other: BoundedCounter): BoundedCounter =
        BoundedCounter(
            initial = initial.piece(other.initial),
            transfers = mergeTransfers(other.transfers),
            spent = spent.piece(other.spent),
        )

    private fun mergeTransfers(other: Map<ReplicaId, GCounter>): Map<ReplicaId, GCounter> {
        val merged = HashMap<ReplicaId, GCounter>(transfers)
        for ((sender, theirRow) in other) {
            val mine = merged[sender]
            merged[sender] = if (mine == null) theirRow else mine.piece(theirRow)
        }
        return merged
    }

    override fun equals(other: Any?): Boolean =
        other is BoundedCounter &&
            initial == other.initial &&
            transfers == other.transfers &&
            spent == other.spent

    override fun hashCode(): Int {
        var h = initial.hashCode()
        h = 31 * h + transfers.hashCode()
        h = 31 * h + spent.hashCode()
        return h
    }

    override fun toString(): String =
        "BoundedCounter(budget=$totalBudget, spent=$totalSpent, initial=$initial, transfers=$transfers)"

    public companion object {
        /** A bounded counter with no participants and no budget. */
        public val EMPTY: BoundedCounter = BoundedCounter(GCounter.ZERO, emptyMap(), GCounter.ZERO)

        /**
         * Seed the counter with initial per-replica quotas. Quotas must be
         * non-negative; zero-quota entries are dropped (equivalent to absent).
         */
        public fun init(quotas: Map<ReplicaId, Long>): BoundedCounter {
            quotas.forEach { (r, q) ->
                require(q >= 0L) { "BoundedCounter init quota must be non-negative for $r, was $q" }
            }
            val nonZero = quotas.filter { it.value > 0L }
            return BoundedCounter(
                initial = GCounter.of(*nonZero.map { it.key to it.value }.toTypedArray()),
                transfers = emptyMap(),
                spent = GCounter.ZERO,
            )
        }
    }
}
