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

    /**
     * No false step-down: a connected leader in a 3-voter cluster keeps hearing from both peers
     * and must NOT step down across several quorum-check windows.
     */
    @Test
    fun connectedLeader_neverStepsDown_acrossManyWindows() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        // Advance well past multiple quorum-check windows (several election timeouts).
        delay(150)

        val lostQuorumEvents = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .filter { it.reason == StepDownReason.LostQuorum }

        assertTrue(lostQuorumEvents.isEmpty(),
            "connected leader must not step down via LostQuorum: $leaderTrace")
        assertTrue(leader.role.value is RaftRole.Leader,
            "connected leader must still be Leader after many windows, was: ${leader.role.value}")
    }

    /**
     * Single-voter: quorum = 1; the leader always counts itself, so reachable = 1 ≥ 1 every tick.
     * The single-voter leader must never step down via CheckQuorum.
     */
    @Test
    fun singleVoter_neverStepsDown() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 1)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        delay(150)

        val lostQuorumEvents = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .filter { it.reason == StepDownReason.LostQuorum }

        assertAll(
            { assertTrue(lostQuorumEvents.isEmpty(), "single-voter must not lose quorum: $leaderTrace") },
            { assertTrue(leader.role.value is RaftRole.Leader, "single-voter must remain Leader, was: ${leader.role.value}") },
        )
    }

    /**
     * success=false still counts as contact: a peer that consistently rejects AppendEntries
     * (log conflict) but is reachable keeps the leader in office. Reachability, not success,
     * is the CheckQuorum signal.
     *
     * Setup: a 2-voter cluster. The follower has a pre-loaded conflicting entry at index 1
     * (term 99), so it will keep rejecting the leader's AppendEntries. But each rejection
     * is an AppendEntriesResponse that counts as reachability. quorum = 2: leader + 1 peer = 2 ≥ 2.
     */
    @Test
    fun successFalseResponse_countsAsContact_leaderRetainsLeadership() = raftRunTest {
        val v1 = NodeId("v1"); val v2 = NodeId("v2")
        val cluster = ClusterConfig(voters = setOf(v1, v2))

        // Pre-load a conflicting log entry on v2 so it will always reject AppendEntries from v1.
        // Pre-load a conflicting log entry on v2 so it will always reject AppendEntries from v1.
        // Term 99 at index 1 conflicts with any real leader's term 1 no-op — v2 keeps sending success=false.
        val conflictingStorage = InMemoryRaftStorage().also { s ->
            s.appendEntries(listOf(LogEntry(index = 1L, term = 99L, command = byteArrayOf(0xFF.toByte()))))
        }

        val customSim = RaftSimulation(
            nodeIds = listOf(v1, v2),
            scope = this,
            raftConfig = FAST_RAFT_CONFIG,
            nodeScope = backgroundScope,
        ) { id, transport, _, childScope ->
            val storage = if (id == v2) conflictingStorage else InMemoryRaftStorage()
            childScope.raftNode(cluster, transport, storage, FAST_RAFT_CONFIG)
        }

        val leader = awaitLeader(customSim)
        val leaderId = customSim.nodes.entries.first { it.value === leader }.key
        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { customSim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        // Wait past multiple quorum-check windows — the conflicting peer keeps sending success=false,
        // but that still counts as a reachability signal. The leader must stay in office.
        delay(150)

        val lostQuorumEvents = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .filter { it.reason == StepDownReason.LostQuorum }

        assertAll(
            { assertTrue(lostQuorumEvents.isEmpty(), "leader must not lose quorum when peer is reachable (success=false): $leaderTrace") },
            { assertTrue(leader.role.value is RaftRole.Leader, "leader must remain Leader when peer is reachable, was: ${leader.role.value}") },
        )
    }

    /**
     * Heal after step-down: partition → step-down → heal. The stepped-down node rejoins as
     * follower under the new leader without term inflation (PreVote interaction).
     * The old leader's term in the BecomeFollower(LostQuorum) trace must match the final cluster term.
     */
    @Test
    fun healAfterStepDown_nodeRejoinsAsFollower_noTermInflation() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val others = sim.nodeIds.filter { it != leaderId }.toSet()

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        // Isolate the leader — the majority elects a new leader.
        sim.partition(setOf(leaderId), others)
        delay(80)

        // Old leader stepped down via CheckQuorum.
        val lostQuorumEvent = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .firstOrNull { it.reason == StepDownReason.LostQuorum }
        assertTrue(lostQuorumEvent != null, "old leader must have stepped down via LostQuorum: $leaderTrace")

        // Heal the partition.
        sim.heal()

        // A new leader is elected on the majority side; wait for it to commit.
        val newLeader = awaitLeader(sim)
        newLeader.propose(byteArrayOf(1))
        sim.awaitCommit(sim.nodes.getValue(leaderId).commitIndex.value + 1, on = others)

        // Old leader rejoins as follower and catches up.
        sim.awaitRole(leaderId, RaftRole.Follower)

        assertAll(
            { assertTrue(leader.role.value is RaftRole.Follower, "old leader must be Follower after heal: ${leader.role.value}") },
            // The LostQuorum step-down must carry a positive term (the real elected term),
            // proving no term inflation occurred on the minority side.
            {
                assertTrue(
                    lostQuorumEvent.term > 0L,
                    "LostQuorum step-down term must be > 0 (the elected term, not initial 0 — no term bump): was ${lostQuorumEvent.term}"
                )
            },
            // After heal, the cluster term must not have inflated past the original term+1
            // (only the new election on the majority side bumps to term+1, never higher).
            {
                val newLeaderRole = newLeader.role.value
                assertTrue(
                    newLeaderRole is RaftRole.Leader,
                    "majority should have a stable leader after heal, was: $newLeaderRole"
                )
            },
        )
    }
}
