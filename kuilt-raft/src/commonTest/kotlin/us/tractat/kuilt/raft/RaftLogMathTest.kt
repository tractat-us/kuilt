package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.raft.internal.isLogUpToDate
import us.tractat.kuilt.raft.internal.majorityCommitIndex
import us.tractat.kuilt.raft.internal.nextIndexAfterFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftLogMathTest {

    // ── isLogUpToDate ─────────────────────────────────────────────────────────

    @Test
    fun isLogUpToDate_emptyOurLog_emptyCandidate_granted() {
        assertTrue(isLogUpToDate(null, candidateLastIndex = 0L, candidateLastTerm = 0L))
    }

    @Test
    fun isLogUpToDate_emptyOurLog_candidateHasEntries_granted() {
        assertTrue(isLogUpToDate(null, candidateLastIndex = 5L, candidateLastTerm = 3L))
    }

    @Test
    fun isLogUpToDate_candidateHigherTerm_granted() {
        val ourLast = LogEntry(index = 10L, term = 2L, command = byteArrayOf())
        assertTrue(isLogUpToDate(ourLast, candidateLastIndex = 1L, candidateLastTerm = 3L))
    }

    @Test
    fun isLogUpToDate_candidateLowerTerm_denied() {
        val ourLast = LogEntry(index = 10L, term = 3L, command = byteArrayOf())
        assertFalse(isLogUpToDate(ourLast, candidateLastIndex = 99L, candidateLastTerm = 2L))
    }

    @Test
    fun isLogUpToDate_sameTerm_equalIndex_granted() {
        val ourLast = LogEntry(index = 5L, term = 3L, command = byteArrayOf())
        assertTrue(isLogUpToDate(ourLast, candidateLastIndex = 5L, candidateLastTerm = 3L))
    }

    @Test
    fun isLogUpToDate_sameTerm_longerCandidateLog_granted() {
        val ourLast = LogEntry(index = 5L, term = 3L, command = byteArrayOf())
        assertTrue(isLogUpToDate(ourLast, candidateLastIndex = 6L, candidateLastTerm = 3L))
    }

    @Test
    fun isLogUpToDate_sameTerm_shorterCandidateLog_denied() {
        val ourLast = LogEntry(index = 5L, term = 3L, command = byteArrayOf())
        assertFalse(isLogUpToDate(ourLast, candidateLastIndex = 4L, candidateLastTerm = 3L))
    }

    // ── nextIndexAfterFailure ─────────────────────────────────────────────────

    private fun failResponse(conflictIndex: Long? = null, conflictTerm: Long? = null) =
        RaftMessage.AppendEntriesResponse(
            term = 1L,
            success = false,
            conflictIndex = conflictIndex,
            conflictTerm = conflictTerm,
        )

    @Test
    fun nextIndexAfterFailure_noConflictMetadata_decrementsOneStep() {
        val log = listOf(
            LogEntry(1L, 1L, byteArrayOf()),
            LogEntry(2L, 1L, byteArrayOf()),
        )
        assertEquals(4L, nextIndexAfterFailure(5L, failResponse(), log))
    }

    @Test
    fun nextIndexAfterFailure_noConflictMetadata_neverBelowOne() {
        assertEquals(1L, nextIndexAfterFailure(1L, failResponse(), emptyList()))
    }

    @Test
    fun nextIndexAfterFailure_conflictIndexOnly_jumpsToIt() {
        val log = listOf(LogEntry(1L, 1L, byteArrayOf()))
        assertEquals(3L, nextIndexAfterFailure(10L, failResponse(conflictIndex = 3L), log))
    }

    @Test
    fun nextIndexAfterFailure_conflictTermFound_skipsPastTerm() {
        val log = listOf(
            LogEntry(1L, 1L, byteArrayOf()),
            LogEntry(2L, 2L, byteArrayOf()),
            LogEntry(3L, 2L, byteArrayOf()),
            LogEntry(4L, 3L, byteArrayOf()),
        )
        // conflictTerm=2: last entry with term 2 is at index 3 → nextIndex = 4
        assertEquals(4L, nextIndexAfterFailure(10L, failResponse(conflictTerm = 2L), log))
    }

    @Test
    fun nextIndexAfterFailure_conflictTermNotInLeaderLog_usesConflictIndex() {
        val log = listOf(LogEntry(1L, 1L, byteArrayOf()), LogEntry(2L, 3L, byteArrayOf()))
        // conflictTerm=2 is not in leader's log → fall back to conflictIndex
        assertEquals(5L, nextIndexAfterFailure(10L, failResponse(conflictIndex = 5L, conflictTerm = 2L), log))
    }

    @Test
    fun nextIndexAfterFailure_conflictTermNotFound_noConflictIndex_decrements() {
        val log = listOf(LogEntry(1L, 1L, byteArrayOf()))
        // conflictTerm=99 absent, no conflictIndex → currentNextIndex - 1
        assertEquals(9L, nextIndexAfterFailure(10L, failResponse(conflictTerm = 99L), log))
    }

    // ── majorityCommitIndex ───────────────────────────────────────────────────

    @Test
    fun majorityCommitIndex_singleVoter_returnsLeaderLastIndex() {
        // peerQuorum=0: leader is the sole voter
        assertEquals(5L, majorityCommitIndex(emptyList(), peerQuorum = 0, leaderLastIndex = 5L))
    }

    @Test
    fun majorityCommitIndex_singleVoter_emptyLog_returnsNull() {
        assertNull(majorityCommitIndex(emptyList(), peerQuorum = 0, leaderLastIndex = 0L))
    }

    @Test
    fun majorityCommitIndex_threeNode_quorumMet() {
        // 3-node cluster: peerQuorum=1 (need 1 peer + leader = 2/3)
        val matches = listOf(4L, 2L) // sorted desc → [4, 2]; [0] = 4
        assertEquals(4L, majorityCommitIndex(matches, peerQuorum = 1, leaderLastIndex = 5L))
    }

    @Test
    fun majorityCommitIndex_fiveNode_quorumMet() {
        // 5-node cluster: peerQuorum=2 (need 2 peers + leader = 3/5)
        val matches = listOf(6L, 4L, 2L) // sorted desc → [6, 4, 2]; [1] = 4
        assertEquals(4L, majorityCommitIndex(matches, peerQuorum = 2, leaderLastIndex = 7L))
    }

    @Test
    fun majorityCommitIndex_insufficientAcks_returnsNull() {
        // 3-node: peerQuorum=1, but no peers have ACKed
        assertNull(majorityCommitIndex(emptyList(), peerQuorum = 1, leaderLastIndex = 5L))
    }

    @Test
    fun majorityCommitIndex_fiveNode_onlyOneAck_returnsNull() {
        // 5-node: peerQuorum=2, only 1 peer has ACKed → no majority yet
        assertNull(majorityCommitIndex(listOf(5L), peerQuorum = 2, leaderLastIndex = 6L))
    }

    @Test
    fun majorityCommitIndex_voterMatchesOnly_learnerExcluded() {
        // With 3 voters (peerQuorum=1), only voter matches are passed; learners excluded.
        // One voter ACKed at index 3 — that alone satisfies quorum.
        val voterMatchesOnly = listOf(3L)
        assertEquals(3L, majorityCommitIndex(voterMatchesOnly, peerQuorum = 1, leaderLastIndex = 5L))
    }
}
