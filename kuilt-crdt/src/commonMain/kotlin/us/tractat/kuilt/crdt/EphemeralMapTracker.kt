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
     * For each replica whose clock advances, the local receive time is
     * re-stamped to `clock()`. Stale or equal-clock deliveries (duplicates,
     * reordered re-sends) do **not** update the receive time — they only
     * absorb into the lattice if their content is new, and they leave the
     * existing TTL timer intact.
     */
    public fun received(update: EphemeralMap<V>) {
        val now = clock()
        for ((replica, inbound) in update.entries) {
            val existing = state.entries[replica]
            if (existing == null || inbound.clock > existing.clock) {
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
 * Production-default clock: elapsed milliseconds from an arbitrary fixed
 * origin using the platform monotonic clock. Avoids wall-clock date/time
 * APIs, which vary in precision across wasmJs/iOS/JVM.
 */
private fun defaultClock(): () -> Long {
    val origin = kotlin.time.TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
