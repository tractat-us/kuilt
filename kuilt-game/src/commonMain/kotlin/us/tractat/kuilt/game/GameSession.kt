package us.tractat.kuilt.game

import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.NamedMux
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
 * App-channel delivery is **best-effort** (`replay = 0`, like the underlying mux): a frame sent
 * before the peer subscribes to that name is not replayed. Layer your own reliability on top if
 * you need at-least-once delivery.
 */
public class GameSession internal constructor(
    public val node: RaftNode,
    private val seam: Seam,
    private val appMux: NamedMux,
    /**
     * Presence channel for this peer, non-null when this session was bootstrapped via
     * [gameJoin] or [gameHost]. Null for [gameNode] (roster-given path — no presence channel).
     *
     * Held to support [leave]: the departing peer publishes a vacate signal on the presence
     * channel so the host can evict immediately without waiting the reconnect window.
     */
    internal val presence: GamePresence? = null,
) {
    /**
     * Returns the application [Seam] for the channel named [name], idempotent per name.
     *
     * The returned seam is single-collection like any other: collect its [Seam.incoming] once,
     * fan out with `shareIn` if needed.
     *
     * @throws IllegalArgumentException if [name]'s UTF-8 encoding is empty or exceeds 255 bytes.
     */
    public fun appChannel(name: String): Seam = appMux.channel(name)

    /**
     * Signals a voluntary departure from the game session.
     *
     * Publishes a vacate marker on the presence channel so the host immediately evicts this
     * voter via [RaftNode.changeMembership] without waiting the reconnect window. This frees
     * the seat for a replacement peer to join promptly.
     *
     * **Distinct from [close].** [leave] is a graceful cluster departure: it signals the
     * host and then returns; the caller is responsible for calling [close] afterward to tear
     * down the local session. [close] is always a hard local teardown with no cluster
     * signalling.
     *
     * **Spectators and roster-given sessions.** Calling [leave] on a spectator or on a session
     * created by [gameNode] (no presence channel) is a no-op — those sessions are not voter
     * seats and cannot be "vacated" in the lobby-presence sense.
     *
     * **Reconnect-after-PeerLost** (resuming the old seat) is out of scope — a peer that
     * returns after eviction joins as a new voter via a fresh [gameJoin] into the freed seat.
     */
    public fun leave() {
        presence?.declareVacate()
    }

    /**
     * Local teardown: stops the consensus [node]'s loops, then closes the underlying fabric.
     * Idempotent — a second call is a no-op.
     *
     * Order matters: the node is stopped before the fabric is torn so its loops do not observe a
     * closed seam mid-shutdown. This is a **hard local teardown**, not a graceful cluster
     * departure — to leave a cluster cleanly, hand off leadership
     * ([RaftNode.transferLeadership]) and/or remove this peer from membership
     * ([RaftNode.changeMembership]) first. For a voluntary voter departure use [leave] before
     * calling [close].
     */
    public suspend fun close(reason: CloseReason = CloseReason.Normal) {
        node.close()
        seam.close(reason)
    }
}
