package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.gossip.starOverlay
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftStorage
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Game-per-room: run **many independent games over one connection set** by composing the game
 * bootstraps with a session-mux [Loom].
 *
 * One server weaves a [us.tractat.kuilt.core.MuxServerLoom] over its accept source; each game
 * lives in its own named room ([us.tractat.kuilt.core.RoomHubSeam]) and each client reaches all
 * of its games over **one** connection via a [us.tractat.kuilt.core.MuxClientLoom]. Every room
 * is wrapped in the star-relay layer ([starOverlay]) so a player's broadcast reaches the room's
 * other players through the server, exactly as on a dedicated `gameHosted` hub — and **only**
 * the room's players: a peer that never sent a frame on a room's channel is structurally absent
 * from that room's fanout, roster, and consensus membership.
 *
 * Three entry points mirror the plain bootstraps, one room each:
 *
 * - [gameHostedRoom] — the room analogue of [gameHosted]: the serving peer hosts an
 *   appoint-the-host game in one room ([AuthoritySeating.SessionPeers]; players are promoted to
 *   voters as they join).
 * - [gameJoinRoom] — the room analogue of [gameJoin]: a player joins a hosted room over its
 *   client-side session mux.
 * - [gameNodeRoom] — the room analogue of [gameNode]: the fixed-authority bootstrap over a room.
 *   With [ConsensusPlacement.serverCore] this is the **server-core game-per-room** composition:
 *   the server calls it once per game with `core = setOf(itself)` (every room a cluster whose
 *   quorum lives on the server), players call it with the same core and ride as learners,
 *   admitted per-room by the core leader's admission loop — which, because the seam it watches
 *   is the room, admits exactly that room's members and nobody else.
 *
 * Each call composes `starOverlay(rooms channel) + game bootstrap` — nothing here is a new
 * mechanism, only the missing composition of two shipped ones.
 */

/**
 * A [Tag] whose only meaning is the room-channel name a session-mux [Loom] routes by.
 *
 * [us.tractat.kuilt.core.MuxClientLoom] maps a rendezvous to a channel name; the game-per-room
 * entry points join by [gameId], so the tag carries the name and nothing else.
 */
private class RoomChannelTag(override val sessionName: String) : Tag {
    override val peerKey: String get() = sessionName
}

/**
 * Host one appoint-the-host game in the room named [gameId] over [rooms] — the game-per-room
 * analogue of [gameHosted].
 *
 * [rooms] is the serving side's session-mux [Loom] (a [us.tractat.kuilt.core.MuxServerLoom]
 * over the server's accept source). `rooms.host(Pattern(gameId))` yields that room's
 * [us.tractat.kuilt.core.RoomHubSeam]; this function wraps it in the star-relay layer
 * ([starOverlay]) and runs [gameHost] on it. Call it once per concurrent game — every game gets
 * its own room, its own Raft cluster, its own presence channel, and its own app-channel
 * namespace, all sharing the one connection set. Players join with [gameJoinRoom] using the
 * same [gameId].
 *
 * Parameters are forwarded to [gameHost] unchanged; see [gameHost] for their semantics.
 * [random] and [clock] additionally drive the room's relay overlay (seeded/virtual-time
 * injectable, like [gameHosted]).
 *
 * @param placement How this session obtains its consensus node — must seat
 *   [AuthoritySeating.SessionPeers] (see [gameHost]). For the server-core placement use
 *   [gameNodeRoom] with [ConsensusPlacement.serverCore].
 * @sample us.tractat.kuilt.game.sampleGameRooms
 */
public suspend fun CoroutineScope.gameHostedRoom(
    rooms: Loom,
    gameId: String,
    peerCount: Int,
    returnAt: ReturnPolicy = ReturnPolicy.FullMembership,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    livenessConfig: HeartbeatConfig? = null,
    random: Random = Random.Default,
    clock: () -> Instant,
    identity: ClientIdentity = ClientIdentity.Auto,
    placement: ConsensusPlacement = ConsensusPlacement.SessionOwned,
): GameSession {
    val overlay = starOverlay(rooms.host(Pattern(gameId)), random, clock)
    return gameHost(
        seam = overlay,
        peerCount = peerCount,
        returnAt = returnAt,
        storage = storage,
        raftConfig = raftConfig,
        livenessConfig = livenessConfig,
        clock = clock,
        identity = identity,
        placement = placement,
    )
}

