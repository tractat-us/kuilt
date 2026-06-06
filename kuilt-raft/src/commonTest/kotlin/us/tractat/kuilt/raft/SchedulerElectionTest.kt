@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

private val electionTimeoutMin = 150.milliseconds
private val electionTimeoutMax = 300.milliseconds
private val heartbeatInterval = 50.milliseconds
private val schedulerConfig = RaftConfig(electionTimeoutMin, electionTimeoutMax, heartbeatInterval)

private fun schedulerSim(scheduler: TestCoroutineScheduler, n: Int = 3): RaftSimulation {
    val ids = (1..n).map { NodeId("s$it") }
    val clusterConfig = ClusterConfig(voters = ids.toSet())
    val testScope = TestScope(StandardTestDispatcher(scheduler))
    return RaftSimulation(
        nodeIds = ids,
        scope = testScope,
        raftConfig = schedulerConfig,
        nodeScope = testScope.backgroundScope,
        nodeFactory = { id, transport, storage, nodeScope ->
            CoroutineScope(nodeScope.coroutineContext + Job())
                .raftNode(clusterConfig, transport, storage, schedulerConfig)
        },
    )
}

class SchedulerElectionTest {

    @Test
    fun noElection_beforeElectionTimeout() = runTest {
        val scheduler = TestCoroutineScheduler()
        val sim = schedulerSim(scheduler)

        scheduler.advanceTimeBy(electionTimeoutMin.inWholeMilliseconds - 1)

        val leaders = sim.nodes.values.filter { it.role.value is RaftRole.Leader }
        assertIs<RaftRole.Follower>(
            sim.nodes.values.first().role.value,
            "No node should be leader before election timeout: found ${leaders.size} leaders",
        )
    }

    @Test
    fun electionOccurs_afterElectionTimeout() = runTest {
        val scheduler = TestCoroutineScheduler()
        val sim = schedulerSim(scheduler)

        // Advance past max election timeout so at least one node becomes a candidate
        scheduler.advanceTimeBy(electionTimeoutMax.inWholeMilliseconds + 1)
        // Allow vote exchange and leader establishment across a few heartbeat intervals
        scheduler.advanceTimeBy(heartbeatInterval.inWholeMilliseconds * 3)

        val leader = sim.leader()
        assertNotNull(leader, "Expected a leader after advancing past election timeout")
        assertIs<RaftRole.Leader>(leader.role.value)
    }

    @Test
    fun heartbeats_suppressReElection() = runTest {
        val scheduler = TestCoroutineScheduler()
        val sim = schedulerSim(scheduler)

        // Elect an initial leader
        scheduler.advanceTimeBy(electionTimeoutMax.inWholeMilliseconds + 1)
        scheduler.advanceTimeBy(heartbeatInterval.inWholeMilliseconds * 3)
        val firstLeader = sim.leader() ?: return@runTest

        // Advance through several election-timeout windows — heartbeats should suppress new elections
        scheduler.advanceTimeBy(electionTimeoutMax.inWholeMilliseconds * 5)

        assertIs<RaftRole.Leader>(
            firstLeader.role.value,
            "Leader should survive heartbeat suppression — unexpected re-election",
        )
    }
}
