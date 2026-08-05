package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.Snapshot

internal sealed interface EngineCommand {
    data class IncomingMessage(val from: NodeId, val message: RaftMessage) : EngineCommand

    /**
     * A frame from [from] that failed to decode and was dropped (#2051).
     *
     * The engine's inbound pump decodes before enqueueing, so this is the one command carrying no
     * `RaftMessage` — the failure *is* that there is no message. It exists so the drop is reported
     * from the **actor loop** rather than the pump coroutine: the trace clock is a plain `var`
     * confined to the actor, and emitting off it would race every other event's clock.
     *
     * **Routing it through the shared command channel adds no queueing surface — but not because
     * that channel bounds anything.** It is `Channel.UNLIMITED`, so a flood of *either* command shape
     * grows it without limit; do not read a boundedness guarantee here that does not exist. The
     * reason this is safe is that an undecodable frame is strictly *cheaper* than a decodable one on
     * every axis: it retains an `Int` where [IncomingMessage] retains a whole `RaftMessage`, and its
     * handler is one non-suspending trace emit where `onMessage` may persist, append and respond. The
     * actor therefore drains these *faster* than the frames a peer could already send, so an
     * undecodable flood grows the queue less than the decodable flood that is always available.
     *
     * Carries [byteCount] rather than the bytes: the payload is remote-controlled and nothing
     * downstream reads it. The decode failure itself is logged at `debug` at the pump, where the
     * exception is still in hand.
     */
    data class UndecodableMessage(val from: NodeId, val byteCount: Int) : EngineCommand
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
     * If the transfer identified by [epoch] is still the in-flight one, it is abandoned and normal
     * operation resumes; a stale [epoch] from an already-resolved transfer's timer is ignored (#1232).
     */
    data class TransferTimeout(val epoch: Long) : EngineCommand
}

/** The result of an [EngineCommand.CommitCut]: the committed instruction prefix plus the cut index. */
internal class CommitCutResult(
    /**
     * One [Committed] per committed index in `fromIndex..cutIndex`, in order — application entries as
     * [Committed.Entry], withheld no-op/config entries as payload-free [Committed.Internal] markers.
     */
    val replay: List<Committed>,
    /** The `commitIndex` at the moment of the cut; live entries with a greater index tail afterwards. */
    val cutIndex: Long,
    /** A snapshot to emit before the replay, or `null` if no install is needed (no compaction yet). */
    val install: Snapshot? = null,
)
