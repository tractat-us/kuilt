@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// SCRATCH: pre-fix characterization, captures the "before" half of the #2052 mutation receipt.
class VoteDenyAttributionTest {

    @Test
    fun beforeReceiptTwoConjunctsReportOnlyAlreadyVoted() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val voter = sim.nodeIds.first { it != leaderId }
        val other = sim.nodeIds.first { it != leaderId && it != voter }

        sim.proposeOnLeader(byteArrayOf(1))
        sim.awaitCommit(1L, on = setOf(voter))

        sim.partitionOff(voter)
        delay(100)
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(voter).trace.collect { trace += it } }
        sim.settle()

        sim.deliverRequestVote(to = voter, from = leaderId, term = 50L, lastLogIndex = 99L, lastLogTerm = 99L)
        sim.settle()
        assertTrue(trace.any { it is RaftTraceEvent.VoteGranted }, "fixture: staging vote must be granted: $trace")

        trace.clear()
        sim.deliverRequestVote(to = voter, from = other, term = 50L, lastLogIndex = 0L, lastLogTerm = 0L)
        sim.settle()

        val denied = trace.filterIsInstance<RaftTraceEvent.VoteDenied>()
        assertEquals(listOf(DenyReason.AlreadyVoted), denied.map { it.reason }, "trace=$trace")
    }
}
