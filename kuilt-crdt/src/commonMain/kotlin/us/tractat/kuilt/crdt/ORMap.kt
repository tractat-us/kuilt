package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * An entry of an [ORMap]: a [DotSet] of "presence tags" plus a [Quilted] value.
 * The tag set is the observed-remove handle for the *key*; the value merges via
 * its own [Quilted.piece] when both sides hold it.
 */
@Serializable
public class ORMapEntry<S : Quilted<S>>(
    public val tags: DotSet,
    public val value: S,
) : DotStore<ORMapEntry<S>> {

    override val dots: Set<Dot> get() = tags.dots
    override val isBottom: Boolean get() = tags.isBottom

    /**
     * The structural empty: empty tag set, same value shape. Used by [DotMap.join]
     * when this entry appears only on one side — causal filtering then decides
     * whether the tags survive, keeping the value iff they do.
     */
    override val empty: ORMapEntry<S> get() = ORMapEntry(DotSet(), value)

    override fun join(
        other: ORMapEntry<S>,
        context: DotContext,
        otherContext: DotContext,
    ): ORMapEntry<S> {
        val joinedTags = tags.join(other.tags, context, otherContext)
        val joinedValue = value.piece(other.value)
        return ORMapEntry(joinedTags, joinedValue)
    }

    override fun equals(other: Any?): Boolean =
        other is ORMapEntry<*> && tags == other.tags && value == other.value

    override fun hashCode(): Int = 31 * tags.hashCode() + value.hashCode()

    override fun toString(): String = "ORMapEntry(tags=$tags, value=$value)"
}

/**
 * An **observed-remove map**: keys [K] each carry a [Quilted] value [S] that
 * merges via its own [Quilted.piece]. A concurrent `put` of the same key
 * survives a `remove` (add-wins on the key), and when both replicas hold the
 * key the values are pieced together.
 *
 * Built over `Causal<DotMap<K, ORMapEntry<S>>>`: each entry's `tags` is the
 * observed-remove handle for the key; the value lives alongside and is merged
 * by its own `piece`.
 *
 * [putDelta]/[removeDelta] return just the change, which is what belongs on the wire.
 *
 * @sample us.tractat.kuilt.crdt.sampleORMap
 */
