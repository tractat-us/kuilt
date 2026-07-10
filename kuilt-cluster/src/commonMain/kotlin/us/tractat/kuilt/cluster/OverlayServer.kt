package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig

/**
 * One server's view of the two-tier overlay — the piece that survives a client's
 * **failover** to a different server (slice 5D).
 *
 * In a federation, three servers form a fully-meshed core and each game's players
 * connect through whichever server is nearest them. Two facts about a player have
 * to stay in step on the server they are behind:
 *
 * - the **attachment directory** must say "this player's packets flow through me"
 *   ([AttachmentDirectory.attach]), so the rest of the core routes messages for
 *   them here; and
 * - the **routed-unicast router** must hold the player's local link
 *   ([RoutedUnicastRouter.registerLocalSpoke]), so a message that arrives across
 *   the core is actually handed down to them.
 *
 * Publishing one without the other is a bug: attach-without-register makes the
 * core route to a server that then drops the frame; register-without-attach leaves
 * a reachable player nobody routes to. [admit] and [evict] do both together so
 * that invariant is structural — a caller cannot do half of it.
 *
 * ## Why this is all failover needs — retry-any-server
 *
 * Durable game membership ("is this player in game G?") lives in consensus and can
 * never be lost, so a client whose entry server dies does not need to be *found* —
 * it simply reconnects to **any** surviving server and re-announces itself, and
 * that server re-admits it straight from the Raft core state. The overlay
 * consequence of that re-admission is exactly one [admit] call on the new server:
 *
 * - [AttachmentDirectory.attach] writes `player → newServer` into the replicated
 *   directory, which **supersedes** the old `player → deadServer` entry under
 *   last-writer-wins and re-homes every server's routing to the new server the
 *   moment the write converges; and
 * - [RoutedUnicastRouter.registerLocalSpoke] makes the new server able to deliver
 *   locally the instant a re-homed frame arrives.
 *
 * There is no directory *lookup* on the failover path and no consensus round per
 * reconnect — the directory update is a **consequence** of re-admission, not a
 * step the client drives.
 *
 * ## The stale window is safe by construction
 *
 * Directory replication is eventually consistent, so between a client's old server
 * dying and its re-admission converging, a sender may still route a unicast to the
 * stale (now dead, or simply wrong) server. That is safe: [RoutedUnicastRouter]
 * misroutes such a frame to *one* server which drops it — it is **never** fanned to
 * a second recipient and never leaks. The guarantee the sender relies on is
 * **resend-on-convergence**: once the directory names the new server, the same
 * unicast, resent, lands. This type adds no buffering or replay of its own — it
 * only keeps the directory and the router in step so that a resend *can* land; the
 * resend itself is the sender's concern.
 *
 * ## Thread-safety
 *
 * [OverlayServer] holds no mutable state of its own — it delegates to
 * [AttachmentDirectory] (an [kotlinx.atomicfu.atomic] write clock plus a
 * lock-guarded [us.tractat.kuilt.quilter.Quilter]) and [RoutedUnicastRouter] (a
 * `reentrantLock`-guarded spoke map). Both are individually correct under a
 * multi-threaded dispatcher, so [admit]/[evict]/[route] are too. Construct one per
 * server via [overlayServer].
 *
 * @see overlayServer for construction and wiring over the inter-server seams.
 * @see AttachmentDirectory for the replicated `player → server` directory.
 * @see RoutedUnicastRouter for the single-addressee cross-core delivery.
 */
