package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A positive/negative counter: two [GCounter] halves composed in a product
 * lattice. One half tracks all increments, the other all decrements.
 *
 * **Intuition:** each half is an independent [GCounter] whose [piece] is
 * elementwise max. Joining the product of two such lattices is just joining
 * each component separately. The observable [value] is `inc − dec`, which can
 * be negative if decrements outpace increments.
 *
 * **Delta-state:** [increment] and [decrement] do not mutate — each returns a
 * [Patch] (a tiny PNCounter carrying only the changed slot in one half) that
 * any replica absorbs with [piece]. This mirrors [GCounter.inc].
 *
 * **Replica ownership:** each replica increments/decrements its *own* slot.
 * Two replicas must never mutate the same slot concurrently — that is the
 * caller's responsibility, exactly as with [GCounter].
 *
 * **Signed vs unsigned:** the counters are `Long`-based throughout. A replica
 * decrementing more than it (or any peer) has incremented is legal — [value]
 * goes negative. This matches standard PNCounter semantics. If you need a
 * floor at zero, use [BoundedCounter] instead.
 *
 * **No wraparound risk:** both halves count upward monotonically; [value] is a
 * subtraction of two non-negative `Long` sums. Overflow is theoretically
 * possible at `Long.MAX_VALUE` increments per replica, which is not a practical
 * concern.
 *
 * @sample us.tractat.kuilt.crdt.samplePNCounter
 */
@Serializable
public class PNCounter private constructor(
    private val inc: GCounter,
    private val dec: GCounter,
) : Quilted<PNCounter> {

    /** The counter's net value: total incremented across all replicas minus total decremented. */
    public val value: Long get() = inc.value - dec.value

    /** Total ever incremented across all replicas (monotonically non-decreasing). */
    public val totalIncrement: Long get() = inc.value

    /** Total ever decremented across all replicas (monotonically non-decreasing). */
    public val totalDecrement: Long get() = dec.value

    /**
     * Increment [replica]'s own slot by [by] (must be positive). Returns the
     * delta to absorb with [piece]; the receiver is unchanged.
     */
    public fun increment(replica: ReplicaId, by: Long = 1L): Patch<PNCounter> {
        require(by >= 1L) { "PNCounter increment must be positive, was $by" }
        return Patch(PNCounter(inc = inc.inc(replica, by).delta, dec = GCounter.ZERO))
    }

    /**
     * Decrement [replica]'s own slot by [by] (must be positive). Returns the
     * delta to absorb with [piece]; the receiver is unchanged.
     *
     * [value] may go negative — there is no floor. For a floor-at-zero bounded
     * counter, use [BoundedCounter].
     */
    public fun decrement(replica: ReplicaId, by: Long = 1L): Patch<PNCounter> {
        require(by >= 1L) { "PNCounter decrement must be positive, was $by" }
        return Patch(PNCounter(inc = GCounter.ZERO, dec = dec.inc(replica, by).delta))
    }

    /** The join: per-component max on both halves. */
    override fun piece(other: PNCounter): PNCounter =
        PNCounter(
            inc = inc.piece(other.inc),
            dec = dec.piece(other.dec),
        )

    override fun equals(other: Any?): Boolean =
        other is PNCounter && inc == other.inc && dec == other.dec

    override fun hashCode(): Int = 31 * inc.hashCode() + dec.hashCode()

    override fun toString(): String = "PNCounter(value=$value, inc=$inc, dec=$dec)"

    public companion object {
        /** The zero counter: no increments, no decrements. */
        public val ZERO: PNCounter = PNCounter(GCounter.ZERO, GCounter.ZERO)
    }
}
