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
 * Immutable, and **every mutator returns the change rather than a new set**: [add] and [remove]
 * hand back a [Patch] holding just the element they touched, which is what belongs on the wire.
 * [piece] is the causal merge — it absorbs a patch, and it is also how a caller who wants the
 * resulting whole set gets one: `set.piece(set.add(replica, element))`.
 *
 * @sample us.tractat.kuilt.crdt.sampleORSet
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
     * Add [element] on behalf of [replica], minting a fresh dot — and return **the change**, one
     * element and a short causal note, rather than the whole new set.
     *
     * This is what to put on the wire. A replicator broadcasts a patch's delta verbatim, so a
     * mutator that handed back the new set would ship every element on every write, at a cost that
     * grows with the set; this frame's size does not depend on how large the set is. The idiom is
     * `quilter.mutate { it.add(replica, element) }` — read-modify-write inside the replicator's
     * own lock. To hold the resulting set locally, absorb the patch: `set.piece(set.add(…))`.
     *
     * A delta is itself an [ORSet], so a peer absorbs it with the ordinary [piece] join, in any
     * order, with any repeats, and lands on a state that encodes byte-for-byte identically to
     * the author's own. Nothing has to be buffered or delivered in causal order.
     *
     * **The delta's context names the minted dot *and the dots this add supersedes*, and that
     * second term must not be simplified away.** An add replaces an element's dots rather than
     * growing them, so a delta announcing only the new dot would leave the superseded ones
     * alive on every receiver — and a later remove, retiring only the dot it knew about, would
     * resurrect the element. That failure was measured while designing this method (#2044) and
     * is pinned by `ORSetDeltaMutatorLawTest`.
     *
     * @sample us.tractat.kuilt.crdt.sampleORSet
     */
    public fun add(replica: ReplicaId, element: E): Patch<ORSet<E>> = addPatch(replica, element)

    /**
     * Remove [element] — and return **the change**: the dots currently on it, retired, and nothing
     * else. Absorbing that patch drops the element; the retired dots stay witnessed, so the removal
     * propagates on merge. To hold the resulting set locally, `set.piece(set.remove(…))`.
     *
     * The context carries exactly [element]'s live dots — never the sender's full history. A
     * delta that carried the whole context would be indistinguishable from *"I have removed
     * everything I ever saw"*, and joining it would empty the receiver's set.
     *
     * Removing an element that is absent yields the lattice identity — an empty store and an
     * empty context — so absorbing it changes nothing.
     *
     * @sample us.tractat.kuilt.crdt.sampleORSet
     */
    public fun remove(element: E): Patch<ORSet<E>> = removePatch(element)

    /**
     * Remove every element of [elements] at once — and return **the change**: the dots currently
     * on those elements, retired, and nothing else.
     *
     * The bulk sibling of [remove], and **indistinguishable from calling it in a loop**: the same
     * elements gone, the same dots retired, the same retained context, the same bytes. A receiver
     * cannot tell which was sent, because the delta this returns is exactly the join of the deltas
     * the loop would have produced.
     *
     * What differs is the cost. Absorbing a patch is a full causal join over the whole set, so
     * `elements.fold(set) { s, e -> s.piece { it.remove(e) } }` pays one join per element — Θ(n·N),
     * and at the buffer sizes this exists for that is minutes, not milliseconds. This pays **one**
     * join for the whole run, and builds its context in a single pass (see
     * `DotContext.witnessing`), so it is Θ(N + n log n).
     *
     * As with [remove], the context carries exactly the named elements' live dots — never the
     * sender's full history. That is what keeps add-wins intact through a bulk removal: a
     * concurrent add from a peer mints a dot this removal never witnessed, so it is absent from
     * the delta's context and survives the join. A tempting "empty the store and keep the whole
     * context" shortcut would look identical on the author's own replica and destroy exactly that
     * — it is the shape `ORSetDeltaMutatorLawTest` keeps pinned as a negative control.
     *
     * Retaining the context is also the point: it is what lets a caller drop every element while
     * remaining **dominant** over a peer that re-merges its pre-removal copy, rather than watching
     * the whole set come back. Elements that are absent are skipped, so an [elements] naming none
     * of them — or an empty one — yields the lattice identity and absorbing it changes nothing.
     *
     * @sample us.tractat.kuilt.crdt.sampleORSetBulkRemoval
     */
    public fun removeAll(elements: Set<E>): Patch<ORSet<E>> = removeAllPatch(elements)

    /**
     * The whole set an [add] produces — the reference semantics [add]'s delta must reproduce
     * under [piece], byte for byte.
     *
     * Deliberately **not public**: it is the O(set) spelling this type exists to keep off the
     * wire. Two callers need it: `ORSetDeltaMutatorLawTest`, which cannot state the
     * delta-mutator law without a reference independent of the delta path it is testing; and
     * `ORSetTest.pieceIsAssociativeOverCausallyRelatedTrajectories`, whose generator walks the
     * whole-state and delta paths in the same trajectory — take this away and both arms become
     * the delta path and the test compares it against itself.
     */
    internal fun addWhole(replica: ReplicaId, element: E): ORSet<E> {
        val dot = causal.context.nextDot(replica)
        val entries = causal.store.entries + (element to DotSet(setOf(dot)))
        return ORSet(Causal(DotMap(entries), causal.context.add(dot)))
    }

    /** The whole set a [remove] produces. Internal for the same reason as [addWhole]. */
    internal fun removeWhole(element: E): ORSet<E> {
        if (element !in causal.store.entries) return this
        return ORSet(Causal(DotMap(causal.store.entries - element), causal.context))
    }

    /** The whole set a [removeAll] produces. Internal for the same reason as [addWhole]. */
    internal fun removeAllWhole(elements: Set<E>): ORSet<E> {
        val survivors = causal.store.entries.filterKeys { it !in elements }
        if (survivors.size == causal.store.entries.size) return this
        return ORSet(Causal(DotMap(survivors), causal.context))
    }

    private fun addPatch(replica: ReplicaId, element: E): Patch<ORSet<E>> {
        val dot = causal.context.nextDot(replica)
        val superseded = causal.store.entries[element]?.dots ?: emptySet()
        val store = DotMap(mapOf(element to DotSet(setOf(dot))))
        return Patch(ORSet(Causal(store, witnessing(superseded + dot))))
    }

    private fun removePatch(element: E): Patch<ORSet<E>> =
        Patch(ORSet(Causal(DotMap(), witnessing(causal.store.entries[element]?.dots ?: emptySet()))))

    private fun removeAllPatch(elements: Set<E>): Patch<ORSet<E>> =
        Patch(ORSet(Causal(DotMap(), witnessing(elements.flatMap { dotsOn(it) }))))

    /** A context witnessing exactly [dots] and nothing else — a delta's whole causal claim. */
    private fun witnessing(dots: Collection<Dot>): DotContext = DotContext.witnessing(dots)

    /**
     * The dots currently on [element]. Internal: the dot layer is an implementation detail, but
     * tests need it to prove a generated state actually carries *concurrent* dots on an element.
     * A set grown by one replica never has more than one dot per element, so a generator built
     * that way makes [add]'s superseded-dots term a singleton every time and never exercises
     * the case it exists for — another replica's dot travelling with the supersession.
     */
    internal fun dotsOn(element: E): Set<Dot> = causal.store.entries[element]?.dots ?: emptySet()

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
