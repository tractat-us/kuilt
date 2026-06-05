package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId

internal sealed interface EngineCommand {
    data class IncomingMessage(val from: NodeId, val message: RaftMessage) : EngineCommand
    data class Propose(val command: ByteArray, val response: CompletableDeferred<LogEntry>) : EngineCommand
    data object ElectionTimeout : EngineCommand
    data object HeartbeatTick : EngineCommand
    data object Close : EngineCommand
}
