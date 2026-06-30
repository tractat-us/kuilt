package us.tractat.kuilt.game

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.MembershipChangeInProgressException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** MuxSeam channel tag for the Raft consensus traffic in [gameHost] and [gameJoin]. */
private const val RAFT_CHANNEL: Byte = 1

/**
 * MuxSeam channel tag for the lobby presence traffic in [gameHost].
 *
 * [gameJoin] does not subscribe to this channel — presence frames destined for a joiner's
 * presence channel are silently discarded by the unsubscribed [MuxSeam] view.
 */
private const val PRESENCE_CHANNEL: Byte = 2

/**
 * MuxSeam channel tag carrying the application-envelope [NamedMux] in every bootstrap path.
 *
 * Named application channels ([GameSession.appChannel]) are nested as a [NamedMux] under this
 * single reserved tag, so the app wire-layout (`[3][len][name]…`) is identical across
 * [gameNode], [gameHost], and [gameJoin]. The mux's 256-tag ceiling bounds only internal
 * channels (raft/presence/app-envelope); application channels live in the unbounded [NamedMux]
 * name namespace, never on a reserved tag.
 */
private const val APP_ENVELOPE_CHANNEL: Byte = 3

/**
 * MuxSeam channel tag for heartbeat ping/pong frames used by [HeartbeatPartitionDetector]
 * in voter liveness monitoring.
 *
 * One [HeartbeatPartitionDetector] per remote voter shares this channel (distinguishing peers
 * by [PeerId] in its internal filtering). Heartbeat frames never reach the application layer —
 * they are consumed by the detectors' inner collection loops, which subscribe to the per-peer
 * [GamePerPeerSeam] views that filter the channel's shared incoming flow.
 */
private const val HEARTBEAT_CHANNEL: Byte = 4

/**
 * A thin [Seam] adapter that presents only frames from [targetPeerId] via [rawShared].
 *
 * Analogous to `PerPeerSeam` in [kuilt-session][us.tractat.kuilt.session.SeamRoom]: because
 * [Seam.incoming] is single-collection (ADR-034), [gameHost] fans the liveness channel's
 * incoming stream into a [MutableSharedFlow] and each [HeartbeatPartitionDetector] subscribes
 * to a filtered view via this class — one instance per monitored voter.
 *
 * [broadcast] and [sendTo] delegate to [delegate] unchanged so the detector can still send
 * ping frames directly. [close] is a no-op — lifecycle is owned by [gameHost], not this view.
 */
private class GamePerPeerSeam(
    private val delegate: Seam,
    private val targetPeerId: PeerId,
    private val rawShared: MutableSharedFlow<Swatch>,
) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state
    override val incoming: Flow<Swatch> get() = rawShared.filter { it.sender == targetPeerId }
    override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = delegate.sendTo(peer, payload)
    override suspend fun close(reason: CloseReason): Unit = Unit
}

/**
 * Thrown by [gameHost] when another peer on the same session has already declared itself host.
 *
 * Exactly one peer per session must call [gameHost]; a duplicate declaration is always a
 * programming error on unarbitrated fabrics. The exception is thrown *before* any Raft
 * bootstrap so the conflicting peer fails fast rather than entering an inconsistent state.
 */
public class DuplicateHostException(
    message: String = "another peer already declared host for this session — exactly one gameHost per session is required",
) : IllegalStateException(message)

/**
 * Thrown by [gameJoin] when the host signals that admission is closed and this peer is not
 * in the final voter set.
 *
 * This is the roster-full case: the host's admission loop reached `peerCount` and exited before
 * this peer called [gameJoin]. There are no available seats. The caller should surface this as a
 * user-visible error (e.g. "game is full") rather than retrying — the roster is a committed Raft
 * cluster config and cannot be grown without the host's cooperation.
 */
public class RosterFullException(
    message: String = "the game roster is full — all seats were filled before this peer joined",
) : IllegalStateException(message)

/**
 * Thrown by [gameJoin] when the host never signals admission (neither admits nor closes the roster)
 * within the [gameJoin] `joinAdmissionTimeout` bound.
 *
 * Distinct from [RosterFullException] so callers can diagnose "host gone / crashed mid-handshake"
 * vs "roster was full" without inspecting the message string. On timeout, the caller may retry
 * [gameJoin] (the host may still be starting up) or surface a connectivity-problem message.
 */
public class JoinTimeoutException(
    message: String = "timed out waiting for the host to admit or reject this peer",
) : IllegalStateException(message)

/**
 * Thrown by [gameSpectate] when the host's spectator gallery is closed — either because
 * the host did not enable spectators (`allowSpectators = false`, the default) or because
 * [maxSpectators] has already been reached.
 *
 * Distinct from [SpectateTimeoutException] so callers can diagnose "spectators are disabled
 * or the gallery is full" vs "host gone / crashed mid-handshake". Surface this as a
 * user-visible error (e.g. "spectating is not allowed for this game") rather than retrying.
 */
public class SpectatorsClosedException(
    message: String = "spectators are not allowed or the spectator cap has been reached",
) : IllegalStateException(message)

/**
 * Thrown by [gameSpectate] when the host never signals spectator admission or rejection
 * within the [gameSpectate] `spectateAdmissionTimeout` bound.
 *
 * Distinct from [SpectatorsClosedException] so callers can diagnose "host gone / crashed
 * mid-handshake" from "spectators are disabled / gallery full". On timeout, the caller may
 * retry [gameSpectate] (the host may still be starting up) or surface a connectivity error.
 */
public class SpectateTimeoutException(
    message: String = "timed out waiting for the host to admit or reject this spectator",
) : IllegalStateException(message)

/**
 * Controls when [gameHost] returns the leader [RaftNode] to the caller.
 *
 * - [FullMembership] (the default) suspends until **all** `peerCount` voters have joined; the
 *   leader is returned only once the roster is complete. This is the original bootstrap behaviour.
 * - [Quorum] returns as soon as a **majority** of `peerCount` voters are present
 *   (`peerCount / 2 + 1`), so the game can start without blocking on the slowest or absent peer.
 *   [gameHost] then keeps admitting the remaining ("latecomer") voters in the background on the
 *   caller's scope for the life of the session — the admission door stays open until the roster
 *   reaches `peerCount`, so a latecomer joins and is promoted *whenever* it connects, however late.
 *   Its `gameJoin` only ever suspends in the normal learner-waiting-for-promotion sense, which
 *   always resolves; there is no window after which a join is silently dead. (A peer connecting
 *   once the roster is already full is the separate, deferred concern in #587.)
 *
 * This is an explicit functional mode — which path runs decides whether the host blocks on full
 * membership — so it is a named enum rather than a boolean flag (per the optional≠tuning convention).
 */
