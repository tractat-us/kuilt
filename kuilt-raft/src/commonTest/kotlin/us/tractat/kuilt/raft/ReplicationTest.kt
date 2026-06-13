@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ReplicationTest {

    @Test
    fun basicReplication_entryReachesAllFollowers() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)

        // Collect first non-no-op (user) entry on all followers before proposing.
        val followerJobs = sim.followers().map { f ->
            async {
                f.committed.filterIsInstance<Committed.Entry>().map { it.entry }
                    .filter { it.command.isNotEmpty() }.first()
            }
        }

        val entry = leader.propose(byteArrayOf(1, 2, 3))
        assertEquals(2L, entry.index) // index 1 is the leader's no-op; first user entry is at 2

        val received = followerJobs.awaitAll()
        received.forEach { assertContentEquals(byteArrayOf(1, 2, 3), it.command) }
        sim.checkInvariants()
    }

    @Test
    fun concurrentProposals_allCommitInOrder() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
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
    fun followerFailure_quorumContinues() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val followerId = sim.nodes.keys.first { it != leaderId }

        sim.crash(followerId)

        val entry = leader.propose(byteArrayOf(99))
        assertEquals(2L, entry.index) // index 1 is the leader's no-op; first user entry is at 2
        sim.checkInvariants()
    }

    @Test
    fun leaderFailure_newLeaderCanCommit() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
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
    fun failNoAgree_quorumLost_noProgress() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        // Crash all followers — leader loses quorum.
        sim.nodes.keys.filter { it != leaderId }.forEach { sim.crash(it) }

        var committed = false
        val job = launch {
            try {
                leader.propose(byteArrayOf(55))
                committed = true
            }
            catch (_: NotLeaderException) {}
            catch (_: LeadershipLostException) {}
        }
        delay(100)
        job.cancel()

        assertFalse(committed, "Should not commit without quorum")
    }
}
