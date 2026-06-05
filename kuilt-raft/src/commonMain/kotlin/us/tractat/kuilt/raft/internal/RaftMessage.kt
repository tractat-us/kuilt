package us.tractat.kuilt.raft.internal

import kotlinx.serialization.Serializable
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId

@Serializable
internal sealed interface RaftMessage {

    @Serializable
    data class RequestVote(
        val term: Long,
        val candidateId: NodeId,
        val lastLogIndex: Long,
        val lastLogTerm: Long,
    ) : RaftMessage

    @Serializable
    data class RequestVoteResponse(
        val term: Long,
        val voteGranted: Boolean,
    ) : RaftMessage

    @Serializable
    data class AppendEntries(
        val term: Long,
        val leaderId: NodeId,
        val prevLogIndex: Long,
        val prevLogTerm: Long,
        val entries: List<LogEntry>,
        val leaderCommit: Long,
    ) : RaftMessage

    /** Response includes §5.3 fast-backup fields for efficient log reconciliation. */
    @Serializable
    data class AppendEntriesResponse(
        val term: Long,
        val success: Boolean,
        val matchIndex: Long = 0L,
        val conflictIndex: Long? = null,
        val conflictTerm: Long? = null,
    ) : RaftMessage
}
