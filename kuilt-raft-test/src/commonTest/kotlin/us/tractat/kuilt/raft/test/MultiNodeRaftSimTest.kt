@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft.test

import us.tractat.kuilt.raft.RaftRole
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Self-tests for [MultiNodeRaftSim] — proves the harness works end-to-end.
 *
 * Each test runs under [raftSimTest] (`StandardTestDispatcher` + 5 s timeout). These are
 * deliberately narrow: they validate the harness machinery, not Raft correctness (that lives in
 * `:kuilt-raft`'s own suite).
 */
class MultiNodeRaftSimTest {

    /**
     * A 3-node cluster must elect a stable leader under virtual time. This is the primary smoke
     * test for the harness: if per-node seeding is wrong, all nodes draw the same election timeout
     * and the cluster churns forever — the thrash bound fires rather than hanging.
     */
    @Test
    fun threeNodeCluster_electsStableLeader() = raftSimTest(n = 3) { sim ->
        val leader = sim.awaitLeader()
        assertNotNull(leader)
        sim.checkInvariants()
    }

    /**
     * Partition the leader off, wait for the surviving majority to elect a new leader, then heal.
     * After healing the old leader must step down to follower. Validates the [MultiNodeRaftSim]
     * partition/heal controls and the [awaitLeader] scoped-to-survivors helper.
     */
    @Test
    fun leaderPartitionAndHeal_recoversCluster() = raftSimTest(n = 3) { sim ->
        sim.awaitLeader()
        val leaderId = sim.nodeIds.first { id -> sim.nodes[id]?.role?.value is RaftRole.Leader }
        val survivors = sim.nodeIds.filter { it != leaderId }.toSet()

        sim.partitionOff(leaderId)
        // Surviving majority elects a new leader while the old one is isolated.
        sim.awaitLeader(among = survivors)

        sim.heal()
        // The old leader has a stale term; it must step down when it receives messages.
        sim.awaitRole(leaderId, RaftRole.Follower)
        sim.checkInvariants()
    }

    /**
     * A crashed node (scope cancelled) is absent from [MultiNodeRaftSim.nodes]. After [restart]
     * it rejoins and the cluster remains functional.
     */
    @Test
    fun crashAndRestart_nodeRejoinsCluster() = raftSimTest(n = 3) { sim ->
        sim.awaitLeader()
        val followerId = sim.nodeIds.first { id -> sim.nodes[id]?.role?.value is RaftRole.Follower }

        sim.crash(followerId)
        assertNull(sim.nodes[followerId])

        sim.restart(followerId)
        // After restart the restarted node catches up; eventually a leader is still present.
        sim.awaitLeader()
        sim.checkInvariants()
    }
}