public enum class ReturnPolicy { FullMembership, Quorum }

/**
 * Constructs a [RaftNode] over [seam] for a session whose full voter roster is
 * already known to every peer (e.g. from matchmaking). Every peer builds the
 * identical [ClusterConfig.ofVoters] and Raft's own election picks the leader —
 * symmetric, no pre-Raft coordination step required.
 *
 * This is the *roster-given* bootstrap path: call it when every participating
 * peer's identity is known before the session starts. For the *appoint-the-host*
 * path (dynamic join without a fixed roster), see `gameHost`/`gameJoin`.
 *
 * **Internal multiplexing.** [gameNode] wraps [seam] in a [MuxSeam] so Raft traffic (channel
 * tag 1) and the application-envelope [NamedMux] (channel tag 3) share the one underlying
 * [Seam]. This costs Raft one extra tag byte per frame versus an unmuxed seam — the accepted
 * price of a uniform [GameSession] return type and ride-along app channels across all three
 * bootstrap paths. Drive consensus through [GameSession.node]; ride extra traffic over
 * [GameSession.appChannel].
 *
 * **Do not collect `seam.incoming` after calling this.** Once the returned
 * [GameSession] is running, the internal [MuxSeam] is the sole consumer of
 * `seam.incoming` (ADR-034 single-collection). A second collector races the
 * Raft engine and drops messages, causing silent liveness failures.
 *
 * @param seam The [Seam] connecting this peer to the rest of the cluster.
 *   This peer's identity ([Seam.selfId]) must appear in [voterIds].
 * @param voterIds The full set of voter [NodeId]s for the cluster. Every peer
 *   must pass the same set; Raft's election then picks one leader symmetrically.
 * @param storage Durable Raft state (term, vote, log). Defaults to
 *   [InMemoryRaftStorage] (non-durable, suitable for short-lived sessions or
 *   tests). Inject a persistent implementation for crash-recovery.
 * @param raftConfig Timing and behaviour parameters. Production callers use the
 *   default [RaftConfig] (real-clock, `expectVirtualTime = false`). Tests pass
 *   `RaftConfig(expectVirtualTime = true)` — this is the *only* supported path
 *   to virtual-time execution; `gameNode` deliberately does not expose
 *   `expectVirtualTime` as its own parameter (D4).
 * @param identity How this peer obtains its Raft §8 dedup id. [ClientIdentity.Auto] (default) mints
 *   a per-incarnation auto id (at-least-once forwarding, no cross-crash dedup). A **durable** peer
 *   passes [ClientIdentity.Durable] with a stable id it persists itself and replays the same
 *   `requestId` on [TurnSequencer.propose] after a restart. See [us.tractat.kuilt.raft.ClientSessionTable].
 *
 * @throws IllegalArgumentException if this peer's [NodeId] is not in [voterIds].
 *
 * @sample us.tractat.kuilt.game.sampleGameNode
 */
public fun CoroutineScope.gameNode(
    seam: Seam,
    voterIds: Set<NodeId>,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    identity: ClientIdentity = ClientIdentity.Auto,
): GameSession {
    require(NodeId(seam.selfId.value) in voterIds) {
        "this peer (${seam.selfId.value}) must be in voterIds $voterIds"
    }
    val mux = MuxSeam(seam, this)
    val node = raftNode(ClusterConfig.ofVoters(voterIds), SeamRaftTransport(mux.channel(RAFT_CHANNEL)), storage, raftConfig, identity)
    val appMux = NamedMux(mux.channel(APP_ENVELOPE_CHANNEL), this)
    return GameSession(node, seam, appMux)
}

