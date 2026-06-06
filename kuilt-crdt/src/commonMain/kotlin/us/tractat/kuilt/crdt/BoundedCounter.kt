package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A **bounded / escrow counter**: a shared budget pre-divided into per-replica
 * **quotas**, with the invariant `quota(r) >= 0` enforced *locally* and the
 * derived global invariant *"total spent <= total received"* held by
 * construction. No coordination is required for the local check — only for
 * **redistributing** quota via [transfer].
 *
 * Internally, two [GCounter]s, both monotonically increasing:
 *
 *  - `received[r]` — total budget `r` has ever received (initial slice plus
 *    every transfer in).
 *  - `spent[r]`    — total `r` has ever spent (own spends plus every transfer
 *    *out*).
 *
 * From these:
 *
 *  - `quota(r)       = received[r] - spent[r]`
 *  - `totalBudget    = sum(received) - sum(spent)`
 *  - `totalSpent     = sum(spent)`
 *
 * Because both components are G-counters, [piece] is just the per-component
 * merge — already proven by [GCounter] — so no new lattice math is introduced.
 * The novelty of this rung is the **local invariant** ([trySpend] denies any
 * operation that would overdraw the calling replica's quota), not the merge.
 *
 * **Rung 5a scope:** the data type and the local-decrement safety. The
 * transfer-request protocol that asks a peer to *initiate* a transfer over a
 * Seam, and any rebalancing policy, are deferred to Rung 5b once the Seam
 * replicator (Rung 12) exists.
 */
@Serializable
public class BoundedCounter private constructor(
    private val received: GCounter,
    private val spent: GCounter,
) : Quilted<BoundedCounter> {

    /** The total budget remaining across the whole system. */
    public val totalBudget: Long get() = received.value - spent.value

    /** The total spent across the whole system. */
    public val totalSpent: Long get() = spent.value

    /** [replica]'s remaining quota — what it may still spend locally. */
    public fun quota(replica: ReplicaId): Long =
        received.count(replica) - spent.count(replica)

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
        return Patch(BoundedCounter(received = GCounter.ZERO, spent = spentDelta))
    }

    /**
     * Move [amount] of quota from [from] to [to]: bump `spent[from]` and
     * `received[to]` together. Returns a [Patch] when [from] has quota to
     * release; `null` otherwise. The caller's state is unchanged either way.
     * [amount] must be positive and [from] must differ from [to].
     *
     * This is the sender-side primitive of the transfer. The deferred Rung 5b
     * adds the request/grant protocol that lets a low replica *ask* a peer
     * with spare quota to invoke this; without it, peers degrade gracefully to
     * "deny when low".
     */
    public fun transfer(from: ReplicaId, to: ReplicaId, amount: Long): Patch<BoundedCounter>? {
        require(amount >= 1L) { "BoundedCounter transfer must be positive, was $amount" }
        require(from != to) { "BoundedCounter transfer requires from != to" }
        if (amount > quota(from)) return null
        val spentDelta = spent.inc(from, amount).delta
        val receivedDelta = received.inc(to, amount).delta
        return Patch(BoundedCounter(received = receivedDelta, spent = spentDelta))
    }

    /** Per-component G-counter merge — both are join-semilattices. */
    override fun piece(other: BoundedCounter): BoundedCounter =
        BoundedCounter(received.piece(other.received), spent.piece(other.spent))

    override fun equals(other: Any?): Boolean =
        other is BoundedCounter && received == other.received && spent == other.spent

    override fun hashCode(): Int = 31 * received.hashCode() + spent.hashCode()

    override fun toString(): String =
        "BoundedCounter(budget=$totalBudget, spent=$totalSpent, received=$received, spent=$spent)"

    public companion object {
        /** A bounded counter with no participants and no budget. */
        public val EMPTY: BoundedCounter = BoundedCounter(GCounter.ZERO, GCounter.ZERO)

        /**
         * Seed the counter with initial per-replica quotas. Each entry funds
         * that replica's `received` slot; all `spent` slots start at zero.
         * Quotas must be non-negative.
         */
        public fun init(quotas: Map<ReplicaId, Long>): BoundedCounter {
            quotas.forEach { (r, q) ->
                require(q >= 0L) { "BoundedCounter init quota must be non-negative for $r, was $q" }
            }
            val nonZero = quotas.filter { it.value > 0L }
            return BoundedCounter(
                received = GCounter.of(*nonZero.map { it.key to it.value }.toTypedArray()),
                spent = GCounter.ZERO,
            )
        }
    }
}
