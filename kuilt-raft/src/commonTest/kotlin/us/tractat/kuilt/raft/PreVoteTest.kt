@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PreVoteTest {

    // A follower that has NOT heard from a leader grants a pre-vote for an up-to-date candidate.
    @Test
    fun granterGrantsPreVoteWhenNoLeaderAndLogOk() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val v1 = NodeId("v1"); val v2 = NodeId("v2"); val v3 = NodeId("v3")
        val granterTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(v2).trace.collect { granterTrace += it } }
        // Isolate all three nodes so no leader is ever established — leaderAlive stays false on v2.
        sim.partition(setOf(v1), setOf(v2))
        sim.partition(setOf(v1), setOf(v3))
        sim.partition(setOf(v2), setOf(v3))
        // Deliver a PreVote from v1 to v2 (empty logs are mutually up-to-date).
        sim.deliverPreVote(to = v2, from = v1, term = 1L, lastLogIndex = 0L, lastLogTerm = 0L)
        sim.settle()
        assertTrue(granterTrace.any { it is RaftTraceEvent.PreVoteGranted },
            "no-leader granter should grant: $granterTrace")
    }

    // After hearing AppendEntries from a leader, the same node DENIES pre-votes (leaderAlive).
    @Test
    fun granterDeniesPreVoteWhileLeaderAlive() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val follower = sim.nodeIds.first { it != leaderId }
        withTimeout(2.seconds) { sim.nodes.getValue(follower).commitIndex.first { it >= 1L } }
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(follower).trace.collect { trace += it } }
        sim.deliverPreVote(to = follower, from = leaderId, term = 99L, lastLogIndex = 99L, lastLogTerm = 99L)
        sim.settle()
        assertTrue(trace.any { it is RaftTraceEvent.PreVoteDenied },
            "follower hearing the leader must deny pre-votes: $trace")
    }
}
