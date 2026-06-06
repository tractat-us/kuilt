package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.LogEntry

/**
 * Pure, stateless Raft log-math functions extracted from [RaftEngine].
 *
 * Each function takes only what it needs as parameters — no engine state is captured —
 * making them straightforwardly unit-testable without a running cluster.
 */

/**
 * §5.4.1 election restriction: is a candidate's log at least as up-to-date as ours?
 *
 * A candidate's log is "at least as up-to-date" if:
 * - its last log term is greater than ours, OR
 * - its last log term equals ours AND its last log index is at least as large.
 *
 * @param ourLast our own last log entry, or null if our log is empty
 * @param candidateLastIndex the candidate's reported lastLogIndex
 * @param candidateLastTerm the candidate's reported lastLogTerm
 */
internal fun isLogUpToDate(ourLast: LogEntry?, candidateLastIndex: Long, candidateLastTerm: Long): Boolean {
    val ourLastTerm = ourLast?.term ?: 0L
    val ourLastIndex = ourLast?.index ?: 0L
    return candidateLastTerm > ourLastTerm ||
        (candidateLastTerm == ourLastTerm && candidateLastIndex >= ourLastIndex)
}

/**
 * §5.3 fast-backup: where should the leader set nextIndex after a rejected AppendEntries?
 *
 * If the follower reported a [RaftMessage.AppendEntriesResponse.conflictTerm], search the
 * leader's log for the last entry with that term:
 * - Found → use that entry's index + 1 (skip over the whole conflicting term in one step).
 * - Not found → the leader doesn't have that term at all; jump straight to conflictIndex.
 *
 * Falls back to `maxOf(1, currentNextIndex - 1)` when no conflict metadata is available.
 *
 * @param currentNextIndex the current nextIndex[peer] value
 * @param response the failed AppendEntriesResponse from the follower
 * @param log the leader's current log (used to probe for conflictTerm)
 */
internal fun nextIndexAfterFailure(
    currentNextIndex: Long,
    response: RaftMessage.AppendEntriesResponse,
    log: List<LogEntry>,
): Long {
    if (response.conflictTerm != null) {
        val lastOfTerm = log.lastOrNull { it.term == response.conflictTerm }
        return if (lastOfTerm != null) lastOfTerm.index + 1L
               else response.conflictIndex ?: maxOf(1L, currentNextIndex - 1L)
    }
    return response.conflictIndex ?: maxOf(1L, currentNextIndex - 1L)
}

/**
 * Highest index replicated to a voter-majority in the current term, or null if none advances commit.
 *
 * The leader always counts itself, so [voterMatchIndices] must contain only the *other* voters'
 * matchIndex values (learners excluded — they replicate but never count toward commit).
 *
 * @param voterMatchIndices matchIndex for each other voter (learners must NOT be included)
 * @param peerQuorum the number of *other* voter acknowledgements needed (quorumSize - 1)
 * @param leaderLastIndex the leader's own last log index (counts as an implicit match)
 * @return the majority-replicated index, or null if fewer than [peerQuorum] voters have ACKed
 */
internal fun majorityCommitIndex(
    voterMatchIndices: List<Long>,
    peerQuorum: Int,
    leaderLastIndex: Long,
): Long? {
    if (peerQuorum == 0) {
        // Single-voter cluster: leader alone constitutes the majority.
        return if (leaderLastIndex > 0L) leaderLastIndex else null
    }
    if (voterMatchIndices.size < peerQuorum) return null
    return voterMatchIndices.sortedDescending()[peerQuorum - 1]
}
