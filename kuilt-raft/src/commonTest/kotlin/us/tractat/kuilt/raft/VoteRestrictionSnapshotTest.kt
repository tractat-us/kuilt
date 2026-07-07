@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §5.4.1 election restriction across a compaction boundary (issue #1245, SAFETY).
 *
 * A voter that has compacted past its entire live log (live log empty, `snapshotIndex = N`) still
 * holds committed entries 1..N — they now live in its snapshot, not in memory. The vote restriction
 * must compare an incoming candidate's `(lastLogTerm, lastLogIndex)` against the voter's
 * **snapshot-aware** last position `(snapshotTerm, snapshotIndex)`, NOT the empty live log (whose
 * `lastOrNull()` is null and would be read as `(0, 0)` — making every candidate look up-to-date and
 * letting a stale candidate win, dropping committed entries → Leader Completeness violation).
 */
class VoteRestrictionSnapshotTest {

    // A voter compacted through N must DENY a RequestVote whose log ends before N, and GRANT one at/ahead of N.
    @Test
    fun compactedVoterRejectsStaleRequestVoteButGrantsUpToDate() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val voter = sim.nodeIds.first { it != leaderId }

        // Commit a batch, then compact the chosen voter past its whole live log.
        repeat(5) { sim.proposeOnLeader(byteArrayOf(it.toByte())) }
        val n = leader.commitIndex.value
        sim.awaitCommit(n, on = setOf(voter))
        sim.nodes.getValue(voter).snapshots.value = Snapshot(throughIndex = n, state = byteArrayOf(0))
        sim.awaitTrue("voter compacted through $n") { sim.nodes.getValue(voter).compactionFloor.value == n }
        val meta = sim.storages.getValue(voter).loadSnapshot()!!.meta   // (lastIncludedIndex=n, lastIncludedTerm)

        // Isolate the voter so its leader lease lapses (leaderAlive=false → §4.2.3 stickiness does not
        // pre-empt the §5.4.1 check) and pre-vote keeps its term from inflating.
        sim.partitionOff(voter)
        delay(100)   // let the leader lease expire
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(voter).trace.collect { trace += it } }
        sim.settle()

        // Candidate whose log ends BEFORE the voter's snapshot: same term, lower index → must be DENIED.
        sim.deliverRequestVote(
            to = voter, from = leaderId, term = meta.lastIncludedTerm + 5,
            lastLogIndex = meta.lastIncludedIndex - 1, lastLogTerm = meta.lastIncludedTerm,
        )
        sim.settle()
        // Candidate at/ahead of the snapshot: same term, equal index (higher term resets votedFor) → GRANTED.
        sim.deliverRequestVote(
            to = voter, from = leaderId, term = meta.lastIncludedTerm + 6,
            lastLogIndex = meta.lastIncludedIndex, lastLogTerm = meta.lastIncludedTerm,
        )
        sim.settle()

        assertAll(
            { assertTrue(trace.any { it is RaftTraceEvent.VoteDenied && it.reason == DenyReason.LogNotUpToDate },
                "compacted voter must DENY a candidate whose log ends before its snapshot (index<$n): $trace") },
            { assertTrue(trace.any { it is RaftTraceEvent.VoteGranted },
                "compacted voter must GRANT a candidate at/ahead of its snapshot (index>=$n): $trace") },
        )
    }

    // The same restriction on the pre-vote path (issue #1245): compacted voter denies a behind pre-vote.
    @Test
    fun compactedVoterRejectsStalePreVoteButGrantsUpToDate() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val voter = sim.nodeIds.first { it != leaderId }

        repeat(5) { sim.proposeOnLeader(byteArrayOf(it.toByte())) }
        val n = leader.commitIndex.value
        sim.awaitCommit(n, on = setOf(voter))
        sim.nodes.getValue(voter).snapshots.value = Snapshot(throughIndex = n, state = byteArrayOf(0))
        sim.awaitTrue("voter compacted through $n") { sim.nodes.getValue(voter).compactionFloor.value == n }
        val meta = sim.storages.getValue(voter).loadSnapshot()!!.meta

        sim.partitionOff(voter)
        delay(100)   // leaderAlive → false so pre-votes may be granted
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(voter).trace.collect { trace += it } }
        sim.settle()

        // Behind the snapshot → PreVoteDenied(LogNotUpToDate).
        sim.deliverPreVote(
            to = voter, from = leaderId, term = meta.lastIncludedTerm + 5,
            lastLogIndex = meta.lastIncludedIndex - 1, lastLogTerm = meta.lastIncludedTerm,
        )
        sim.settle()
        // At/ahead of the snapshot → PreVoteGranted.
        sim.deliverPreVote(
            to = voter, from = leaderId, term = meta.lastIncludedTerm + 6,
            lastLogIndex = meta.lastIncludedIndex, lastLogTerm = meta.lastIncludedTerm,
        )
        sim.settle()

        assertAll(
            { assertTrue(trace.any { it is RaftTraceEvent.PreVoteDenied && it.reason == DenyReason.LogNotUpToDate },
                "compacted voter must DENY a pre-vote whose log ends before its snapshot (index<$n): $trace") },
            { assertTrue(trace.any { it is RaftTraceEvent.PreVoteGranted },
                "compacted voter must GRANT a pre-vote at/ahead of its snapshot (index>=$n): $trace") },
        )
    }
}
