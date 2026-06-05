@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val fastConfig = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
)

private fun TestScope.threeNodeSim(): RaftSimulation {
    val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
    val config = ClusterConfig(voters = ids.toSet())
    return RaftSimulation(
        nodeIds = ids,
        scope = this,
        raftConfig = fastConfig,
        nodeScope = backgroundScope,
        nodeFactory = { id, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, fastConfig)
        },
    )
}

private suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
    repeat(500) {
        sim.leader()?.let { return it }
        delay(1)
    }
    error("No leader elected within timeout")
}

class ElectionTest {
    @Test fun initialElection_elects_exactly_one_leader() = runTest(UnconfinedTestDispatcher()) {
        val sim = threeNodeSim()
        val leader = awaitLeader(sim)
        assertNotNull(leader)
        assertTrue(leader.role.value is RaftRole.Leader)
        sim.checkInvariants()
    }

    @Test fun reElection_after_leader_crash() = runTest(UnconfinedTestDispatcher()) {
        val sim = threeNodeSim()
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        sim.crash(leaderId)
        delay(50)
        val newLeader = awaitLeader(sim)
        assertNotNull(newLeader)
        assertTrue(newLeader !== leader)
        sim.checkInvariants()
    }

    @Test fun manyElections_invariants_hold() = runTest(UnconfinedTestDispatcher()) {
        val sim = threeNodeSim()
        repeat(5) {
            val leader = awaitLeader(sim)
            val id = sim.nodes.entries.first { it.value === leader }.key
            sim.crash(id)
            delay(20)
            sim.checkInvariants()
            sim.restart(id)
            delay(10)
        }
    }
}
