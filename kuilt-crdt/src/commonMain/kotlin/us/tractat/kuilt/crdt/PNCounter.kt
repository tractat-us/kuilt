package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable
import us.tractat.kuilt.math.Int128

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
 * **Overflow is refused, never wrapped:** each replica's slot in either half
 * holds at most [Long.MAX_VALUE], and [increment] or [decrement] throws
 * [ArithmeticException] rather than carry one past it. A half's total is a sum
 * over every replica's slot, so after a join it can pass `Long.MAX_VALUE` even
 * though no single replica's change did; [totalIncrement] and [totalDecrement]
 * then throw. [value] is formed exactly from both halves and throws only when
 * the net itself does not fit.
 *
 * @sample us.tractat.kuilt.crdt.samplePNCounter
 */
@Serializable
public class PNCounter private constructor(
    private val inc: GCounter,
    private val dec: GCounter,
) : Quilted<PNCounter> {

    /**
     * The counter's net value: total incremented across all replicas minus total decremented.
     *
     * Formed exactly from both halves, so it is answered whenever the net fits in a `Long`, even
     * when [totalIncrement] or [totalDecrement] on its own does not.
     *
     * @throws ArithmeticException if the net does not fit in a `Long`.
     */
    public val value: Long
        get() = exactNet.toLongOrNull()
            ?: throw ArithmeticException("PNCounter value $exactNet does not fit in a Long")

    /**
     * Total ever incremented across all replicas (monotonically non-decreasing).
     *
     * @throws ArithmeticException if the total does not fit in a `Long`.
     */
    public val totalIncrement: Long get() = inc.value

    /**
     * Total ever decremented across all replicas (monotonically non-decreasing).
     *
     * @throws ArithmeticException if the total does not fit in a `Long`.
     */
    public val totalDecrement: Long get() = dec.value

    private val exactNet: Int128 get() = inc.exactValue - dec.exactValue

    /**
     * Increment [replica]'s own slot by [by] (must be positive). Returns the
     * delta to absorb with [piece]; the receiver is unchanged.
     *
     * @throws IllegalArgumentException if [by] is not positive.
     * @throws ArithmeticException if [replica]'s increment slot would pass [Long.MAX_VALUE].
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
     *
     * @throws IllegalArgumentException if [by] is not positive.
     * @throws ArithmeticException if [replica]'s decrement slot would pass [Long.MAX_VALUE].
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

    // The exact net, not [value]: a counter whose value does not fit must still print.
    override fun toString(): String = "PNCounter(value=$exactNet, inc=$inc, dec=$dec)"

    public companion object {
        /** The zero counter: no increments, no decrements. */
        public val ZERO: PNCounter = PNCounter(GCounter.ZERO, GCounter.ZERO)
    }
}
