package us.tractat.kuilt.game

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
): GameSession {
    require(NodeId(seam.selfId.value) in voterIds) {
        "this peer (${seam.selfId.value}) must be in voterIds $voterIds"
    }
    val mux = MuxSeam(seam, this)
    val node = raftNode(ClusterConfig.ofVoters(voterIds), SeamRaftTransport(mux.channel(RAFT_CHANNEL)), storage, raftConfig)
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
 * @param storage Durable Raft state. Defaults to [InMemoryRaftStorage].
 * @param raftConfig Timing and behaviour parameters. Tests pass
 *   `RaftConfig(expectVirtualTime = true)` — this is the only supported path to
 *   virtual-time execution (D4).
 * @param hostDeclarationTimeout Upper bound on the presence-convergence wait before the
 *   duplicate-host check proceeds regardless. This is genuine tuning, not a functional
 *   switch: a connected-but-silent peer (or a real fabric whose round-trip exceeds the
 *   bound) only weakens detection, never disables the host. The default is sized to clear a
 *   typical WAN round-trip; raise it on high-latency fabrics, lower it where joiners are
 *   known-local.
 * @throws IllegalArgumentException if [peerCount] < 1.
 * @throws DuplicateHostException if another peer on the same session already declared host.
 *
 * @sample us.tractat.kuilt.game.sampleGameHostJoin
 */
public suspend fun CoroutineScope.gameHost(
    seam: Seam,
    peerCount: Int,
    returnAt: ReturnPolicy = ReturnPolicy.FullMembership,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
    hostDeclarationTimeout: Duration = DEFAULT_HOST_DECLARATION_TIMEOUT,
): GameSession {
    require(peerCount >= 1) { "peerCount must be >= 1" }

    val mux = MuxSeam(seam, this)
    val raftSeam = mux.channel(RAFT_CHANNEL)
    val presenceSeam = mux.channel(PRESENCE_CHANNEL)
    val appMux = NamedMux(mux.channel(APP_ENVELOPE_CHANNEL), this)

    val presence = checkNotDuplicateHost(presenceSeam, this, raftConfig.expectVirtualTime, hostDeclarationTimeout)

    val self = NodeId(seam.selfId.value)
    val node = raftNode(ClusterConfig.ofVoters(setOf(self)), SeamRaftTransport(raftSeam), storage, raftConfig)
    node.awaitLeadership()

    // Admit synchronously up to the return watermark: the full roster in FullMembership mode, a
    // majority in Quorum mode.
    val returnThreshold = when (returnAt) {
        ReturnPolicy.FullMembership -> peerCount
        ReturnPolicy.Quorum -> peerCount / 2 + 1
    }
    val voters = mutableSetOf(self)
    admitUntil(node, seam, voters, target = returnThreshold)

    // Quorum mode: keep admitting the remaining voters in the background for the life of the
    // session. The loop runs on the caller's scope (this), so it stays alive until the roster
    // reaches peerCount or the scope is cancelled at session end — the admission door does not close
    // on a timer, so a latecomer is admitted whenever it connects, however late. A give-up failure
    // propagates to the caller (fail-loud, no swallow); cancellation tears admission down. After
    // this point only the background coroutine touches `voters`, so no synchronization is needed.
    if (voters.size < peerCount) {
        launch {
            admitUntil(node, seam, voters, target = peerCount)
            presence.declareAdmissionClosed(voters)
        }
    } else {
        // FullMembership mode (or Quorum with peerCount == 1): the roster is already full.
        // Publish the signal synchronously so any joiner that arrives after this point can
        // detect the full roster immediately without waiting for the background loop.
        presence.declareAdmissionClosed(voters)
    }
    return GameSession(node, seam, appMux)
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
    )

    awaitAdmissionOrThrow(node, presence, self, joinAdmissionTimeout)
    return GameSession(node, seam, appMux)
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
 * Admit connecting peers as learner→voter until the cluster reaches [target] voters.
 *
 * Drives the shared admit loop used by both the synchronous return-watermark phase and the
 * background latecomer phase of [gameHost]. Mutates [voters] in place as each peer is promoted.
 */
private suspend fun admitUntil(node: RaftNode, seam: Seam, voters: MutableSet<NodeId>, target: Int) {
    while (voters.size < target) {
        val joinerId = nextPeer(seam, voters)
        admitLearnerThenVoter(node, voters, joinerId)
        voters += joinerId
    }
}

/** Suspends until a peer appears in [seam.peers] that is not already in [admitted]. */
private suspend fun nextPeer(seam: Seam, admitted: Set<NodeId>): NodeId {
    val newPeers = seam.peers.first { peerSet ->
        peerSet.any { peerId -> NodeId(peerId.value) !in admitted }
    }
    return newPeers
        .map { NodeId(it.value) }
        .first { it !in admitted }
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
