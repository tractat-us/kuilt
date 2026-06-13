package us.tractat.kuilt.crdt

/**
 * A stateful wrapper around [EphemeralMap] that stamps *local receive times*
 * and drives TTL eviction.
 *
 * ## Responsibilities
 *
 * - Maintains a mutable [EphemeralMap] state by merging inbound [EphemeralMap]
 *   updates via [received].
 * - Stamps a local receive time (via [clock]) whenever a replica's entry
 *   advances to a higher clock — i.e. only real updates reset the TTL, not
 *   stale re-deliveries.
 * - Surfaces [live]: the set of entries not yet expired and not departed.
 *
 * ## Clock contract
 *
 * [clock] is a `() -> Long` that returns the current local monotonic time in
 * milliseconds. The production default is `kotlin.time.TimeSource.Monotonic`.
 * Tests inject a controlled counter so eviction can be driven deterministically
 * without wall-clock dependencies.
 *
 * @param V the presence value type.
 * @param ttlMs expiry window in milliseconds. An entry is considered expired
 *   when `now - receiveTime >= ttlMs`. The boundary is exclusive: exactly at
 *   `ttlMs` ms the entry is expired.
 * @param clock injectable monotonic time source (milliseconds).
 */
public class EphemeralMapTracker<V>(
    public val ttlMs: Long,
    private val clock: () -> Long = defaultClock(),
) {
    private var state: EphemeralMap<V> = EphemeralMap.empty()
    private val receiveTime: MutableMap<ReplicaId, Long> = mutableMapOf()

    /**
     * Merge an inbound [update] into the local state.
     *
     * For each replica whose entry advances (higher clock, or present beating a
     * same-clock null), the local receive time is re-stamped to `clock()`.
     * Stale deliveries and same-clock equal-value duplicates do **not** update
     * the receive time — they leave the existing TTL timer intact.
     */
    public fun received(update: EphemeralMap<V>) {
        val now = clock()
        for ((replica, inbound) in update.entries) {
            val existing = state.entries[replica]
            if (advancesEntry(inbound, existing)) {
                receiveTime[replica] = now
            }
        }
        state = state.piece(update)
    }

    /**
     * Returns the current set of live entries: non-departed, non-expired
     * replicas mapped to their values.
     *
     * Delegates to [EphemeralMap.live] with the tracker's own [receiveTime]
     * map and [clock].
     */
    public fun live(): Map<ReplicaId, V> =
        state.live(receiveTime = receiveTime, now = clock(), ttlMs = ttlMs)

    /** The current merged CRDT state (all entries, including departed/stale). */
    public fun snapshot(): EphemeralMap<V> = state
}

/**
 * Returns true when [inbound] should be considered an advance over [existing],
 * triggering a receive-time re-stamp.
 *
 * Mirrors the tie-break in [EphemeralMap.piece]:
 * - Higher clock always advances.
 * - Equal clock: present (non-null) over null advances (so the TTL is refreshed
 *   when a present heartbeat arrives at the same logical time as a departure).
 * - A missing existing entry means any inbound entry is an advance.
 */
private fun <V> advancesEntry(inbound: EphemeralEntry<V>, existing: EphemeralEntry<V>?): Boolean {
    if (existing == null) return true
    return inbound.clock > existing.clock ||
        (inbound.clock == existing.clock && inbound.value != null && existing.value == null)
}

/**
 * Production-default clock: elapsed milliseconds from an arbitrary fixed
 * origin using the platform monotonic clock. Avoids wall-clock date/time
 * APIs, which vary in precision across wasmJs/iOS/JVM.
 */
private fun defaultClock(): () -> Long {
    val origin = kotlin.time.TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
