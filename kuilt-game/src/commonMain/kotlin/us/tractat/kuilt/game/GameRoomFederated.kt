package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.tieredSeam
import us.tractat.kuilt.gossip.TwoTier
import us.tractat.kuilt.gossip.policyOverlay
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftStorage
import kotlin.random.Random
import kotlin.time.Instant

/**
 * Run one game-per-room across a **federated server core** — the [gameNodeRoom] analogue for a
 * game whose players are spread over *several* servers instead of one.
 *
 * Where [gameNodeRoom] with [ConsensusPlacement.serverCore] runs a game on a **single** server
 * (every player connects to that one server, which is the whole voter core), this entry point runs
 * the same game across a **fully-meshed core of servers**: each server holds the players nearest it,
 * all servers vote in the game's Raft cluster, and a *broadcast* crosses the core once and fans to
 * each server's local players (the [TwoTier] dissemination shape). It is called **once per game on
 * each core server** — never by a player. A federated player joins with [gameNodeRoom] and
 * `ConsensusPlacement.federatedCore(core) { null }` (its player role — see below); it must **not**
 * use [ConsensusPlacement.serverCore], which has no relay wrapper and would never receive a
 * cross-server leader's log.
 *
 * ## Cross-server learner delivery — wired via the routed transport
 *
 * Raft replicates the committed log to each learner by **unicast** (`sendTo`), and [tieredSeam]'s
 * `sendTo` reaches only a peer in one of its two tiers (this server's local room, or the other
 * servers) — so on its own a leader delivers `AppendEntries` to players **local to itself** and to
 * the other servers, but never to a player behind a *different* server. This entry point closes that
 * gap by seating the game with [ConsensusPlacement.federatedCore], which wraps each node's Raft
 * transport in a routing decorator (a `RoutedRaftTransport`): a frame addressed to a node no server
 * can reach directly is relayed one hop closer over a dedicated `RAFT_RELAY` channel along the bounded
 * path `player → server → core → server → player`, preserving the true Raft origin end-to-end (so a
 * far player's reply still credits `matchIndex`, votes and read-index acks correctly). A federated
 * player joins with [gameNodeRoom] and `ConsensusPlacement.federatedCore(core) { null }` — the same
 * relay decorator, in its player role (always forwards to its one server). This entry point therefore
 * supports **server-core consensus, failover, *and* delivery to players spread across the core**.
 *
 * ### Caller obligation — populate the attachment directory (H5)
 *
 * The relay's final server → player hop resolves the destination through [attachment], so a joined
 * player must be **attached** in the directory ([us.tractat.kuilt.cluster.AttachmentDirectory.attach],
 * whose sole caller is [us.tractat.kuilt.cluster.OverlayServer.admit] in a server's accept path).
 * No code in this module attaches players — it is the caller's duty, discharged where connections are
 * admitted (the `ServerCluster` relay-dialect cutover; see #1360 PR 2b). Until a player is attached,
 * cross-server *membership* still converges (the `CORE_ROSTER_CHANNEL` roster union admits it), but
 * the leader's `AppendEntries` **cannot be routed to it** — it is admitted yet never delivered. A
 * federation orchestrator populates the directory by observing each server's room roster and
 * attaching every local player (the guard tests do exactly this to exercise the mechanism).
 *
 * ## The one-line difference from [gameNodeRoom]
 *
 * [gameNodeRoom] wraps a single room seam in the star relay:
 *
 * ```
 * val overlay = starOverlay(rooms.host(Pattern(gameId)), random, clock)         // one tier
 * ```
 *
 * This entry point bonds **two** tiers into one seam and swaps the star for the two-tier policy:
 *
 * ```
 * val localRoom     = rooms.host(Pattern(gameId))                               // this server's local players
 * val federatedSeam = tieredSeam(localRoom, perGameCore, this)                  // local players ∪ other servers
 * val overlay       = policyOverlay(federatedSeam, TwoTier(core, attachment), random, clock)
 * ```
 *
 * Below the overlay, the game bootstraps with [ConsensusPlacement.federatedCore] (not
 * [ConsensusPlacement.serverCore]): consensus physically lives on the server core, and the leader
 * admits players as learners from the **union of every core server's local roster** — each server
 * publishes its local players to the others over the `CORE_ROSTER_CHANNEL`, so a player behind *any*
 * server is admitted, not just the leader's own (that cross-server admission is what makes a
 * federated player reachable at all — a leader can only replicate to a member of the committed
 * config). The dissemination *shape* becomes [TwoTier]: a server floods the other servers plus its
 * own local clients; a broadcast crosses the core once and each server fans it to its own periphery.
 *
 * ## What the caller provisions — the per-game core channel and the attachment lookup
 *
 * This function is a pure composition: it owns neither the inter-server mesh nor the attachment
 * directory. The caller (a per-server federation context) provisions both and passes them in, for
 * one reason — the inter-server mesh seam is **single-collection** (ADR-034), and several
 * components ride it. A server carves its one inter-server mesh seam into distinct
 * [us.tractat.kuilt.core.NamedMux] channels over **one** base collector:
 *
 * - a **directory** channel that an [us.tractat.kuilt.cluster.AttachmentDirectory] (or
 *   [us.tractat.kuilt.cluster.OverlayServer]) replicates the `player → server` table over, and
 * - one **per-game** channel per concurrent game — `NamedMux(coreMesh).channel(gameId)` — which is
 *   the [perGameCore] seam handed to *this* call.
 *
 * The per-game channel carries everything this game sends between servers (its Raft traffic and its
 * broadcasts), nested inside the channel by the [gameNode] mux — so no component ever opens a
 * second collector on the shared mesh. [tieredSeam] then takes sole ownership of [perGameCore]'s
 * `incoming` (alongside the local room's), and the caller must not collect it elsewhere.
 *
 * [attachment] is the live `player → server` lookup the two-tier overlay reads on every view
 * recomputation — pass [us.tractat.kuilt.cluster.AttachmentDirectory.lookup] /
 * [us.tractat.kuilt.cluster.OverlayServer.lookup]. A client the directory does not (yet) place
 * yields an empty flood view for that client until its attachment converges — the failover seam,
 * handled by the overlay layer, not here.
 *
 * @param rooms this server's session-mux [Loom] (a [us.tractat.kuilt.core.MuxServerLoom]); its
 *   `host(Pattern(gameId))` yields this game's local [us.tractat.kuilt.core.RoomHubSeam]. **Its
 *   `selfId` must equal [perGameCore]'s `selfId`** — [tieredSeam] bonds two tiers of the *same*
 *   node and rejects a mismatch.
 * @param gameId the room/channel name selecting this game on both the local session mux and the
 *   inter-server mesh.
 * @param core the [NodeId]s of the server core — every one of them votes in this game. Non-empty.
 * @param perGameCore this server's per-game inter-server seam — `NamedMux(coreMesh).channel(gameId)`.
 *   Its roster is the *other* core servers reachable for this game; [tieredSeam] takes sole
 *   ownership of its `incoming`.
 * @param attachment live `player → server` lookup for the [TwoTier] flood shape (an
 *   [us.tractat.kuilt.cluster.AttachmentDirectory]-backed directory reads it live).
 * @param storage durable Raft state; defaults to [InMemoryRaftStorage].
 * @param raftConfig Raft timing/behaviour; tests pass `RaftConfig(expectVirtualTime = true)`.
 * @param random RNG for the overlay's bookkeeping; tests inject a seeded instance.
 * @param clock clock for the overlay's per-neighbour liveness detectors. **Required** — no
 *   wall-clock default so a virtual-time caller can't silently reach the system clock.
 * @param identity how this server obtains its Raft §8 dedup id; see [gameNode].
 */
