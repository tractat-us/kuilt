package us.tractat.kuilt.raft

/**
 * One engine state transition, emitted on [RaftNode.trace].
 *
 * The event vocabulary follows the etcd TLA+ action names so traces can be
 * replayed through the Vanlightly standard-raft TLA+ spec for TLC validation.
 */
public sealed interface RaftTraceEvent {
    /** Logical monotonic clock — incremented on every emitted event. */
    public val clock: Long

    /** Election timeout fired; node becomes candidate. */
    public data class Timeout(override val clock: Long, val node: NodeId, val newTerm: Long) : RaftTraceEvent

    /** RequestVote RPC sent. */
    public data class RequestVote(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val term: Long,
        val lastLogIndex: Long,
        val lastLogTerm: Long,
    ) : RaftTraceEvent

    /** Node became leader. */
    public data class BecomeLeader(override val clock: Long, val node: NodeId, val term: Long) : RaftTraceEvent

    /** Node stepped down to follower. */
    public data class BecomeFollower(
        override val clock: Long,
        val node: NodeId,
        val term: Long,
        val reason: StepDownReason,
    ) : RaftTraceEvent

    /** A client proposal was appended to the leader's log. */
    public data class ClientRequest(
        override val clock: Long,
        val node: NodeId,
        val index: Long,
        val term: Long,
    ) : RaftTraceEvent

    /** AppendEntries RPC sent (including heartbeats — entryCount=0). */
    public data class AppendEntries(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val term: Long,
        val prevLogIndex: Long,
        val prevLogTerm: Long,
        val entryCount: Int,
        val leaderCommit: Long,
    ) : RaftTraceEvent

    /** AppendEntries accepted by follower. */
    public data class AppendEntriesAccepted(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val matchIndex: Long,
    ) : RaftTraceEvent

    /** AppendEntries rejected by follower. */
    public data class AppendEntriesRejected(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val conflictIndex: Long?,
        val conflictTerm: Long?,
    ) : RaftTraceEvent

    /** Log prefix discarded after a compaction. */
    public data class Compacted(
        override val clock: Long,
        val node: NodeId,
        val throughIndex: Long,
        val throughTerm: Long,
    ) : RaftTraceEvent

    /** commitIndex advanced. */
    public data class AdvanceCommitIndex(
        override val clock: Long,
        val node: NodeId,
        val oldCommitIndex: Long,
        val newCommitIndex: Long,
    ) : RaftTraceEvent

    /** Vote granted to a candidate. */
    public data class VoteGranted(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val term: Long,
    ) : RaftTraceEvent

    /** Vote denied to a candidate. */
    public data class VoteDenied(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val term: Long,
        val reason: DenyReason,
    ) : RaftTraceEvent
}

/** Why a node stepped down from [RaftRole.Leader] or [RaftRole.Candidate] to [RaftRole.Follower]. */
public enum class StepDownReason {
    /** A message from a peer carried a term higher than this node's current term. */
    HigherTermObserved,

    /** A valid AppendEntries arrived from a legitimate leader, resetting the election timer. */
    AppendEntriesFromLeader,
}

/** Why a candidate's RequestVote was denied by the responding node. */
public enum class DenyReason {
    /** The candidate's term is lower than the responder's current term. */
    StaleTerm,

    /** The responder already voted for a different candidate in this term. */
    AlreadyVoted,

    /** The candidate's log is less up-to-date than the responder's (Raft §5.4.1). */
    LogNotUpToDate,
}
