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

    /** §7 InstallSnapshot chunk sent to a follower whose needed prefix has been compacted away. */
    public data class InstallSnapshot(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val lastIncludedIndex: Long,
        val offset: Long,
        val done: Boolean,
    ) : RaftTraceEvent

    /**
     * A config entry was appended to the log (adopted on append, per §6).
     * Emitted by both the leader (on [RaftNode.changeMembership]) and followers
     * (on receiving the AppendEntries carrying the config entry). This is the
     * primary assertion point for membership-change tests — config and term are
     * private engine state, so tests observe transitions through this event.
     *
     * [old] is the previous effective configuration — the bootstrap config on the first
     * ever change. [new] is the newly adopted configuration.
     */
    public data class ConfigChange(
        override val clock: Long,
        val node: NodeId,
        val index: Long,
        val old: ClusterConfig?,
        val new: ClusterConfig,
    ) : RaftTraceEvent

    /** A follower finished reassembling and installed a snapshot. */
    public data class InstallSnapshotAccepted(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val lastIncludedIndex: Long,
    ) : RaftTraceEvent

    /** Pre-vote phase started: candidate broadcasts hypothetical-term requests. */
    public data class PreVoteStarted(
        override val clock: Long,
        val node: NodeId,
        val proposedTerm: Long,
    ) : RaftTraceEvent

    /** Node granted a pre-vote to a candidate. */
    public data class PreVoteGranted(
        override val clock: Long,
        val node: NodeId,
        val to: NodeId,
        val proposedTerm: Long,
    ) : RaftTraceEvent

    /** Node denied a pre-vote to a candidate. */
    public data class PreVoteDenied(
        override val clock: Long,
        val node: NodeId,
        val to: NodeId,
        val proposedTerm: Long,
        val reason: DenyReason,
    ) : RaftTraceEvent

    /**
     * The leader confirmed quorum freshness for a linearizable read at [readIndex] in [term].
     * No log entry is written for the read. Emitted once per pending read as it resolves.
     */
    public data class ReadIndexConfirmed(
        override val clock: Long,
        val readIndex: Long,
        val term: Long,
    ) : RaftTraceEvent

    /**
     * The leader started a leadership transfer to [target].
     * Proposals are blocked until the transfer completes or is abandoned.
     */
    public data class LeadershipTransferStarted(
        override val clock: Long,
        val leader: NodeId,
        val target: NodeId,
    ) : RaftTraceEvent

    /**
     * A leadership transfer was abandoned — either because the auto-timeout expired before the
     * target won an election, or because [RaftNode.cancelTransfer] was called explicitly.
     * [reason] describes which path fired. Normal proposal acceptance is resumed.
     */
    public data class LeadershipTransferAbandoned(
        override val clock: Long,
        val leader: NodeId,
        val target: NodeId,
        val reason: LeadershipTransferAbandonReason,
    ) : RaftTraceEvent
}

/** Why a leadership transfer was abandoned without completing. */
public enum class LeadershipTransferAbandonReason {
    /** The target did not win an election within one election-timeout window. */
    Timeout,

    /** [RaftNode.cancelTransfer] was called explicitly by the application. */
    Cancelled,
}

/** Why a node stepped down from [RaftRole.Leader] or [RaftRole.Candidate] to [RaftRole.Follower]. */
public enum class StepDownReason {
    /** A message from a peer carried a term higher than this node's current term. */
    HigherTermObserved,

    /** A valid AppendEntries arrived from a legitimate leader, resetting the election timer. */
    AppendEntriesFromLeader,

    /**
     * CheckQuorum: the leader did not hear from a voter-quorum within an election-timeout window.
     * The node reverts to follower **at the same term** — no term bump.
     */
    LostQuorum,

    /**
     * The leader stepped down after C_new committed and the leader itself is not a member of
     * C_new.voters (§6.4.1 — the removed-leader case). Used in PR B; added now so the enum
     * is stable across the PR stack.
     */
    RemovedFromConfig,
}

/** Why a candidate's RequestVote or PreVote was denied by the responding node. */
public enum class DenyReason {
    /** The candidate's term is lower than the responder's current term. */
    StaleTerm,

    /** The responder already voted for a different candidate in this term. */
    AlreadyVoted,

    /** The candidate's log is less up-to-date than the responder's (Raft §5.4.1). */
    LogNotUpToDate,

    /** The responder recently heard from a leader and considers it alive. */
    LeaderAlive,
}
