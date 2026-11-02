package us.tractat.kuilt.game

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.RaftNode

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
     * channel so the host can evict immediately without waiting the reconnect window.
     *
     * Named `lobbyPresence` (not `presence`) so the name `presence` is free for the room-liveness
     * surface on the [RoomGameSession] subtype — the two are distinct concepts: this is the
     * appoint-the-host **lobby** vacate channel, `RoomGameSession.presence` is the backing room's
     * live membership/liveness stream.
     */
    internal val lobbyPresence: GamePresence? = null,
    /**
     * The liveness monitor a [gameHost] given a `livenessConfig` runs on its caller's scope. [close]
     * cancels it first, because otherwise it would go on watching and evicting through a node that is
     * closing under it, on a scope that closing this session does not end.
     */
    internal val livenessMonitor: Job? = null,
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
     * Declares a voluntary departure from the game session, so that the seat can come free without
     * waiting the reconnect window.
     *
     * Publishes a vacate marker on the presence channel. When the frame reaches a [gameHost] that was
     * given a `livenessConfig`, and that host is the leader, it evicts this voter via
     * [RaftNode.changeMembership] and re-opens the seat for a replacement peer.
     *
     * **`leave()` then [close] can lose the marker, on every fabric.** This function does not suspend,
     * so the frame is still queued when it returns, and [close] waits for nothing: it closes the fabric
     * with the frame still queued. There is no public way yet to wait for the frame, and #2830 is
     * tracked to add one. Until then, treat [leave] as best effort, and see [close] for what a lost
     * marker costs.
     *
     * **The removal may need this peer.** The eviction commits only with a majority of the old
     * membership, and a two-seat table has no majority without the voter that is leaving. If this peer
     * has gone by then, the change cannot commit and the host is left without a leader. Nothing waits
     * for that commit, on either side.
     *
     * **Spectators and roster-given sessions.** Calling [leave] on a spectator or on a session
     * created by [gameNode] or [gameOverRoom] (no presence channel) is a no-op — those sessions are
     * not voter seats and cannot be "vacated" in the lobby-presence sense.
     *
     * **Reconnect-after-PeerLost** (resuming the old seat) is out of scope, and so is coming back under
     * the same peer id: the host will not re-admit an id it evicted, so a peer that returns has to join
     * through a fresh [gameJoin] as a new peer.
     */
    public fun leave() {
        lobbyPresence?.declareVacate()
    }

    /**
     * Local teardown: stops the liveness monitor if this session runs one, stops the consensus [node]'s
     * loops, then closes the underlying fabric. Idempotent — a second call is a no-op.
     *
     * Order matters: the node is stopped before the fabric is torn so its loops do not observe a
     * closed seam mid-shutdown. To leave a cluster cleanly as its leader, hand off leadership
     * ([RaftNode.transferLeadership]) and/or remove this peer from membership
     * ([RaftNode.changeMembership]) first.
     *
     * **This waits for nothing, and signals nothing.** A frame still queued is dropped, the vacate
     * marker from [leave] included, and a seam that queues what it accepts can drop a frame it has
     * already accepted. Then the host holds this seat until its liveness monitor sees the reconnect
     * window pass, the same as for a crash. That frees the seat only if the host is the leader at that
     * moment, and a [gameHost] given no `livenessConfig` never frees it.
     *
     * **It always ends closed.** The teardown is shielded, so a cancelled caller, or a caller whose
     * timeout fires, still closes the node and the fabric, and the cancellation then goes on to the
     * caller. The cost is that a caller's timeout does not bound a slow [Seam.close]. If stopping the
     * node throws, the fabric is closed anyway and the throw goes on to the caller.
     */
    public open suspend fun close(reason: CloseReason = CloseReason.Normal) {
        livenessMonitor?.cancel()
        withContext(NonCancellable) {
            try {
                node.close()
            } finally {
                seam.close(reason)
            }
        }
    }

    private companion object {
        // Constant roster for a seam with no attestation concept — one shared instance, never mutated.
        private val EMPTY_ROSTER: StateFlow<Map<PeerId, Principal>> =
            MutableStateFlow<Map<PeerId, Principal>>(emptyMap())
    }
}
