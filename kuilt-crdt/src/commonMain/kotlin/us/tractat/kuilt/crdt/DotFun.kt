package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A [DotStore] mapping each [Dot] to a value [V]. Because a dot is minted once
 * and never reused, a dot in both stores carries the same value, so the causal
 * [join] needs no value-level conflict resolution — surviving dots simply keep
 * their value. As `Causal<DotFun<V>>` this is a Multi-Value Register: concurrent
 * writes each keep their (dot, value), surfacing the conflict rather than hiding it.
 */
@Serializable
public class DotFun<V>(public val values: Map<Dot, V> = emptyMap()) : DotStore<DotFun<V>> {

    override val dots: Set<Dot> get() = values.keys

    override val empty: DotFun<V> get() = DotFun()

    override fun join(other: DotFun<V>, context: DotContext, otherContext: DotContext): DotFun<V> {
        val kept = LinkedHashMap<Dot, V>()
        for ((dot, value) in values) {
            if (dot in other.values || !otherContext.contains(dot)) kept[dot] = value
        }
        for ((dot, value) in other.values) {
            if (!context.contains(dot)) kept[dot] = value
        }
        return DotFun(kept)
    }

    override fun equals(other: Any?): Boolean = other is DotFun<*> && values == other.values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "DotFun($values)"
}
