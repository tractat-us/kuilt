package us.tractat.kuilt.session.election

import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.session.Room

/**
 * The elected host is the peer with the lowest [PeerId] value among those currently connected.
 *
 * Pure and deterministic: every peer computes the SAME function of the SAME membership set, so
 * they agree with no negotiation. [peers] must be non-empty ([us.tractat.kuilt.core.Seam.peers]
 * always includes this peer, so this holds for any live seam).
 *
 * **Same set, or no agreement.** The guarantee is only as good as the input. Applied to a
 * *discovery* roster rather than `Seam.peers` — peers seen but not yet connected — the sets
 * differ per peer and the results diverge, so the answer is **advisory** and must be recomputed
 * continuously rather than acted on once. See `docs/discovery-bootstrap.md` for why kuilt ships
 * no pre-`Seam` election primitive, and the documented pattern for fabrics that need one.
 */
public fun electHost(peers: Set<PeerId>): PeerId =
    requireNotNull(peers.minByOrNull { it.value }) { "electHost requires a non-empty peer set" }

/** Thrown by [ElectionLobby.start] when this peer is not the currently-elected host. */
public class NotElectedHostException(message: String) : Exception(message)

/**
 * Thrown by [ElectionLobby.start] / [ElectionLobby.awaitRoom] on a mid-2PC **peer-set collapse** — the
 * co-elector(s) needed to complete the freeze/ack round are gone. This covers both ways a fabric
 * signals it: a **transport tear** (the underlying seam latches [us.tractat.kuilt.core.SeamState.Torn],
 * e.g. a 2-peer mesh whose only link dropped) and a **membership drain** (`Seam.peers` collapses to
 * `{self}` while `state` stays `Woven` — the elected host simply left the roster). **Retryable, not
 * fatal:** the caller may re-run `electLobby(...)` to rejoin and re-elect. It exists so the collapse
 * surfaces a terminal signal the caller can act on, rather than suspending [awaitRoom]/[start] forever.
 *
 * [reason] is the seam's own [us.tractat.kuilt.core.CloseReason] when it tore, or
 * [us.tractat.kuilt.core.CloseReason.Unreachable] for a membership drain with no tear.
 */
public class LobbyTornException(public val reason: us.tractat.kuilt.core.CloseReason) :
    Exception("lobby election collapsed mid-2PC: $reason")

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
     * The underlying seam's own lifecycle — `Weaving` / `Woven` / `Torn` — exposed so a consumer can
     * distinguish the two ways liveness can collapse mid-election (#1466): a **transport tear**
     * ([SeamState.Torn]) versus a **membership drain** ([peers] shrinking while [state] stays
     * [SeamState.Woven]). Sourced from [us.tractat.kuilt.core.Seam.state] — same value the lobby's own
     * collapse detection races against.
     */
    public val state: StateFlow<SeamState>

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
