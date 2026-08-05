package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A map from [K] to last-writer-wins values [V]: per-key [LWWRegister]s
 * composed under union-merge of keys. Each [set] (or [remove] — a tombstone
 * write) tags one key with `(timestamp, replicaId)`; merge picks the per-key
 * max tag.
 *
 * Suited to settings, ready-toggles, and similar small key→latest-value state
 * where surfacing concurrent edits (a la [MVRegister]) is unwanted.
 *
 * Immutable, and **every mutator returns the change rather than a new map**:
 * [set] and [remove] hand back a [Patch] holding just the one cell they wrote,
 * which is what belongs on the wire. [piece] is the per-key merge — it absorbs a
 * patch, and it is also how a caller who wants the resulting whole map gets one:
 * `map.piece(map.set(replica, timestamp, key, value))`.
 *
 * **Clock-skew warning.** Wall-clock timestamps work only when clocks are
 * well-synchronized across all replicas. NTP-class drift will cause surprising
 * silent drops: a write with a lagging timestamp loses to an older write from a
 * faster clock. For correctness under arbitrary clock skew, pair this map with
 * a Hybrid Logical Clock above this layer.
 *
 * @sample us.tractat.kuilt.crdt.sampleLWWMap
 */
@Serializable
public class LWWMap<K, V> private constructor(
    @Serializable(with = CanonicalMapSerializer::class)
    private val cells: Map<K, LWWRegister<V>>,
) : Quilted<LWWMap<K, V>> {

    /** All currently-set entries with their values. */
    public val entries: Map<K, V>
        get() = cells.mapNotNull { (k, r) -> r.value?.let { k to it } }.toMap()

    /** The current value for [key], or `null` if unset. */
    public operator fun get(key: K): V? = cells[key]?.value

    /**
     * Write [value] for [key] tagged with ([timestamp], [replica]) — and return
     * **the change**, one key's cell, rather than the whole map.
     *
     * This is what to put on the wire. A replicator broadcasts a patch's delta
     * verbatim, so a mutator that handed back the new map would ship every key on
     * every write, at a cost that grows with the map; this frame carries one cell
     * and its size does not depend on how many keys the map holds. The idiom is
     * `quilter.mutate { it.set(replica, timestamp, key, value) }` —
     * read-modify-write inside the replicator's own lock. To hold the resulting
     * map locally, absorb the patch: `map.piece(map.set(…))`.
     *
     * A delta is itself an [LWWMap], so a peer absorbs it with the ordinary
     * [piece] join, in any order, with any repeats, and lands on a state that
     * encodes byte-for-byte identically to the writer's own. There is no causal
     * context to carry and nothing to buffer: this map's merge is a per-key max
     * of independent tags.
     *
     * **Precondition — tag uniqueness.** The `(replica, timestamp)` pair MUST
     * uniquely identify this write for the given key. Reusing the same
     * `(replica, timestamp)` across two writes to the same key with different
     * values produces non-deterministic convergence under merge — which value
     * survives depends on merge order, not write order. Use a monotonic
     * timestamp source per replica and never reuse a `(replica, timestamp)`
     * pair. Not enforced at runtime.
     *
     * **Domain of the one-cell delta.** The equivalence above holds exactly while
     * that precondition and a monotonic timestamp source are honoured — that is,
     * while the write's `(timestamp, replica)` tag *dominates* the key's current
     * one (#2087). Outside that domain no delta exists at all, because a write
     * whose tag loses is dropped by the join while an assigning mutator would
     * still show it locally until the next merge took it away. That case is one
     * this map's own clock-skew warning says is already lost; every replica
     * converges on the same value either way.
     *
     * @sample us.tractat.kuilt.crdt.sampleLWWMap
     */
    public fun set(replica: ReplicaId, timestamp: Long, key: K, value: V): Patch<LWWMap<K, V>> =
        setPatch(replica, timestamp, key, value)

    /**
     * Remove [key] tagged with ([timestamp], [replica]) — a last-writer-wins
     * *tombstone* ([LWWRegister.unset]) that competes under merge exactly like a
     * [set]: a remove at a later tag beats an earlier set, and a set at a later
     * tag revives the key, with the same deterministic `(timestamp, replicaId)`
     * tie-break. Removed keys disappear from [get] and [entries]. Returns **the
     * change**: one key's tombstone cell, not the whole map.
     *
     * **A removal's delta is a one-cell map, never an empty one.** A remove here
     * is a write like any other, so the change to transmit is that tombstone. An
     * empty map is the lattice identity: joining it says nothing at all, and the
     * removal would never leave the replica that made it.
     *
     * Removing a key that was never set locally still ships a tombstone, so a
     * concurrent earlier-tagged [set] arriving later loses.
     *
     * The tombstone cell is retained in state (like every set cell) — this map
     * has no per-key garbage collection.
     *
     * The tag-uniqueness precondition and the domination domain on [set] apply
     * equally here.
     *
     * @sample us.tractat.kuilt.crdt.sampleLWWMap
     */
    public fun remove(replica: ReplicaId, timestamp: Long, key: K): Patch<LWWMap<K, V>> =
        removePatch(replica, timestamp, key)

    /**
     * The whole map a [set] produces — the reference semantics [set]'s delta must reproduce under
     * [piece], byte for byte, throughout the domination domain named on [set].
     *
     * Deliberately **not public**: it is the O(keys) spelling this type exists to keep off the
     * wire. Two callers need it, and both are reasons to keep it rather than delete it:
     *
     * - `LWWMapDeltaMutatorLawTest` cannot state the delta-mutator law — nor find the edge of
     *   its domain — without a reference independent of the delta path it is testing.
     * - `LWWMapTest` drives it throughout (46 [setWhole] calls, 18 [removeWhole]) because
     *   [piece]'s lattice laws must hold over states a *joined* delta can no longer produce.
     *   Assigning can move a replica **down** the lattice (#2087); joining cannot. Delete this
     *   and that whole region stops being searchable — the associativity sweeps over #2087
     *   down-moves would silently start asserting over well-behaved inputs only.
     */
    internal fun setWhole(replica: ReplicaId, timestamp: Long, key: K, value: V): LWWMap<K, V> {
        val current = cells[key] ?: LWWRegister.empty()
        return LWWMap(cells + (key to current.set(replica, timestamp, value)))
    }

    /** The whole map a [remove] produces. Internal for the same reason as [setWhole]. */
    internal fun removeWhole(replica: ReplicaId, timestamp: Long, key: K): LWWMap<K, V> {
        val current = cells[key] ?: LWWRegister.empty()
        return LWWMap(cells + (key to current.unset(replica, timestamp)))
    }

    // [LWWRegister.set]/[LWWRegister.unset] replace rather than merge, so the cell built from an
    // empty register is the very cell an assigning mutator would write — the delta needs no
    // local state.
    private fun setPatch(replica: ReplicaId, timestamp: Long, key: K, value: V): Patch<LWWMap<K, V>> =
        Patch(LWWMap(mapOf(key to LWWRegister.empty<V>().set(replica, timestamp, value))))

    private fun removePatch(replica: ReplicaId, timestamp: Long, key: K): Patch<LWWMap<K, V>> =
        Patch(LWWMap(mapOf(key to LWWRegister.empty<V>().unset(replica, timestamp))))

    /** The join: per-key max-tag of the underlying registers. */
    override fun piece(other: LWWMap<K, V>): LWWMap<K, V> =
        LWWMap(cells.mergeValues(other.cells) { mine, theirs -> mine.piece(theirs) })

    override fun equals(other: Any?): Boolean = other is LWWMap<*, *> && cells == other.cells
    override fun hashCode(): Int = cells.hashCode()
    override fun toString(): String = "LWWMap($entries)"

    public companion object {
        /** The empty map. */
        public fun <K, V> empty(): LWWMap<K, V> = LWWMap(emptyMap())
    }
}
