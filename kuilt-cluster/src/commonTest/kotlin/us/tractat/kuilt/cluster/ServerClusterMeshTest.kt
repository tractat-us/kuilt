@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.raftSimTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Voter-core consensus tests for [VoterMesh], S3b-2 of epic #485.
 *
 * Proves that an M=3 voter mesh wired under [us.tractat.kuilt.raft.test.MultiNodeRaftSim]
 * (virtual time, [kotlinx.coroutines.test.StandardTestDispatcher], 5 s timeout) converges
 * correctly. This is the headline proof for S3b-2: the voter mesh wiring works.
 *
 * Tests run in `commonTest` — `MultiNodeRaftSim` is a commonMain artifact in `:kuilt-raft-test`,
 * and the voter mesh proof requires no JVM-specific types. Real-socket E2E is deferred to S3b-3.
 *
 * ## Harness discipline (enforced by `verifyRaftHarnessDiscipline`)
 *
 * - Every `runTest(...)` carries an explicit `timeout =` (via `raftSimTest`'s 5 s default).
 * - Cluster-state awaits use `sim.await*` bounded helpers — never raw `.first/.filter` on
 *   `commitIndex` or `role`, never `advanceUntilIdle()`.
 * - Node coroutines run on `backgroundScope` (wired by `raftSimTest`).
 * - Per-node seeded [kotlin.random.Random] is wired by `MultiNodeRaftSim`.
 */
class ServerClusterMeshTest {

    /**
     * An M=3 voter cluster elects a stable leader under virtual time.
     *
     * This is the headline proof for S3b-2: the mesh wiring converges.
     */
    @Test
    fun threeVoterMesh_electsStableLeader() = raftSimTest(n = 3) { sim ->
        val leader = sim.awaitLeader()

        assertNotNull(leader, "M=3 mesh must elect a leader")
        assertTrue(leader.role.value is RaftRole.Leader, "elected node must hold Leader role")
        sim.checkInvariants()
    }

    /**
     * A proposal committed by the leader is observed on all three voters.
     *
     * Proves log replication across the K_3 mesh.
     */
    @Test
    fun threeVoterMesh_replicatesProposalToAllVoters() = raftSimTest(n = 3) { sim ->
        val command = "action:move=1".encodeToByteArray()
        val entry = sim.proposeOnLeader(command)

        sim.awaitCommit(entry.index)
        sim.checkInvariants()

        val allApplied = sim.nodeIds.all { id ->
            sim.appliedState(id).contentEquals(command)
        }
        assertTrue(allApplied, "proposal must be applied on all three voters")
    }

    /**
     * Leadership transfer within an M=3 mesh succeeds.
     *
     * Proves `transferLeadership` (Raft §3.10) across the voter mesh.
     */
    @Test
    fun threeVoterMesh_leadershipTransferSucceeds() = raftSimTest(n = 3) { sim ->
        val leader = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val target = sim.nodeIds.first { it != leaderId }

        leader.transferLeadership(target)

        sim.awaitLeader(among = setOf(target))
        sim.awaitRole(leaderId, RaftRole.Follower)
        sim.checkInvariants()
    }

    /**
     * Crash the leader; surviving two voters elect a new leader and continue committing.
     *
     * Proves fault tolerance of the M=3 mesh (majority = 2 survives one crash).
     */
    @Test
    fun threeVoterMesh_crashedLeaderTriggersNewElection() = raftSimTest(n = 3) { sim ->
        val leader = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val survivors = sim.nodeIds.filter { it != leaderId }.toSet()

        sim.crash(leaderId)

        val newLeader = sim.awaitLeader(among = survivors)
        assertNotNull(newLeader, "surviving voters must elect a new leader")

        val command = "action:after-crash".encodeToByteArray()
        val entry = sim.proposeOnLeader(command, among = survivors)
        sim.awaitCommit(entry.index, on = survivors)
        sim.checkInvariants()
    }

    /**
     * Partition the leader, let survivors elect a new leader, then heal
     * and confirm the ex-leader rejoins as a follower.
     */
    @Test
    fun threeVoterMesh_partitionAndHeal_recovers() = raftSimTest(n = 3) { sim ->
        val leader = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val survivors = sim.nodeIds.filter { it != leaderId }.toSet()

        sim.partitionOff(leaderId)

        // Survivors elect a new leader. Do NOT checkInvariants here: the partitioned old leader
        // is still in Leader role (it's isolated but not yet stepped down), so there are
        // transiently two nodes reporting Leader. That's expected during partition.
        sim.awaitLeader(among = survivors)

        // Heal: old leader receives a higher-term message and steps down to Follower.
        sim.heal()
        sim.awaitRole(leaderId, RaftRole.Follower)

        // Now invariants must hold: exactly one leader.
        sim.checkInvariants()
    }

    /**
     * [voterMeshFromNodes] wraps pre-wired `MultiNodeRaftSim` nodes and exposes
     * [VoterMesh.awaitLeader].
     *
     * Proves the [VoterMesh] API surface works over sim-wired nodes — the seam
     * between sim and the [ServerCluster] voter-core.
     */
    @Test
    fun voterMeshFromNodes_awaitLeader_returnsElectedLeader() = raftSimTest(n = 3) { sim ->
        val simLeader = sim.awaitLeader()

        val mesh = voterMeshFromNodes(
            voterNodes = sim.nodes.toMap(),
            scope = backgroundScope,
        )

        val meshLeader = mesh.awaitLeader()
        assertTrue(
            meshLeader.role.value is RaftRole.Leader,
            "mesh.awaitLeader must return a leader",
        )
        assertTrue(
            meshLeader === simLeader,
            "mesh.awaitLeader must return the same node as sim.awaitLeader",
        )
    }

    /**
     * [VoterMesh.committed] streams entries committed by the voter mesh.
     */
    @Test
    fun voterMeshFromNodes_committed_streamsEntries() = raftSimTest(n = 3) { sim ->
        val mesh = voterMeshFromNodes(
            voterNodes = sim.nodes.toMap(),
            scope = backgroundScope,
        )

        val received = mutableListOf<ByteArray>()
        val job = backgroundScope.launch {
            mesh.committed
                .filterIsInstance<Committed.Entry>()
                .collect { received.add(it.entry.command) }
        }
        sim.settle()

        val command = "cluster-command".encodeToByteArray()
        val entry = sim.proposeOnLeader(command)
        sim.awaitCommit(entry.index)
        sim.settle()

        assertTrue(
            received.any { it.contentEquals(command) },
            "committed stream must deliver the proposed entry",
        )
        job.cancel()
    }
}
