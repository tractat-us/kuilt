package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A full delta-state CRDT built from a [DotStore] and the [DotContext] that
 * adjudicates its dots. [piece] is the causal join: merge the stores using both
 * contexts, then union the contexts. This is the glue that turns any DotStore
 * shape ([DotSet] now; DotFun/DotMap later) into a [Quilted].
 *
 * Invariant: every dot in [store] is witnessed by [context]. The factory paths
 * that build CRDTs on top of `Causal` maintain it; merges preserve it.
 */
@Serializable
public class Causal<S : DotStore<S>>(
    public val store: S,
    public val context: DotContext,
) : Quilted<Causal<S>> {

    override fun piece(other: Causal<S>): Causal<S> =
        Causal(
            store.join(other.store, context, other.context),
            context.piece(other.context),
        )

    override fun equals(other: Any?): Boolean =
        other is Causal<*> && store == other.store && context == other.context

    override fun hashCode(): Int = 31 * store.hashCode() + context.hashCode()

    override fun toString(): String = "Causal(store=$store, context=$context)"
}
