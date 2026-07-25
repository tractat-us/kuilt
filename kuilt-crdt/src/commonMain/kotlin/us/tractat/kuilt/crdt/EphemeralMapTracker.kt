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
 * ## Update contract — feed [received] author-fresh deltas
 *
 * Every entry handed to [received] should be one its **author** just published.
 * That is what makes "an update arrived" mean "that replica is alive", which is
 * the whole basis of TTL presence.
 *
 * Relaying is the thing to avoid: re-sending another replica's slot, echoing a
 * merged map back, or exchanging [snapshot] wholesale (as generic anti-entropy
 * does) delivers entries whose author may be long gone.
 *
 * [received] guards what a guard can reach. An inbound entry **identical** to the
 * one already held is inert, so echoing an unchanged merged map cannot resurrect
 * anyone; and eviction never admits an entry the standing one already dominates,
 * so a departed replica stays departed and a relayed departure cannot re-open a
 * slot (#1675). What remains is genuinely undecidable: a relayed *presence* entry
 * differing from an expired *presence* slot looks exactly like a restarted
 * replica's first heartbeat, and is admitted as one — re-stamping the TTL and
 * showing a dead replica live for another window, once per such delivery.
 *
 * That residue is why there are two channels. If a delivery is not a heartbeat
 * from the replica it names, hand it to [relayed] instead: it joins the state
 * without ever evicting, and stamps only entries that genuinely advance, so the
 * undecidable case never arises. [received] for author-fresh deltas, [relayed]
 * for everything else.
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
     *
     * **Evict-on-read past TTL.** An existing slot whose local receive time has
     * aged past [ttlMs] reads as *absent* here — exactly as it already does in
     * [live]. So when an inbound entry arrives for an expired slot, it is accepted
     * as **fresh**: the receive time is re-stamped and the stale slot is dropped
     * before the join, so the merge takes the inbound entry even when its clock
     * counter is *lower* than the dead one's. This is what makes a restarted
     * replica (whose process-local clock restarts from zero) visible again within
     * one TTL of its first heartbeat, rather than being pinned behind the dead
     * incarnation's higher clock forever — honouring [EphemeralMap]'s
     * restart-recovery contract.
     *
     * **Identical re-delivery is inert.** An inbound entry equal to the one already
     * held (same value, same clock) carries no evidence that its author is alive —
     * it is this observer's own copy coming back, via a merged-state echo or an
     * anti-entropy round. Such an entry never re-stamps the receive time, expired
     * slot or not, so a crashed peer stays evicted however many times its last
     * frame is re-delivered. A genuine restart's heartbeat differs (a new
     * incarnation-epoch clock, or simply a different counter) and is still
     * accepted. The one cost: a restart whose very first heartbeat reproduces the
     * dead entry exactly is deferred to its *next* heartbeat.
     *
     * **Eviction never installs an older entry.** Evicting a slot drops causal
     * information, so it is confined to the one case that needs it: a *presence*
     * entry re-opening an expired *presence* slot. It is never applied when the
     * standing entry is a departure tombstone — [EphemeralMap.leave] is a
     * permanent statement, and an inbound entry the tombstone already dominates
     * is provably not news, whoever relayed it, so admitting it would invert the
     * lattice's own ordering (#1675). Nor is it applied to an inbound *departure*
     * that the standing presence entry dominates, which would re-open the slot for
     * a later relay of that same presence entry to win. Everything else is left to
     * the join, which keeps the dominating entry. A replica that departed
     * gracefully therefore returns only by out-clocking its own tombstone — see
     * the restart-recovery contract on [EphemeralMap].
     */
    public fun received(update: EphemeralMap<V>) {
        val now = clock()
        var evicted: MutableSet<ReplicaId>? = null
        for ((replica, inbound) in update.entries) {
            val existing = state.entries[replica]
            if (existing == null || advancesEntry(inbound, existing)) {
                receiveTime[replica] = now
                continue
            }
            // `inbound` loses the join, so it is a duplicate or a stale re-delivery — with one
            // exception: a restarted replica publishing from a reset clock. Admit only that.
            if (!readmitsRestart(replica, inbound, existing, now)) continue
            receiveTime[replica] = now
            (evicted ?: mutableSetOf<ReplicaId>().also { evicted = it }).add(replica)
        }
        val base = evicted?.let { state.evicting(it) } ?: state
        state = base.piece(update)
    }

    /**
     * True when [inbound] must be admitted for [replica] even though it loses the join against
     * [existing] — i.e. the slot's standing entry is to be evicted so a restarted replica's
     * lower-clock heartbeat can take it.
     *
     * All four conditions are load-bearing:
     * - **not identical** — an inbound equal to what we hold is this observer's own copy coming
     *   back and is evidence of nothing (#1675 break 1).
     * - **standing entry is a presence assertion** — a departure tombstone is permanent, and an
     *   entry it dominates cannot be news (#1675 break 2).
     * - **inbound is a presence assertion** — eviction exists to re-open a slot for a live
     *   replica; admitting a dominated *departure* only re-opens it for a later stale relay.
     * - **expired** — within the TTL the standing entry is still trusted, so a lower-clock
     *   delivery is an ordinary stale re-delivery and is dropped.
     */
    private fun readmitsRestart(
        replica: ReplicaId,
        inbound: EphemeralEntry<V>,
        existing: EphemeralEntry<V>,
        now: Long,
    ): Boolean =
        inbound != existing &&
            existing.value != null &&
            inbound.value != null &&
            isExpired(replica, now)

    /** True when [replica]'s slot has no receive time, or its last update aged past [ttlMs]. */
    private fun isExpired(replica: ReplicaId, now: Long): Boolean {
        val receivedAt = receiveTime[replica] ?: return true
        return (now - receivedAt) >= ttlMs
    }

    /**
     * Merge state this replica did **not** receive from its author — an anti-entropy round, a
     * full-state exchange, a forwarded slot, a merged map echoed back. The compliant way to feed
     * a tracker anything [received] must not be given.
     *
     * A pure join plus a receive-time stamp for entries that genuinely advance the local state.
     * Nothing here can evict, so no relayed frame can drop a tombstone or displace a standing
     * entry; and a non-advancing frame stamps nothing, so re-delivering state this replica
     * already holds is a complete no-op however many rounds run.
     *
     * What relaying still costs, and its bound: an entry that *does* advance the local state
     * re-stamps the TTL even though its author may be long gone, so a relayed replica can read
     * live for one window per **distinct** entry of its slot still circulating. That total is
     * finite and monotonically exhausted — once this replica holds the highest entry the network
     * has for a slot, no further relay of it can ever stamp again. Contrast [received], where a
     * differing frame re-arms the timer on every delivery, without bound.
     *
     * Presence is a claim about the author being alive *now*, and only the author can make it.
     * Route each replica's own heartbeat to [received] and everything else here.
     *
     * @sample us.tractat.kuilt.crdt.sampleEphemeralMapTrackerChannels
     */
    public fun relayed(update: EphemeralMap<V>) {
        val now = clock()
        for ((replica, inbound) in update.entries) {
            if (advancesEntry(inbound, state.entries[replica])) receiveTime[replica] = now
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

    /**
     * The current merged CRDT state (all entries, including departed/stale).
     *
     * Intended for inspection and persistence. **Not** a frame to broadcast: it
     * carries other replicas' slots, so feeding it to a remote [received] relays
     * entries this replica did not author — see the update contract in the class
     * KDoc. Publish only your own slot. If a peer must exchange whole states
     * anyway (generic anti-entropy), the receiving side merges it with [relayed],
     * never [received].
     */
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
