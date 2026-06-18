package us.tractat.kuilt.game

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

/**
 * Constructs a [RaftNode] over [seam] for a session whose full voter roster is
 * already known to every peer (e.g. from matchmaking). Every peer builds the
 * identical [ClusterConfig.ofVoters] and Raft's own election picks the leader —
 * symmetric, no pre-Raft coordination step required.
 *
 * This is the *roster-given* bootstrap path: call it when every participating
 * peer's identity is known before the session starts. For the *appoint-the-host*
 * path (dynamic join without a fixed roster), see `gameHost`/`gameJoin` (Task 3).
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
 * Host a game session over [seam]: bootstrap a singleton-voter cluster, then admit each
 * connecting peer as learner→voter until the cluster reaches [peerCount] voters. Suspends
 * until the cluster is at full membership, then returns the leader [RaftNode].
 *
 * **Precondition: exactly one `gameHost` per session.** On unarbitrated fabrics the
 * application designates the host; duplicate-host detection is Task 6.
 *
 * **Do not collect `seam.incoming` after calling this** — [SeamRaftTransport] is the sole
 * consumer of `seam.incoming` (ADR-034 single-collection). A second collector silently drops
 * Raft messages, causing liveness failures.
 *
 * @param peerCount Total number of voters (including the host) the cluster must reach.
 * @param storage Durable Raft state. Defaults to [InMemoryRaftStorage].
 * @param raftConfig Timing and behaviour parameters. Tests pass
 *   `RaftConfig(expectVirtualTime = true)` — this is the only supported path to
 *   virtual-time execution (D4).
 * @throws IllegalArgumentException if [peerCount] < 1.
 */
public suspend fun CoroutineScope.gameHost(
    seam: Seam,
    peerCount: Int,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode {
    require(peerCount >= 1) { "peerCount must be >= 1" }
    val self = NodeId(seam.selfId.value)
    val node = raftNode(ClusterConfig.ofVoters(setOf(self)), SeamRaftTransport(seam), storage, raftConfig)
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
    val self = NodeId(seam.selfId.value)
    // Start as a learner with no known voters. The host's changeMembership will commit a
    // config that promotes us to voter; recomputeMembership then transitions role to Follower.
    val node = raftNode(
        ClusterConfig(voters = emptySet(), learners = setOf(self)),
        SeamRaftTransport(seam),
        storage,
        raftConfig,
    )
    // Suspend until the host commits the config entry that promotes us from Learner to voter.
    // RaftEngine.reevaluateSelfRole() fires on every recomputeMembership() and transitions
    // role from RaftRole.Learner to RaftRole.Follower once we appear in the voters set.
    node.role.first { it !is RaftRole.Learner }
    return node
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