/**
 * Host a game session over [seam]: check for duplicate hosts, bootstrap a
 * singleton-voter cluster, then admit each connecting peer as learner→voter until
 * the cluster reaches the [returnAt] watermark, then returns a [GameSession] whose
 * [GameSession.node] is the leader.
 *
 * **Return policy.** With the default [ReturnPolicy.FullMembership], [gameHost] suspends until all
 * [peerCount] voters have joined before returning. With [ReturnPolicy.Quorum] it returns as soon as
 * a majority (`peerCount / 2 + 1`) are present and continues admitting the remaining voters in the
 * background — see [ReturnPolicy].
 *
 * **Precondition: exactly one `gameHost` per session.** This function detects a
 * duplicate host via lobby presence before bootstrapping Raft and throws
 * [DuplicateHostException] if another peer already declared itself host.
 *
 * **Deterministic duplicate-host arbitration.** The check declares this peer as host on the
 * lobby presence channel, then waits — bounded by [hostDeclarationTimeout] — until presence
 * has converged with every peer connected at that moment (each peer, host or joiner, announces
 * itself), and only then inspects the declared-host set. This replaces an earlier fixed 1 ms
 * window that on a real fabric elapsed before any network round-trip, silently passing two
 * concurrent hosts. When the converged set holds more than one declared host, the lowest-NodeId
 * declarant is elected the canonical host and proceeds; every other declared host throws
 * [DuplicateHostException]. Because every peer has converged on the same set, they independently
 * agree on the winner with no extra round-trip — a *genuinely* simultaneous race therefore
 * resolves to exactly one host instead of collapsing the session (the earlier behaviour, where
 * every declarant threw). Exactly-one-`gameHost` remains the caller's precondition: a losing
 * host still fails loud, and if it was needed to reach [peerCount] the winner blocks on it like
 * any missing peer.
 *
 * **Internal multiplexing.** [gameHost] wraps [seam] in a [MuxSeam] so that Raft
 * traffic (channel tag 1), lobby presence traffic (channel tag 2), and the application-envelope
 * [NamedMux] (channel tag 3) share the single underlying [Seam] without violating the ADR-034
 * single-collection contract. [gameJoin] applies the same tags so both sides communicate on
 * matching frames. The caller passes a plain [Seam] in both cases — muxing is an internal
 * implementation detail; ride extra application traffic over [GameSession.appChannel].
 *
 * @param peerCount Total number of voters (including the host) the cluster must reach.
 * @param returnAt When to return the leader [RaftNode] — at [ReturnPolicy.FullMembership] (default)
 *   or at [ReturnPolicy.Quorum]. See [ReturnPolicy]. In [ReturnPolicy.Quorum] mode the remaining
 *   voters are admitted in the background, on this scope, for the life of the session — the
 *   admission door stays open until the roster reaches [peerCount], so a latecomer joins whenever
 *   it connects, however late. A peer arriving once the roster is already full is the separate,
 *   deferred concern in #587.
 * @param allowSpectators Whether to admit peers that call [gameSpectate]. Disabled by default —
 *   a [gameSpectate] call when spectators are off is rejected immediately with
 *   [SpectatorsClosedException] rather than hanging. Spectators are permanent non-voting learners;
 *   see [gameSpectate].
 * @param maxSpectators Maximum number of spectators to admit. Ignored when [allowSpectators] is
 *   `false`. Once this cap is reached, additional [gameSpectate] calls throw
 *   [SpectatorsClosedException]. Must be ≥ 0.
 * @param storage Durable Raft state. Defaults to [InMemoryRaftStorage].
 * @param raftConfig Timing and behaviour parameters. Tests pass
 *   `RaftConfig(expectVirtualTime = true)` — this is the only supported path to
 *   virtual-time execution (D4).
 * @param livenessConfig Optional configuration for per-voter [HeartbeatPartitionDetector]s.
 *   When non-null, [gameHost] launches one detector per admitted voter and — on the leader —
 *   automatically evicts a voter whose [HeartbeatConfig.reconnectWindow] expires without
 *   recovery ([PartitionEvent.PeerLost]), then re-opens the admission loop so a replacement
 *   can join. When null (the default) no liveness monitoring is performed; callers that need
 *   automatic seat reclamation must pass a [HeartbeatConfig]. This is an explicit opt-in
 *   because the feature carries observable timing state; omitting it for sessions that have
 *   their own membership management (e.g. `gameNode`) is a supported use case.
 * @param clock Clock for heartbeat liveness measurements. Production callers use the default
 *   ([kotlin.time.Clock.System.now]); tests inject a controllable clock so virtual time drives
 *   all timing without wall-clock dependency. Ignored when [livenessConfig] is null.
 * @param hostDeclarationTimeout Upper bound on the presence-convergence wait before the
 *   duplicate-host check proceeds regardless. This is genuine tuning, not a functional
 *   switch: a connected-but-silent peer (or a real fabric whose round-trip exceeds the
 *   bound) only weakens detection, never disables the host. The default is sized to clear a
 *   typical WAN round-trip; raise it on high-latency fabrics, lower it where joiners are
 *   known-local.
 * @param identity How this host obtains its Raft §8 dedup id. [ClientIdentity.Auto] (default) mints
 *   a per-incarnation auto id (at-least-once forwarding, no cross-crash dedup). A **durable** host
 *   passes [ClientIdentity.Durable] with a stable id it persists itself and replays the same
 *   `requestId` on [TurnSequencer.propose] after a restart. See [us.tractat.kuilt.raft.ClientSessionTable].
 * @throws IllegalArgumentException if [peerCount] < 1 or [maxSpectators] < 0.
 * @throws DuplicateHostException if another peer on the same session already declared host.
 *
 * @sample us.tractat.kuilt.game.sampleGameHostJoin
 */
public suspend fun CoroutineScope.gameHost(
    seam: Seam,
    peerCount: Int,
    returnAt: ReturnPolicy = ReturnPolicy.FullMembership,
    allowSpectators: Boolean = false,
    maxSpectators: Int = 0,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    livenessConfig: HeartbeatConfig? = null,
    clock: () -> Instant = { Clock.System.now() },
    hostDeclarationTimeout: Duration = DEFAULT_HOST_DECLARATION_TIMEOUT,
    identity: ClientIdentity = ClientIdentity.Auto,
): GameSession {
    require(peerCount >= 1) { "peerCount must be >= 1" }
    require(maxSpectators >= 0) { "maxSpectators must be >= 0" }

    val mux = MuxSeam(seam, this)
    val raftSeam = mux.channel(RAFT_CHANNEL)
    val presenceSeam = mux.channel(PRESENCE_CHANNEL)
    val appMux = NamedMux(mux.channel(APP_ENVELOPE_CHANNEL), this)

    val presence = checkNotDuplicateHost(presenceSeam, this, raftConfig.expectVirtualTime, hostDeclarationTimeout)

    val self = NodeId(seam.selfId.value)
    val node = raftNode(ClusterConfig.ofVoters(setOf(self)), SeamRaftTransport(raftSeam), storage, raftConfig, identity)
    node.awaitLeadership()

    // Admit synchronously up to the return watermark: the full roster in FullMembership mode, a
    // majority in Quorum mode.
    val returnThreshold = when (returnAt) {
        ReturnPolicy.FullMembership -> peerCount
        ReturnPolicy.Quorum -> peerCount / 2 + 1
    }
    val voters = mutableSetOf(self)
    // spectatorIds tracks NodeIds admitted as spectators so the voter loop skips them.
    val spectatorIds = mutableSetOf<NodeId>()
    admitVotersUntil(node, seam, voters, spectatorIds, target = returnThreshold, presence)

    // Quorum mode: keep admitting the remaining voters in the background for the life of the
    // session. The loop runs on the caller's scope (this), so it stays alive until the roster
    // reaches peerCount or the scope is cancelled at session end — the admission door does not close
    // on a timer, so a latecomer is admitted whenever it connects, however late. A give-up failure
    // propagates to the caller (fail-loud, no swallow); cancellation tears admission down. After
    // this point only the background coroutine touches `voters`, so no synchronization is needed.
    if (voters.size < peerCount) {
        launch {
            admitVotersUntil(node, seam, voters, spectatorIds, target = peerCount, presence)
            presence.declareAdmissionClosed(voters)
        }
    } else {
        // FullMembership mode (or Quorum with peerCount == 1): the roster is already full.
        // Publish the signal synchronously so any joiner that arrives after this point can
        // detect the full roster immediately without waiting for the background loop.
        presence.declareAdmissionClosed(voters)
    }

    // Reactive spectator management — runs persistently in the background. On every new spectator
    // declaration:
    //   - If spectators are enabled and the cap is not yet reached → admit the peer as a permanent
    //     learner.
    //   - Otherwise → publish spectators-closed and exit.
    //
    // Rejection is REACTIVE, not proactive: the `:sc` signal is only published AFTER a would-be
    // spectator has declared, guaranteeing that its Quilter is already subscribed and will receive
    // the rejection Delta. Publishing `:sc` eagerly (before any declaration) would race against the
    // peer's MuxSeam setup and silently lose the frame under StandardTestDispatcher.
    launch {
        var admitted = spectatorIds.size
        while (true) {
            val spectatorId = nextSpectatorPeer(presence, seam, spectatorIds)
            if (!allowSpectators || admitted >= maxSpectators) {
                presence.declareSpectatorsClosed()
                break
            }
            admitSpectatorLearner(node, voters, spectatorIds, spectatorId)
            spectatorIds += spectatorId
            admitted++
        }
    }

    // Voter liveness monitoring — optional; enabled when the caller supplies a [livenessConfig].
    // The leader observes PeerLost events and evicts the dead voter, then re-opens the admission
    // loop for a replacement. Graceful leave (vacate signal) is also handled here.
    if (livenessConfig != null) {
        monitorVoterLiveness(node, seam, mux, voters, spectatorIds, peerCount, presence, livenessConfig, clock)
    }

    return GameSession(node, seam, appMux, presence)
}

