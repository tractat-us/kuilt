package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * An entry of an [ORMap]: the key's presence tags, **each carrying the value that was written
 * under it**. The key's observable [value] is the join of every tag's contribution; the tags are
 * the observed-remove handle for the key.
 *
 * **Why the value hangs off the dots and not off the entry (#2086).** The obvious shape — one tag
 * set plus one value beside it — is not a join-semilattice. An entry has exactly one value slot, so
 * a join that keeps two operands' contributions must *blend* them into that slot, and no later join
 * can tell them apart again. Retire one of the two tags afterwards and the blend keeps the
 * contribution the tag was carrying, so the result depends on the order the operands were joined
 * in. That is not a bug in any one line: any entry-level value has it, because the value stops
 * being a function of which dots are live. Attaching each write to the dot that made it restores
 * the correspondence — a contribution survives exactly as long as its tag does — and the join
 * becomes the same causal filter [DotSet] already uses, which is associative.
 *
 * **Invariant: a dot names one write.** [contributions] maps a dot to the value written under it,
 * and a dot is minted once, so wherever it appears it carries the same value. [join] still folds
 * the two sides with [Quilted.piece] rather than picking one, so a violation degrades to a merge
 * instead of a silent asymmetry between `a ⊔ b` and `b ⊔ a`.
 */
@Serializable(with = ORMapEntrySerializer::class)
public class ORMapEntry<S : Quilted<S>>(
    public val contributions: Map<Dot, S> = emptyMap(),
) : DotStore<ORMapEntry<S>> {

    override val dots: Set<Dot> get() = contributions.keys
    override val isBottom: Boolean get() = contributions.isEmpty()

    /** The structural empty. Used by [DotMap.join] when this entry appears only on one side. */
    override val empty: ORMapEntry<S> get() = ORMapEntry()

    /**
     * The key's value: every live contribution, joined. `null` only when the entry is bottom, which
     * [DotMap.join] drops — so a key that is present always has a value.
     *
     * Folded in [Dot] order rather than map order, so the returned value's own internal ordering is
     * a function of the state and not of the delivery order that built it.
     */
    public val value: S?
        get() = when (contributions.size) {
            0 -> null
            1 -> contributions.values.first()
            else -> contributions.keys.sorted()
                .map { contributions.getValue(it) }
                .reduce { left, right -> left.piece(right) }
        }

    override fun join(
        other: ORMapEntry<S>,
        context: DotContext,
        otherContext: DotContext,
    ): ORMapEntry<S> {
        val kept = LinkedHashMap<Dot, S>()
        for ((dot, mine) in contributions) {
            val theirs = other.contributions[dot]
            when {
                theirs != null -> kept[dot] = mine.piece(theirs)
                !otherContext.contains(dot) -> kept[dot] = mine
            }
        }
        for ((dot, theirs) in other.contributions) {
            if (dot !in contributions && !context.contains(dot)) kept[dot] = theirs
        }
        return ORMapEntry(kept)
    }

    override fun equals(other: Any?): Boolean =
        other is ORMapEntry<*> && contributions == other.contributions

    override fun hashCode(): Int = contributions.hashCode()

    override fun toString(): String = "ORMapEntry($contributions)"
}

/**
 * An **observed-remove map**: keys [K] each carry a [Quilted] value [S] that
 * merges via its own [Quilted.piece]. A concurrent `put` of the same key
 * survives a `remove` (add-wins on the key), and a key's value is every
 * surviving write to it, pieced together.
 *
 * Built over `Causal<DotMap<K, ORMapEntry<S>>>`: an entry maps each of the key's presence tags to
 * the value written under it, and the key's value is the join of the tags that are still live. A
 * [remove] retires the tags it can see, so it takes their contributions with it; a write it never
 * observed keeps both its tag and its value. That correspondence between a contribution and its tag
 * is what makes [piece] associative — see [ORMapEntry] for why an entry-level value cannot be
 * (#2086).
 *
 * [putDelta]/[removeDelta] return just the change, which is what belongs on the wire.
 *
 * **Serialization.** An entry keys its contributions by [Dot], so plain JSON needs
 * `Json { allowStructuredMapKeys = true }` — the same flag [MVRegister] and [ResettableCounter]
 * already require. CBOR and Protobuf encode it without any flag.
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
     *
     * A put is **additive, not destructive**, for the value lattice: reading [key] back afterwards
     * gives the old value `piece`d with [value]. What the put replaces is [replica]'s *own*
     * previous tag on the key — its earlier contribution is folded into the fresh tag, so a replica
     * never accumulates more than one tag per key. Tags minted by other replicas are left alone;
     * retiring those is [remove]'s job, and taking them here would silently discard a peer's
     * contribution that nobody asked to drop.
     */
    public fun put(replica: ReplicaId, key: K, value: S): ORMap<K, S> {
        val dot = causal.context.nextDot(replica)
        val existing = causal.store.entries[key]?.contributions.orEmpty()
        val newEntry = ORMapEntry(
            existing.filterKeys { it.replica != replica } + (dot to foldOwn(existing, replica, value)),
        )
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
     * **The delta carries [value], plus whatever this replica has already contributed to the key —
     * never the whole stored value.** The frame is flat in the size of the map and flat in the size
     * of *other* replicas' contributions, which is where the saving lives; on a nested
     * `ORMap<K, ORSet<X>>` a peer adding one element ships one element, not the roster. What it
     * cannot leave out is its own history, because the tag this put mints supersedes the sender's
     * older tags and therefore has to carry what they were holding. A replica that keeps growing
     * one key on its own pays for that: its deltas grow with its own contribution to that key. The
     * alternative — never superseding — would keep every delta minimal and let a key accumulate one
     * tag per put forever, which is a worse trade for a state that is held, hashed and re-shipped.
     *
     * **The delta's context names the minted tag *and the sender's own tags that it supersedes*,
     * and that second term must not be simplified away.** A delta announcing only the new tag would
     * leave the superseded ones alive on every receiver — and a later remove, retiring only the tags
     * the remover knew about, would resurrect the key. That failure was measured while designing
     * this method (#2044) and is pinned by `ORMapDeltaMutatorLawTest`.
     *
     * **A concurrent [remove] is no longer a special case.** The key survives — that is add-wins —
     * and so does exactly this delta's contribution, in either order and on every peer, because the
     * remove can only retire tags it observed and this one is not among them (#2086).
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
        val existing = causal.store.entries[key]?.contributions.orEmpty()
        val superseded = existing.keys.filterTo(mutableSetOf()) { it.replica == replica }
        val store = DotMap(mapOf(key to ORMapEntry(mapOf(dot to foldOwn(existing, replica, value)))))
        return Patch(ORMap(Causal(store, witnessing(superseded + dot))))
    }

    private fun removePatch(key: K): Patch<ORMap<K, S>> =
        Patch(ORMap(Causal(DotMap(), witnessing(tagsOn(key)))))

    /**
     * [value] joined with everything [replica] has already contributed to this key, in [Dot] order.
     *
     * This is what makes a put additive while still leaving the replica one tag: the fresh tag
     * carries its own history, so superseding the older tags loses nothing. It is also why
     * [putDelta] can carry this — and must: the dot the delta names and the dot [put] mints have to
     * agree on their value, or a peer that took the delta and a peer that took the whole state
     * would hold the same dot with two different payloads.
     */
    private fun foldOwn(existing: Map<Dot, S>, replica: ReplicaId, value: S): S =
        existing.keys.filter { it.replica == replica }.sorted()
            .fold(value) { folded, dot -> folded.piece(existing.getValue(dot)) }

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
    internal fun tagsOn(key: K): Set<Dot> = causal.store.entries[key]?.dots ?: emptySet()

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
