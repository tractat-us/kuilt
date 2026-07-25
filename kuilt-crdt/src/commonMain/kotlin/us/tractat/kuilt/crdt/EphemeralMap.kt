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
 * **Tie-break at equal clocks — present beats null.** A crash-detector
 * tombstone minted at `seenClock + 1` can collide with a live peer's next
 * heartbeat if both increment from the same base. At equal clock, [piece]
 * keeps the non-null (present) entry, so a live peer's heartbeat is never
 * evicted by a same-clock departure. Null-vs-null at equal clock is a no-op.
 * Value-vs-value at equal clock for the same replica is precluded by the
 * single-writer contract (each replica writes only its own slot), so no
 * second tie-break is needed there.
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
 * ## Reconnect and clock-reset recovery
 *
 * When a replica restarts it resets its local clock to zero (or a low value),
 * which is below the stale high-clock entry that peers already have for that
 * replica. The join ([piece], [put]) alone would silently drop the restarted
 * replica's writes until its clock catches up. **TTL eviction is what recovers
 * it**: an observer measures staleness by its own local receive time, and once a
 * slot has gone `ttlMs` without a fresh update it reads as *absent*
 * ([EphemeralMapTracker] evicts it on the next inbound update — see [evicting]),
 * so the restarted replica's next heartbeat is accepted as fresh even though its
 * clock counter is lower. Rejoin-visibility latency is therefore bounded by
 * `ttlMs` from the restart's first heartbeat — **not** unbounded. Note this is a
 * per-observer, receive-time signal: within the TTL window an observer that has
 * not yet expired the dead slot still hides the restart (the accepted ephemeral
 * within-TTL skew), and a stale re-delivery of the dead incarnation's high-clock
 * entry (e.g. via anti-entropy) can re-pin it.
 *
 * TTL eviction also cannot recover a replica that departed *gracefully*: [leave]
 * is permanent by design, and the tracker will not drop a tombstone to admit an
 * entry that tombstone already outranks (#1675). Such a replica rejoins only by
 * out-clocking its own departure.
 *
 * So for clock-domination that does **not** depend on TTL timing — and for any
 * rejoin after [leave] — drive [put] from a clock whose high bits carry a
 * per-boot incarnation epoch, so a restart's clock always exceeds the dead
 * incarnation's; then TTL eviction is only the backstop, not the sole mechanism.
 * [IncarnationClock] packs exactly that layout.
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
     *
     * **Departure is permanent, not TTL-bounded.** The tombstone outranks every
     * entry the replica published before it, so no re-delivery of one of those —
     * however late, whoever relays it — can bring the replica back; the join
     * discards it, and [EphemeralMapTracker] will not evict a tombstone to let it
     * in (#1675). A departed replica returns only by publishing a presence entry
     * at a clock **strictly above** its own departure clock. A replica that
     * restarts with a reset counter therefore cannot rejoin on the counter alone:
     * carry a per-boot epoch in the clock's high bits (see the restart-recovery
     * section of the class KDoc) so its first heartbeat outranks the tombstone.
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

    /**
     * The join: per-replica max-clock wins. At equal clocks, present beats null
     * (see class-level KDoc for the tie-break rationale).
     */
    override fun piece(other: EphemeralMap<V>): EphemeralMap<V> {
        if (other.entries.isEmpty()) return this
        if (entries.isEmpty()) return other
        return EphemeralMap(entries.mergeValues(other.entries) { mine, theirs -> if (dominates(theirs, mine)) theirs else mine })
    }

    override fun equals(other: Any?): Boolean =
        other is EphemeralMap<*> && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "EphemeralMap($entries)"

    /**
     * Returns a copy with [replicas] dropped from the slot map.
     *
     * This is **not** a join-lattice operation — dropping a slot is not monotone. It exists
     * solely so [EphemeralMapTracker] can evict a slot whose local receive time has aged past
     * the TTL, honouring the restart-recovery contract (see the "Reconnect and clock-reset
     * recovery" section of the class KDoc): once the dead incarnation's slot reads as absent,
     * the restarted replica's next heartbeat — even with a lower clock counter — is accepted as
     * fresh rather than pinned behind the dead entry's higher clock. Removal is driven by
     * receive-time TTL, exactly the same local, per-observer, eventually-consistent presence
     * signal that [live] already filters on; it is `internal` because only the tracker may drive
     * it.
     *
     * Because it is not monotone, its use is confined to that single case: the tracker calls it
     * only for a slot holding an **expired presence entry** that an inbound **presence entry**
     * is re-opening. It is never used to drop a departure tombstone, nor to install a departure
     * the standing entry dominates — either would invert the lattice's own ordering (#1675).
     */
    internal fun evicting(replicas: Set<ReplicaId>): EphemeralMap<V> =
        if (replicas.isEmpty() || entries.isEmpty()) this
        else EphemeralMap(entries.filterKeys { it !in replicas })

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

/**
 * Returns true when [candidate] should replace [current] in the merge.
 *
 * Rules:
 * - Higher clock always wins.
 * - At equal clocks: present (non-null value) beats null (departure). Null vs null is
 *   a no-op (returns false). Value vs value at the same clock is precluded by the
 *   single-writer contract, so no further tie-break is needed.
 */
private fun <V> dominates(candidate: EphemeralEntry<V>, current: EphemeralEntry<V>): Boolean =
    candidate.clock > current.clock ||
        (candidate.clock == current.clock && candidate.value != null && current.value == null)