/**
 * Join a game session over [seam] hosted by exactly one [gameHost]. Starts as a non-voting
 * learner and waits until the host admits this peer as a voter. Returns the local [GameSession]
 * (its [GameSession.node] is the admitted follower) once admitted.
 *
 * **Roster-full detection.** If the host has already filled all `peerCount` seats and published
 * an admission-closed signal, [gameJoin] throws [RosterFullException] instead of suspending
 * indefinitely. This is the host-authoritative path: the signal travels over the same presence
 * channel as the duplicate-host check, so it converges deterministically under virtual time.
 *
 * **Backstop timeout.** If neither admission nor an admission-closed signal arrives within
 * [joinAdmissionTimeout], [gameJoin] throws [JoinTimeoutException] — distinct from
 * [RosterFullException] so callers can tell "host gone / crashed" from "roster was full". The
 * backstop is a fallback for real-fabric scenarios (host crashed mid-handshake, network partition);
 * the structural signal is the primary path. Under virtual time the backstop fires promptly.
 *
 * **Internal multiplexing.** [gameJoin] wraps [seam] in a [MuxSeam] and routes Raft traffic
 * on channel tag 1, lobby presence on channel tag 2, and the application-envelope [NamedMux]
 * on channel tag 3 — matching [gameHost]'s channels — so both sides communicate on compatible
 * frames. The caller passes a plain [Seam]; muxing is internal. Ride extra application traffic
 * over [GameSession.appChannel].
 *
 * **Presence announcement.** Before awaiting admission, this peer announces itself as a
 * non-host participant on the presence channel. This is what lets [gameHost]'s
 * duplicate-host convergence wait observe contact with each connected joiner instead of
 * blocking on it until the timeout elapses.
 *
 * **Do not collect `seam.incoming` after calling this** (ADR-034 single-collection).
 *
 * @param storage Durable Raft state. Defaults to [InMemoryRaftStorage].
 * @param raftConfig Timing and behaviour parameters. Tests pass
 *   `RaftConfig(expectVirtualTime = true)` (D4).
 * @param joinAdmissionTimeout Upper bound on waiting for the host to either admit this peer or
 *   signal admission-closed. If this bound expires before either signal arrives, [gameJoin]
 *   throws [JoinTimeoutException]. The default is sized to clear a typical WAN round-trip and
 *   a full Raft election cycle; lower it in test scenarios where you want the backstop to fire
 *   quickly under virtual time.
 * @param identity How this joiner obtains its Raft §8 dedup id. [ClientIdentity.Auto] (default)
 *   mints a per-incarnation auto id (at-least-once forwarding, no cross-crash dedup). A **durable**
 *   joiner passes [ClientIdentity.Durable] with a stable id it persists itself and replays the same
 *   `requestId` on [TurnSequencer.propose] after a restart. See [us.tractat.kuilt.raft.ClientSessionTable].
 * @throws RosterFullException if the host has already filled all seats and this peer is not in
 *   the final voter set.
 * @throws JoinTimeoutException if neither admission nor a roster-full signal arrives within
 *   [joinAdmissionTimeout].
 *
 * @sample us.tractat.kuilt.game.sampleGameHostJoin
 */
public suspend fun CoroutineScope.gameJoin(
    seam: Seam,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    joinAdmissionTimeout: Duration = DEFAULT_JOIN_ADMISSION_TIMEOUT,
    identity: ClientIdentity = ClientIdentity.Auto,
): GameSession {
    val mux = MuxSeam(seam, this)
    val raftSeam = mux.channel(RAFT_CHANNEL)
    val presenceSeam = mux.channel(PRESENCE_CHANNEL)
    val appMux = NamedMux(mux.channel(APP_ENVELOPE_CHANNEL), this)

    val self = NodeId(seam.selfId.value)

    // Announce presence (non-host) so the host's convergence wait sees us promptly.
    val presence = GamePresence(presenceSeam, this, raftConfig.expectVirtualTime)
    presence.declarePresent()

    // Start as a learner with no known voters. The host's changeMembership will commit a
    // config that promotes us to voter; recomputeMembership then transitions role to Follower.
    val node = raftNode(
        ClusterConfig(voters = emptySet(), learners = setOf(self)),
        SeamRaftTransport(raftSeam),
        storage,
        raftConfig,
        identity,
    )

    awaitAdmissionOrThrow(node, presence, self, joinAdmissionTimeout)
    return GameSession(node, seam, appMux, presence)
}

