package us.tractat.kuilt.raft.internal

import kotlinx.serialization.Serializable
import us.tractat.kuilt.raft.ConfigPayload
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

    /**
     * §7 InstallSnapshot — one chunk of a snapshot transfer. The leader diverts to this when a
     * follower's needed prefix has been compacted away. [data] carries bytes `[offset, offset+size)`
     * of the opaque snapshot; [done] marks the final chunk.
     *
     * [config] is the effective membership as of [lastIncludedIndex] (see [SnapshotMeta.config]).
     * Carried on every chunk (it is tiny relative to the state) so the installer can adopt it
     * regardless of which chunk it finalizes on; `null` when the covered prefix held no config change.
     */
    @Serializable
    data class InstallSnapshot(
        val term: Long,
        val leaderId: NodeId,
        val lastIncludedIndex: Long,
        val lastIncludedTerm: Long,
        val offset: Long,
        val data: ByteArray,
        val done: Boolean,
        val config: ConfigPayload? = null,
    ) : RaftMessage

    /** Follower's reply to [InstallSnapshot]: [nextOffset] is how many bytes it has stored, resyncing the leader after a dropped chunk. */
    @Serializable
    data class InstallSnapshotResponse(
        val term: Long,
        val nextOffset: Long,
    ) : RaftMessage

    /**
     * PreVote phase-1 request: a candidate asks whether peers would vote for it in a hypothetical
     * election at [term] (= currentTerm + 1), without actually incrementing its own term. This
     * prevents term inflation from isolated nodes triggering spurious elections.
     *
     * [round] is a monotonically-increasing nonce incremented by the candidate on every probe
     * cycle. Because pre-vote deliberately does NOT bump [term], the same [term] value recurs on
     * every timeout. Without [round], a delayed [PreVoteResponse] from a previous probe cycle is
     * indistinguishable from one in the current cycle and can prematurely satisfy a quorum.
     */
    @Serializable
    data class PreVote(
        val term: Long,
        val candidateId: NodeId,
        val lastLogIndex: Long,
        val lastLogTerm: Long,
        val round: Long,
    ) : RaftMessage

    /**
     * Response to [PreVote]. [proposedTerm] echoes the [PreVote.term] and [round] echoes the
     * [PreVote.round] so the candidate can reject responses from a previous probe cycle.
     */
    @Serializable
    data class PreVoteResponse(
        val term: Long,
        val voteGranted: Boolean,
        val proposedTerm: Long,
        val round: Long,
    ) : RaftMessage
}
