package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.Room

/**
 * A [GameSession] bootstrapped over a membership-aware [Room] — so the game speaks the **same**
 * presence vocabulary the room already emits, with no second source of truth.
 *
 * Returned by [gameOverRoom]. In addition to everything a [GameSession] offers (drive the game
 * through [node], ride app traffic over [appChannel]), a room-backed session exposes the backing
 * room's live membership: [presence] **is** `room.events` and [roster] **is** `room.roster`. A
 * consumer that already knew how to render "who dropped / who's back" for a [Room] renders it for a
 * game unchanged.
 *
 * The raw, no-[Room] bootstraps ([gameNode] / [gameHost] / [gameJoin]) return a plain [GameSession]
 * with no presence surface — "this session has presence" is a compile-time fact of the type, not a
 * silently-empty flow.
 *
 * @sample us.tractat.kuilt.game.sampleGameOverRoom
 */
public class RoomGameSession internal constructor(
    node: RaftNode,
    private val room: Room,
    appMux: NamedMux,
    lobby: GamePresence?,
) : GameSession(node, room.channel(GAME_ROOM_CHANNEL), appMux, lobby) {

    /**
     * Live membership/presence events — identical to the backing [Room]'s `events`.
     *
     * **These are link-liveness events, not human presence.** A [MembershipEvent.Partitioned] means
     * a peer's *transport link* went silent; a [MembershipEvent.Resumed] / [MembershipEvent.Recovered]
     * means the link healed. A brief transport heal (app returning to foreground, a Wi-Fi blip) can
     * *legitimately* resume in ~3 s while the player is, from the app's point of view, still "away".
     * Human-presence semantics ("seated / away / thinking") are an app-layer concept the consumer
     * composes on top (e.g. debounce, or gate on app-foreground) — do not mistake a legitimate
     * link-resume for a bug.
     */
    public val presence: Flow<MembershipEvent> get() = room.events

    /** Live roster with per-member [Member.liveness] — identical to the backing [Room]'s `roster`. */
    public val roster: StateFlow<Set<Member>> get() = room.roster

    /**
     * Tears down the game **and** the room it owns: stops the consensus node and closes the game
     * channel view ([GameSession.close]), then leaves the backing [Room].
     *
     * A bare [GameSession.close] would close only the game's channel *view* — a no-op, since the
     * [Room] owns the channel's lifecycle — leaking the room, its liveness detectors, and the
     * underlying fabric. Because `gameOverRoom` takes ownership of the room, this override is the
     * single teardown path; the caller must **not** call [Room.leave] directly.
     *
     * Idempotent: a second call is safe — [Room.leave] latches on the room's `closed` flag.
     */
    override suspend fun close(reason: CloseReason) {
        super.close(reason)
        room.leave(reason.toLeave())
    }
}

/**
 * Maps a game [CloseReason] to the room [LeaveReason] `close` propagates into [Room.leave]:
 * an [CloseReason.Error] carries its message forward; every other reason is a graceful leave.
 */
private fun CloseReason.toLeave(): LeaveReason = when (this) {
    is CloseReason.Error -> LeaveReason.Error(throwable.message ?: "closed")
    else -> LeaveReason.Normal
}

/**
 * Bootstrap a [RoomGameSession] over an already-adopted [Room], so the game surfaces the room's
 * presence directly instead of the caller hand-wiring a `room.events` → presence adapter.
 *
 * This is the **roster-given** path: the voter set is taken from the room's current
 * [Room.roster] (plus this peer), and Raft elects the leader symmetrically — there is no
 * appoint-the-host quorum block (which would suspend `gameOverRoom` until enough peers joined).
 * The game rides a single named [Room.channel] view ([GAME_ROOM_CHANNEL]); the game's internal mux
 * tags nest inside that one channel.
 *
 * **Single-ownership contract.** `gameOverRoom` *takes ownership* of [room]: the returned session's
 * lifecycle owns it. Do **not** call [Room.leave] on [room] directly afterwards — hand the adopted
 * room straight into `gameOverRoom` and drive teardown through [RoomGameSession.close].
 *
 * **Mesh-latency note.** On a mesh, joiner↔joiner Raft frames over the room channel are dropped
 * until the host has admitted each member; Raft retries make convergence eventual.
 *
 * **Clock.** The game inherits the [room]'s time domain — the room's own detectors run on the clock
 * it was constructed with — so, like [gameNode] (the other roster-given path), `gameOverRoom` takes
 * no `clock`. Timing is the room's concern here.
 *
 * @param room The already-adopted room (e.g. from an election lobby's `adopt`). Its current
 *   [Room.roster] plus this peer becomes the voter set.
 * @param storage Durable Raft state. Defaults to [InMemoryRaftStorage].
 * @param raftConfig Timing and behaviour parameters. Tests pass `RaftConfig(expectVirtualTime = true)`.
 * @param identity How this peer obtains its Raft §8 dedup id. See [gameNode].
 * @param overlay Optional gossip-flood wrapper for the broadcast plane; `null` (default) gives a
 *   flat mux over the room channel. See [gameNode].
 */
public fun CoroutineScope.gameOverRoom(
    room: Room,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    identity: ClientIdentity = ClientIdentity.Auto,
    overlay: (CoroutineScope.(Seam) -> Seam)? = null,
): RoomGameSession {
    val self = NodeId(room.selfId.value)
    val voterIds = room.roster.value.mapTo(mutableSetOf(self)) { NodeId(it.id.value) }
    val seam = room.channel(GAME_ROOM_CHANNEL)
    val bootstrap = bootstrapRosterGiven(
        seam = seam,
        voterIds = voterIds,
        storage = storage,
        raftConfig = raftConfig,
        identity = identity,
        placement = ConsensusPlacement.SessionOwned,
        overlay = overlay,
    )
    val session = RoomGameSession(bootstrap.node, room, bootstrap.appMux, lobby = null)
    // If the room dies on its own (terminal HostLost), close the session so a torn room never
    // leaves a live consensus node spinning over a dead transport. Double-close-safe via the leave
    // latch. Lives on the bootstrap scope, so it is cancelled when the session's scope ends.
    launch {
        room.events.filterIsInstance<MembershipEvent.HostLost>().first()
        session.close(CloseReason.Normal)
    }
    return session
}
