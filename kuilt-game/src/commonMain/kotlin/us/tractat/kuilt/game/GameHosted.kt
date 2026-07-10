package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.fabric.LinkAdmission
import us.tractat.kuilt.gossip.hostedMesh
import us.tractat.kuilt.gossip.starOverlay
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftStorage
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Instant

/**
 * Host a turn-based game over a [ConnectionSource]: accept-pump a raw hub mesh
 * ([us.tractat.kuilt.gossip.hostedMesh]) and run [gameHost] on it with a star-relay
 * ([us.tractat.kuilt.gossip.starOverlay], [us.tractat.kuilt.gossip.FullFanout]) `overlay`. Clients
 * connect as usual — they call [gameJoin] over a `KtorClientLoom` seam, unchanged.
 *
 * This is thin sugar over `hostedMesh + gameHost(overlay = { starOverlay(…) })`. Advanced callers
 * who need to interpose on the hub seam (logging, principal extraction, metering) call
 * [us.tractat.kuilt.gossip.hostedMesh] directly and pass the resulting [us.tractat.kuilt.core.Seam]
 * as `gameHost(seam = mesh, overlay = { starOverlay(it, …) })`.
 *
 * **Migration trap (#1370).** Do **not** interpose via the older
 * `gameHost(seam = hostedOverlay(...))` shape — that wraps the whole session mux, Raft included,
 * *above* the gossip flood, so the overlay's origin-restamping can launder a forged consensus
 * frame. The commit-safe shape passes the **raw** [us.tractat.kuilt.gossip.hostedMesh] as `seam` and
 * the flood as `overlay`, keeping Raft and heartbeat below it.
 *
 * @param selfId Identity the hub uses on the overlay mesh.
 * @param source The front door: one [us.tractat.kuilt.core.fabric.Connection] per accepted peer.
 *   On a real server this is `KtorConnectionSource(application, "/game")`; in tests it is
 *   [us.tractat.kuilt.test.fabric.InMemoryConnectionSource].
 * @param peerCount Total number of voters (including the hub) the cluster must reach.
 * @param returnAt When to return the leader — [ReturnPolicy.FullMembership] (default) or
 *   [ReturnPolicy.Quorum]. See [ReturnPolicy].
 * @param storage Durable Raft state. Defaults to [InMemoryRaftStorage].
 * @param raftConfig Timing and behaviour parameters. Tests pass
 *   `RaftConfig(expectVirtualTime = true)` (the only supported virtual-time path).
 * @param livenessConfig Optional per-voter heartbeat monitoring. When non-null, lost voters are
 *   evicted and re-admitted automatically. See [gameHost] for details.
 * @param random RNG for gossip jitter and overlay bookkeeping. Production uses [Random.Default];
 *   tests inject a seeded instance for deterministic virtual-time execution.
 * @param clock Clock for heartbeat measurements. **Required** — no wall-clock default (the same
 *   "optional ≠ tuning" convention `gameHost` and `clusterClient` follow). Fed unconditionally
 *   into the overlay [hostedOverlay] as well as [gameHost], so it is always live here. Production
 *   callers pass `{ kotlin.time.Clock.System.now() }`; tests inject a controllable clock.
 * @param identity How the hub obtains its Raft §8 dedup id. See [gameHost].
 * @param admission Per-link admission policy for the hub's accepted connections, enforced at the
 *   overlay mesh between each spoke's `MeshHello` handshake and its publication (see
 *   [LinkAdmission]). Defaults to [LinkAdmission.AcceptAll] — open, today's behaviour. Once
 *   supplied, the policy is authoritative for **every** spoke, including unattested ones. Pair it
 *   with a principal-extracting [source] (a `KtorConnectionSource` `principalExtractor`) so the
 *   policy sees verified identities; admitted principals are observable via
 *   [GameSession.attestedPrincipals].
 * @param placement How this session obtains its consensus node — forwarded to [gameHost]. The
 *   default [ConsensusPlacement.SessionOwned] is today's behaviour; must seat
 *   [AuthoritySeating.SessionPeers] (see [gameHost]).
 */
public suspend fun CoroutineScope.gameHosted(
    selfId: PeerId,
    source: ConnectionSource,
    peerCount: Int,
    returnAt: ReturnPolicy = ReturnPolicy.FullMembership,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    livenessConfig: HeartbeatConfig? = null,
    random: Random = Random.Default,
    clock: () -> Instant,
    identity: ClientIdentity = ClientIdentity.Auto,
    admission: LinkAdmission = LinkAdmission.AcceptAll,
    placement: ConsensusPlacement = ConsensusPlacement.SessionOwned,
): GameSession {
    val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
        "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
    }
    return gameHost(
        seam = hostedMesh(selfId, source, dispatcher, admission),
        peerCount = peerCount,
        returnAt = returnAt,
        storage = storage,
        raftConfig = raftConfig,
        livenessConfig = livenessConfig,
        clock = clock,
        identity = identity,
        placement = placement,
        overlay = { starOverlay(it, random, clock) },
    )
}