/**
 * Join a game session over [seam] as a **permanent, non-voting spectator learner**.
 *
 * A spectator receives the full committed log (log replication + chunked InstallSnapshot)
 * and follows the game live, but **never votes** and **never counts toward quorum**. The
 * session's voter quorum is unaffected by how many spectators are present — two voters can
 * still commit with a spectator watching.
 *
 * **Host opt-in required.** The host must call [gameHost] with `allowSpectators = true` and
 * `maxSpectators >= 1`. If spectators are disabled (the default) or the cap is already reached,
 * [gameSpectate] throws [SpectatorsClosedException] immediately — never a silent hang.
 *
 * **Permanent role — no promotion.** A spectator's [GameSession.node] role is permanently
 * [RaftRole.Learner]. There is no promotion-to-voter path in this entry point; that is a
 * separate concern (see issue #594).
 *
 * **Internal multiplexing.** [gameSpectate] wraps [seam] in a [MuxSeam] and routes Raft
 * traffic on channel tag 1, lobby presence on channel tag 2, and the application-envelope
 * [NamedMux] on channel tag 3 — matching [gameHost]'s channels. The caller passes a plain
 * [Seam]; muxing is internal. Ride extra application traffic over [GameSession.appChannel].
 *
 * **Do not collect `seam.incoming` after calling this** (ADR-034 single-collection).
 *
 * @param storage Durable Raft state. Defaults to [InMemoryRaftStorage].
 * @param raftConfig Timing and behaviour parameters. Tests pass
 *   `RaftConfig(expectVirtualTime = true)` (D4).
 * @param spectateAdmissionTimeout Upper bound on waiting for the host to either admit this
 *   spectator or signal spectators-closed. If this bound expires before either signal arrives,
 *   [gameSpectate] throws [SpectateTimeoutException]. The default is sized to clear a typical
 *   WAN round-trip; lower it in tests where you want the backstop to fire quickly.
 * @param identity How this spectator obtains its Raft §8 dedup id. [ClientIdentity.Auto] (default)
 *   mints a per-incarnation auto id. A spectator never proposes, so this is rarely needed; accepted
 *   for symmetry with the other bootstrap paths. See [us.tractat.kuilt.raft.ClientSessionTable].
 * @throws SpectatorsClosedException if the host has spectators disabled or the cap is full.
 * @throws SpectateTimeoutException if neither admission nor a spectators-closed signal arrives
 *   within [spectateAdmissionTimeout].
 */
public suspend fun CoroutineScope.gameSpectate(
    seam: Seam,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    spectateAdmissionTimeout: Duration = DEFAULT_SPECTATE_ADMISSION_TIMEOUT,
    identity: ClientIdentity = ClientIdentity.Auto,
): GameSession {
    val mux = MuxSeam(seam, this)
    val raftSeam = mux.channel(RAFT_CHANNEL)
    val presenceSeam = mux.channel(PRESENCE_CHANNEL)
    val appMux = NamedMux(mux.channel(APP_ENVELOPE_CHANNEL), this)

    val self = NodeId(seam.selfId.value)

    // Announce spectate intent so the host can enumerate spectator-intending replicas.
    val presence = GamePresence(presenceSeam, this, raftConfig.expectVirtualTime)
    presence.declareSpectate()

    // Start as a learner with no known voters. The host will commit a config that includes
    // this peer in the learners set; the spectator never leaves Learner role.
    val node = raftNode(
        ClusterConfig(voters = emptySet(), learners = setOf(self)),
        SeamRaftTransport(raftSeam),
        storage,
        raftConfig,
        identity,
    )

    awaitSpectatorAdmissionOrThrow(node, presence, spectateAdmissionTimeout)
    return GameSession(node, seam, appMux)
}

/**
 * Races spectator admission against the spectators-closed signal, with a backstop timeout.
 *
 * Returns normally when the node's [RaftNode.commitIndex] advances past zero — this is the
 * first AppendEntries from the host after the spectator has been added to the cluster config.
 * Throws [SpectatorsClosedException] if [presence] signals spectators-closed.
 * Throws [SpectateTimeoutException] if [timeout] elapses before either signal arrives.
 *
 * **Why `commitIndex > 0` signals admission:** a spectator node starts in a vacuous cluster
 * (`voters = {}`) and receives no AppendEntries until the host's leader commits a membership
 * change that adds the spectator to its learner set. The first replication the spectator
 * receives advances its `commitIndex` beyond zero. Config entries are withheld from
 * `_committed` / `committedFrom()`, so role and committed-log observation are not viable
 * signals here — `commitIndex` is.
 */
private suspend fun awaitSpectatorAdmissionOrThrow(
    node: RaftNode,
    presence: GamePresence,
    timeout: Duration,
) {
    val admitted = withTimeoutOrNull(timeout) {
        merge(
            node.commitIndex.asSpectatorAdmissionFlow(),
            presence.spectatorsClosed.asSpectatorRejectionFlow(),
        ).first()
    } ?: throw SpectateTimeoutException()

    if (!admitted) throw SpectatorsClosedException()
}

/**
 * Maps the commit-index flow to a Boolean emission: `true` once the index advances past zero.
 *
 * This is the spectator admission signal: `commitIndex > 0` means the host has committed the
 * membership change that adds this spectator to the cluster learner set and is replicating.
 */
private fun StateFlow<Long>.asSpectatorAdmissionFlow(): Flow<Boolean> = flow {
    first { index -> index > 0L }
    emit(true)
}

/**
 * Maps the spectators-closed flow to a Boolean emission: `false` once the signal is `true`.
 *
 * Emits nothing while spectators are still open.
 */
private fun Flow<Boolean>.asSpectatorRejectionFlow(): Flow<Boolean> = flow {
    first { closed -> closed }
    emit(false)
}

/**
 * Races admission against roster-full signal, with a backstop timeout.
 *
 * Returns normally if [node] leaves [RaftRole.Learner] (admitted). Throws [RosterFullException]
 * if [presence] signals admission-closed and [self] is not in the final voter set. Throws
 * [JoinTimeoutException] if [timeout] elapses before either signal arrives.
 */
private suspend fun awaitAdmissionOrThrow(
    node: RaftNode,
    presence: GamePresence,
    self: NodeId,
    timeout: Duration,
) {
    val admitted = withTimeoutOrNull(timeout) {
        merge(
            node.role.asAdmissionFlow(),
            presence.admissionClosed.asRejectionFlow(self),
        ).first()
    } ?: throw JoinTimeoutException()

    if (!admitted) throw RosterFullException()
}

