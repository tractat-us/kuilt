package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A set with tombstoned remove: two grow-only sets, the **added** set and the
 * **removed** (tombstone) set, both merged by union. An element is present iff
 * it was added and is **not** tombstoned. Tombstones win permanently — once
 * removed, an element can never be re-added; even a fresh add will be masked.
 *
 * The pedagogical wart: tombstones grow without bound. The next rung up,
 * [ORSet], uses dots + causal context so an add issued after a remove can
 * survive — at the cost of more bookkeeping.
 *
 * @sample us.tractat.kuilt.crdt.sampleTwoPhaseSet
 */
@Serializable
public class TwoPhaseSet<E> private constructor(
    public val added: Set<E>,
    public val removed: Set<E>,
) : Quilted<TwoPhaseSet<E>> {

    /** True if [element] was added and has not been tombstoned. */
    public fun contains(element: E): Boolean = element in added && element !in removed

    /** Elements currently present. */
    public val elements: Set<E> get() = added - removed

    /** Add [element] (no effect on present-state if it's already tombstoned). */
    public fun add(element: E): Patch<TwoPhaseSet<E>> =
        Patch(TwoPhaseSet(setOf(element), emptySet()))

    /** Tombstone [element] (permanent — even a later add will be masked). */
    public fun remove(element: E): Patch<TwoPhaseSet<E>> =
        Patch(TwoPhaseSet(emptySet(), setOf(element)))

    /** The join: union both component sets. */
    override fun piece(other: TwoPhaseSet<E>): TwoPhaseSet<E> =
        TwoPhaseSet(added + other.added, removed + other.removed)

    override fun equals(other: Any?): Boolean =
        other is TwoPhaseSet<*> && added == other.added && removed == other.removed

    override fun hashCode(): Int = 31 * added.hashCode() + removed.hashCode()

    override fun toString(): String = "TwoPhaseSet(added=$added, removed=$removed)"

    public companion object {
        /** The empty 2P-set. */
        public fun <E> empty(): TwoPhaseSet<E> = TwoPhaseSet(emptySet(), emptySet())
    }
}
