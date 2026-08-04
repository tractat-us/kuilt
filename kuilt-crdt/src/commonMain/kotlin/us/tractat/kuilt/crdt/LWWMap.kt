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
 * Immutable: [set]/[remove] return a new map. [piece] is the per-key merge.
 * [setDelta]/[removeDelta] return just the one cell that changed, which is what
 * belongs on the wire.
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
     * Write [value] for [key] tagged with ([timestamp], [replica]).
     *
     * **Precondition — tag uniqueness.** The `(replica, timestamp)` pair MUST
     * uniquely identify this write for the given key. Reusing the same
     * `(replica, timestamp)` across two writes to the same key with different
     * values produces non-deterministic convergence under merge — which value
     * survives depends on merge order, not write order. Use a monotonic
     * timestamp source per replica and never reuse a `(replica, timestamp)`
     * pair. Not enforced at runtime.
     */
    public fun set(replica: ReplicaId, timestamp: Long, key: K, value: V): LWWMap<K, V> {
        val current = cells[key] ?: LWWRegister.empty()
        val next = current.set(replica, timestamp, value)
        return LWWMap(cells + (key to next))
    }

    /**
     * Remove [key] tagged with ([timestamp], [replica]) — a last-writer-wins
     * *tombstone* ([LWWRegister.unset]) that competes under merge exactly like a
     * [set]: a remove at a later tag beats an earlier set, and a set at a later
     * tag revives the key, with the same deterministic `(timestamp, replicaId)`
     * tie-break. Removed keys disappear from [get] and [entries].
     *
     * Removing a key that was never set locally still records the tombstone, so
     * a concurrent earlier-tagged set arriving later loses.
     *
     * The tombstone cell is retained in state (like every set cell) — this map
     * has no per-key garbage collection.
     *
     * The tag-uniqueness precondition on [set] applies equally here.
     */
    public fun remove(replica: ReplicaId, timestamp: Long, key: K): LWWMap<K, V> {
        val current = cells[key] ?: LWWRegister.empty()
        return LWWMap(cells + (key to current.unset(replica, timestamp)))
    }

    /**
     * The **change** [set] would make, on its own — one key's cell — rather than
     * the whole map.
     *
     * This is what to put on the wire. A replicator broadcasts a patch's delta
     * verbatim, so `Patch(map.set(…))` ships every key on every write, at a cost
     * that grows with the map; this frame carries one cell and its size does not
     * depend on how many keys the map holds. The idiom is
     * `quilter.mutate { it.setDelta(replica, timestamp, key, value) }` —
     * read-modify-write inside the replicator's own lock.
     *
     * A delta is itself an [LWWMap], so a peer absorbs it with the ordinary
     * [piece] join, in any order, with any repeats, and lands on a state that
     * encodes byte-for-byte identically to the writer's [set] result. There is no
     * causal context to carry and nothing to buffer: this map's merge is a per-key
     * max of independent tags.
     *
     * **The tag-uniqueness precondition on [set] applies unchanged**, and the
     * equivalence above holds exactly while it and a monotonic timestamp source
     * are honoured — that is, while the write's `(timestamp, replica)` tag beats
     * the key's current one. A write whose tag *loses* is one this map's own
     * clock-skew warning says will be dropped: [set] shows it locally until the
     * next merge takes it away, whereas this delta drops it immediately. Both
     * replicas converge on the same value either way, because a delta is joined
     * rather than assigned.
     *
     * @sample us.tractat.kuilt.crdt.sampleLWWMapDelta
     */
    public fun setDelta(replica: ReplicaId, timestamp: Long, key: K, value: V): Patch<LWWMap<K, V>> =
        setPatch(replica, timestamp, key, value)

    /**
     * The **change** [remove] would make, on its own: one key's *tombstone* cell.
     * Ship this rather than `Patch(map.remove(…))`, which is the whole map.
     *
     * **A removal's delta is a one-cell map, never an empty one.** A remove here
     * is a write like any other — [remove] records a tombstone that competes on
     * its tag — so the change to transmit is that tombstone. An empty map is the
     * lattice identity: joining it says nothing at all, and the removal would
     * never leave the replica that made it.
     *
     * Removing a key that was never set locally still ships a tombstone, matching
     * [remove], so a concurrent earlier-tagged [set] arriving later still loses.
     *
     * The domination caveat on [setDelta] applies here too.
     *
     * @sample us.tractat.kuilt.crdt.sampleLWWMapDelta
     */
    public fun removeDelta(replica: ReplicaId, timestamp: Long, key: K): Patch<LWWMap<K, V>> =
        removePatch(replica, timestamp, key)

    // [LWWRegister.set]/[LWWRegister.unset] replace rather than merge, so the cell built from an
    // empty register is the very cell [set]/[remove] would write — the delta needs no local state.
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
