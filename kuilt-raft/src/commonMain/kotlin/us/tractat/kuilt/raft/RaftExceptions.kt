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
 * (a config entry is uncommitted), and by [RaftNode.transferLeadership] for the reverse
 * direction of the same §3.10 exclusion.
 *
 * The one-change-at-a-time rule is a liveness guard: it keeps the membership state
 * machine trivial and prevents multiple joint configs from stacking. The caller should
 * wait for the in-flight change to complete (or fail) before retrying.
 *
 * [reason] names **which** of the engine's converging-membership guards refused, as a typed value
 * rather than a message a reader has to parse (#2032). It is `null` on an instance this library did
 * not throw — the message-only constructor is retained so a fake or a consumer can still raise the
 * type — so read it as *attribution when present*, never as a switch that must be total.
 */
public class MembershipChangeInProgressException(
    message: String = DEFAULT_MEMBERSHIP_IN_PROGRESS_MESSAGE,
    public val reason: MembershipRefusal? = null,
) : Exception(message) {
    /** Names the refusing guard and derives the message from it — the constructor the engine uses. */
    public constructor(reason: MembershipRefusal) :
        this("$DEFAULT_MEMBERSHIP_IN_PROGRESS_MESSAGE — ${reason.detail}", reason)
}

private const val DEFAULT_MEMBERSHIP_IN_PROGRESS_MESSAGE: String =
    "a membership change is already in progress — wait for it to commit before starting another"

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
 * This exception is a report about the **medium**, whichever adapter is reading it. kuilt's own durable
 * adapter is [DurableStoreRaftStorage]; any other persistent one is consumer code — and the report means
 * the same thing either way, because that adapter round-trips faithfully and validates no ranges, so a
 * damaged record reaches this check rather than being repaired behind it. Treat it as you would a failed
 * integrity check: inspect the persisted term (a truncated column, a sign-extended `Int`, a torn or
 * partially deserialised read are the usual causes) and repair or re-provision the node deliberately.
 * Erasing the node's durable state and letting it rejoin as a fresh member is safe; silently continuing
 * is not — and with [DurableStoreRaftStorage] "erasing" is concrete, because its three
 * [DurableStoreRaftStorage.META_KEY]-family constants name exactly what to delete.
 *
 * Note that [DurableStoreRaftStorage.open] raises this same type for a record it cannot **decode**, or
 * one carrying a storage format version it does not know. Those messages name the [us.tractat.kuilt.store.StoreKey]
 * involved; this one names the value.
 *
 * Because the restore runs in the coroutine started by [CoroutineScope.raftNode][raftNode], this
 * surfaces through the scope rather than from the `raftNode(...)` call itself.
 */
public class CorruptDurableStateException(message: String) : IllegalStateException(message)
