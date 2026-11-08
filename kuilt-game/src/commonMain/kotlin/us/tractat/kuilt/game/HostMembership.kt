package us.tractat.kuilt.game

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.changeMembershipWithRetry

/**
 * [gameHost]'s only membership writer: every config it commits is the node's current config plus
 * exactly the one delta the caller intends, so a change never removes a member it did not mean to.
 *
 * Three loops change the host's membership — the voter admission loop, the spectator admission
 * loop and [VoterLivenessMonitor]'s eviction loop — each in its own coroutine. Each used to build an
 * absolute [ClusterConfig] from its own view, so one loop's change silently undid another's: a
 * voter admitted after a spectator committed a config with no learners at all, and the spectator
 * stopped receiving the log (#3135). Here every change is a transform of
 * [RaftNode.membership] read under one [Mutex], and the [voters] and [spectators] snapshots the
 * loops read are updated under the same lock, after the change commits.
 *
 * ## Why resending a target on retry is safe here
 *
 * [changeMembershipWithRetry] resends the same absolute target after a
 * `MembershipChangeInProgressException`, and with a second writer that target can be stale (#3128).
 * Under this lock there is no second writer on this node, and each change returns only once it has
 * committed, so the only refusal left is an uncommitted config this node inherited on winning an
 * election. [RaftNode.membership] adopts a config on append, so the target was computed from that
 * inherited entry, and committing it changes nothing the target depends on.
 *
 * That premise is the whole guarantee: it holds only while nothing else on the host calls
 * [RaftNode.changeMembership]. A caller that changes the host's membership directly through
 * [GameSession.node] can still race these loops.
 */
internal class HostMembership(private val node: RaftNode, self: NodeId) {
    private val lock = Mutex()

    private val _voters = MutableStateFlow(setOf(self))

    /** The voters this host has admitted, self included; updated once each change commits. */
    val voters: StateFlow<Set<NodeId>> = _voters.asStateFlow()

    private val _spectators = MutableStateFlow(emptySet<NodeId>())

    /** The spectators this host has admitted as permanent learners. */
    val spectators: StateFlow<Set<NodeId>> = _spectators.asStateFlow()

    /**
     * Admit [joiner] as a learner, then promote it to voter. Every other member is kept.
     *
     * A joiner that is already a voter is left alone: two admission loops (the latecomer loop and
     * the eviction's replacement) can both pick the same peer, and the second must not add a voter
     * to the learner set as well.
     */
    suspend fun admitVoter(joiner: NodeId): Unit = lock.withLock {
        if (joiner !in node.membership.value.voters) {
            commit { it.copy(learners = it.learners + joiner) }
            commit { it.copy(voters = it.voters + joiner, learners = it.learners - joiner) }
        }
        _voters.update { it + joiner }
    }

    /** Admit [spectator] as a permanent, non-voting learner. Every other member is kept. */
    suspend fun admitSpectator(spectator: NodeId): Unit = lock.withLock {
        commit { it.copy(learners = it.learners + spectator) }
        _spectators.update { it + spectator }
    }

    /** Remove [voter] from the voter set. Every other member is kept. */
    suspend fun evict(voter: NodeId): Unit = lock.withLock {
        commit { it.copy(voters = it.voters - voter) }
        _voters.update { it - voter }
    }

    /** Commit [delta] applied to the current membership; a delta that changes nothing commits nothing. */
    private suspend fun commit(delta: (ClusterConfig) -> ClusterConfig) {
        val current = node.membership.value
        val target = delta(current)
        if (target != current) node.changeMembershipWithRetry(target)
    }
}
