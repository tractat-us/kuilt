package us.tractat.kuilt.raft.internal

import kotlinx.serialization.Serializable
import us.tractat.kuilt.raft.ConfigPayload
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.RaftMessageType

/**
 * The Raft wire frames.
 *
 * **No frame restates its own sender (#1912).** The transport already carries the true origin as
 * `from` — `SeamRaftTransport` / `RoutedRaftTransport` / `RaftRelayHub` unwrap the relay envelope —
 * and every handler takes it as its first parameter. A `leaderId`/`candidateId` field alongside it
 * would be redundant on every honest frame (the sender writes its own `selfId`) and forgeable on
 * every hostile one, and the engine read those fields as *authority*: `_leader.value`,
 * `persistVote(…)`, §3.10 transfer confirmation. Five such fields were deleted rather than
 * checked against `from`, which removes the forgery instead of adding four checks that a new read
 * site can forget. A new frame type must not reintroduce one: read `from`.
 */
@Serializable
internal sealed interface RaftMessage {

    /**
     * [leadershipTransfer] carries the dissertation §4.2.3 "permission to disrupt" flag: when the
     * candidate is campaigning because its leader sent it a [TimeoutNow] (a §3.10 graceful transfer),
     * the vote must be processed by the other servers *even when they believe a current leader exists*
     * — the recipient's leader-stickiness (`leaderAlive`) deny is bypassed. It is a pure wire hint;
     * every other check (term, §5.4.1 log-up-to-date, already-voted) still applies. Defaulted `false`
     * so a normal election never disrupts a healthy leader. It is wire-compatible in both directions:
     * a new peer with `false` omits the field (encodeDefaults is off), and an older peer decodes it via
     * the `ignoreUnknownKeys` codec (see `raftCbor`) — dropping the flag and treating a disrupt-flagged
     * vote as an ordinary one, i.e. denying it under stickiness (the correct graceful degradation).
     */
    @Serializable
    data class RequestVote(
        val term: Long,
        val lastLogIndex: Long,
        val lastLogTerm: Long,
        val leadershipTransfer: Boolean = false,
    ) : RaftMessage {
        /** The candidate's reported last position, for §5.4.1 comparisons — see [isLogUpToDate]. */
        val lastLogPosition: LogPosition get() = LogPosition(term = lastLogTerm, index = lastLogIndex)
    }

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
        val prevLogIndex: Long,
        val prevLogTerm: Long,
        val entries: List<LogEntry>,
        val leaderCommit: Long,
        /**
         * The leader's [ReadIndexTracker.round] at send time, echoed back by the follower
         * in [AppendEntriesResponse.echoedRound]. Used by the leader to credit an ACK to the
         * round it actually responded to — not to the current round at receipt, which
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
         * The leader uses this (via [ReadIndexTracker.recordAck]) to credit the ACK to the round the
         * follower actually responded to, preventing a round-slip stale ACK from being credited to a
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
     * [round] echoes the leader's [ReadIndexTracker.round] at send time; it is returned in
     * [InstallSnapshotResponse.echoedRound] so the leader can credit the ACK to the correct round
     * (BLOCKER 1a fix, same as [AppendEntries.round]).
     *
     * Note: intentionally never value-compared — [data] is a ByteArray whose generated equals
     * compares by reference. This is a transport envelope only; identity equality is never meaningful.
     */
    @Serializable
    data class InstallSnapshot(
        val term: Long,
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
        val lastLogIndex: Long,
        val lastLogTerm: Long,
        val round: Long,
    ) : RaftMessage {
        /** The candidate's reported last position, for §5.4.1 comparisons — see [isLogUpToDate]. */
        val lastLogPosition: LogPosition get() = LogPosition(term = lastLogTerm, index = lastLogIndex)
    }

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
     * §3.10 TimeoutNow: sent by the leader to the transfer target to initiate a graceful leadership
     * transfer. The target immediately converts to a candidate and starts a real election (bypassing
     * its election-timeout wait and the pre-vote phase), so the transfer completes within one
     * round-trip.
     *
     * [term] is the sender's current term; the target uses it to verify the message is current. The
     * sender's identity is the transport's `from`, which `onTimeoutNow` checks against the leader it
     * currently recognises — see the banner above for why the frame carries no `leaderId` (#1912).
     */
    @Serializable
    data class TimeoutNow(
        val term: Long,
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

/**
 * The Raft term this frame asserts, or `null` for the two frames that carry none.
 *
 * The single place a term is read off the wire before any adoption decision, so [RaftEngine] can
 * apply one plausibility bound at the dispatch boundary (issue #1833) instead of at each of the
 * handlers that call `stepDown(m.term, …)`.
 *
 * [RaftMessage.Forward] / [RaftMessage.ForwardResponse] are correlated by `clientRequestId`, not by
 * term, and neither influences term state — hence `null` rather than a sentinel. The `when` is
 * exhaustive (no `else`), so a new [RaftMessage] variant must decide explicitly whether it carries a
 * term rather than silently defaulting out of the bound.
 */
internal val RaftMessage.wireTerm: Long?
    get() = when (this) {
        is RaftMessage.RequestVote             -> term
        is RaftMessage.RequestVoteResponse     -> term
        is RaftMessage.AppendEntries           -> term
        is RaftMessage.AppendEntriesResponse   -> term
        is RaftMessage.InstallSnapshot         -> term
        is RaftMessage.InstallSnapshotResponse -> term
        is RaftMessage.PreVote                 -> term
        is RaftMessage.PreVoteResponse         -> term
        is RaftMessage.TimeoutNow              -> term
        is RaftMessage.Forward                 -> null
        is RaftMessage.ForwardResponse         -> null
    }

/**
 * `true` for the three RPCs only a leader ever sends to a peer.
 *
 * §5.2 makes this set special: a candidate needs a majority of the voter set to win, so the sender of
 * one of these is claiming to be leader and therefore claiming to be a voter. That is what
 * `RaftEngine.onMessage`'s §5.2/§8 leader-authority gate (#1383, #1889) tests, and — since these are
 * also the only frames that can carry a config or advance a follower's commit index — what makes a
 * *refused* one, unlike a refused vote frame, evidence of a node that cannot make progress (#1898).
 *
 * Exhaustive `when` (no `else`) for the same reason as [wireTerm]: a new [RaftMessage] variant must
 * decide explicitly which side of this it is on.
 */
internal val RaftMessage.isLeaderToPeer: Boolean
    get() = when (this) {
        is RaftMessage.AppendEntries           -> true
        is RaftMessage.InstallSnapshot         -> true
        is RaftMessage.TimeoutNow              -> true
        is RaftMessage.RequestVote             -> false
        is RaftMessage.RequestVoteResponse     -> false
        is RaftMessage.AppendEntriesResponse   -> false
        is RaftMessage.InstallSnapshotResponse -> false
        is RaftMessage.PreVote                 -> false
        is RaftMessage.PreVoteResponse         -> false
        is RaftMessage.Forward                 -> false
        is RaftMessage.ForwardResponse         -> false
    }

/**
 * This frame's public [RaftMessageType] — the wire vocabulary a trace consumer can hold.
 *
 * `RaftTraceEvent.FrameRefused` reports the *type* of a refused frame rather than the frame itself,
 * because these classes are internal. Deriving it here rather than from `this::class.simpleName`
 * (what the engine's operator-facing `wedgeDiagnostic` string does) makes it comparable and
 * cross-target stable: `simpleName` is not guaranteed identical on Kotlin/Native, JVM and wasmJs, so
 * a test asserting on one would not be portable.
 *
 * Exhaustive `when` (no `else`) for the same reason as [wireTerm] and [isLeaderToPeer]: a new
 * [RaftMessage] variant must name itself rather than silently falling into a catch-all.
 */
internal val RaftMessage.messageType: RaftMessageType
    get() = when (this) {
        is RaftMessage.RequestVote             -> RaftMessageType.RequestVote
        is RaftMessage.RequestVoteResponse     -> RaftMessageType.RequestVoteResponse
        is RaftMessage.AppendEntries           -> RaftMessageType.AppendEntries
        is RaftMessage.AppendEntriesResponse   -> RaftMessageType.AppendEntriesResponse
        is RaftMessage.InstallSnapshot         -> RaftMessageType.InstallSnapshot
        is RaftMessage.InstallSnapshotResponse -> RaftMessageType.InstallSnapshotResponse
        is RaftMessage.PreVote                 -> RaftMessageType.PreVote
        is RaftMessage.PreVoteResponse         -> RaftMessageType.PreVoteResponse
        is RaftMessage.TimeoutNow              -> RaftMessageType.TimeoutNow
        is RaftMessage.Forward                 -> RaftMessageType.Forward
        is RaftMessage.ForwardResponse         -> RaftMessageType.ForwardResponse
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
