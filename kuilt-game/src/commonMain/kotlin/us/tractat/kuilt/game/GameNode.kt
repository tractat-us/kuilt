package us.tractat.kuilt.game

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Seam
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
import kotlin.time.Duration.Companion.milliseconds

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
 * Constructs a [RaftNode] over [seam] for a session whose full voter roster is
 * already known to every peer (e.g. from matchmaking). Every peer builds the
 * identical [ClusterConfig.ofVoters] and Raft's own election picks the leader —
 * symmetric, no pre-Raft coordination step required.
 *
 * This is the *roster-given* bootstrap path: call it when every participating
 * peer's identity is known before the session starts. For the *appoint-the-host*
 * path (dynamic join without a fixed roster), see `gameHost`/`gameJoin`.
 *
 * **Do not collect `seam.incoming` after calling this.** Once the returned
 * [RaftNode] is running, `SeamRaftTransport` is the sole consumer of
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
 */
public fun CoroutineScope.gameNode(
    seam: Seam,
    voterIds: Set<NodeId>,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode {
    require(NodeId(seam.selfId.value) in voterIds) {
        "this peer (${seam.selfId.value}) must be in voterIds $voterIds"
    }
    return raftNode(ClusterConfig.ofVoters(voterIds), SeamRaftTransport(seam), storage, raftConfig)
}

/**
 * Host a game session over [seam]: check for duplicate hosts, bootstrap a
 * singleton-voter cluster, then admit each connecting peer as learner→voter until
 * the cluster reaches [peerCount] voters. Suspends until the cluster is at full
 * membership, then returns the leader [RaftNode].
 *
 * **Precondition: exactly one `gameHost` per session.** This function detects a
 * duplicate host via lobby presence before bootstrapping Raft and throws
 * [DuplicateHostException] if another peer already declared itself host.
 *
 * **Internal multiplexing.** [gameHost] wraps [seam] in a [MuxSeam] so that Raft
 * traffic (channel tag 1) and lobby presence traffic (channel tag 2) share the
 * single underlying [Seam] without violating the ADR-034 single-collection contract.
 * [gameJoin] applies the same tag to its Raft channel so both sides communicate on
 * matching frames. The caller passes a plain [Seam] in both cases — muxing is an
 * internal implementation detail.
 *
 * @param peerCount Total number of voters (including the host) the cluster must reach.
 * @param storage Durable Raft state. Defaults to [InMemoryRaftStorage].
 * @param raftConfig Timing and behaviour parameters. Tests pass
 *   `RaftConfig(expectVirtualTime = true)` — this is the only supported path to
 *   virtual-time execution (D4).
 * @throws IllegalArgumentException if [peerCount] < 1.
 * @throws DuplicateHostException if another peer on the same session already declared host.
 *
 * @sample us.tractat.kuilt.game.sampleGameHostJoin
 */
public suspend fun CoroutineScope.gameHost(
    seam: Seam,
    peerCount: Int,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode {
    require(peerCount >= 1) { "peerCount must be >= 1" }

    val mux = MuxSeam(seam, this)
    val raftSeam = mux.channel(RAFT_CHANNEL)
    val presenceSeam = mux.channel(PRESENCE_CHANNEL)

    checkNotDuplicateHost(presenceSeam, this, raftConfig.expectVirtualTime)

    val self = NodeId(seam.selfId.value)
    val node = raftNode(ClusterConfig.ofVoters(setOf(self)), SeamRaftTransport(raftSeam), storage, raftConfig)
    node.awaitLeadership()

    val voters = mutableSetOf(self)
    while (voters.size < peerCount) {
        val joinerId = nextPeer(seam, voters)
        admitLearnerThenVoter(node, voters, joinerId)
        voters += joinerId
    }
    return node
}

/**
 * Join a game session over [seam] hosted by exactly one [gameHost]. Starts as a non-voting
 * learner and waits until the host admits this peer as a voter. Returns the local [RaftNode]
 * once admitted.
 *
 * **Internal multiplexing.** [gameJoin] wraps [seam] in a [MuxSeam] and routes Raft traffic
 * on channel tag 1 — matching [gameHost]'s Raft channel — so both sides communicate on
 * compatible frames. The caller passes a plain [Seam]; muxing is internal.
 *
 * **Do not collect `seam.incoming` after calling this** (ADR-034 single-collection).
 *
 * @param storage Durable Raft state. Defaults to [InMemoryRaftStorage].
 * @param raftConfig Timing and behaviour parameters. Tests pass
 *   `RaftConfig(expectVirtualTime = true)` (D4).
 */
public suspend fun CoroutineScope.gameJoin(
    seam: Seam,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode {
    val mux = MuxSeam(seam, this)
    val raftSeam = mux.channel(RAFT_CHANNEL)

    val self = NodeId(seam.selfId.value)
    // Start as a learner with no known voters. The host's changeMembership will commit a
    // config that promotes us to voter; recomputeMembership then transitions role to Follower.
    val node = raftNode(
        ClusterConfig(voters = emptySet(), learners = setOf(self)),
        SeamRaftTransport(raftSeam),
        storage,
        raftConfig,
    )
    // Suspend until the host commits the config entry that promotes us from Learner to voter.
    // RaftEngine.reevaluateSelfRole() fires on every recomputeMembership() and transitions
    // role from RaftRole.Learner to RaftRole.Follower once we appear in the voters set.
    node.role.first { it !is RaftRole.Learner }
    return node
}

/**
 * Declares this peer as host via [GamePresence] on [presenceSeam], waits a bounded
 * interval for presence to converge, then checks for duplicate declarations.
 *
 * The convergence window ([PRESENCE_CONVERGENCE_DELAY]) is short: under virtual time it
 * lets all queued Quilter coroutines (full-state exchange, delta delivery) run before the
 * check. Under real time it is negligible.
 *
 * @throws DuplicateHostException if any other replica has also declared host after convergence.
 */
private suspend fun checkNotDuplicateHost(presenceSeam: Seam, scope: CoroutineScope, expectVirtualTime: Boolean) {
    val presence = GamePresence(presenceSeam, scope, expectVirtualTime)
    presence.declareHost()
    delay(PRESENCE_CONVERGENCE_DELAY)
    val others = presence.declaredHosts() - presence.replica
    if (others.isNotEmpty()) throw DuplicateHostException()
}

/**
 * Bounded convergence window for the lobby presence check in [checkNotDuplicateHost].
 *
 * Under virtual time (StandardTestDispatcher), this delay yields the coroutine and allows
 * all queued presence coroutines (Quilter startup, full-state exchange, delta delivery) to
 * run before the duplicate-host check. Under real time the 1 ms wait is negligible.
 */
private val PRESENCE_CONVERGENCE_DELAY = 1.milliseconds

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
