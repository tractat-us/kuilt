package us.tractat.kuilt.game

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.RaftNode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A running game session: the consensus [node] plus any named application channels riding the
 * same fabric.
 *
 * Returned by [gameNode], [gameHost], and [gameJoin]. Drive the game through [node] (typically
 * wrapped in a [TurnSequencer]); ride extra application traffic — chat, cursors, voice
 * signalling — over [appChannel].
 *
 * **App channels share the consensus fabric.** Each [appChannel] is a [NamedMux] view nested
 * under a reserved internal channel, so application frames travel over the very same [Seam] as
 * Raft without a second connection and without violating the ADR-034 single-collection contract
 * (the session owns the sole collector). The app wire-layout is identical across all three
 * bootstrap paths. The application owns the entire [appChannel] name namespace — there are no
 * reserved names, because internal channels never live in it.
 *
 * Calling [appChannel] subscribes that name before returning. The view buffers arriving frames
 * until the application collects its incoming flow, including replies to an immediate send.
 * Frames arriving before the peer creates that named channel are not replayed (`replay = 0`).
 */
public open class GameSession internal constructor(
    public val node: RaftNode,
    private val seam: Seam,
    private val appMux: NamedMux,
    /**
     * Lobby presence channel for this peer, non-null when this session was bootstrapped via
     * [gameJoin] or [gameHost]. Null for [gameNode] (roster-given path — no presence channel).
     *
     * Held to support [leave]: the departing peer publishes a vacate signal on the presence
     * channel so the host can evict immediately without waiting the reconnect window, and [close]
     * waits for that signal to reach the seam.
     *
     * Named `lobbyPresence` (not `presence`) so the name `presence` is free for the room-liveness
     * surface on the [RoomGameSession] subtype — the two are distinct concepts: this is the
     * appoint-the-host **lobby** vacate channel, `RoomGameSession.presence` is the backing room's
     * live membership/liveness stream.
     */
    internal val lobbyPresence: GamePresence? = null,
) {
    /**
     * Returns the application [Seam] for the channel named [name], idempotent per name.
     *
     * The returned seam is single-collection like any other: collect its [Seam.incoming] once,
     * fan out with `shareIn` if needed.
     *
     * @throws IllegalArgumentException if [name]'s UTF-8 encoding is empty or exceeds 255 bytes.
     * @sample us.tractat.kuilt.game.sampleGameChat
     */
    public fun appChannel(name: String): Seam = appMux.channel(name)

    /**
     * Host-verified principals of currently-linked peers, keyed by the [PeerId] each was verified
     * against at admission — the hosted-path analogue of the relay path's `Member.principal`.
     *
     * Populated when this session rides a [PrincipalRoster] seam (a `gameHosted` hub whose
     * connection source attaches principals — e.g. a `KtorConnectionSource` with a
     * `principalExtractor`); a constant empty map on seams with no attestation concept (a
     * `gameJoin` spoke, `gameNode`, in-memory fabrics without attached principals).
     */
    public val attestedPrincipals: StateFlow<Map<PeerId, Principal>>
        get() = (seam as? PrincipalRoster)?.attestedPrincipals ?: EMPTY_ROSTER

    /**
     * The host-verified [Principal] of [peer], or `null` if that peer is not currently linked
     * with an attestation. A convenience snapshot of [attestedPrincipals].
     */
    public fun principalFor(peer: PeerId): Principal? = attestedPrincipals.value[peer]

    /**
     * Declares a voluntary departure from the game session: the first half of `leave()` then
     * [close], the order that frees this seat at once.
     *
     * Publishes a vacate marker on the presence channel, and the host evicts this voter via
     * [RaftNode.changeMembership] as soon as it hears it, without waiting the reconnect window. This
     * frees the seat for a replacement peer to join promptly.
     *
     * **It returns before the marker has gone anywhere.** This function does not suspend, so the
     * frame is still queued when it returns. [close] is what waits for it, and a teardown other than
     * [close] loses it: cancelling the session's scope cancels the queued send.
     *
     * **The host has to be listening.** Only a [gameHost] given a `livenessConfig` watches for the
     * marker. Without one, nothing frees this seat, by this path or by the reconnect window.
     *
     * **Spectators and roster-given sessions.** Calling [leave] on a spectator or on a session
     * created by [gameNode] or [gameOverRoom] (no presence channel) is a no-op — those sessions are
     * not voter seats and cannot be "vacated" in the lobby-presence sense.
     *
     * **Reconnect-after-PeerLost** (resuming the old seat) is out of scope — a peer that
     * returns after eviction joins as a new voter via a fresh [gameJoin] into the freed seat.
     */
    public fun leave() {
        lobbyPresence?.declareVacate()
    }

    /**
     * Local teardown: hands a departure [leave] declared to the fabric, stops the consensus [node]'s
     * loops, then closes the underlying fabric. Idempotent — a second call is a no-op.
     *
     * Order matters: the node is stopped before the fabric is torn so its loops do not observe a
     * closed seam mid-shutdown. To leave a cluster cleanly as its leader, hand off leadership
     * ([RaftNode.transferLeadership]) and/or remove this peer from membership
     * ([RaftNode.changeMembership]) first.
     *
     * **After [leave], this waits for the vacate frame**, for at most [DEPARTURE_HANDOFF_BOUND], since
     * a seam can hold a send as long as it likes. Without [leave] it waits for nothing and signals
     * nothing: a hard local teardown, and the host holds the seat for the reconnect window.
     *
     * **What that wait promises is the hand-off, not delivery.** The seam has accepted the frame, and
     * it then closes straight away. A seam that delivers before `broadcast` returns, such as an
     * in-memory one, has delivered it. A seam that queues it can drop it at this close: the WebSocket
     * and kuilt-nw fabrics do, which is tracked by #2830. A frame lost that way, or one that misses
     * the bound, costs a replacement peer the reconnect window and nothing else: the host's liveness
     * monitor frees the seat then, the same as it does for a crash.
     *
     * **It always ends closed.** If the caller is cancelled or its timeout fires during the wait, the
     * node and the fabric are closed anyway, and the cancellation goes on to the caller. That teardown
     * is shielded on every path, so a caller's timeout cannot abandon a fabric half closed, and the
     * time a slow [Seam.close] takes is not bounded by it.
     */
    public open suspend fun close(reason: CloseReason = CloseReason.Normal) {
        try {
            withTimeoutOrNull(DEPARTURE_HANDOFF_BOUND) { lobbyPresence?.handOffDeparture() }
        } finally {
            withContext(NonCancellable) {
                try {
                    node.close()
                } finally {
                    seam.close(reason)
                }
            }
        }
    }

    private companion object {
        // Constant roster for a seam with no attestation concept — one shared instance, never mutated.
        private val EMPTY_ROSTER: StateFlow<Map<PeerId, Principal>> =
            MutableStateFlow<Map<PeerId, Principal>>(emptyMap())
    }
}

/**
 * How long [GameSession.close] waits for the vacate frame to reach the seam. The wait normally ends
 * after one send. This bound is for a send that does not finish, and it only ever delays a session
 * that called [GameSession.leave], whose alternative on expiry is a seat held for the reconnect window.
 */
internal val DEPARTURE_HANDOFF_BOUND: Duration = 2.seconds
