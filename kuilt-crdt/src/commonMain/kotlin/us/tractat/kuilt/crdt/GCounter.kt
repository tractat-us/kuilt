package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A grow-only counter: a per-replica map of counts that only ever increase.
 * Each replica increments **its own** slot; [piece] is elementwise max, so the
 * merged value is the sum of every replica's highest-seen count.
 *
 * Delta-state: [inc] does not mutate — it returns a [Patch] (a tiny GCounter
 * carrying just the bumped slot) that any replica absorbs with [piece]. This is
 * the first type in the zoo to actually emit a delta.
 *
 * Because merge is max, two replicas must never increment the *same* slot
 * concurrently — each replica owns its own [ReplicaId] slot.
 */
@Serializable
public class GCounter private constructor(
    private val counts: Map<ReplicaId, Long>,
) : Quilted<GCounter> {

    /** The counter's value: the sum of all per-replica counts. */
    public val value: Long get() = counts.values.sum()

    /** This replica's current count (0 if it has never incremented). */
    public fun count(replica: ReplicaId): Long = counts[replica] ?: 0L

    /**
     * Increment [replica]'s own slot by [by] (must be positive). Returns the
     * delta to merge in with [piece]; the receiver is unchanged.
     */
    public fun inc(replica: ReplicaId, by: Long = 1L): Patch<GCounter> {
        require(by >= 1L) { "GCounter increment must be positive, was $by" }
        val newCount = (counts[replica] ?: 0L) + by
        return Patch(GCounter(mapOf(replica to newCount)))
    }

    /** The join: elementwise max of the two count maps. */
    override fun piece(other: GCounter): GCounter {
        val merged = HashMap<ReplicaId, Long>(counts)
        for ((replica, c) in other.counts) {
            val current = merged[replica]
            if (current == null || c > current) merged[replica] = c
        }
        return GCounter(merged)
    }

    override fun equals(other: Any?): Boolean =
        other is GCounter && counts == other.counts

    override fun hashCode(): Int = counts.hashCode()

    override fun toString(): String = "GCounter($counts)"

    public companion object {
        /** The zero counter. */
        public val ZERO: GCounter = GCounter(emptyMap())

        /** A counter with the given per-replica counts (test/seed helper). */
        public fun of(vararg pairs: Pair<ReplicaId, Long>): GCounter = GCounter(pairs.toMap())
    }
}
