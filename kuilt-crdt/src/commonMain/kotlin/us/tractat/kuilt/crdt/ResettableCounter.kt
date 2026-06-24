package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A counter that supports **reset-to-zero without coordination**, using causal
 * context to distinguish increments the resetter has observed (cleared by a
 * reset) from those concurrent with it (which survive the merge).
 *
 * This is the counter analogue of observed-remove: a reset removes exactly the
 * increments it has causally witnessed, so an increment that raced with the
 * reset — minted on a replica that had not yet seen the reset — survives and
 * appears in the merged value.
 *
 * **Convergence rule:** `Causal<DotFun<Long>>`. Each increment mints a fresh
 * [Dot] carrying the `by` amount. A reset retires every currently-live dot
 * into the causal context and empties the store. Subsequent increments carry
 * dots the resetter has not yet witnessed, so they survive the causal merge.
 *
 * **Use cases:** per-round scores, seasonal leaderboards, or any counter that
 * needs a clean-slate without coordination.
 *
 * **Delta-state:** [increment] and [reset] return a [Patch] that any replica
 * absorbs with [piece]. The receiver is never mutated.
 *
 * **Serialization:** the internal dot map uses [Dot] as a key. Standard JSON
 * requires `Json { allowStructuredMapKeys = true }`; CBOR and Protobuf encode
 * it cleanly without any flag.
 *
 * @sample us.tractat.kuilt.crdt.sampleResettableCounter
 */
@Serializable
public class ResettableCounter private constructor(
    internal val causal: Causal<DotFun<Long>>,
) : Quilted<ResettableCounter> {

    /** The counter's current value: sum of all live increment amounts. */
    public val value: Long get() = causal.store.values.values.sum()

    /**
     * Increment by [by] (must be positive) on behalf of [replica]. Returns
     * the delta to absorb with [piece]; the receiver is unchanged.
     *
     * Each call mints a fresh [Dot] carrying exactly [by]. Prior increments
     * from the same replica remain live until a [reset] retires them.
     */
    public fun increment(replica: ReplicaId, by: Long = 1L): Patch<ResettableCounter> {
        require(by >= 1L) { "ResettableCounter increment must be positive, was $by" }
        val dot = causal.context.nextDot(replica)
        return Patch(ResettableCounter(Causal(DotFun(mapOf(dot to by)), DotContext.of(dot))))
    }

    /**
     * Reset the counter to zero: retire every currently-live dot into the
     * causal context and clear the store. Returns the delta to absorb with
     * [piece]; the receiver is unchanged.
     *
     * Any increment concurrent with this reset — minted on a replica that had
     * not yet seen the reset — will survive the merge, because its dot is not
     * in this reset's context.
     */
    public fun reset(): Patch<ResettableCounter> {
        val allDots = causal.store.values.keys
        val newContext = allDots.fold(causal.context) { ctx, dot -> ctx.add(dot) }
        return Patch(ResettableCounter(Causal(DotFun(), newContext)))
    }

    /** The causal merge of two replicas. */
    override fun piece(other: ResettableCounter): ResettableCounter =
        ResettableCounter(causal.piece(other.causal))

    override fun equals(other: Any?): Boolean =
        other is ResettableCounter && causal == other.causal

    override fun hashCode(): Int = causal.hashCode()

    override fun toString(): String = "ResettableCounter(value=$value)"

    public companion object {
        /** The zero counter: no increments, no causal history. */
        public val ZERO: ResettableCounter =
            ResettableCounter(Causal(DotFun(), DotContext.EMPTY))
    }
}
