package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A [DotStore] mapping each [Dot] to a value [V] that is **itself a lattice**, so a dot's value can
 * keep growing after the dot is minted, and a dot both sides hold keeps the join of the two values.
 *
 * [DotFun] is the store for values that never change: a dot names one write, so wherever it
 * appears it carries the same value, and [DotFun.join] keeps either copy. That premise is what an
 * MV register needs, and a per-element tombstone would break it. The motivating case is a sequence
 * whose elements are dots (#3126): marking an element deleted has to grow its payload in place,
 * under the dot that inserted it, because dropping the dot would orphan whatever was positioned
 * after it. One dot then carries two different values on two replicas, and keeping either copy
 * makes the join depend on which side is `this`. This store joins them instead, which
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

    override fun join(other: LatticeDotFun<V>, context: DotContext, otherContext: DotContext): LatticeDotFun<V> =
        LatticeDotFun(joinLatticeDotValues(values, other.values, context, otherContext))

    override fun equals(other: Any?): Boolean = other is LatticeDotFun<*> && values == other.values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "LatticeDotFun($values)"
}

/**
 * The join of two dot → lattice-value maps, Fig. 12's `DotFun⟨V⟩`: a dot both sides hold keeps the
 * join of its two values, a dot one side holds survives unless the other side has witnessed it.
 * Shared by [LatticeDotFun.join] and [ORMapEntry.join], which are the same store under two names.
 *
 * The `dot !in mine` guard in the second loop matters even though the first loop already wrote
 * every shared dot: without it a dot in [mine] that [context] has not witnessed — a [Causal]
 * invariant violation, but a reachable input — would be overwritten with [theirs]' value alone.
 */
internal fun <V : Quilted<V>> joinLatticeDotValues(
    mine: Map<Dot, V>,
    theirs: Map<Dot, V>,
    context: DotContext,
    otherContext: DotContext,
): Map<Dot, V> {
    val kept = LinkedHashMap<Dot, V>()
    for ((dot, value) in mine) {
        val other = theirs[dot]
        when {
            other != null -> kept[dot] = value.piece(other)
            !otherContext.contains(dot) -> kept[dot] = value
        }
    }
    for ((dot, value) in theirs) {
        if (dot !in mine && !context.contains(dot)) kept[dot] = value
    }
    return kept
}
