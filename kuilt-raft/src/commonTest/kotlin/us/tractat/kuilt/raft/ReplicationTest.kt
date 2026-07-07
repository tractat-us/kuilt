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
import us.tractat.kuilt.test.assertAll
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

    /**
     * §5.3 fast-backup livelock regression (issue #1246).
     *
     * A follower that is merely missing a *suffix* of the log (its log is too short — the leader's
     * `prevLogIndex` is beyond the follower's `lastLogIndex`) must CONVERGE after a leader change, not
     * loop forever. The bug: the too-short rejection synthesised a `conflictTerm` from the follower's
     * last entry, and the leader's `lastOfTerm(conflictTerm)+1` reproduced the SAME `nextIndex` every
     * heartbeat — an identical AppendEntries, an identical rejection, forever. The follower resets its
     * election timer on every rejected AE, so it never campaigns out; enough stuck followers and the
     * leader loses commit quorum (a cluster-wide write outage).
     *
     * Scenario (the issue's): a laggard holds `[1:no-op, 2:user]`; a fresh leader holds `[1..5]` plus
     * its term-2 no-op at 6, with `nextIndex[laggard]` initialised past the laggard's end. With the
     * fix the reject reports `conflictTerm=null, conflictIndex=lastLogIndex+1`, the leader backs
     * `nextIndex` straight to the laggard's end, and replication converges within the bounded await.
     *
     * Failure signature without the fix: the leader re-sends AppendEntries immediately on every
     * rejection (no delay), so the reject/resend loop spins at a *single* virtual instant and freezes
     * virtual time. `awaitCommit`'s virtual `withTimeout` therefore never fires (no state dump); the
     * red is `runTest`'s wall-clock `UncompletedCoroutinesError` — the frozen-virtual-time hot-loop
     * signature (see the repo's diagnostic playbook), NOT an `awaitCommit` timeout dump.
     */
    @Test
    fun tooShortFollowerConvergesAfterLeaderChange_noFastBackupLivelock() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val (survivorId, laggardId) = sim.nodeIds.filter { it != leaderId }.let { it[0] to it[1] }

        // Baseline: one user entry replicated to ALL three. The laggard now holds [1:no-op, 2:user].
        leader.propose(byteArrayOf(1))
        sim.awaitCommit(2L, on = sim.nodeIds)

        // Isolate the laggard, then extend the leader + survivor well past it (indices 3, 4, 5).
        sim.partitionOff(laggardId)
        leader.propose(byteArrayOf(2))
        leader.propose(byteArrayOf(3))
        leader.propose(byteArrayOf(4))
        sim.awaitCommit(5L, on = listOf(leaderId, survivorId))

        // Force a leader change: crash the old leader and heal the partition. The survivor's log is
        // up-to-date so it wins the election; becomeLeader RE-INITIALISES nextIndex[laggard] to its
        // own lastLogIndex + 1 — past the laggard's end — arming the fast-backup path. The new leader
        // appends its term-2 no-op at index 6.
        sim.crash(leaderId)
        sim.heal()
        sim.awaitLeader(among = setOf(survivorId))

        // Convergence: the laggard — merely missing a suffix — catches up within the bounded await.
        // With the livelock this never happens and awaitCommit fails fast with a dump.
        sim.awaitCommit(6L, on = listOf(survivorId, laggardId))

        val survivorLog = sim.storages.getValue(survivorId).entries(1L).map { it.index }
        val laggardLog = sim.storages.getValue(laggardId).entries(1L).map { it.index }
        assertAll(
            { assertEquals(6L, sim.nodes.getValue(laggardId).commitIndex.value, "laggard commit converged") },
            { assertEquals(survivorLog, laggardLog, "laggard log matches the new leader's after convergence") },
        )
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