public suspend fun CoroutineScope.gameNodeRoomFederated(
    rooms: Loom,
    gameId: String,
    core: Set<NodeId>,
    perGameCore: Seam,
    attachment: (PeerId) -> PeerId?,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    random: Random = Random.Default,
    clock: () -> Instant,
    identity: ClientIdentity = ClientIdentity.Auto,
): GameSession {
    val localRoom = rooms.host(Pattern(gameId))
    val federatedSeam = tieredSeam(localRoom, perGameCore, this)
    val corePeers = core.mapTo(mutableSetOf()) { PeerId(it.value) }
    // The same directory that drives the two-tier flood shape ([attachment], keyed by PeerId) is the
    // "which server is this player behind" lookup the routing decorator needs, keyed by NodeId — the
    // Task-1-review identity contract (NodeId string == PeerId string) is exactly what makes the two
    // views interchangeable across this seam.
    val nodeAttachment: (NodeId) -> NodeId? = { node -> attachment(PeerId(node.value))?.let { NodeId(it.value) } }
    return gameNode(
        seam = federatedSeam,
        voterIds = core,
        storage = storage,
        raftConfig = raftConfig,
        identity = identity,
        placement = ConsensusPlacement.federatedCore(core, nodeAttachment),
        overlay = { policyOverlay(it, TwoTier(core = corePeers, attachment = attachment), random, clock) },
    )
}
