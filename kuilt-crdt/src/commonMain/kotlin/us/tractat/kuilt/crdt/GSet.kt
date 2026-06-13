package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A grow-only set of [E]. [add] returns a [Patch] carrying a one-element set;
 * [piece] is union. Once added, an element cannot be removed — for
 * remove-supporting sets see [TwoPhaseSet] (tombstones) or [ORSet] (causal).
 *
 * @sample us.tractat.kuilt.crdt.sampleGSet
 */
@Serializable
public class GSet<E> private constructor(public val elements: Set<E>) : Quilted<GSet<E>> {

    /** True if [element] is in this set. */
    public fun contains(element: E): Boolean = element in elements

    /** Add [element], returning a delta. The receiver is unchanged. */
    public fun add(element: E): Patch<GSet<E>> = Patch(GSet(setOf(element)))

    /** The join: set union. */
    override fun piece(other: GSet<E>): GSet<E> = GSet(elements + other.elements)

    override fun equals(other: Any?): Boolean = other is GSet<*> && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()
    override fun toString(): String = "GSet($elements)"

    public companion object {
        /** The empty set. */
        public fun <E> empty(): GSet<E> = GSet(emptySet())

        /** A set initialized with the given elements. */
        public fun <E> of(vararg elements: E): GSet<E> = GSet(elements.toSet())
    }
}
