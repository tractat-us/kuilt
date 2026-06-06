package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A [DotStore] mapping keys [K] to nested DotStores [S]. The causal [join] is
 * applied per key, recursively, using the surrounding contexts; a key whose
 * nested store becomes bottom (empty) is dropped. `Causal<DotMap<E, DotSet>>` is
 * an OR-Set; a richer nested store gives an OR-Map.
 */
@Serializable
public class DotMap<K, S : DotStore<S>>(
    public val entries: Map<K, S> = emptyMap(),
) : DotStore<DotMap<K, S>> {

    override val dots: Set<Dot>
        get() = entries.values.flatMapTo(LinkedHashSet()) { it.dots }

    override val empty: DotMap<K, S> get() = DotMap()

    override fun join(
        other: DotMap<K, S>,
        context: DotContext,
        otherContext: DotContext,
    ): DotMap<K, S> {
        val merged = LinkedHashMap<K, S>()
        for (key in entries.keys + other.entries.keys) {
            val mine = entries[key]
            val theirs = other.entries[key]
            val joined: S = when {
                mine != null && theirs != null -> mine.join(theirs, context, otherContext)
                mine != null -> mine.join(mine.empty, context, otherContext)
                else -> theirs!!.join(theirs.empty, otherContext, context)
            }
            if (!joined.isBottom) merged[key] = joined
        }
        return DotMap(merged)
    }

    override fun equals(other: Any?): Boolean = other is DotMap<*, *> && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "DotMap($entries)"
}
