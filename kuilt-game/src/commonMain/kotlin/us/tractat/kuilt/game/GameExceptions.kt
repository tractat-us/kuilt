package us.tractat.kuilt.game

/**
 * Thrown by [TurnSequencer.propose] when this node is not the current turn leader.
 *
 * Wraps the underlying Raft `NotLeaderException` as [cause]. Callers should
 * inspect [TurnSequencer] (or the backing node's role/leader state) to find
 * the current leader and redirect the proposal, or wait for a role transition.
 */
public class NotYourTurnException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * Thrown by [TurnSequencer.propose] when turn leadership is lost while the
 * proposal is awaiting quorum commit.
 *
 * Wraps the underlying Raft `LeadershipLostException` as [cause]. The entry
 * may or may not have been replicated to a majority before the step-down.
 * Callers must treat the proposal as having an unknown outcome and should
 * retry with an idempotent command or use a deduplication key.
 */
public class TurnLostInFlightException(message: String, cause: Throwable) : Exception(message, cause)
