@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val fastConfig = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
)
private val voterIds = setOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
private val learnerId = NodeId("learner")
private val clusterConfig = ClusterConfig(voters = voterIds, learners = setOf(learnerId))

private fun TestScope.simWithLearner(): RaftSimulation = RaftSimulation(
    nodeIds = voterIds.toList() + learnerId,
    scope = this,
    raftConfig = fastConfig,
    nodeScope = backgroundScope,
    nodeFactory = { id, transport, storage, nodeScope ->
        nodeScope.raftNode(clusterConfig, transport, storage, fastConfig)
    },
)

private suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
    repeat(500) {
        sim.leader()?.let { return it }
        delay(1)
    }
    error("No leader elected within timeout")
}

class LearnerTest {

    @Test
    fun learner_receives_committed_entries() = runTest(UnconfinedTestDispatcher()) {
        val sim = simWithLearner()
        val leader = awaitLeader(sim)
        val learner = sim.nodes[learnerId]
        assertNotNull(learner)
        assertIs<RaftRole.Learner>(learner.role.value)
        val received = async { learner.committed.first() }
        delay(1) // let collector subscribe before proposing
        leader.propose(byteArrayOf(42))
        assertContentEquals(byteArrayOf(42), received.await().command)
    }

    @Test
    fun learner_never_becomes_leader() = runTest(UnconfinedTestDispatcher()) {
        val sim = simWithLearner()
        awaitLeader(sim)
        assertIs<RaftRole.Learner>(sim.nodes[learnerId]!!.role.value)
    }

    @Test
    fun learner_propose_throws_NotLeaderException() = runTest(UnconfinedTestDispatcher()) {
        val sim = simWithLearner()
        awaitLeader(sim)
        assertFailsWith<NotLeaderException> { sim.nodes[learnerId]!!.propose(byteArrayOf(1)) }
    }

    @Test
    fun learner_catchup_after_partition() = runTest(UnconfinedTestDispatcher()) {
        val sim = simWithLearner()
        val leader = awaitLeader(sim)
        val learner = sim.nodes[learnerId]!!

        // Partition learner away from voters — quorum (2 of 3) still intact
        sim.partition(setOf(learnerId), voterIds)

        // Commit two entries while learner is isolated
        leader.propose(byteArrayOf(10))
        leader.propose(byteArrayOf(20))

        // Heal — AppendEntries backfill should deliver missed entries to learner
        sim.heal()

        val entries = mutableListOf<LogEntry>()
        val job = launch { learner.committed.collect { entries.add(it) } }
        delay(100)
        job.cancel()

        assertTrue(entries.isNotEmpty(), "Learner received no entries after healing partition")
    }
}
