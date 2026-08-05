package us.tractat.kuilt.cluster

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.gossip.TwoTier
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig

/**
 * Who is a game's players talking *through* right now — the **attachment
 * directory**.
 *
 * In a federation, three servers form a fully-meshed core and a game's players
 * each connect to whichever server is nearest them: Alice through S1, Bob
 * through S2, Carol through S3. To hand a message meant only for Bob to the one
 * server Bob is behind, every server needs the same little lookup table:
 * `Bob → S2`. That table is this directory.
 *
 * It is deliberately *not* the durable record of who is in the game — that lives
 * in consensus and can never be lost. The directory carries only the cheap,
 * fast-changing fact of *which server a player's packets are flowing through
 * this moment*, which changes every time they reconnect. So it is kept as a
 * **replicated map that servers gossip to each other** rather than a
 * consensus-committed fact: each server writes its own local players into the
 * map, the map spreads across the core over the inter-server link, and every
 * server ends up with the same picture a moment later.
 *
 * ## How it maps onto kuilt primitives
 *
 * The map is an [LWWMap]`<`[PeerId]`, `[PeerId]`>` — client → server, *last
 * writer wins*. LWW is the exact fit for "latest attachment wins": a fresh
 * attach (a newer-tagged [LWWMap.set]) supersedes an older one, and a detach is
 * an [LWWMap.remove] tombstone that competes under the same tag order, so a
 * player who leaves disappears from the table and a player who moves is routed
 * to their new server once the newer write wins. (`ORMap`/`ORSet` model
 * *add-wins sets of things*; a register-per-key last-writer table is what this
 * is.) A [Quilter] runs the map live over the inter-server [Seam], driving the
 * delta-exchange and anti-entropy that make every server converge.
 *
 * ## Eventual, and safe to be
 *
 * Replication is eventually consistent: for a brief window after a player moves,
 * a lagging server may still hold the stale server for them. That is safe by
 * construction — a stale entry misroutes a single unicast to *one* wrong server,
 * which simply drops it (a message is never fanned to extra recipients), and the
 * sender resends once the map converges. Correctness is never at risk, only a
 * few milliseconds of routing latency.
 *
 * ## Feeding the topology
 *
 * [twoTier] hands the live lookup to a [TwoTier] topology policy as its
 * `attachment` function, so the two-tier overlay floods each player's broadcasts
 * through exactly the server they are attached to — reading the directory *live*
 * on every view recomputation.
 *
 * Construct one per server via [attachmentDirectory]. Not thread-confined: the
 * underlying [Quilter] guards its own state with a lock, and the per-write
 * timestamp source here is an [atomic] counter, so [attach]/[detach]/[lookup]
 * are safe to call from any coroutine on any dispatcher.
 *
 * @see attachmentDirectory for construction and wiring over the inter-server seam.
 */
public class AttachmentDirectory internal constructor(
    private val self: PeerId,
    private val quilter: Quilter<LWWMap<PeerId, PeerId>>,
    private val clock: () -> Long,
) {
    /**
     * Strictly-increasing per-replica write timestamp. Seeds from [clock] (wall
     * time, so a genuinely-later attach on a well-synced peer wins the LWW race)
     * but never repeats or regresses — `max(now, last + 1)` — so the
     * `(replica, timestamp)` tag is unique for every write this server makes,
     * satisfying [LWWMap]'s tag-uniqueness precondition even for two writes to
     * the same client inside one clock tick.
     */
    private val lastTimestamp = atomic(0L)

    private fun nextTimestamp(): Long {
        while (true) {
            val prev = lastTimestamp.value
            val next = maxOf(clock(), prev + 1)
            if (lastTimestamp.compareAndSet(prev, next)) return next
        }
    }

    /** This server's current attachments plus every peer server's — the whole converged table. */
    public val entries: Map<PeerId, PeerId>
        get() = quilter.state.value.entries

    /**
     * Record that [client]'s packets now flow through this server: writes
     * `client → self` into the replicated map, which gossips to the other
     * servers. A newer [attach] (or [detach]) for the same client supersedes
     * this one under last-writer-wins.
     */
    public fun attach(client: PeerId) {
        quilter.mutate { it.set(quilter.replica, nextTimestamp(), client, self) }
    }

    /**
     * Record that [client] is no longer attached here — a last-writer-wins
     * tombstone that removes them from the table once it wins under tag order.
     */
    public fun detach(client: PeerId) {
        quilter.mutate { it.remove(quilter.replica, nextTimestamp(), client) }
    }

    /**
     * The server [client]'s packets are currently flowing through, or `null`
     * when the client is unattached or unknown to this server yet. This is the
     * `attachment: (PeerId) -> PeerId?` function [TwoTier] consumes; it reads the
     * converged map live on every call.
     */
    public fun lookup(client: PeerId): PeerId? = quilter.state.value[client]

    /**
     * A [TwoTier] topology policy over [core] whose `attachment` reads this
     * directory live: the overlay always floods each player's broadcasts through
     * the server the directory currently names for them, and re-routes for free
     * the moment a newer attachment converges.
     */
    public fun twoTier(core: Set<PeerId>): TwoTier =
        TwoTier(core = core, attachment = ::lookup)

    /** Stop replicating and release the underlying [Quilter]'s coroutines. */
    public fun close() {
        quilter.close()
    }
}

/**
 * Build an [AttachmentDirectory] for one server, replicating its attachments to
 * the rest of the core over [interServerSeam] — the fully-meshed inter-server
 * link. Each server calls this once with its own seam into the core.
 *
 * @param self this server's [PeerId] — the value written for every client that
 *   attaches here.
 * @param interServerSeam the seam this server holds into the fully-meshed core
 *   (the inter-server mesh seam). The directory takes sole ownership of the
 *   seam's `incoming` stream via its [Quilter], per the single-collection
 *   contract — do not run another collector over the same seam.
 * @param scope the [CoroutineScope] whose [kotlinx.coroutines.Job] parents the
 *   replicator's coroutines. **Required** — no real-dispatcher default; inject a
 *   test scope's `backgroundScope` under virtual time.
 * @param clock a wall-clock millis source used to tag writes so a genuinely
 *   later attach wins under last-writer-wins. **Required** — inject a fixed or
 *   controlled clock in tests (never [kotlin.time.Clock.System] directly there).
 * @param replica the [Quilter] replica id; defaults to the seam's `selfId`,
 *   satisfying the one-replicator-per-`(replica, CRDT)` precondition.
 * @param config replication tuning; pass `QuilterConfig(expectVirtualTime = true)`
 *   in tests that run the real [Quilter] under `UnconfinedTestDispatcher`.
 */
public fun attachmentDirectory(
    self: PeerId,
    interServerSeam: Seam,
    scope: CoroutineScope,
    clock: () -> Long,
    replica: ReplicaId = ReplicaId(interServerSeam.selfId.value),
    config: QuilterConfig = QuilterConfig(),
): AttachmentDirectory {
    val quilter = Quilter(
        seam = interServerSeam,
        initial = LWWMap.empty<PeerId, PeerId>(),
        valueSerializer = LWWMap.serializer(PeerId.serializer(), PeerId.serializer()),
        scope = scope,
        replica = replica,
        config = config,
    )
    return AttachmentDirectory(self = self, quilter = quilter, clock = clock)
}
