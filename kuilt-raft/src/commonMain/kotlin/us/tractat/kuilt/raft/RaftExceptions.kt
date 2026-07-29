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

/**
 * Thrown by [RaftNode.transferLeadership] when the transfer could not complete.
 *
 * Two causes:
 * - **Timeout**: the target did not win an election within one election-timeout window.
 *   The old leader resumed normal operation before throwing this exception.
 * - **Cancelled**: [RaftNode.cancelTransfer] was called explicitly.
 *
 * In both cases the old leader is back in its normal operating mode (accepting proposals)
 * when this exception propagates — the caller does not need to do anything to recover.
 */
public class LeadershipTransferException(message: String) : Exception(message)

/**
 * Thrown during a node's start-up restore when the [RaftStorage] it was given returns durable state
 * that violates the storage contract — currently, a persisted term outside the plausible range
 * (issue #1855).
 *
 * ### Why this is loud rather than repaired
 *
 * Terms advance once per election, so an honest deployment stays many orders of magnitude below the
 * `2^60` ceiling the wire boundary already enforces (issue #1833). A restored term above it — or below
 * zero — is therefore not a value to interpret; it is evidence that the durable state is wrong.
 *
 * Clamping it would be worse than the fault: rewriting a persisted term silently discards the record of
 * which terms this node has already voted in, so it can vote a second time in a term it has forgotten —
 * a Raft §5.2 election-safety violation, and a strictly worse trade than the lost liveness.
 *
 * Ignoring it is not free either. The node adopts the poisoned term, every frame it emits is dropped by
 * peers as implausible, and every frame it receives looks stale — it is permanently and *silently*
 * isolated. In a one-voter bootstrap it is worse still: the node wins its own election, `currentTerm + 1`
 * wraps, and it persists a **negative** term, driving its own durable state backwards past
 * [RaftStorage.term]'s monotonicity guarantee.
 *
 * ### What to do about it
 *
 * kuilt ships no durable [RaftStorage] — [InMemoryRaftStorage] is the only implementation in the library
 * — so this exception is a report about *your* storage adapter. Treat it as you would a failed integrity
 * check: inspect the persisted term (a truncated column, a sign-extended `Int`, a torn or partially
 * deserialised read are the usual causes) and repair or re-provision the node deliberately. Erasing the
 * node's durable state and letting it rejoin as a fresh member is safe; silently continuing is not.
 *
 * Because the restore runs in the coroutine started by [CoroutineScope.raftNode][raftNode], this
 * surfaces through the scope rather than from the `raftNode(...)` call itself.
 */
public class CorruptDurableStateException(message: String) : IllegalStateException(message)
