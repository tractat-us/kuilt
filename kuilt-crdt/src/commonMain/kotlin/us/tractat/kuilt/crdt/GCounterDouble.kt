package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A grow-only counter over `Double` — the exact-precision sibling of [GCounter].
 *
 * Same lattice as [GCounter] (a per-replica map, [piece] is elementwise max), but the
 * slot values are `Double`. It exists so a monotonic OTLP `DOUBLE_SUM` metric folds
 * into a grow-only counter **without** truncation or fixed-point scaling.
 *
 * ## The one wrinkle: value determinism
 *
 * [value] sums the per-replica slots, and floating-point `+` is **not associative**, so
 * summing in map-iteration order could yield a slightly different [value] on two
 * replicas that hold *identical converged state*. The merged **state** always converges
 * (elementwise max is order-independent); only the derived [value] is order-sensitive.
 * [value] therefore sums in **canonical [ReplicaId] order** so every replica computes the
 * same number. (Very large magnitude differences can still lose low-order bits — the
 * honest floating-point limit, analogous to [HyperLogLog]'s estimation error.)
 *
 * @sample us.tractat.kuilt.crdt.sampleGCounterDouble
 */
@Serializable
public class GCounterDouble private constructor(
    private val counts: Map<ReplicaId, Double>,
) : Quilted<GCounterDouble> {

    /** The counter's value: the sum of all per-replica counts, in canonical replica order. */
    public val value: Double get() = counts.entries.sortedBy { it.key }.sumOf { it.value }

    /** This replica's current count (0.0 if it has never incremented). */
    public fun count(replica: ReplicaId): Double = counts[replica] ?: 0.0

    /**
     * Increment [replica]'s own slot by [by] (must be > 0). Returns the delta to merge
     * in with [piece]; the receiver is unchanged.
     */
    public fun inc(replica: ReplicaId, by: Double = 1.0): Patch<GCounterDouble> {
        require(by > 0.0) { "GCounterDouble increment must be positive, was $by" }
        val newCount = (counts[replica] ?: 0.0) + by
        return Patch(GCounterDouble(mapOf(replica to newCount)))
    }

    /** The join: elementwise max of the two count maps. */
    override fun piece(other: GCounterDouble): GCounterDouble {
        val merged = HashMap<ReplicaId, Double>(counts)
        for ((replica, c) in other.counts) {
            val current = merged[replica]
            if (current == null || c > current) merged[replica] = c
        }
        return GCounterDouble(merged)
    }

    override fun equals(other: Any?): Boolean = other is GCounterDouble && counts == other.counts
    override fun hashCode(): Int = counts.hashCode()
    override fun toString(): String = "GCounterDouble($counts)"

    public companion object {
        /** The zero counter. */
        public val ZERO: GCounterDouble = GCounterDouble(emptyMap())

        /** A counter with the given per-replica counts (test/seed helper). */
        public fun of(vararg pairs: Pair<ReplicaId, Double>): GCounterDouble = GCounterDouble(pairs.toMap())
    }
}
