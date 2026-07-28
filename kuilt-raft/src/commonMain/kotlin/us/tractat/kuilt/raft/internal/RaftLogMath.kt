package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.LogEntry

/**
 * Pure, stateless Raft log-math functions extracted from [RaftEngine].
 *
 * Each function takes only what it needs as parameters — no engine state is captured —
 * making them straightforwardly unit-testable without a running cluster.
 */

/**
 * The position of a log's last entry, ordered by the §5.4.1 "up-to-date" relation:
 * higher [term] wins; equal terms compare by [index].
 *
 * This is a value type on purpose (issue #1254): the previous `isLogUpToDate(ourLastTerm,
 * ourLastIndex, candidateLastIndex, candidateLastTerm)` signature ordered the two sides
 * oppositely (inherited from the wire-message field order), so transposing two bare `Long`s
 * at a call site compiled silently — in the vote-restriction path, an election-safety hazard.
 * With [LogPosition] each side is constructed once, next to its source fields
 * (`RaftState.lastLogPosition`, `RequestVote.lastLogPosition`, `PreVote.lastLogPosition`),
 * and a term/index swap is a compile error.
 */
internal data class LogPosition(
    val term: Long,
    val index: Long,
) : Comparable<LogPosition> {
    override fun compareTo(other: LogPosition): Int =
        compareValuesBy(this, other, LogPosition::term, LogPosition::index)
}

/**
 * §5.4.1 election restriction: is a candidate's log at least as up-to-date as ours?
 *
 * A candidate's log is "at least as up-to-date" if:
 * - its last log term is greater than ours, OR
 * - its last log term equals ours AND its last log index is at least as large.
 *
 * i.e. `candidate >= ours` under [LogPosition]'s ordering.
 *
 * The voter's position is [ours] — NOT the live log's last entry. This is deliberate and
 * safety-critical (issue #1245): after compaction empties the live log the voter still holds
 * committed entries in its snapshot, so callers MUST pass the **snapshot-aware** last position
 * (`RaftState.lastLogPosition`, which falls back to `snapshotTerm`/`snapshotIndex` when the live
 * log is empty). Reading `log.lastOrNull()` here would map an empty live log to `(0, 0)`, making
 * every candidate look up-to-date and letting a compacted voter grant a vote to an arbitrarily
 * stale candidate — a Leader Completeness violation.
 *
 * @param ours the voter's snapshot-aware last log position
 * @param candidate the candidate's reported last log position
 */