/**
 * Maps the role flow to a Boolean emission: `true` when this peer leaves [RaftRole.Learner]
 * (i.e. has been admitted as a voter).
 */
private fun Flow<RaftRole>.asAdmissionFlow(): Flow<Boolean> = flow {
    first { it !is RaftRole.Learner }
    emit(true)
}

/**
 * Maps the admission-closed flow to a Boolean emission: `false` when admission is closed and
 * [self] is not in the final voter set (i.e. this peer is rejected).
 *
 * Emits nothing if `self` IS in the final voter set — the admission flow wins in that case.
 */
private fun Flow<Set<NodeId>?>.asRejectionFlow(self: NodeId): Flow<Boolean> = flow {
    val closedVoters = first { voters -> voters != null && self !in voters }
    if (closedVoters != null) emit(false)
}

/**
 * Declares this peer as host via [GamePresence] on [presenceSeam], waits — bounded by
 * [timeout] — until presence has converged with every peer connected at declaration time,
 * then arbitrates duplicate declarations by lowest NodeId.
 *
 * The convergence signal is honest under both clocks: it suspends until [GamePresence.announced]
 * contains an entry for every currently-connected peer (each peer announces itself — host via
 * `declareHost`, joiner via `declarePresent`), so the check inspects a genuinely-exchanged view
 * rather than a fixed time window. Under virtual time this completes as soon as the queued
 * Quilter coroutines deliver; under real time it waits for the actual round-trip. If a connected
 * peer never announces (e.g. it crashed mid-handshake, or a real-fabric round-trip exceeds
 * [timeout]), the wait falls through after [timeout] and the check proceeds on whatever has
 * converged — weaker detection, never a stuck host.
 *
 * When more than one host has declared, the lowest-NodeId declarant wins (returns normally to
 * proceed as the canonical host) and every other declared host throws (#584). Because every peer
 * converges on the same declared-host set, they agree on the winner without coordination.
 *
 * Returns the [GamePresence] instance so the caller can publish further signals (e.g.
 * [GamePresence.declareAdmissionClosed]) on the same presence channel after this check.
 *
 * @throws DuplicateHostException if another replica with a lower NodeId has also declared host.
 */
private suspend fun checkNotDuplicateHost(
    presenceSeam: Seam,
    scope: CoroutineScope,
    expectVirtualTime: Boolean,
    timeout: Duration,
): GamePresence {
    val presence = GamePresence(presenceSeam, scope, expectVirtualTime)
    presence.declareHost()

    val connectedNow = (presenceSeam.peers.value - presenceSeam.selfId).map { ReplicaId(it.value) }.toSet()
    withTimeoutOrNull(timeout) {
        presence.announced.first { announced -> connectedNow.all { it in announced } }
    }

    val declaredHosts = presence.declaredHosts()
    if (declaredHosts.size <= 1) return presence // this peer is the only declared host — proceed.

    // Deterministic arbitration (#584): every peer has converged on the same declared-host set,
    // so each independently elects the lowest-NodeId declarant as the canonical host with no
    // extra round-trip. The winner proceeds; every other declared host fails fast. This replaces
    // the earlier all-or-nothing behaviour where a simultaneous race left the session with no
    // host at all (every declarant threw).
    val winner = declaredHosts.minByOrNull { it.value }
    if (presence.replica != winner) throw DuplicateHostException()
    return presence
}

/**
 * Default upper bound on [gameHost]'s presence-convergence wait. Sized to clear a typical WAN
 * round-trip; the wait normally returns much sooner, the moment presence has converged with the
 * connected peers. Exposed as the [gameHost] `hostDeclarationTimeout` parameter for tuning.
 */
private val DEFAULT_HOST_DECLARATION_TIMEOUT = 2.seconds

/**
 * Default backstop for [gameJoin]'s admission wait. Sized to clear a typical WAN round-trip
 * plus a full Raft election cycle. The structural signal ([GamePresence.admissionClosed]) is
 * the primary path; this fires only when the host crashes or the network partitions. Exposed
 * as the [gameJoin] `joinAdmissionTimeout` parameter for tuning (lower it in tests).
 */
private val DEFAULT_JOIN_ADMISSION_TIMEOUT = 10.seconds

/**
 * Default backstop for [gameSpectate]'s admission wait. Sized similarly to [gameJoin]'s.
 * Exposed as the [gameSpectate] `spectateAdmissionTimeout` parameter for tuning.
 */
private val DEFAULT_SPECTATE_ADMISSION_TIMEOUT = 10.seconds

/**
 * Admit connecting voter peers as learner→voter until the cluster reaches [target] voters.
 *
 * Drives the shared admit loop used by both the synchronous return-watermark phase and the
 * background latecomer phase of [gameHost]. Mutates [voters] in place as each peer is promoted.
 * Skips any peer whose NodeId appears in [spectatorIds] — those are permanent learners that
 * must not consume a voter seat.
 */
private suspend fun admitVotersUntil(
    node: RaftNode,
    seam: Seam,
    voters: MutableSet<NodeId>,
    spectatorIds: Set<NodeId>,
    target: Int,
    presence: GamePresence,
) {
    while (voters.size < target) {
        val joinerId = nextVoterPeer(seam, voters, spectatorIds, presence)
        admitLearnerThenVoter(node, voters, joinerId)
        voters += joinerId
    }
}

/**
 * Suspends until a voter-intending peer appears in [seam.peers] that is neither in [admitted]
 * nor in [spectatorIds], then returns that peer's [NodeId].
 *
 * Spectator NodeIds are excluded: they are identified by checking [presence.spectators] and
 * mapping to [NodeId]. This ensures a spectator peer connecting to the mesh never consumes
 * a voter seat — the voter loop simply waits past it for the next non-spectator peer.
 */
private suspend fun nextVoterPeer(
    seam: Seam,
    admitted: Set<NodeId>,
    spectatorIds: Set<NodeId>,
    presence: GamePresence,
): NodeId {
    // Combine peers and announced so the exclusion set refreshes whenever either changes:
    // a spectator that declares just before connecting is still excluded.
    return combine(seam.peers, presence.announced) { peerSet, _ ->
        val currentSpectators = presence.spectators().map { NodeId(it.value) }.toSet()
        val allExcluded = admitted + spectatorIds + currentSpectators
        peerSet.map { NodeId(it.value) }.firstOrNull { it !in allExcluded }
    }
        .filterNotNull()
        .first()
}

