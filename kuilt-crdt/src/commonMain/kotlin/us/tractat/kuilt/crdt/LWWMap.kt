package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A map from [K] to last-writer-wins values [V]: per-key [LWWRegister]s
 * composed under union-merge of keys. Each [set] writes one key with a
 * `(timestamp, replicaId)` tag; merge picks the per-key max tag.
 *
 * Suited to settings, ready-toggles, and similar small key→latest-value state
 * where surfacing concurrent edits (a la [MVRegister]) is unwanted.
 *
 * **Clock-skew warning.** Wall-clock timestamps work only when clocks are
 * well-synchronized across all replicas. NTP-class drift will cause surprising
 * silent drops: a write with a lagging timestamp loses to an older write from a
 * faster clock. For correctness under arbitrary clock skew, pair this map with
 * a Hybrid Logical Clock above this layer.
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

    /** The join: per-key max-tag of the underlying registers. */
    override fun piece(other: LWWMap<K, V>): LWWMap<K, V> {
        val merged = HashMap<K, LWWRegister<V>>(cells)
        for ((key, theirReg) in other.cells) {
            val mine = merged[key]
            merged[key] = if (mine == null) theirReg else mine.piece(theirReg)
        }
        return LWWMap(merged)
    }

    override fun equals(other: Any?): Boolean = other is LWWMap<*, *> && cells == other.cells
    override fun hashCode(): Int = cells.hashCode()
    override fun toString(): String = "LWWMap($entries)"

    public companion object {
        /** The empty map. */
        public fun <K, V> empty(): LWWMap<K, V> = LWWMap(emptyMap())
    }
}
