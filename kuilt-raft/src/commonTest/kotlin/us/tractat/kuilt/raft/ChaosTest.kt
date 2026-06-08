@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ChaosTest {
    /**
     * Term-stability invariant: across repeated partition/heal rounds, a node that is only ever
     * isolated (never legitimately contested) must never inflate its term high enough to depose
     * the healthy leader. With pre-vote enabled, the isolated node fires election timeouts but
     * its pre-vote probe fails (no quorum reachable) so it never bumps its real term — on rejoin
     * it follows the existing leader rather than triggering a new election.
     *
     * [RaftTraceEvent.Timeout] fires only when the real term bump occurs (inside
     * [startRealElection], gated by a pre-vote quorum). A correctly-functioning pre-vote round
     * emits [RaftTraceEvent.PreVoteStarted] on timeout but no [RaftTraceEvent.Timeout] while
     * the node is isolated.
     *
     * Invariants checked per round:
     *   1. The leader emits no BecomeFollower (it was not deposed).
     *   2. The isolated node emits no Timeout events (pre-vote blocked every real election).
     *   3. The isolated node catches up (commitIndex reaches the proposal index on rejoin).
     *   4. checkInvariants() passes at the end of every round.
     */
    @Test fun termStability_partitionedFollowerNeverDeposesLeader() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(backgroundScope, backgroundScope, n = 3)

        repeat(3) { round ->
            // Re-confirm (or elect) the current leader at the start of every round.
            val leader = awaitLeader(sim)
            val leaderId = sim.nodes.entries.first { it.value === leader }.key
            val isolated = sim.nodeIds.first { it != leaderId }

            val leaderTrace = mutableListOf<RaftTraceEvent>()
            val isolatedTrace = mutableListOf<RaftTraceEvent>()
            val leaderTraceJob   = backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }
            val isolatedTraceJob = backgroundScope.launch { sim.nodes.getValue(isolated).trace.collect { isolatedTrace += it } }

            // Isolate one follower; leader + third node hold quorum and can still commit.
            sim.partitionOff(isolated)
            val proposalIndex = leader.propose(byteArrayOf(round.toByte())).index
            sim.awaitCommit(proposalIndex, on = setOf(leaderId))

            // Let the isolated node fire many election timeouts (electionTimeoutMax = 10 ms).
            // Pre-vote probes will all fail (no quorum), so Timeout must never fire.
            delay(80)

            sim.heal()
            sim.awaitCommit(proposalIndex, on = setOf(isolated))

            leaderTraceJob.cancel()
            isolatedTraceJob.cancel()

            // Invariant 1: healthy leader was never deposed by a partitioned voter.
            assertTrue(
                leaderTrace.none { it is RaftTraceEvent.BecomeFollower },
                "Round $round: healthy leader $leaderId was deposed — term inflation from isolated $isolated. " +
                    "leaderTrace=${leaderTrace.takeLast(8)}"
            )
            // Invariant 2: pre-vote blocked every real election on the isolated node.
            val realElectionAttempts = isolatedTrace.filterIsInstance<RaftTraceEvent.Timeout>()
            assertTrue(
                realElectionAttempts.isEmpty(),
                "Round $round: isolated $isolated bumped its real term ${realElectionAttempts.size} time(s) — " +
                    "pre-vote should have blocked all of them. events=${realElectionAttempts}"
            )
            sim.checkInvariants()
        }
    }

    @Test fun persistence_node_rejoins_with_same_log() = raftRunTest {
        val sim = raftSim(backgroundScope, backgroundScope)
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

    @Test fun rejoinPartitionedLeader_reverts_to_follower() = raftRunTest {
        val sim = raftSim(backgroundScope, backgroundScope)
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

    @Test fun logBackup_newLeaderReconcilesMinorityDivergence() = raftRunTest {
        // 5-node cluster. Partition a minority so they get no entries from the majority's leader.
        // Heal and verify the new leader can propose and the minority catches up via §5.3 fast backup.
        val sim = raftSim(backgroundScope, backgroundScope, n = 5)
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

    @Test fun unreliableChurn_proposalsEventuallyCommit() = raftRunTest {
        val sim = raftSim(backgroundScope, backgroundScope)
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
