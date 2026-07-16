package us.tractat.kuilt.session.election

import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.Room

/**
 * The elected host is the peer with the lowest [PeerId] value among those currently connected.
 *
 * Pure and deterministic: every peer computes the SAME function of the SAME membership set, so
 * they agree with no negotiation. [peers] must be non-empty ([us.tractat.kuilt.core.Seam.peers]
 * always includes this peer, so this holds for any live seam).
 */
public fun electHost(peers: Set<PeerId>): PeerId =
    requireNotNull(peers.minByOrNull { it.value }) { "electHost requires a non-empty peer set" }

/** Thrown by [ElectionLobby.start] when this peer is not the currently-elected host. */
public class NotElectedHostException(message: String) : Exception(message)

/**
 * Thrown by [ElectionLobby.start] / [ElectionLobby.awaitRoom] when the lobby's underlying seam tears
 * mid-election — the co-elector(s) needed to complete the freeze/ack round are permanently gone (e.g.
 * a 2-peer mesh whose only link dropped). **Retryable, not fatal:** the caller may re-run
 * `electLobby(...)` to rejoin and re-elect. It exists so a mid-2PC peer-set collapse surfaces a
 * terminal signal the caller can act on, rather than suspending [awaitRoom]/[start] forever.
 */
public class LobbyTornException(public val reason: us.tractat.kuilt.core.CloseReason) :
    Exception("lobby seam tore mid-election: $reason")

/**
 * A pre-session lobby over a symmetric mesh. **Not a [Room]** — during the lobby there is no admit
 * handshake, no admitted roster, no heartbeat: just the live connected peers and a reactive elected
 * host. A [Room] is created exactly once, at [start]/[awaitRoom], by adopting the woven seam with a
 * now-fixed role. See `docs/host-election-design.md`.
 */
public interface ElectionLobby {
    /** This peer's own identifier. */
    public val selfId: PeerId

    /**
     * Live set of connected peers (includes [selfId]). Join / leave / two-group-merge are all just
     * set changes. Sourced from [us.tractat.kuilt.core.Seam.peers] — never a discovery roster such
     * as `NwLoom.visiblePeers`, which accumulates and never removes.
     */
    public val peers: StateFlow<Set<PeerId>>

    /** The elected host — `electHost(peers)` — reactive. Every peer computes the same value. */
    public val host: StateFlow<PeerId>

    /**
     * **HOST-ONLY.** Close the lobby and begin the session: run the freeze/ack round, then adopt the
     * seam as [us.tractat.kuilt.session.SessionRole.Host]. Retries internally while membership churns;
     * returns the admitted [Room] once every member has acknowledged.
     *
     * @throws NotElectedHostException if this peer is not currently [host].
     * @throws LobbyTornException if the underlying seam tears mid-election (co-electors permanently
     *   gone). Terminal and retryable — never suspends indefinitely on a mid-2PC collapse.
     */
    public suspend fun start(memberName: String? = null): Room

    /**
     * Await the session as a member: suspend until the elected host freezes the lobby, acknowledge it,
     * and adopt the seam as [us.tractat.kuilt.session.SessionRole.Joiner]. (The host obtains its [Room]
     * from [start] instead.) Returns once the session is committed.
     *
     * @throws LobbyTornException if the underlying seam tears mid-election (the elected host or the
     *   co-electors are permanently gone). Terminal and retryable — [awaitRoom] resolves or throws
     *   within a bound, it never suspends forever on a mid-2PC peer-set collapse.
     */
    public suspend fun awaitRoom(memberName: String? = null): Room

    /** Leave the lobby, closing the underlying seam — unless a [Room] has already adopted it. Idempotent. */
    public suspend fun leave()
}
