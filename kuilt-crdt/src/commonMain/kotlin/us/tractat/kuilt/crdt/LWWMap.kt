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