/**
 * Join the hosted game in the room named [gameId] over [rooms] — the game-per-room analogue of
 * [gameJoin].
 *
 * [rooms] is this player's session-mux [Loom] (a [us.tractat.kuilt.core.MuxClientLoom] over one
 * base connection). Joining several games through the same loom multiplexes them all over that
 * single connection — the channel name is the `(gameId)` envelope the server routes on. The
 * room channel is wrapped in the star-relay layer ([starOverlay]) so this player speaks the
 * same relay envelope as the hosting side, then [gameJoin] runs on it unchanged.
 *
 * Parameters are forwarded to [gameJoin] unchanged; see [gameJoin] for their semantics.
 *
 * @param placement Must seat [AuthoritySeating.SessionPeers] (see [gameJoin]). To ride a
 *   server-core game as a learner use [gameNodeRoom] with [ConsensusPlacement.serverCore].
 * @sample us.tractat.kuilt.game.sampleGameRooms
 */
public suspend fun CoroutineScope.gameJoinRoom(
    rooms: Loom,
    gameId: String,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    joinAdmissionTimeout: Duration = DEFAULT_JOIN_ADMISSION_TIMEOUT,
    random: Random = Random.Default,
    clock: () -> Instant,
    identity: ClientIdentity = ClientIdentity.Auto,
    placement: ConsensusPlacement = ConsensusPlacement.SessionOwned,
): GameSession {
    val overlay = starOverlay(rooms.join(RoomChannelTag(gameId)), random, clock)
    return gameJoin(
        seam = overlay,
        storage = storage,
        raftConfig = raftConfig,
        joinAdmissionTimeout = joinAdmissionTimeout,
        identity = identity,
        placement = placement,
    )
}

/**
 * Run the fixed-authority bootstrap for the game in the room named [gameId] over [rooms] — the
 * game-per-room analogue of [gameNode], and the entry point of the **server-core** game-per-room
 * composition.
 *
 * With `placement = ConsensusPlacement.serverCore(core)` on every peer:
 *
 * - The **server** (a core member) passes its [us.tractat.kuilt.core.MuxServerLoom], once per
 *   game. Each call opens that game's room, wraps it in the star-relay layer, and seats a Raft
 *   cluster whose voters are [core]. [gameNode] launches the core leader's learner-admission
 *   loop against the room seam — its roster **is the room**, so each game admits exactly its
 *   own players (the per-room scoping that makes the core-side admission loop a proper part of
 *   this composition rather than transitional glue).
 * - Each **player** passes its [us.tractat.kuilt.core.MuxClientLoom] with the same [core] and
 *   placement, and rides that game as a learner over the shared connection: committed log in,
 *   proposals forwarded to the core leader, identical consuming layer.
 *
 * [rooms] must be a session-mux [Loom] — the room is selected purely by the [gameId] channel
 * name on either side ([us.tractat.kuilt.core.MuxClientLoom] maps host and join of one name to
 * the same channel). Do not pass a plain transport loom here; on a loom where host-vs-join is a
 * real role split this entry point would open a new session instead of entering the room.
 *
 * Remaining parameters are forwarded to [gameNode] unchanged; see [gameNode] for their
 * semantics.
 *
 * @sample us.tractat.kuilt.game.sampleGameRooms
 */
public suspend fun CoroutineScope.gameNodeRoom(
    rooms: Loom,
    gameId: String,
    voterIds: Set<NodeId>,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    random: Random = Random.Default,
    clock: () -> Instant,
    identity: ClientIdentity = ClientIdentity.Auto,
    placement: ConsensusPlacement = ConsensusPlacement.SessionOwned,
): GameSession {
    val overlay = starOverlay(rooms.host(Pattern(gameId)), random, clock)
    return gameNode(
        seam = overlay,
        voterIds = voterIds,
        storage = storage,
        raftConfig = raftConfig,
        identity = identity,
        placement = placement,
    )
}
