package us.tractat.kuilt.crdt

/**
 * A container of [Dot]s, optionally each carrying a payload. The shapes —
 * [DotSet] (presence), and later DotFun (dot → value) and DotMap (key → store) —
 * all share the one causal [join] defined here in spirit; pairing any of them
 * with a [DotContext] inside a [Causal] yields a full delta-state CRDT.
 */
public interface DotStore<S : DotStore<S>> {
    /** Every dot currently live in this store. */
    public val dots: Set<Dot>

    /** True when the store holds no dots. */
    public val isBottom: Boolean get() = dots.isEmpty()

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
