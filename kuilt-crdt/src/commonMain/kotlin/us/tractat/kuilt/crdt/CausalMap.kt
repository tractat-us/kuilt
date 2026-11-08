package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A map whose keys and **everything nested under them** share one causal context, which is never
 * reset, so no dot is ever minted twice: not after a remove, not after the key is recreated, not
 * at any depth.
 *
 * Think of a shared poll: `{poll: {question, votes}}`. One person deletes the poll and starts it
 * again while someone else, at the same moment, votes on the old one. Both changes have to land
 * as two different events. A map whose nested values each keep their own history cannot promise
 * that: the recreated poll starts counting from scratch, reuses the names of the events it
 * replaced, and a peer that saw the old ones takes the new ones as already seen and drops them
 * (#3126). Here the poll and its fields are named from one counter that only grows, so the second
 * poll's events can never be mistaken for the first's.
 *
 * This is the map embedding of Almeida, Shoker and Baquero, *Delta State Replicated Data Types*
 * (arXiv:1603.01529, §7, Fig. 17): `Causal<DotMap<K, V>>` with each value a bare [DotStore] (not
 * a full causal CRDT), every dot minted through the shared context, and
 *
 * ```
 * apply(oδ, k, (m, c)) = ({k ↦ v}, c′)   where (v, c′) = oδ((m(k), c))
 * remove(k, (m, c))    = ({}, dots(m(k)))
 * ```
 *
 * **What a remove means: reset, not resurrect.** [remove] retires every dot the remover had seen
 * under the key, at every depth. Only writes it had *not* seen survive, so a key removed while a
 * peer was writing to it comes back holding that peer's unseen writes and nothing else. A key is
 * present exactly when its store is not bottom; there are no per-key tags and no tombstones, and a
 * removed key costs nothing once the context compacts.
 *
 * **Writing goes through [apply] only.** A nested mutator gets a [Slot]: the key's current store
 * and a [Slot.mint] that draws from the shared context. It returns a [Change]: the new store
 * fragment and the dots it retires. [apply] builds the delta's context itself, from exactly those
 * dots, and checks the change before shipping it — see [apply] for the checks. A mutator never
 * sees or returns a context, which is what keeps it from minting from the wrong one, or shipping
 * the whole one.
 *
 * Its join is `Causal`'s, unchanged: associative, commutative and idempotent.
 *
 * "Never minted twice" holds along one replica's history of absorbed states, which is how kuilt's
 * mutators are used: two [apply] calls on the same state, without absorbing the first, mint the
 * same dot, as any delta mutator does.
 *
 * Serialized by [CausalMapSerializer], as the bare `Causal<DotMap<K, V>>`.
 *
 * @sample us.tractat.kuilt.crdt.sampleCausalMap
 */
@Serializable(with = CausalMapSerializer::class)
public class CausalMap<K, V : DotStore<V>> private constructor(
    internal val causal: Causal<DotMap<K, V>>,
) : Quilted<CausalMap<K, V>> {

    /** The keys present: those whose store is not bottom. */
    public val keys: Set<K> get() = causal.store.entries.keys

    /** The store under [key], or `null` when the key is absent. */
    public operator fun get(key: K): V? = causal.store.entries[key]

    /** The one shared causal context: every dot this map has witnessed, at any depth. */
    public val context: DotContext get() = causal.context

    /**
     * Change the store under [key] on behalf of [replica] — and return **the change**: one key,
     * the fragment [mutator] wrote, and a context naming exactly the dots it minted, re-carried or
     * retired.
     *
     * [mutator] receives a [Slot] over the key's current store, or [bottom] when the key is absent.
     * Every new dot comes from [Slot.mint], which draws `next(c)` from this map's shared context
     * and records it. [Slot.narrow] hands the same minting to a nested store, so an outer mutator
     * can run an inner one. The [Change] it returns says what the key now holds *in this delta*
     * and which of the dots it held are retired. Any bottom store nested in the change is left out
     * of the delta, and a change whose whole store is bottom leaves the key out, which is how a
     * nested remove that empties the key reads. Shipping a nested bottom would make the delta
     * change under a join with itself.
     *
     * The delta is checked, and an invalid one throws [IllegalArgumentException] rather than
     * shipping. The checks exist because the bug class this type removes is a mutator minting from
     * the wrong context, and every one of them names a shape that bug takes:
     * 1. every written dot sits at one position: a dot under two nested keys would be retired at
     *    both by a write that meant to retire it at one;
     * 2. every retired dot is held under [key], and none of them is also written;
     * 3. every written dot was minted here or is held under [key]: no dot from another context, no
     *    retired dot coming back;
     * 4. every minted dot is written;
     * 5. absorbing the delta keeps exactly the dots it should, `(held − retired) ∪ written`, and
     *    gives the same store from either side of the join. A dot re-carried at a different
     *    position fails the first half; a re-carried dot whose payload cannot be joined (a
     *    [DotFun] value changed in place) fails the second.
     *
     * Check 5 needs [V]'s `equals` to be structural and canonical. An equal-payload re-carry at
     * the same position passes, harmlessly: it is a no-op delta.
     *
     * Check 1 looks inside [DotMap], the one in-kuilt store that nests other stores, and counts
     * every other store as flat. A store of your own that nests dots is counted as flat too, so
     * check 1 cannot see a dot repeated inside it.
     *
     * **Cost:** O(subtree under [key]) per delta produced, for the checks. A wide value under one
     * key pays that on every write to it. Absorbing the delta with [piece] is a separate cost, and
     * it walks every key, as `Causal`'s join always has.
     *
     * @throws IllegalArgumentException if [bottom] is not bottom, or the change fails a check.
     */
    public fun apply(
        replica: ReplicaId,
        key: K,
        bottom: V,
        mutator: (Slot<V>) -> Change<V>,
    ): Patch<CausalMap<K, V>> {
        require(bottom.isBottom) { "bottom must hold no dots, was $bottom" }
        val held = causal.store.entries[key] ?: bottom
        val minter = Minter(causal.context, replica)
        val change = try {
            mutator(Slot(held, minter))
        } finally {
            minter.close()
        }
        return Patch(CausalMap(checkedDelta(key, held, change, minter.minted)))
    }

    /**
     * Remove [key] — and return **the change**: no store, and a context naming every dot held
     * under the key, at every depth. Absorbing it retires all of them, so only writes this
     * replica had not seen survive anywhere (Fig. 17's `remove`).
     *
     * The delta's context grows with what the key holds: O(dots under [key]), sparse until it
     * compacts. Removing an absent key yields the lattice identity.
     */
    public fun remove(key: K): Patch<CausalMap<K, V>> {
        val held = causal.store.entries[key] ?: return Patch(empty())
        return Patch(CausalMap(Causal(DotMap(), DotContext.witnessing(held.dots))))
    }

    private fun checkedDelta(key: K, held: V, change: Change<V>, minted: List<Dot>): Causal<DotMap<K, V>> {
        val store = change.store.canonical()
        val written = store.dots
        requireOneOccurrencePerDot(store) { "a change may write each dot at one position only" }
        val heldDots = held.dots
        val retired = change.retired
        require(heldDots.containsAll(retired)) {
            "a change may retire only dots held under $key; not held: ${retired - heldDots}"
        }
        require(retired.none { it in written }) {
            "a change may not retire a dot it also writes: ${retired.intersect(written)}"
        }
        val mintedSet = minted.toSet()
        require(written.all { it in mintedSet || it in heldDots }) {
            "every written dot must be minted by Slot.mint() or held under $key; " +
                "foreign: ${written.filterNot { it in mintedSet || it in heldDots }}"
        }
        require(written.containsAll(mintedSet)) {
            "every minted dot must be written; minted and dropped: ${mintedSet - written}"
        }
        val deltaContext = DotContext.witnessing(retired + written)
        val forward = held.join(store, causal.context, deltaContext)
        val backward = store.join(held, deltaContext, causal.context)
        require(forward.dots == (heldDots - retired) + written) {
            "absorbing the change at $key must keep (held − retired) ∪ written; " +
                "kept ${forward.dots}, expected ${(heldDots - retired) + written}. " +
                "A held dot re-carried at a different position does this."
        }
        require(forward == backward) {
            "a re-carried dot's payload must join with the held one; $forward vs $backward"
        }
        val shipped = if (store.isBottom) DotMap() else DotMap(mapOf(key to store))
        return Causal(shipped, deltaContext)
    }

    override fun piece(other: CausalMap<K, V>): CausalMap<K, V> = CausalMap(causal.piece(other.causal))

    override fun equals(other: Any?): Boolean = other is CausalMap<*, *> && causal == other.causal
    override fun hashCode(): Int = causal.hashCode()
    override fun toString(): String = "CausalMap(${causal.store.entries}, ${causal.context})"

    /**
     * A key's current store, and the one way to mint a dot while changing it.
     *
     * Valid only while the mutator that received it runs: [mint] throws once [apply] has returned.
     */
    public class Slot<V : DotStore<V>> internal constructor(
        /** The store currently under the key, or the `bottom` handed to [apply] when it is absent. */
        public val store: V,
        private val minter: Minter,
    ) {
        /**
         * The next dot from the shared context, recorded so [apply] can check it is written.
         *
         * @throws IllegalStateException if called after the mutator that received this slot returned.
         */
        public fun mint(): Dot = minter.mint()

        /** A slot over a store nested inside this one, minting from the same context. */
        public fun <W : DotStore<W>> narrow(store: W): Slot<W> = Slot(store, minter)
    }

    /**
     * What a mutator changed under one key: the fragment it [store]s, and the held dots it
     * [retired]. Written dots that were already held are re-carried (for a payload that grows, as
     * in [LatticeDotFun]); held dots neither written nor retired are left as they are.
     *
     * The paper writes a payload bump as `({d ↦ v′}, {d})`. Here that is `Change(store = {d ↦ v′})`
     * with nothing retired: [apply] adds `d` to the context because it is written, and naming it in
     * [retired] as well is rejected, since a retired dot is one the change takes away.
     */
    public class Change<V : DotStore<V>>(
        public val store: V,
        public val retired: Set<Dot> = emptySet(),
    )

    /**
     * `next(c)` for one [apply], repeated: each dot is the next sequence number of [replica] that
     * [context] has not witnessed. A local counter rather than adding each dot to [context], which
     * would re-compact the whole shared context once per minted dot.
     */
    internal class Minter(private val context: DotContext, private val replica: ReplicaId) {
        private val mintedDots = mutableListOf<Dot>()
        private var closed = false
        private var seq = context.nextDot(replica).seq

        val minted: List<Dot> get() = mintedDots

        fun mint(): Dot {
            check(!closed) { "Slot.mint() called after apply returned; a slot is valid only inside its mutator" }
            while (context.contains(Dot(replica, seq))) seq++
            val dot = Dot(replica, seq++)
            mintedDots += dot
            return dot
        }

        fun close() {
            closed = true
        }
    }

    public companion object {
        /** The empty map. */
        public fun <K, V : DotStore<V>> empty(): CausalMap<K, V> = CausalMap(Causal(DotMap(), DotContext.EMPTY))

        /**
         * A map from decoded bytes, held to the rules [apply], [remove] and the join keep:
         * - every stored dot is witnessed by the context, or the next [apply] could mint it again;
         * - every dot sits at one position, or retiring it at one would delete it at the other;
         * - no store at any depth is bottom, or the state would change under a join with itself.
         *
         * Checked here and not in the constructor, which every [piece] runs and which would
         * otherwise walk every nested dot on each join. Deltas arrive through the same decoder, so
         * a frame from a peer is held to the same rules.
         */
        internal fun <K, V : DotStore<V>> decoded(causal: Causal<DotMap<K, V>>): CausalMap<K, V> {
            val store = causal.store
            val unwitnessed = store.dots.filterNot { causal.context.contains(it) }
            require(unwitnessed.isEmpty()) { "a CausalMap stores only dots its context has seen; not witnessed: $unwitnessed" }
            requireOneOccurrencePerDot(store) { "a CausalMap holds each dot at one position only" }
            require(store.canonical() == store) { "a CausalMap holds no bottom store at any depth: $store" }
            return CausalMap(causal)
        }
    }
}

/**
 * This store with every bottom store nested in it dropped, and nothing else changed. A join with
 * bottom under two empty contexts keeps every dot ("live in one, not witnessed by the other") and
 * [DotMap.join] drops each child that comes out bottom, at every depth.
 */
private fun <S : DotStore<S>> S.canonical(): S = join(empty, DotContext.EMPTY, DotContext.EMPTY)

/**
 * Every dot in this store, once per position it occupies. [DotStore.dots] is a set, so it cannot
 * show a dot held under two keys. Recurses into [DotMap] only; any other store, a consumer's own
 * included, is counted as flat.
 */
private fun DotStore<*>.dotOccurrences(): List<Dot> =
    if (this is DotMap<*, *>) entries.values.flatMap { it.dotOccurrences() } else dots.toList()

private inline fun requireOneOccurrencePerDot(store: DotStore<*>, message: () -> String) {
    val occurrences = store.dotOccurrences()
    require(occurrences.size == store.dots.size) {
        "${message()}; at more than one position: ${occurrences.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
    }
}

/**
 * [CausalMap]'s serializer: the map's `Causal<DotMap<K, V>>` exactly as [Causal]'s own serializer
 * writes it, so it inherits the dot family's canonical order. Decoding refuses a key whose store is
 * bottom, which no [CausalMap] path produces.
 */
@OptIn(ExperimentalSerializationApi::class)
public class CausalMapSerializer<K, V : DotStore<V>>(
    kSerializer: KSerializer<K>,
    vSerializer: KSerializer<V>,
) : KSerializer<CausalMap<K, V>> {

    private val causalSerializer = Causal.serializer(DotMap.serializer(kSerializer, vSerializer))

    override val descriptor: SerialDescriptor =
        SerialDescriptor("us.tractat.kuilt.crdt.CausalMap", causalSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: CausalMap<K, V>) {
        causalSerializer.serialize(encoder, value.causal)
    }

    override fun deserialize(decoder: Decoder): CausalMap<K, V> =
        CausalMap.decoded(causalSerializer.deserialize(decoder))
}
