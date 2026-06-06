package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * An observed-remove (add-wins) set of [E]. Concurrent `add` and `remove` of the
 * same element resolve in favour of the add: a remove only cancels the adds it
 * has actually witnessed (the [Dot]s currently on the element), so an add the
 * remover never saw survives. This is the usable form of the dots + causal
 * context machinery — e.g. a presence set of who is currently online.
 *
 * Built as a thin wrapper over `Causal<DotMap<E, DotSet>>`: each element key maps
 * to the set of dots that added it; it is present iff that set is non-empty.
 *
 * Immutable: [add]/[remove] return a new set. [piece] is the causal merge.
 */
@Serializable
public class ORSet<E> private constructor(
    private val causal: Causal<DotMap<E, DotSet>>,
) : Quilted<ORSet<E>> {

    /** The elements currently present. */
    public val elements: Set<E> get() = causal.store.entries.keys

    /** True if [element] is currently present. */
    public fun contains(element: E): Boolean = element in causal.store.entries

    /**
     * Add [element] on behalf of [replica], minting a fresh dot. The new dot
     * supersedes this element's prior dots locally; concurrent adds on other
     * replicas still survive the merge.
     */
    public fun add(replica: ReplicaId, element: E): ORSet<E> {
        val dot = causal.context.nextDot(replica)
        val entries = causal.store.entries + (element to DotSet(setOf(dot)))
        return ORSet(Causal(DotMap(entries), causal.context.add(dot)))
    }

    /**
     * Remove [element]: drop the dots currently on it. The context is unchanged
     * (those dots stay witnessed, so the removal propagates on merge).
     */
    public fun remove(element: E): ORSet<E> {
        if (element !in causal.store.entries) return this
        return ORSet(Causal(DotMap(causal.store.entries - element), causal.context))
    }

    /** The causal merge of two replicas of this set. */
    override fun piece(other: ORSet<E>): ORSet<E> = ORSet(causal.piece(other.causal))

    override fun equals(other: Any?): Boolean = other is ORSet<*> && causal == other.causal
    override fun hashCode(): Int = causal.hashCode()
    override fun toString(): String = "ORSet($elements)"

    public companion object {
        /** The empty set. */
        public fun <E> empty(): ORSet<E> = ORSet(Causal(DotMap(), DotContext.EMPTY))
    }
}
