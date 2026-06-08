package us.tractat.kuilt.raft

/**
 * Thrown by [RaftNode.propose] when this node is not the current leader.
 *
 * This includes learner nodes, which can never lead. Callers should read
 * [RaftNode.leader] to find the current leader and redirect the proposal, or
 * retry after the next [RaftNode.role] transition to [RaftRole.Leader].
 */
public class NotLeaderException(message: String = "not the current leader") : Exception(message)

/**
 * Thrown by [RaftNode.propose] when this node loses leadership while waiting
 * for the proposal to be committed by a quorum.
 *
 * The entry may or may not have been replicated to a majority before the
 * step-down. Callers must treat the proposal as having an unknown outcome and
 * should either retry (idempotent commands) or use a deduplication key.
 */
public class LeadershipLostException(message: String = "leadership lost while proposal was in flight") : Exception(message)

/**
 * Thrown by [RaftNode.changeMembership] when a membership change is already in progress
 * (a config entry is uncommitted).
 *
 * The one-change-at-a-time rule is a liveness guard: it keeps the membership state
 * machine trivial and prevents multiple joint configs from stacking. The caller should
 * wait for the in-flight change to complete (or fail) before retrying.
 */
public class MembershipChangeInProgressException(
    message: String = "a membership change is already in progress — wait for it to commit before starting another",
) : Exception(message)