/**
 * Suspends until a new spectator-intending peer appears in [presence.spectators] whose NodeId
 * is not already in [admitted], then returns that peer's [NodeId].
 *
 * Matches presence declarations to connected peers via [seam.peers] to ensure the declaring
 * replica is actually reachable before trying to admit it.
 */
private suspend fun nextSpectatorPeer(
    presence: GamePresence,
    seam: Seam,
    admitted: Set<NodeId>,
): NodeId {
    // Combine presence announcements with connected peers so the result stays fresh as
    // new spectator declarations arrive or new peers connect.
    return combine(presence.announced, seam.peers) { _, peerSet ->
        val spectatorNodeIds = presence.spectators().map { NodeId(it.value) }.toSet()
        val connectedPeerIds = peerSet.map { NodeId(it.value) }.toSet()
        (spectatorNodeIds intersect connectedPeerIds - admitted).firstOrNull()
    }
        .filterNotNull()
        .first()
}

/** Admit [spectatorId] as a permanent learner (never voter) in the cluster. */
private suspend fun admitSpectatorLearner(
    node: RaftNode,
    currentVoters: Set<NodeId>,
    currentLearners: Set<NodeId>,
    spectatorId: NodeId,
) {
    changeMembershipWithRetry(
        node,
        ClusterConfig(voters = currentVoters, learners = currentLearners + spectatorId),
    )
}

/** Admit [joiner] first as a learner, then promote to voter, using bounded retry. */
private suspend fun admitLearnerThenVoter(
    node: RaftNode,
    currentVoters: Set<NodeId>,
    joiner: NodeId,
) {
    changeMembershipWithRetry(node, ClusterConfig(voters = currentVoters, learners = setOf(joiner)))
    changeMembershipWithRetry(node, ClusterConfig(voters = currentVoters + joiner))
}

/**
 * Calls [RaftNode.changeMembership] with bounded retry on [MembershipChangeInProgressException].
 *
 * Raft serializes membership changes: only one may be in flight at a time. When the prior
 * change is still uncommitted, retrying after a short delay allows it to commit first.
 * Any other exception (including [CancellationException]) propagates immediately.
 */
private suspend fun changeMembershipWithRetry(
    node: RaftNode,
    config: ClusterConfig,
    maxAttempts: Int = 20,
    retryDelay: kotlin.time.Duration = 200.milliseconds,
) {
    repeat(maxAttempts) {
        try {
            node.changeMembership(config)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: MembershipChangeInProgressException) {
            delay(retryDelay)
        }
    }
    error("changeMembership gave up after $maxAttempts attempts for config=$config")
}

// ── Voter liveness monitoring (#594) ─────────────────────────────────────────

/**
 * Launches voter liveness monitoring on the caller's [CoroutineScope].
 *
 * For each currently-admitted voter (excluding self), starts a [HeartbeatPartitionDetector].
 * All detectors share the [HEARTBEAT_CHANNEL] seam for send (pings), but each subscribes to
 * a per-peer filtered view ([GamePerPeerSeam]) of a single shared [MutableSharedFlow], satisfying
 * the ADR-034 single-collection contract.
 *
 * On [PartitionEvent.PeerLost] (leader only): evicts the dead voter via [changeMembershipWithRetry],
 * re-opens admission, runs [admitVotersUntil] for one replacement, then starts a fresh detector for
 * the new voter. Graceful leave ([GamePresence.vacaters]) triggers eviction immediately without
 * waiting the reconnect window.
 *
 * Non-leader nodes receive [PartitionEvent.PeerLost] but take no action — Raft's commit-majority
 * gate means only the leader can commit the membership change.
 *
 * @param node The leader [RaftNode] (may or may not currently hold the leader role).
 * @param seam The game seam (used for [PeerId] extraction and [nextVoterPeer]).
 * @param mux The [MuxSeam] wrapping [seam]; the liveness channel ([HEARTBEAT_CHANNEL]) is a view on it.
 * @param voters The mutable live voter set (shared with the admission loop; mutations are serialised
 *   by the single background eviction coroutine).
 * @param spectatorIds The mutable spectator NodeId set (passed to [ClusterConfig] to preserve learners).
 * @param peerCount Total configured voter count; used to re-open admission to exactly one replacement.
 * @param presence The [GamePresence] instance; used to detect vacaters and re-open/re-close admission.
 * @param config [HeartbeatConfig] driving ping interval, timeout, and reconnect window.
 * @param clock Clock for liveness measurements; injected for virtual-time test determinism.
 */
private fun CoroutineScope.monitorVoterLiveness(
    node: RaftNode,
    seam: Seam,
    mux: MuxSeam,
    voters: MutableSet<NodeId>,
    spectatorIds: MutableSet<NodeId>,
    peerCount: Int,
    presence: GamePresence,
    config: HeartbeatConfig,
    clock: () -> Instant,
) {
    val self = NodeId(seam.selfId.value)
    val heartbeatSeam = mux.channel(HEARTBEAT_CHANNEL)

    // Fan the liveness channel's incoming stream into a shared flow so multiple per-peer
    // [GamePerPeerSeam] instances can each subscribe independently — satisfying single-collection.
    val rawLiveness = MutableSharedFlow<Swatch>(extraBufferCapacity = 256)
    launch { heartbeatSeam.incoming.collect { rawLiveness.emit(it) } }

    // Serialised evictions: detector coroutines send lost NodeIds here; the eviction loop
    // processes them one at a time on this scope. Channel.UNLIMITED so detector jobs never block.
    val evictions = Channel<NodeId>(Channel.UNLIMITED)

    // Active detector job per voter; updated as voters leave and replacements join.
    val detectorJobs = mutableMapOf<NodeId, Job>()

    // Start one detector per initial admitted voter (excluding self).
    voters.filter { it != self }.forEach { voterId ->
        detectorJobs[voterId] = launchDetectorFor(voterId, heartbeatSeam, rawLiveness, evictions, config, clock)
    }

    // Graceful-leave watcher: vacate signals bypass the reconnect window.
    launch {
        watchVacaters(node, seam, voters, spectatorIds, peerCount, presence, evictions, detectorJobs, heartbeatSeam, rawLiveness, config, clock, self)
    }

    // Eviction loop: process one PeerLost at a time.
    launch {
        evictAndReopenAdmission(node, seam, voters, spectatorIds, peerCount, presence, evictions, detectorJobs, heartbeatSeam, rawLiveness, config, clock, self)
    }
}

