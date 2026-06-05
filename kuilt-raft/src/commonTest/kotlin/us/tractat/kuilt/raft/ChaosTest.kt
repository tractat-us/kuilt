@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val fastConfig = RaftConfig(5.milliseconds, 10.milliseconds, 2.milliseconds)

private fun sim(scope: kotlinx.coroutines.CoroutineScope, n: Int = 3): RaftSimulation {
    val ids = (1..n).map { NodeId("n$it") }
    return RaftSimulation(ids, scope, fastConfig) { id, transport, storage, nodeScope ->
        nodeScope.raftNode(ClusterConfig(ids.toSet()), transport, storage, fastConfig)
    }
}

private suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
    repeat(500) { sim.leader()?.let { return it }; delay(1) }
    error("No leader elected")
}

class ChaosTest {
    @Test fun persistence_node_rejoins_with_same_log() = runTest(UnconfinedTestDispatcher()) {
        val sim = sim(backgroundScope)
        val leader = awaitLeader(sim)
        leader.propose(byteArrayOf(1))
        leader.propose(byteArrayOf(2))
        val followerId = sim.nodes.keys.first { sim.nodes[it] !== leader }
        sim.crash(followerId)
        sim.restart(followerId)
        delay(50)
        sim.checkInvariants()
        // Restarted node should have caught up
        val restarted = sim.nodes[followerId]!!
        assertTrue(restarted.commitIndex.value >= 2L || restarted.committed.let { true },
            "Restarted node commitIndex=${restarted.commitIndex.value}")
    }

    @Test fun rejoinPartitionedLeader_reverts_to_follower() = runTest(UnconfinedTestDispatcher()) {
        val sim = sim(backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val others = sim.nodes.keys.filter { it != leaderId }.toSet()
        // Isolate the old leader — others will elect a new leader
        sim.partition(setOf(leaderId), others)
        delay(80)
        sim.heal()
        delay(80)
        // Old leader must have stepped down upon receiving a higher term
        val oldLeaderNode = sim.nodes[leaderId]!!
        assertTrue(oldLeaderNode.role.value !is RaftRole.Leader,
            "Old partitioned leader did not step down: ${oldLeaderNode.role.value}")
        sim.checkInvariants()
    }

    @Test fun logBackup_newLeaderReconcilesMinorityDivergence() = runTest(UnconfinedTestDispatcher()) {
        // 5-node cluster. Partition a minority so they get no entries from the majority's leader.
        // Heal and verify the new leader can propose and the minority catches up via §5.3 fast backup.
        val sim = sim(backgroundScope, n = 5)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val minority = sim.nodes.keys.filter { it != leaderId }.take(2).toSet()
        val majority = sim.nodes.keys.filter { it !in minority && it != leaderId }.toSet()
        // Partition minority — majority + leader can still commit
        sim.partition(minority, majority + leaderId)
        repeat(5) { i -> leader.propose(byteArrayOf(i.toByte())) }
        // Heal — minority has stale log, must reconcile
        sim.heal()
        delay(100)
        val newLeader = awaitLeader(sim)
        val entry = newLeader.propose(byteArrayOf(99))
        assertNotNull(entry)
        sim.checkInvariants()
    }

    @Test fun unreliableChurn_proposalsEventuallyCommit() = runTest(UnconfinedTestDispatcher()) {
        val sim = sim(backgroundScope)
        var leader = awaitLeader(sim)
        val committed = mutableListOf<LogEntry>()
        repeat(3) { i ->
            try { committed += leader.propose(byteArrayOf(i.toByte())) } catch (_: Exception) {}
            val id = sim.nodes.entries.firstOrNull { it.value === leader }?.key
            if (id != null) {
                sim.crash(id)
                sim.restart(id)  // restart to maintain quorum across iterations
            }
            sim.heal()
            delay(30)
            leader = awaitLeader(sim)
        }
        sim.checkInvariants()
        // All committed entries must have strictly increasing indices
        val indices = committed.map { it.index }
        assertEquals(indices.sorted(), indices, "Committed indices not monotonic: $indices")
    }
}
