@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertTrue

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }

class CheckQuorumTest {

    /**
     * Canonical #196 finding: a leader partitioned onto the minority side steps down to Follower
     * without bumping its term. In a 3-voter cluster, partitioning the leader from both followers
     * means it can no longer hear from any peer — quorum check fires and yields BecomeFollower(LostQuorum).
     */
    @Test
    fun partitionedLeader_stepsDown_withinOneElectionTimeout() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        // Isolate the leader from both followers — it cannot reach quorum.
        sim.partitionOff(leaderId)

        // Wait well past one election-timeout window (electionTimeoutMax = 10 ms).
        delay(80)

        val becomeFollowerEvent = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .firstOrNull { it.reason == StepDownReason.LostQuorum }

        assertAll(
            { assertTrue(becomeFollowerEvent != null, "expected BecomeFollower(LostQuorum) in trace: $leaderTrace") },
            { assertTrue(leader.role.value is RaftRole.Follower, "expected leader to be Follower, was: ${leader.role.value}") },
        )
    }
}
