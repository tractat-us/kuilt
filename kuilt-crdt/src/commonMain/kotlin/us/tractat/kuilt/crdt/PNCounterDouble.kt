package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A positive/negative counter over `Double` — the fractional sibling of [PNCounter], made of two
 * [GCounterDouble] halves the way [PNCounter] is made of two [GCounter]s.
 *
 * It exists for a quantity that goes both up and down and adds up across replicas, measured in
 * fractions: the gigabytes each node has buffered, say. Each replica moves only **its own** slot,
 * [increment] and [decrement] return a [Patch] carrying just that slot, and [piece] joins each half
 * by elementwise max.
 *
 * ## Reading it
 *
 * [count] is one replica's share, its increments less its decrements. [value] is the sum of every
 * replica's share, taken in **canonical [ReplicaId] order**: floating-point `+` is not associative,
 * and two replicas holding equal state must read the same number.
 *
 * ## Honest limits
 *
 * A share is a difference of two totals that only grow, so its rounding error grows with how far
 * the share has moved in all, not with where it stands: a share that has gone up and down by a
 * billion units in total carries an error near `1e-7`, wherever it is now. A replica moving its share
 * to a reported level should therefore treat a gap of a few ulps of its own totals as no gap.
 *
 * Every slot is finite, and the type enforces it. A slot at infinity could never come back, since
 * `∞ − x` is `∞` and `∞ − ∞` is NaN, so [increment] and [decrement] throw [ArithmeticException]
 * rather than take a slot past [Double.MAX_VALUE], and the decoder refuses an infinite slot. That
 * holds each slot, not the sum: replicas whose shares are each finite can still add up past
 * [Double.MAX_VALUE], and [value] is then infinite.
 *
 * @sample us.tractat.kuilt.crdt.samplePNCounterDouble
 */
@Serializable
public class PNCounterDouble private constructor(
    private val inc: GCounterDouble,
    private val dec: GCounterDouble,
) : Quilted<PNCounterDouble> {

    // In `init`, so the generated decoder runs it too. Each half already refuses a negative or NaN slot.
    init {
        requireFiniteSlots(inc)
        requireFiniteSlots(dec)
    }

    /** The sum of every replica's [count], in canonical replica order. */
    public val value: Double
        get() = (inc.slots.keys + dec.slots.keys).sorted().sumOf { count(it) }

    /** Total ever incremented across all replicas, in canonical replica order. */
    public val totalIncrement: Double get() = inc.value

    /** Total ever decremented across all replicas, in canonical replica order. */
    public val totalDecrement: Double get() = dec.value

    /** [replica]'s own share of [value]: its increments less its decrements, `0.0` with no slot. */
    public fun count(replica: ReplicaId): Double = inc.count(replica) - dec.count(replica)

    /**
     * Increment [replica]'s own slot by [by]. Returns the delta to absorb with [piece]; the receiver
     * is unchanged.
     *
     * @throws IllegalArgumentException if [by] is not positive and finite.
     * @throws ArithmeticException if [replica]'s increment slot would pass [Double.MAX_VALUE].
     */
    public fun increment(replica: ReplicaId, by: Double = 1.0): Patch<PNCounterDouble> {
        requireStep("increment", by)
        checkFinite("increment", replica, inc.count(replica), by)
        return Patch(PNCounterDouble(inc = inc.inc(replica, by).delta, dec = GCounterDouble.ZERO))
    }

    /**
     * Decrement [replica]'s own slot by [by]. Returns the delta to absorb with [piece]; the receiver
     * is unchanged. [value] may go negative; there is no floor.
     *
     * @throws IllegalArgumentException if [by] is not positive and finite.
     * @throws ArithmeticException if [replica]'s decrement slot would pass [Double.MAX_VALUE].
     */
    public fun decrement(replica: ReplicaId, by: Double = 1.0): Patch<PNCounterDouble> {
        requireStep("decrement", by)
        checkFinite("decrement", replica, dec.count(replica), by)
        return Patch(PNCounterDouble(inc = GCounterDouble.ZERO, dec = dec.inc(replica, by).delta))
    }

    /** The join: elementwise max on both halves. */
    override fun piece(other: PNCounterDouble): PNCounterDouble =
        PNCounterDouble(inc = inc.piece(other.inc), dec = dec.piece(other.dec))

    override fun equals(other: Any?): Boolean =
        other is PNCounterDouble && inc == other.inc && dec == other.dec

    override fun hashCode(): Int = 31 * inc.hashCode() + dec.hashCode()

    override fun toString(): String = "PNCounterDouble(value=$value, inc=$inc, dec=$dec)"

    public companion object {
        /** The zero counter: no increments, no decrements. */
        public val ZERO: PNCounterDouble = PNCounterDouble(GCounterDouble.ZERO, GCounterDouble.ZERO)

        private fun requireStep(what: String, by: Double) {
            require(by > 0.0 && by.isFinite()) { "PNCounterDouble $what must be positive and finite, was $by" }
        }

        private fun checkFinite(what: String, replica: ReplicaId, current: Double, by: Double) {
            if (!(current + by).isFinite()) {
                throw ArithmeticException(
                    "PNCounterDouble $what slot $replica at $current cannot take $by more without passing Double.MAX_VALUE",
                )
            }
        }
    }
}

/** Top-level rather than in the companion, so [PNCounterDouble.ZERO]'s own construction never waits on it. */
private fun requireFiniteSlots(half: GCounterDouble) {
    for ((replica, count) in half.slots) {
        require(count.isFinite()) { "PNCounterDouble slot $replica must be finite, was $count" }
    }
}
