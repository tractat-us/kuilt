@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

private val fastConfig = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
)

private fun replicationSim(
    scope: kotlinx.coroutines.CoroutineScope,
    nodeScope: kotlinx.coroutines.CoroutineScope,
    n: Int = 3,
): RaftSimulation {
    val ids = (1..n).map { NodeId("node$it") }
    val config = ClusterConfig(voters = ids.toSet())
    return RaftSimulation(
        nodeIds = ids,
        scope = scope,
        raftConfig = fastConfig,
        nodeScope = nodeScope,
        nodeFactory = { id, transport, storage, childScope ->
            childScope.raftNode(config, transport, storage, fastConfig)
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

class ReplicationTest {

    @Test
    fun basicReplication_entryReachesAllFollowers() = runTest(UnconfinedTestDispatcher()) {
        val sim = replicationSim(this, backgroundScope)
        val leader = awaitLeader(sim)

        // Start collecting on all followers before proposing so we don't miss the emission.
        val followerJobs = sim.followers().map { f ->
            async { f.committed.first() }
        }

        val entry = leader.propose(byteArrayOf(1, 2, 3))
        assertEquals(1L, entry.index)

        val received = followerJobs.awaitAll()
        received.forEach { assertContentEquals(byteArrayOf(1, 2, 3), it.command) }
        sim.checkInvariants()
    }

    @Test
    fun concurrentProposals_allCommitInOrder() = runTest(UnconfinedTestDispatcher()) {
        val sim = replicationSim(this, backgroundScope)
        val leader = awaitLeader(sim)

        val results = (1..5).map { i ->
            async { leader.propose(byteArrayOf(i.toByte())) }
        }.awaitAll()

        assertEquals(5, results.size)
        val indices = results.map { it.index }
        assertEquals(indices.sorted(), indices, "Commit indices must be monotonically increasing")
        sim.checkInvariants()
    }

    @Test
    fun followerFailure_quorumContinues() = runTest(UnconfinedTestDispatcher()) {
        val sim = replicationSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val followerId = sim.nodes.keys.first { it != leaderId }

        sim.crash(followerId)

        val entry = leader.propose(byteArrayOf(99))
        assertEquals(1L, entry.index)
        sim.checkInvariants()
    }

    @Test
    fun leaderFailure_newLeaderCanCommit() = runTest(UnconfinedTestDispatcher()) {
        val sim = replicationSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        sim.crash(leaderId)
        delay(50)

        val newLeader = awaitLeader(sim)
        assertNotNull(newLeader)
        assertIs<RaftRole.Leader>(newLeader.role.value)

        val entry = newLeader.propose(byteArrayOf(77))
        assertContentEquals(byteArrayOf(77), entry.command)
        sim.checkInvariants()
    }

    @Test
    fun failNoAgree_quorumLost_noProgress() = runTest(UnconfinedTestDispatcher()) {
        val sim = replicationSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        // Crash all followers — leader loses quorum.
        sim.nodes.keys.filter { it != leaderId }.forEach { sim.crash(it) }

        var committed = false
        val job = launch {
            try {
                leader.propose(byteArrayOf(55))
                committed = true
            } catch (_: Exception) {}
        }
        delay(100)
        job.cancel()

        assertFalse(committed, "Should not commit without quorum")
    }
}
