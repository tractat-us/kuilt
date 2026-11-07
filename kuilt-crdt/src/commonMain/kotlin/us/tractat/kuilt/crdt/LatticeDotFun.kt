package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A [DotStore] mapping each [Dot] to a value [V] that is **itself a lattice**, so a dot's value can
 * keep growing after the dot is minted, and a dot both sides hold keeps the join of the two values.
 *
 * [DotFun] is the store for values that never change: a dot names one write, so wherever it
 * appears it carries the same value, and [DotFun.join] keeps either copy. That premise is what an
 * MV register needs and what a per-element tombstone breaks. Marking an element deleted grows its
 * payload in place, under the dot that inserted it, because dropping the dot would orphan whatever
 * was positioned after it. One dot then carries two different values on two replicas, and keeping
 * either copy makes the join depend on which side is `this`. This store joins them instead, which
 * is the `DotFun⟨V⟩` of Almeida, Shoker and Baquero, *Delta State Replicated Data Types*
 * (arXiv:1603.01529, Fig. 12):
 *
 * ```
 * (m, c) ⊔ (m′, c′) = { d ↦ m(d) ⊔ m′(d) | d ∈ dom m ∩ dom m′ }
 *                   ∪ { (d, v) ∈ m  | d ∉ c′ }
 *                   ∪ { (d, v) ∈ m′ | d ∉ c  }
 * ```
 *
 * **An observed remove beats a concurrent growth.** A dot one side holds and the other side has
 * witnessed but no longer holds was removed on purpose, and it is dropped whatever its value grew
 * to meanwhile. A type that wants a removal growth can survive expresses the removal as growth (a
 * `deleted` flag in the value), not as dropping the dot.
 *
 * The join is associative, commutative and idempotent over states that satisfy [Causal]'s
 * invariant (every dot in the store is in the context), given that [V]'s own [Quilted.piece] is.
 *
 * Serialized by [LatticeDotFunSerializer], which emits entries sorted by [Dot] so the encoding is a
 * function of the logical value and not of merge order — provided [V]'s own serializer is
 * canonical too, since a payload is encoded by it.
 */
@Serializable(with = LatticeDotFunSerializer::class)
public class LatticeDotFun<V : Quilted<V>>(public val values: Map<Dot, V> = emptyMap()) : DotStore<LatticeDotFun<V>> {

    override val dots: Set<Dot> get() = values.keys

    override val empty: LatticeDotFun<V> get() = LatticeDotFun()

    override fun join(other: LatticeDotFun<V>, context: DotContext, otherContext: DotContext): LatticeDotFun<V> {
        val kept = LinkedHashMap<Dot, V>()
        for ((dot, mine) in values) {
            val theirs = other.values[dot]
            when {
                theirs != null -> kept[dot] = mine.piece(theirs)
                !otherContext.contains(dot) -> kept[dot] = mine
            }
        }
        for ((dot, theirs) in other.values) {
            if (dot !in values && !context.contains(dot)) kept[dot] = theirs
        }
        return LatticeDotFun(kept)
    }

    override fun equals(other: Any?): Boolean = other is LatticeDotFun<*> && values == other.values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "LatticeDotFun($values)"
}