@Serializable
public class ORMap<K, S : Quilted<S>> private constructor(
    private val causal: Causal<DotMap<K, ORMapEntry<S>>>,
) : Quilted<ORMap<K, S>> {

    /** Currently-present keys. */
    public val keys: Set<K> get() = causal.store.entries.keys

    /** The value for [key], or `null` if absent. */
    public operator fun get(key: K): S? = causal.store.entries[key]?.value

    /**
     * Put [value] under [key], minting a fresh add-tag on behalf of [replica].
     * If the key already exists locally, the new value is `piece`d with the
     * existing one (so a put is additive, not destructive, for the value lattice).
     */
    public fun put(replica: ReplicaId, key: K, value: S): ORMap<K, S> {
        val dot = causal.context.nextDot(replica)
        val existing = causal.store.entries[key]
        val mergedValue = existing?.value?.piece(value) ?: value
        val newEntry = ORMapEntry(DotSet(setOf(dot)), mergedValue)
        return ORMap(
            Causal(
                DotMap(causal.store.entries + (key to newEntry)),
                causal.context.add(dot),
            ),
        )
    }

    /** Remove [key]: drop its current tags. Context retains them — propagates on merge. */
    public fun remove(key: K): ORMap<K, S> {
        if (key !in causal.store.entries) return this
        return ORMap(Causal(DotMap(causal.store.entries - key), causal.context))
    }

    /**
     * The **change** [put] would make, on its own — one key, the value you supplied, and a short
     * causal note — rather than the whole new map.
     *
     * This is what to put on the wire. A replicator broadcasts a patch's delta verbatim, so
     * `Patch(map.put(…))` ships every key *and every key's value* on every write, at a cost that
     * grows with the map; this frame's size does not depend on how large the map is. The idiom is
     * `quilter.mutate { it.putDelta(replica, key, value) }` — read-modify-write inside the
     * replicator's own lock.
     *
     * A delta is itself an [ORMap], so a peer absorbs it with the ordinary [piece] join, in any
     * order, with any repeats, and lands on a state that encodes byte-for-byte identically to the
     * sender's [put] result. Nothing has to be buffered or delivered in causal order.
     *
     * **The delta carries [value] exactly as you passed it, not the merged value [put] stores
     * locally.** A put is additive over the value lattice, and `ORMapEntry`'s join re-does that
     * merge at the receiver against *the receiver's* value — which is the one that matters there.
     * Sending the sender's merged value would converge just the same and quietly throw the saving
     * away: the frame would be O(stored value) again, re-transmitting history the receiver already
     * has. On a nested `ORMap<K, ORSet<X>>` that is most of the win.
     *
     * **The delta's context names the minted tag *and the tags this put supersedes*, and that
     * second term must not be simplified away.** [put] replaces a key's tags rather than growing
     * them, so a delta announcing only the new tag would leave the superseded ones alive on every
     * receiver — and a later remove, retiring only the tag it knew about, would resurrect the key.
     * That failure was measured while designing this method (#2044) and is pinned by
     * `ORMapDeltaMutatorLawTest`.
     *
     * **One caveat about a concurrent [remove].** The key survives — that is add-wins, and it holds
     * in either order. Its *value* is order-sensitive: a peer that applied the remove first no
     * longer has the old value to merge [value] into, so it keeps only [value], while a peer that
     * applied this delta first keeps both. `ORMap`'s value merge has never been associative across a
     * remove (#2086); a full-state put hides it by carrying the merged value along. The two peers
     * reconcile at the next anti-entropy round.
     *
     * @sample us.tractat.kuilt.crdt.sampleORMapDelta
     */
    public fun putDelta(replica: ReplicaId, key: K, value: S): Patch<ORMap<K, S>> =
        putPatch(replica, key, value)

    /**
     * The **change** [remove] would make, on its own: the tags currently on [key], retired, and
     * nothing else. Ship this rather than `Patch(map.remove(…))`, which is the whole map.
     *
     * The context carries exactly [key]'s live tags — never the sender's full history. A delta that
     * carried the whole context would be indistinguishable from *"I have removed every key I ever
     * saw"*, and joining it would empty the receiver's map.
     *
     * Removing a key that is absent yields the lattice identity — an empty store and an empty
     * context — so absorbing it changes nothing, matching [remove]'s own no-op.
     *
     * @sample us.tractat.kuilt.crdt.sampleORMapDelta
     */
    public fun removeDelta(key: K): Patch<ORMap<K, S>> = removePatch(key)

    private fun putPatch(replica: ReplicaId, key: K, value: S): Patch<ORMap<K, S>> {
        val dot = causal.context.nextDot(replica)
        val superseded = causal.store.entries[key]?.tags?.dots ?: emptySet()
        val store = DotMap(mapOf(key to ORMapEntry(DotSet(setOf(dot)), value)))
        return Patch(ORMap(Causal(store, witnessing(superseded + dot))))
    }

    private fun removePatch(key: K): Patch<ORMap<K, S>> =
        Patch(ORMap(Causal(DotMap(), witnessing(tagsOn(key)))))

    /** A context witnessing exactly [dots] and nothing else — a delta's whole causal claim. */
    private fun witnessing(dots: Set<Dot>): DotContext =
        dots.fold(DotContext.EMPTY) { context, dot -> context.add(dot) }

    /**
     * The map's presence tags for [key] — not the nested value's dots, which live in their own
     * space. Internal: the dot layer is an implementation detail, but tests need it to prove a
     * generated state actually carries *concurrent* tags on a key. A map grown by one replica never
     * has more than one tag per key, so a generator built that way makes [putDelta]'s
     * superseded-tags term a singleton every time and never exercises the case it exists for —
     * another replica's tag travelling with the supersession.
     */
    internal fun tagsOn(key: K): Set<Dot> = causal.store.entries[key]?.tags?.dots ?: emptySet()

    /** The causal merge. */
    override fun piece(other: ORMap<K, S>): ORMap<K, S> = ORMap(causal.piece(other.causal))

    override fun equals(other: Any?): Boolean = other is ORMap<*, *> && causal == other.causal
    override fun hashCode(): Int = causal.hashCode()
    override fun toString(): String = "ORMap(${causal.store.entries.mapValues { it.value.value }})"

    public companion object {
        /** The empty map. */
        public fun <K, S : Quilted<S>> empty(): ORMap<K, S> =
            ORMap(Causal(DotMap(), DotContext.EMPTY))
    }
}