/**
 * Launches a [HeartbeatPartitionDetector] for [voterId] and returns its [Job].
 *
 * On [PartitionEvent.PeerLost], sends [voterId] to [evictions] and stops.
 * [PartitionEvent.PeerUnresponsive] and [PartitionEvent.PeerRecovered] are no-ops at this layer
 * (Raft's own replication tracks liveness; the eviction gate is [PeerLost] only).
 *
 * `internal` (not `private`) so the per-voter teardown contract is unit-testable: cancelling the
 * returned [Job] must tear down *all* of the detector's coroutines, not just the events collector.
 */
internal fun CoroutineScope.launchDetectorFor(
    voterId: NodeId,
    heartbeatSeam: Seam,
    rawLiveness: MutableSharedFlow<Swatch>,
    evictions: Channel<NodeId>,
    config: HeartbeatConfig,
    clock: () -> Instant,
): Job {
    val peerId = PeerId(voterId.value)
    val perPeerSeam = GamePerPeerSeam(heartbeatSeam, peerId, rawLiveness)
    val detector = HeartbeatPartitionDetector(perPeerSeam, peerId, config, clock)
    // Own all of the detector's coroutines under one umbrella job: `detector.start(this)` makes
    // the heartbeat loop and the inbound collector (which subscribes to the never-completing
    // [rawLiveness]) children of this launch, so cancelling the returned job tears the whole
    // detector down. Storing only the events-collector would orphan the other two past the
    // voter's eviction, on the long-lived session scope (#1001-class leak).
    return launch {
        detector.start(this)
        detector.events.collect { event ->
            if (event is PartitionEvent.PeerLost) {
                evictions.trySend(voterId)
            }
        }
    }
}

/**
 * Watches for graceful-leave vacate signals on [presence] and triggers immediate eviction.
 *
 * Polls [presence.vacaters] on every Quilter state change (announced flow). When a new vacater
 * is seen that is a current voter, sends its [NodeId] to [evictions] to bypass the reconnect window.
 */
private suspend fun watchVacaters(
    node: RaftNode,
    seam: Seam,
    voters: MutableSet<NodeId>,
    spectatorIds: MutableSet<NodeId>,
    peerCount: Int,
    presence: GamePresence,
    evictions: Channel<NodeId>,
    detectorJobs: MutableMap<NodeId, Job>,
    heartbeatSeam: Seam,
    rawLiveness: MutableSharedFlow<Swatch>,
    config: HeartbeatConfig,
    clock: () -> Instant,
    self: NodeId,
) {
    val seenVacaters = mutableSetOf<NodeId>()
    presence.announced.collect {
        val newVacaters = presence.vacaters()
            .map { NodeId(it.value) }
            .filter { it in voters && it !in seenVacaters && it != self }
        newVacaters.forEach { vacaterId ->
            seenVacaters += vacaterId
            // Cancel the detector job for this voter — it's leaving voluntarily.
            detectorJobs.remove(vacaterId)?.cancel()
            evictions.trySend(vacaterId)
        }
    }
}

/**
 * Processes evictions from [evictions]: removes the dead voter, re-opens admission for one
 * replacement, then starts a fresh detector for the replacement.
 *
 * Only the Raft **leader** calls [changeMembershipWithRetry]; non-leaders return early.
 * If leadership has transferred by the time eviction fires, the new leader's own loop handles
 * the eviction — or the evicted peer's seat remains open until another PeerLost fires.
 */
private suspend fun CoroutineScope.evictAndReopenAdmission(
    node: RaftNode,
    seam: Seam,
    voters: MutableSet<NodeId>,
    spectatorIds: MutableSet<NodeId>,
    peerCount: Int,
    presence: GamePresence,
    evictions: Channel<NodeId>,
    detectorJobs: MutableMap<NodeId, Job>,
    heartbeatSeam: Seam,
    rawLiveness: MutableSharedFlow<Swatch>,
    config: HeartbeatConfig,
    clock: () -> Instant,
    self: NodeId,
) {
    // Peers that have been evicted from the voter set — excluded from re-admission so a gracefully
    // departing peer (still connected) is not immediately re-admitted to its own freed seat.
    val evictedVoterIds = mutableSetOf<NodeId>()

    for (lostId in evictions) {
        // Skip if not leader — only the leader can commit membership changes.
        if (node.role.value !is RaftRole.Leader) continue
        // Skip if already evicted (e.g. duplicate signal from detector + vacate).
        if (lostId !in voters) continue

        // Cancel the stale detector job (if still running — vacate path cancels it first).
        detectorJobs.remove(lostId)?.cancel()

        // Remove the dead voter and commit the shrunken config.
        voters.remove(lostId)
        evictedVoterIds += lostId
        changeMembershipWithRetry(node, ClusterConfig(voters = voters.toSet(), learners = spectatorIds.toSet()))

        // Re-open admission so a new gameJoin can take the freed seat.
        presence.declareAdmissionOpen()

        // Admit exactly one replacement voter, excluding evicted peers so a gracefully-departing
        // peer (still connected) cannot immediately reclaim its own freed seat.
        val votersBeforeAdmit = voters.toSet()
        admitVotersUntil(
            node,
            seam,
            voters,
            spectatorIds + evictedVoterIds,
            target = votersBeforeAdmit.size + 1,
            presence,
        )

        // Re-close admission with the refreshed voter set.
        presence.declareAdmissionClosed(voters)

        // Start a liveness detector for the replacement voter (the one not in votersBeforeAdmit).
        val newVoterId = voters.firstOrNull { it !in votersBeforeAdmit }
        if (newVoterId != null) {
            detectorJobs[newVoterId] = launchDetectorFor(newVoterId, heartbeatSeam, rawLiveness, evictions, config, clock)
        }
    }
}
