package us.tractat.kuilt.crdt

/**
 * A container of [Dot]s, optionally each carrying a payload. The shapes —
 * [DotSet] (presence), [DotFun] (dot → value), [LatticeDotFun] (dot → a value that
 * grows) and [DotMap] (key → store) — all share the one causal [join] defined here
 * in spirit; pairing any of them
 * with a [DotContext] inside a [Causal] yields a full delta-state CRDT.
 *
 * [DotMap] is the one store that nests other stores, and [CausalMap] relies on that: since
 * [dots] is a set, it counts a dot held at two positions by recursing into [DotMap] and taking
 * every other store as flat. A new store that nests dots belongs in that count too.
 */
public interface DotStore<S : DotStore<S>> {
    /** Every dot currently live in this store. */
    public val dots: Set<Dot>

    /** True when the store holds no dots. */
    public val isBottom: Boolean get() = dots.isEmpty()

    /** The empty (bottom) store of this shape — no dots. */
    public val empty: S

    /**
     * The causal join with [other]. A dot is kept when it is live in **both**
     * stores, or live in one and **not yet witnessed** by the other side's
     * context; it is dropped only when one side still has it while the other has
     * it in [otherContext] but not in its store — the signature of a deliberate
     * remove. [context] is this store's surrounding causal history; [otherContext]
     * is the other's.
     */
    public fun join(other: S, context: DotContext, otherContext: DotContext): S
}
