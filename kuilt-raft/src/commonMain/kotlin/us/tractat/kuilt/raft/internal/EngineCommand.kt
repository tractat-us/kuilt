package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.Snapshot

internal sealed interface EngineCommand {
    data class IncomingMessage(val from: NodeId, val message: RaftMessage) : EngineCommand
    /** [requestId] is the caller-pinned Raft §8 serial, or `null` to draw the next auto-serial on the actor loop. */
    data class Propose(val command: ByteArray, val requestId: Long?, val response: CompletableDeferred<LogEntry>) : EngineCommand
    data class ChangeMembership(val target: ClusterConfig, val response: CompletableDeferred<ClusterConfig>) : EngineCommand
    data object ElectionTimeout : EngineCommand
    data object HeartbeatTick : EngineCommand
    data object LeaseExpired : EngineCommand
    data object Compact : EngineCommand
    data object Close : EngineCommand

    /** Periodic leader self-check: did a voter-quorum reach us this window? */
    data object QuorumCheck : EngineCommand

    /**
     * Request a linearizable read index from the leader. The leader confirms quorum freshness
     * via a heartbeat round, then completes [deferred] with the current commit index (the read index).
     * On non-leader or leadership-loss, [deferred] completes exceptionally.
     */
    data class RequestReadIndex(val deferred: CompletableDeferred<Long>) : EngineCommand

    /**
     * Atomically snapshot the committed log for [committedFrom][us.tractat.kuilt.raft.RaftNode.committedFrom].
     * Processed inside the actor so the captured [CommitCutResult.cutIndex] and the
     * replayed entries are consistent with a single point in the commit stream.
     */
    data class CommitCut(
        val fromIndex: Long,
        val response: CompletableDeferred<CommitCutResult>,
    ) : EngineCommand

    /**
     * §3.10 leadership transfer: the leader should sync [target]'s log and send [RaftMessage.TimeoutNow].
     * [response] is completed when the transfer either succeeds (this node steps down) or fails
     * (auto-timeout or explicit cancel).
     */
    data class TransferLeadership(
        val target: NodeId,
        val response: CompletableDeferred<Unit>,
    ) : EngineCommand

    /**
     * Abort an in-flight leadership transfer. If no transfer is in flight this is a no-op.
     * The transfer's [TransferLeadership.response] will be failed with [us.tractat.kuilt.raft.LeadershipTransferException].
     */
    data object CancelTransfer : EngineCommand

    /**
     * Auto-timeout for a leadership transfer: fired by a timer after one election timeout window.
     * If the transfer is still in flight, it is abandoned and normal operation resumes.
     */
    data object TransferTimeout : EngineCommand
}

/** The result of an [EngineCommand.CommitCut]: committed application entries plus the cut index. */
internal class CommitCutResult(
    /** Committed application entries (no-ops excluded) with index in `fromIndex..cutIndex`. */
    val replay: List<LogEntry>,
    /** The `commitIndex` at the moment of the cut; live entries with a greater index tail afterwards. */
    val cutIndex: Long,
    /** A snapshot to emit before the replay, or `null` if no install is needed (no compaction yet). */
    val install: Snapshot? = null,
)
