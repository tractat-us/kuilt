package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.plusOrNull

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
 *
 * @sample us.tractat.kuilt.crdt.sampleGCounter
 * @sample us.tractat.kuilt.crdt.sampleGCounterPiece
 */
@Serializable
public class GCounter private constructor(
    @Serializable(with = CanonicalMapSerializer::class)
    private val counts: Map<ReplicaId, Long>,
) : Quilted<GCounter> {

    /**
     * The counter's value: the sum of all per-replica counts.
     *
     * The sum is formed exactly, so it is right whenever it fits in a `Long`, and refused when it
     * does not. [inc] keeps every slot at or below [Long.MAX_VALUE], but that cannot protect the
     * sum: slots are only added together here, after a join, and two replicas can each hold 2⁶²
     * without either one ever making an increment it could have refused.
     *
     * @throws ArithmeticException if the sum of the counts does not fit in a `Long`. [valueOrNull]
     *   answers `null` instead.
     */
    public val value: Long
        get() = valueOrNull()
            ?: throw ArithmeticException("GCounter value $exactValue does not fit in a Long")

    /**
     * [value], or `null` when the sum of the counts does not fit in a `Long`.
     *
     * For a caller that has to carry on past one counter it cannot read, such as an exporter
     * draining many series: it skips that one and reports it, rather than catching an exception.
     */
    public fun valueOrNull(): Long? = exactValue.toLongOrNull()

    /** The sum of all per-replica counts, exactly: fewer than 2⁶³ `Long` slots cannot leave 128 bits. */
    internal val exactValue: Int128
        get() = counts.values.fold(Int128.ZERO) { sum, count -> sum + count }

    /** This replica's current count (0 if it has never incremented). */
    public fun count(replica: ReplicaId): Long = counts[replica] ?: 0L

    /**
     * The replicas holding a slot in this counter. On the mutator path that is exactly the
     * replicas that have ever incremented, since [inc] must be positive — but [of] can seed a
     * slot at `0`, and such a replica *is* listed. The set is "has a slot", not "has a positive
     * count"; take [count] if you need the latter.
     */
    public fun replicas(): Set<ReplicaId> = counts.keys

    /**
     * Increment [replica]'s own slot by [by] (must be positive). Returns the
     * delta to merge in with [piece]; the receiver is unchanged.
     *
     * @throws IllegalArgumentException if [by] is not positive.
     * @throws ArithmeticException if the slot would pass [Long.MAX_VALUE]. A wrapped count would be
     *   negative, and [piece], a max, would discard it, so the increment would be lost on every
     *   replica with nothing to say so.
     */
    public fun inc(replica: ReplicaId, by: Long = 1L): Patch<GCounter> {
        require(by >= 1L) { "GCounter increment must be positive, was $by" }
        val current = count(replica)
        val newCount = current.plusOrNull(by)
            ?: throw ArithmeticException(
                "GCounter slot $replica at $current cannot take $by more without passing Long.MAX_VALUE",
            )
        return Patch(GCounter(mapOf(replica to newCount)))
    }

    /** The join: elementwise max of the two count maps. */
    override fun piece(other: GCounter): GCounter = GCounter(counts.mergeMax(other.counts))

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
