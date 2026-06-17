package us.tractat.kuilt.raft.internal

import kotlinx.serialization.Serializable
import us.tractat.kuilt.raft.ConfigPayload
import us.tractat.kuilt.raft.DedupKey
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

    // Note: AppendEntries is intentionally never value-compared — entries contains List<LogEntry>
    // whose ByteArray command fields compare by reference in generated equals. It is only used as a
    // transport envelope decoded from the wire; identity equality is never meaningful here.
    @Serializable
    data class AppendEntries(
        val term: Long,
        val leaderId: NodeId,
        val prevLogIndex: Long,
        val prevLogTerm: Long,
        val entries: List<LogEntry>,
        val leaderCommit: Long,
        /**
         * The leader's [RaftEngine.heartbeatRound] at send time, echoed back by the follower
         * in [AppendEntriesResponse.echoedRound]. Used by the leader to credit an ACK to the
         * round it actually responded to — not to the current heartbeatRound at receipt, which
         * may have advanced during transit (round-slip bug, BLOCKER 1a).
         */
        val round: Long = 0L,
    ) : RaftMessage

    /** Response includes §5.3 fast-backup fields for efficient log reconciliation. */
    @Serializable
    data class AppendEntriesResponse(
        val term: Long,
        val success: Boolean,
        val matchIndex: Long = 0L,
        val conflictIndex: Long? = null,
        val conflictTerm: Long? = null,
        /**
         * Echoes the [AppendEntries.round] from the request that triggered this response.
         * The leader uses this to set [RaftEngine.lastAckRound] to the round the follower
         * actually responded to, preventing a round-slip stale ACK from being credited to a
         * later round that the follower has not yet confirmed (BLOCKER 1a fix).
         */
        val echoedRound: Long = 0L,
    ) : RaftMessage

    /**
     * §7 InstallSnapshot — one chunk of a snapshot transfer. The leader diverts to this when a
     * follower's needed prefix has been compacted away. [data] carries bytes `[offset, offset+size)`
     * of the opaque snapshot; [done] marks the final chunk.
     *
     * [config] is the effective membership as of [lastIncludedIndex] (see [SnapshotMeta.config]).
     * Carried on every chunk (it is tiny relative to the state) so the installer can adopt it
     * regardless of which chunk it finalizes on; `null` when the covered prefix held no config change.
     *
     * [round] echoes the leader's [RaftEngine.heartbeatRound] at send time; it is returned in
     * [InstallSnapshotResponse.echoedRound] so the leader can credit the ACK to the correct round
     * (BLOCKER 1a fix, same as [AppendEntries.round]).
     *
     * Note: intentionally never value-compared — [data] is a ByteArray whose generated equals
     * compares by reference. This is a transport envelope only; identity equality is never meaningful.
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
        val round: Long = 0L,
    ) : RaftMessage

    /**
     * Follower's reply to [InstallSnapshot]: [nextOffset] is how many bytes it has stored, resyncing
     * the leader after a dropped chunk. [echoedRound] echoes the [InstallSnapshot.round] from the
     * request (BLOCKER 1a fix — same purpose as [AppendEntriesResponse.echoedRound]).
     */
    @Serializable
    data class InstallSnapshotResponse(
        val term: Long,
        val nextOffset: Long,
        val echoedRound: Long = 0L,
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

    /**
     * §3.10 TimeoutNow: sent by the leader to [targetId] to initiate a graceful leadership transfer.
     * The target immediately converts to a candidate and starts a real election (bypassing its
     * election-timeout wait and the pre-vote phase), so the transfer completes within one round-trip.
     *
     * [term] is the sender's current term; the target uses it to verify the message is current.
     */
    @Serializable
    data class TimeoutNow(
        val term: Long,
        val leaderId: NodeId,
    ) : RaftMessage

    /**
     * Client-proposal forwarding (Raft paper §8): a follower relays a `propose` command to the
     * current leader, which appends it on the follower's behalf. [clientRequestId] is the
     * follower-local correlation nonce echoed back in [ForwardResponse]; it is NOT written to the
     * log, so committed entries are unchanged. [dedupKey] is the separate end-to-end §8 client-serial
     * identity stamped by the *originating* proposer; the leader appends it UNCHANGED (it never
     * re-stamps), so a retried forward maps to the same key. `null` for an unkeyed/legacy proposal.
     */
    @Serializable
    data class Forward(
        val clientRequestId: Long,
        val command: ByteArray,
        val dedupKey: DedupKey? = null,
    ) : RaftMessage {
        // command is a ByteArray (reference equals in generated equals); this is a transport
        // envelope only — identity equality is never meaningful (same rationale as AppendEntries).
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = clientRequestId.hashCode()
    }

    /** Leader's reply to [Forward]: the proposal's fate, correlated by [clientRequestId]. */
    @Serializable
    data class ForwardResponse(
        val clientRequestId: Long,
        val outcome: ForwardOutcome,
    ) : RaftMessage
}

/** Outcome of a forwarded proposal, carried in [RaftMessage.ForwardResponse]. */
@Serializable
internal sealed interface ForwardOutcome {
    /** Committed at [index] in [term]. */
    @Serializable
    data class Committed(val index: Long, val term: Long) : ForwardOutcome

    /** The target was not (or no longer) the leader; the caller should retry. */
    @Serializable
    data object NotLeader : ForwardOutcome

    /** The proposal failed for a non-retryable reason. */
    @Serializable
    data object Failed : ForwardOutcome
}
