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
     */
    public suspend fun start(memberName: String? = null): Room

    /**
     * Await the session as a member: suspend until the elected host freezes the lobby, acknowledge it,
     * and adopt the seam as [us.tractat.kuilt.session.SessionRole.Joiner]. (The host obtains its [Room]
     * from [start] instead.) Returns once the session is committed.
     */
    public suspend fun awaitRoom(memberName: String? = null): Room

    /** Leave the lobby, closing the underlying seam — unless a [Room] has already adopted it. Idempotent. */
    public suspend fun leave()
}