internal fun isLogUpToDate(ours: LogPosition, candidate: LogPosition): Boolean =
    candidate >= ours

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
 * ## The result is clamped to `1..currentNextIndex - 1` (issue #1829)
 *
 * [RaftMessage.AppendEntriesResponse.conflictIndex] arrives off the wire and was previously stored
 * into `nextIndex[peer]` verbatim, which put three failure modes one malformed frame away:
 *
 * - **Above the leader's log** ⇒ the immediately following `sendAppendEntries` computes a
 *   `prevIndex` with no backing entry and hits the hard `error("prevTerm for in-window index …
 *   missing")`. That throws inside the engine's actor loop, whose `try`/`finally` has no `catch`, so
 *   the leader is torn down permanently — the same crash #1175's success-branch clamp was added to
 *   prevent, reached through the branch that clamp does not cover.
 * - **Below 1** ⇒ `nextIndex ≤ snapshotIndex` forever, so every heartbeat diverts to
 *   `sendSnapshotChunk` and that peer never resumes log replication. A silent per-peer wedge.
 * - **Equal to `currentNextIndex`** ⇒ no progress at all. `onAppendEntriesResponse`'s rejection
 *   branch calls `sendAppendEntries(from)` **synchronously**, so an unchanged `nextIndex` emits a
 *   byte-identical frame, draws an identical rejection, and ping-pongs with no delay — the §5.3
 *   fast-backup livelock of #1246, and in this repo's virtual-time harness it **hangs rather than
 *   fails**. This is why the ceiling is `currentNextIndex - 1` and not `currentNextIndex`:
 *   §5.3 requires backup to *back up*, so the value must **strictly decrease** on a rejection.
 *
 * `conflictIndex` is a **quantity**, not a nonce, so a conservative in-range reading exists and the
 * clamp is the right disposition (unlike the round echo of #1817, where an out-of-range value is
 * proof of forgery and must be discarded). But note the trap the exclusive ceiling closes: clamping
 * was the right *shape* for a quantity, and an inclusive bound would still have laundered hostile
 * input into the most favourable valid value — just at the boundary instead of across the range.
 *
 * Both honest constructions are bounded by the index the leader probed —
 * `prevLogIndex = currentNextIndex - 1`: the "log too short" reply reports
 * `followerLastLogIndex + 1`, taken only when `prevLogIndex > followerLastLogIndex`; a real term
 * conflict reports the first index carrying the conflicting term, at or below `prevLogIndex`. §5.3
 * fast backup is monotonically non-increasing by construction, so an honest `conflictIndex` always
 * satisfies `conflictIndex <= currentNextIndex - 1` and the clamp is a no-op for every correct
 * follower — the same bound the no-metadata fallback beside it already applies.
 *
 * The `lastOfTerm.index + 1` path is routed through the same clamp so the invariant lives in one
 * place: it is already log-bounded, but a follower-supplied `conflictTerm` that the leader happens
 * to hold *above* the probed index would otherwise move `nextIndex` **forward** on a rejection —
 * the one direction §5.3 backup must never take.
 *
 * The floor at 1 is the one place strict decrease cannot hold: `nextIndex` has nowhere lower to go,
 * so a peer that rejects at `nextIndex == 1` is a fixed point. That is unreachable honestly — a
 * rejection requires `prevLogIndex > snapshotIndex`, and at `nextIndex == 1` the probe is
 * `prevLogIndex == 0` — and it is a pre-existing property of the floor, not of this clamp.
 *
 * The clamp is written out rather than expressed as `coerceIn` on purpose: `coerceIn` throws when
 * the range is inverted (reachable here at `currentNextIndex == 1`, where the ceiling is 0), and
 * throwing would kill the actor loop — the exact failure this function is being hardened against.
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
    val proposed = if (response.conflictTerm != null) {
        val lastOfTerm = log.lastOrNull { it.term == response.conflictTerm }
        if (lastOfTerm != null) lastOfTerm.index + 1L
        else response.conflictIndex ?: maxOf(1L, currentNextIndex - 1L)
    } else {
        response.conflictIndex ?: maxOf(1L, currentNextIndex - 1L)
    }
    return maxOf(1L, minOf(proposed, currentNextIndex - 1L))
}

/**
 * O(1) log lookup: the entry at the given Raft log [index], or null if [index] falls outside the
 * live log window.
 *
 * The live log is the contiguous suffix that begins at `snapshotIndex + 1` — all earlier entries
 * have been compacted into a snapshot. Because indices are monotonically increasing and there are
 * no gaps, the offset from the base is exactly `index - (snapshotIndex + 1)`.
 *
 * Returns null when:
 * - [log] is empty (all entries are in a snapshot, or the cluster just started)
 * - [index] ≤ [snapshotIndex] (compacted away)
 * - [index] > last entry's index (not yet appended)
 *
 * @param log the in-memory log suffix (entries at indices `snapshotIndex + 1 .. lastLogIndex`)
 * @param snapshotIndex the last index covered by the most-recently-installed snapshot (0 if none)
 * @param index the 1-based Raft log index to look up
 */
internal fun logEntryAt(log: List<LogEntry>, snapshotIndex: Long, index: Long): LogEntry? {
    if (log.isEmpty()) return null
    val offset = index - (snapshotIndex + 1L)
    if (offset < 0L || offset >= log.size) return null
    return log[offset.toInt()]
}

/**
 * O(1) log slice: the entries at indices `[fromIndex, lastLogIndex]`, as a [List.subList] view.
 *
 * Clamps [fromIndex] to the log base (`snapshotIndex + 1`) when it falls below it — i.e. all
 * entries in the live window are included. Returns an empty list when [fromIndex] is beyond the
 * last entry or [log] is empty.
 *
 * The returned list is a subList view (backed by the original list); callers must not mutate the
 * backing log while iterating it.
 *
 * @param log the in-memory log suffix (entries at indices `snapshotIndex + 1 .. lastLogIndex`)
 * @param snapshotIndex the last index covered by the most-recently-installed snapshot (0 if none)
 * @param fromIndex the first Raft log index to include in the slice
 */
internal fun logSliceFrom(log: List<LogEntry>, snapshotIndex: Long, fromIndex: Long): List<LogEntry> {
    if (log.isEmpty()) return emptyList()
    val baseIndex = snapshotIndex + 1L
    val clampedFrom = maxOf(fromIndex, baseIndex)
    val offset = (clampedFrom - baseIndex).toInt()
    if (offset >= log.size) return emptyList()
    return log.subList(offset, log.size)
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
