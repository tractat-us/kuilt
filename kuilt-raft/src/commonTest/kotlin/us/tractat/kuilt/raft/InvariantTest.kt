@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

private val fastConfig = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
)

private fun TestScope.sim(n: Int = 3): RaftSimulation {
    val ids = (1..n).map { NodeId("inv$it") }
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

class InvariantTest {
    @Test fun invariants_hold_during_steady_state() = runTest(UnconfinedTestDispatcher()) {
        val sim = sim()
        val leader = awaitLeader(sim)
        repeat(5) { i -> leader.propose(byteArrayOf(i.toByte())) }
        delay(20)
        sim.checkInvariants()
    }

    @Test fun invariants_hold_across_leader_churn() = runTest(UnconfinedTestDispatcher()) {
        val sim = sim(5)
        repeat(3) { i ->
            val leader = awaitLeader(sim)
            try { leader.propose(byteArrayOf(i.toByte())) } catch (_: Exception) {}
            val id = sim.nodes.entries.first { e -> e.value === leader }.key
            sim.crash(id)
            delay(20)
            sim.checkInvariants()
            sim.restart(id)
            delay(10)
        }
    }

    @Test fun invariants_hold_after_partition_heal() = runTest(UnconfinedTestDispatcher()) {
        val sim = sim()
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val others = sim.nodes.keys.filter { it != leaderId }.toSet()
        sim.partition(setOf(leaderId), others)
        delay(40)
        sim.heal()
        delay(40)
        sim.checkInvariants()
    }
}
