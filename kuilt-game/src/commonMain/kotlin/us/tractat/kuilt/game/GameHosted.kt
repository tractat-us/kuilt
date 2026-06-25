package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.gossip.hostedOverlay
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftStorage
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Host a turn-based game over a [ConnectionSource]: compose a star hub from accepted connections
 * ([hostedOverlay], [us.tractat.kuilt.gossip.ActiveViewPolicy.FullFanout]) and run [gameHost] on
 * it. Clients connect as usual — they call [gameJoin] over a `KtorClientLoom` seam, unchanged.
 *
 * This is thin sugar over `hostedOverlay + gameHost`; advanced callers who need to interpose on
 * the hub seam (logging, principal extraction, metering) call [hostedOverlay] directly and pass
 * the resulting [us.tractat.kuilt.core.Seam] to [gameHost].
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
 * @param clock Clock for heartbeat measurements. Tests inject a controllable clock.
 * @param identity How the hub obtains its Raft §8 dedup id. See [gameHost].
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
    clock: () -> Instant = { Clock.System.now() },
    identity: ClientIdentity = ClientIdentity.Auto,
): GameSession {
    val dispatcher = coroutineContext[ContinuationInterceptor]!!
    val overlay = hostedOverlay(selfId, source, dispatcher, random, clock)
    return gameHost(
        seam = overlay,
        peerCount = peerCount,
        returnAt = returnAt,
        storage = storage,
        raftConfig = raftConfig,
        livenessConfig = livenessConfig,
        clock = clock,
        identity = identity,
    )
}
