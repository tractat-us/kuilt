package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.Snapshot

internal sealed interface EngineCommand {
    data class IncomingMessage(val from: NodeId, val message: RaftMessage) : EngineCommand
    data class Propose(val command: ByteArray, val response: CompletableDeferred<LogEntry>) : EngineCommand
    data object ElectionTimeout : EngineCommand
    data object HeartbeatTick : EngineCommand
    data object Compact : EngineCommand
    data object Close : EngineCommand

    /**
     * Atomically snapshot the committed log for [committedFrom][us.tractat.kuilt.raft.RaftNode.committedFrom].
     * Processed inside the actor so the captured [CommitCutResult.cutIndex] and the
     * replayed entries are consistent with a single point in the commit stream.
     */
    data class CommitCut(
        val fromIndex: Long,
        val response: CompletableDeferred<CommitCutResult>,
    ) : EngineCommand
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
