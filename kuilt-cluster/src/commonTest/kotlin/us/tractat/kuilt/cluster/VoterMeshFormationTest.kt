package us.tractat.kuilt.cluster

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The virtual-time analog of `examples`' `VoterMeshOverSeamsE2ETest`: form a 3-voter K_3 mesh via the
 * **real** [assembleVoterMesh] over an [InMemoryVoterFabric], elect a leader, propose a command, and
 * prove it commits on **all three** voters — deterministically, under `StandardTestDispatcher`, with
 * no real sockets.
 *
 * This is the proof that [VoterMeshSim] + [InMemoryVoterFabric] drive the production voter-mesh
 * assembly to working consensus under virtual time. A reconnection harness (a severable
 * [InMemoryVoterFabric]) builds on exactly this foundation.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoterMeshFormationTest {

    @Test
    fun formsAMeshElectsALeaderAndCommitsToEveryVoter() = voterMeshSimTest(n = 3) { sim ->
        // (a) The K_3 mesh forms and a leader is elected across it.
        val leader = sim.awaitLeader()

        // (b) A command proposed on the leader commits on every voter — replicated across the mesh.
        val command = "action:voter-mesh-move=1".encodeToByteArray()
        sim.proposeOnLeader(command)
        sim.awaitCommit(command)

        assertAll(
            { assertNotNull(sim.leader(), "a leader remains elected after the commit") },
            {
                assertTrue(
                    sim.voterIds.all { sim.hasCommitted(it, command) },
                    "every voter must have committed the command across the mesh",
                )
            },
            {
                // The three voters' inter-server seams each see the other two — a complete K_3 roster.
                sim.voterIds.forEach { id ->
                    val others = sim.voterIds.filter { it != id }.map { PeerId(it.value) }.toSet()
                    val peers = sim.seamOf(id).peers.value
                    assertTrue(others.all { it in peers }, "voter $id must see all peers, saw $peers")
                }
            },
        )

        // The leader we elected is one of the voters (sanity on the returned node).
        assertTrue(leader in sim.mesh.voterNodes.values, "awaitLeader returned a voter node")
    }
}