public class OverlayServer internal constructor(
    private val directory: AttachmentDirectory,
    private val router: RoutedUnicastRouter,
) {
    /**
     * Admit a (re-)connecting [client] whose local link into this server is [link].
     *
     * Registers the local spoke **first** so that a frame which races in across the
     * core — from a peer server that already learned of this attachment — finds the
     * spoke waiting, then publishes `client → self` into the replicated directory so
     * the rest of the core re-homes its routing here. On a failover this is the whole
     * of the overlay consequence: the directory write supersedes the client's old
     * (dead-server) entry under last-writer-wins.
     */
    public fun admit(client: PeerId, link: Seam) {
        router.registerLocalSpoke(client, link)
        directory.attach(client)
    }

    /**
     * Evict a disconnecting [client].
     *
     * Retracts the directory attachment **first** (a last-writer-wins tombstone, so
     * peers stop routing here once it converges), then drops the local spoke. A
     * client that fails over elsewhere does not need an explicit [evict] — its
     * re-[admit] on the new server supersedes the stale entry on its own — but a
     * clean disconnect calls it to clear the table promptly.
     */
    public fun evict(client: PeerId) {
        directory.detach(client)
        router.removeLocalSpoke(client)
    }

    /**
     * Route [payload] to exactly [recipient], crossing the core if they are behind
     * another server. Delegates to [RoutedUnicastRouter.route]: a stale/absent
     * directory entry drops the frame at exactly one destination (never fanned); the
     * caller resends on convergence.
     */
    public suspend fun route(recipient: PeerId, payload: ByteArray): Unit =
        router.route(recipient, payload)

    /** The server [client] is currently attached to, or `null` if unknown here yet. */
    public fun lookup(client: PeerId): PeerId? = directory.lookup(client)

    /** Stop replicating and relaying; release both components' coroutines. Idempotent. */
    public fun close() {
        router.close()
        directory.close()
    }
}

/**
 * Build an [OverlayServer] for one server, wiring its [AttachmentDirectory] and
 * [RoutedUnicastRouter] together over the two inter-server channels.
 *
 * The directory and the router each take **sole ownership** of a *distinct* seam's
 * `incoming` stream, per the single-collection contract: the directory replicates
 * over [directorySeam] (the CRDT gossip channel) and the router relays over
 * [coreSeam] (the routing channel). In production these are separate channels over
 * the one inter-server mesh; here they are separate [Seam]s. Do not pass the same
 * seam for both.
 *
 * @param self this server's routing identity — the value written into the directory
 *   for every client admitted here and the address peer servers reach it at over
 *   [coreSeam]. Must equal [coreSeam]'s `selfId`.
 * @param coreSeam this server's seam into the fully-meshed core; the router owns its
 *   `incoming`.
 * @param directorySeam this server's seam for directory replication; the directory's
 *   [us.tractat.kuilt.quilter.Quilter] owns its `incoming`. Must be a different seam
 *   from [coreSeam].
 * @param scope the [CoroutineScope] whose [kotlinx.coroutines.Job] parents both the
 *   directory replicator and the routing relay. **Required** — no real-dispatcher
 *   default; inject a test scope's `backgroundScope` under virtual time.
 * @param clock a wall-clock millis source used to tag directory writes so a
 *   genuinely-later attach wins under last-writer-wins. **Required** — inject a fixed
 *   or controlled clock in tests.
 * @param directoryReplica the directory [us.tractat.kuilt.quilter.Quilter] replica id;
 *   defaults to [directorySeam]'s `selfId`.
 * @param directoryConfig directory replication tuning; pass
 *   `QuilterConfig(expectVirtualTime = true)` in tests that run under
 *   `UnconfinedTestDispatcher`.
 */
public fun overlayServer(
    self: PeerId,
    coreSeam: Seam,
    directorySeam: Seam,
    scope: CoroutineScope,
    clock: () -> Long,
    directoryReplica: ReplicaId = ReplicaId(directorySeam.selfId.value),
    directoryConfig: QuilterConfig = QuilterConfig(),
): OverlayServer {
    val directory = attachmentDirectory(
        self = self,
        interServerSeam = directorySeam,
        scope = scope,
        clock = clock,
        replica = directoryReplica,
        config = directoryConfig,
    )
    val router = routedUnicastRouter(
        self = self,
        coreSeam = coreSeam,
        lookup = directory::lookup,
        scope = scope,
    )
    return OverlayServer(directory = directory, router = router)
}
