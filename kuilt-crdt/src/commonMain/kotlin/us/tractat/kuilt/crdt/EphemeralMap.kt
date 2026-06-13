package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * An entry in an [EphemeralMap]: a nullable value tagged with a per-replica
 * monotonic [clock].
 *
 * - [value] `!= null` — the replica is present.
 * - [value] `== null` — the replica has departed gracefully (Yjs pattern:
 *   null-state + incremented clock). Any peer that merges a departure with a
 *   higher clock than a previously-seen presence entry will hide the replica.
 *
 * The [clock] is used only for ordering within a single replica's slot — two
 * replicas never compare clocks across slots, so cross-replica clock skew does
 * not affect correctness.
 */
@Serializable
public class EphemeralEntry<V>(
    public val value: V?,
    public val clock: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is EphemeralEntry<*> && value == other.value && clock == other.clock

    override fun hashCode(): Int = 31 * (value?.hashCode() ?: 0) + clock.hashCode()

    override fun toString(): String = "EphemeralEntry(value=$value, clock=$clock)"
}

/**
 * A presence/awareness CRDT.
 *
 * ## What this models
 *
 * Each replica (`A`, `B`, `C`, …) owns exactly one slot. A peer can write
 * an arbitrary value `V` into its own slot ("I am present with cursor = X") or
 * explicitly vacate it ("I am leaving"). Entries expire on observers that have
 * not received a heartbeat within a caller-supplied TTL.
 *
 * ## Design decisions
 *
 * **Expiry clock — local receive time.** Cross-peer wall-clock comparison is
 * unbounded under clock skew (wasmJs, iOS). Instead, every observer
 * measures staleness by its *own* locally-stamped receive time: when was the
 * last update from replica `R` received *here*? The CRDT carries a
 * per-replica monotonic [EphemeralEntry.clock] for ordering re-publishes, but
 * that clock is never compared across replica slots. Make the time source
 * injectable (see [EphemeralMapTracker]); the CRDT itself is time-free.
 *
 * **Graceful departure — null + higher clock.** Yjs Awareness pattern.
 * [leave] writes a `null`-valued entry with a clock one higher than the
 * current. Peers that merge the departure suppress the slot from [live] output
 * even if a stale presence entry with a lower clock also exists.
 *
 * **TTL eviction location.** The CRDT state is time-free and serialisable: it
 * holds all entries, including stale and null ones. The [live] helper filters
 * entries given a caller-supplied *receive-time* map and a `now` timestamp —
 * it is pure and does not mutate any state. [EphemeralMapTracker] wraps the
 * CRDT with an injectable clock, maintains the receive-time map, and surfaces
 * a single `live()` call that drives eviction.
 *
 * **Each replica writes only its own slot.** There is no mechanism for replica
 * `A` to write into `B`'s slot, so no tombstone or add-wins logic is needed —
 * absence after TTL is sufficient for removal.
 *
 * **Not durable.** This CRDT is intentionally *not* designed for persistence
 * across reconnect. Use [LWWMap] or [ORMap] for durable key→value mappings.
 *
 * @param V the value type carried in each presence entry.
 */
@Serializable
public class EphemeralMap<V> private constructor(
    /** Per-replica latest entry. Null value = departed; null key = never heard of. */
    public val entries: Map<ReplicaId, EphemeralEntry<V>>,
) : Quilted<EphemeralMap<V>> {

    /**
     * Publish or update this replica's presence with [value] and the given [clock].
     *
     * **Clock contract.** The [clock] must be strictly greater than any previously
     * published clock for [replica]. Use a monotonically-incrementing counter — the
     * simplest valid source is `currentClock + 1`. Reusing a clock value produces
     * non-deterministic outcomes on merge tie-break.
     */
    public fun put(replica: ReplicaId, value: V, clock: Long): EphemeralMap<V> {
        val current = entries[replica]
        if (current != null && current.clock >= clock) return this
        return EphemeralMap(entries + (replica to EphemeralEntry(value, clock)))
    }

    /**
     * Signal graceful departure for [replica]: publishes a `null`-value entry
     * with [clock], which must be higher than any prior entry. Peers that
     * merge this departure will suppress [replica] from [live] output.
     */
    public fun leave(replica: ReplicaId, clock: Long): EphemeralMap<V> {
        val current = entries[replica]
        if (current != null && current.clock >= clock) return this
        return EphemeralMap(entries + (replica to EphemeralEntry(null, clock)))
    }

    /**
     * Returns the set of *live* entries: those with a non-null value whose
     * receive time is within [ttlMs] milliseconds of [now].
     *
     * **Eviction semantics.** An entry is evicted if:
     * - Its value is `null` (graceful departure), or
     * - Its [ReplicaId] is absent from [receiveTime] (never heard from), or
     * - `now - receiveTime[replica] >= ttlMs` (stale — TTL expired).
     *
     * The boundary is **exclusive at TTL**: `now - receivedAt < ttlMs` is live.
     *
     * @param receiveTime a map from [ReplicaId] to the local monotonic timestamp
     *   (in ms) at which that replica's last update was stamped. Maintained
     *   externally by [EphemeralMapTracker] or equivalent.
     * @param now the current local monotonic timestamp in ms.
     * @param ttlMs the expiry window in ms.
     */
    public fun live(
        receiveTime: Map<ReplicaId, Long>,
        now: Long,
        ttlMs: Long,
    ): Map<ReplicaId, V> = entries
        .mapNotNull { (replica, entry) ->
            val value = entry.value ?: return@mapNotNull null
            if (!isLive(replica, entry, receiveTime, now, ttlMs)) return@mapNotNull null
            replica to value
        }
        .toMap()

    /** The join: per-replica max-clock wins. */
    override fun piece(other: EphemeralMap<V>): EphemeralMap<V> {
        if (other.entries.isEmpty()) return this
        if (entries.isEmpty()) return other
        val merged = HashMap<ReplicaId, EphemeralEntry<V>>(entries)
        for ((replica, theirEntry) in other.entries) {
            val mine = merged[replica]
            if (mine == null || theirEntry.clock > mine.clock) {
                merged[replica] = theirEntry
            }
        }
        return EphemeralMap(merged)
    }

    override fun equals(other: Any?): Boolean =
        other is EphemeralMap<*> && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "EphemeralMap($entries)"

    public companion object {
        /** The empty map — the CRDT's bottom element. */
        public fun <V> empty(): EphemeralMap<V> = EphemeralMap(emptyMap())
    }
}

private fun <V> isLive(
    replica: ReplicaId,
    entry: EphemeralEntry<V>,
    receiveTime: Map<ReplicaId, Long>,
    now: Long,
    ttlMs: Long,
): Boolean {
    val receivedAt = receiveTime[replica] ?: return false
    return (now - receivedAt) < ttlMs
}
