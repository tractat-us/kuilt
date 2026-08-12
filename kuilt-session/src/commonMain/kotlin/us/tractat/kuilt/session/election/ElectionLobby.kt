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
 * Thrown by [ElectionLobby.start] on a mid-2PC **peer-set collapse** — the co-elector(s) needed to
 * complete the freeze/ack round are gone. This covers both ways a fabric signals it: a **transport
 * tear** (the underlying seam latches [us.tractat.kuilt.core.SeamState.Torn], e.g. a 2-peer mesh whose
 * only link dropped) and a **membership drain** (`Seam.peers` collapses to `{self}` while `state`
 * stays `Woven` — the last member simply left the roster). **Retryable, not fatal:** the caller may
 * re-run `electLobby(...)` to rejoin and re-elect. It exists so the collapse surfaces a terminal
 * signal the caller can act on, rather than suspending [start] forever.
 *
 * [ElectionLobby.awaitRoom] reports the same collapse as [ElectionOutcome.Torn] instead of throwing —
 * a member has a second, non-collapse way to stop being a member (it gets promoted), and a sealed
 * return makes the caller confront which one happened. See [ElectionOutcome].
 *
 * [reason] is the seam's own [us.tractat.kuilt.core.CloseReason] when it tore, or
 * [us.tractat.kuilt.core.CloseReason.Unreachable] for a membership drain with no tear.
 */
public class LobbyTornException(public val reason: us.tractat.kuilt.core.CloseReason) :
    Exception("lobby election collapsed mid-2PC: $reason")

/**
 * How a member's wait for the session ended — the result of [ElectionLobby.awaitRoom].
 *
 * There are three ways it can end, and two of them are **not** the session starting. Returning them
 * as a sealed value rather than as one adopted [Room] plus one exception is deliberate: the failure
 * mode this prevents is a caller not realising [BecameHost] exists at all, and documentation does not
 * prevent that (#1483).
 */
public sealed interface ElectionOutcome {
    /** The elected host froze the lobby and committed. [room] is this peer's joined session. */
    public data class Adopted(val room: Room) : ElectionOutcome

    /**
     * The election collapsed mid-2PC: the co-elector(s) needed to finish it are gone, either through
     * a transport tear or through a membership drain that left this peer alone. Retryable — re-run
     * `electLobby(...)` to rejoin and re-elect. [reason] is the seam's own
     * [us.tractat.kuilt.core.CloseReason] when it tore, else
     * [us.tractat.kuilt.core.CloseReason.Unreachable].
     */
    public data class Torn(val reason: us.tractat.kuilt.core.CloseReason) : ElectionOutcome

    /**
     * **This peer is now the elected host, and the other members are still here.** The peer that was
     * hosting left the roster, [ElectionLobby.host] recomputed to [ElectionLobby.selfId], and a member
     * can never receive a `Freeze` from a host that is now itself — so the wait ended. Nothing
     * collapsed: the seam is live and the co-members are parked in their own `awaitRoom` on it.
     *
     * **The recovery is [ElectionLobby.start] on this SAME lobby.** The lobby's message collector is
     * still running, the seam is intact, and every co-member is already waiting for a `Freeze` on it —
     * so they ack the freeze round immediately and the session forms with no re-discovery.
     *
     * Two obvious alternatives are wrong, and both lose the co-members:
     * - **Re-running `electLobby(...)` weaves a *fresh* seam.** The co-members are still waiting on the
     *   old one. Nobody ever freezes them, and the promoted peer ends up hosting an empty lobby.
     * - **Calling [ElectionLobby.leave] first closes the shared seam,** collapsing every co-member that
     *   was waiting on it into a [Torn] of their own.
     *
     * Not emitted for the **weave-in transient** — this peer being momentarily the lowest id it has
     * seen, before a lower peer propagates. That is not a promotion, and acting on it would start a
     * session the real host is about to freeze anyway.
     */
    public data object BecameHost : ElectionOutcome
}

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
     * from [start] instead.)
     *
     * Ends one of three ways — see [ElectionOutcome]. [ElectionOutcome.Adopted] is the session
     * starting; [ElectionOutcome.Torn] is a mid-2PC collapse; [ElectionOutcome.BecameHost] means the
     * hosting peer left and **this** peer is now the elected host, whose recovery is [start] on this
     * same lobby. All three are terminal and reached within a bound: [awaitRoom] never suspends
     * forever on a mid-2PC peer-set collapse.
     *
     * It **does** suspend indefinitely while the lobby is simply empty or still weaving in — waiting
     * for peers to show up is what a lobby is for, and is not a collapse. Cancel it to stop waiting.
     */
    public suspend fun awaitRoom(memberName: String? = null): ElectionOutcome

    /** Leave the lobby, closing the underlying seam — unless a [Room] has already adopted it. Idempotent. */
    public suspend fun leave()
}
