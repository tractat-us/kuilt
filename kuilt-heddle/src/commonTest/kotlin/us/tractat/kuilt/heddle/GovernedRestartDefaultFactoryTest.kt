package us.tractat.kuilt.heddle

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * A governed peer restarted through [MultiNodeRaftSim]'s **default** node factory forwards again
 * (#3425). Its Raft node runs on `ClientIdentity.Auto`, the default, and mints its client id from
 * its seeded RNG; before #3425 the default factory reseeded a restart with its first seed, so the
 * restarted peer's enroll reused the old incarnation's `(clientId, requestId)` with new bytes, and the
 * leader, still holding the old result in its cache, refused it with `RequestIdConflictException`.
 *
 * The rig needs both incarnations to forward under the same serial (each one's first, its enroll)
 * and the **same** leader on both sides of the restart; it asserts both rather than assuming them.
 *
 * Two distinct ids under one serial would also come from a reused seed whose collision re-mint
 * landed before the enroll was stamped, so the test pins each id to the **first** draw from its
 * start's seed, worked by hand from the sim's formula rather than read back through it: a reused seed
 * fails the restarted arm, and so does a re-mint, which takes a later draw.
 */
class GovernedRestartDefaultFactoryTest {

    @Test
    fun aGovernedFollowerRestartedThroughTheDefaultFactoryEnrollsThroughTheLeader() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val ids = (1..3).map { NodeId("g$it") }
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val loom = InMemoryLoom()
        val booted = ids.mapIndexed { i, id -> GovernedCluster.boot(this, sim, loom, id, joinAt = i, quantum = 10L, cap = 1_000L) }
        sim.awaitLeader()
        val cluster = GovernedCluster(sim, this, loom, quantum = 10L, cap = 1_000L, initial = booted)
        cluster.bootstrap(topology = emptyList(), mint = emptyMap())

        val leaderId = ids.first { sim.nodes.getValue(it).role.value is RaftRole.Leader }
        val i = cluster.peers.indexOfFirst { it.raftId != leaderId }
        val old = cluster.peers[i]
        val oldRaft = sim.nodes.getValue(old.raftId)

        cluster.restart(i) // crash, reboot from the durable log, and enroll: a forward to the leader

        val fresh = cluster.peers[i]
        val nodeIndex = ids.indexOf(old.raftId)
        val forwards = sim.storages.getValue(leaderId).entries(1L)
            .mapNotNull { it.dedupKey }
            .filter { it.clientId.value.startsWith("auto:${old.raftId.value}-") }
        assertAll(
            { assertNotSame(oldRaft, sim.nodes.getValue(old.raftId), "the rig: the Raft node restarted") },
            { assertNotEquals(old.replica, fresh.replica, "the rig: the restarted process is a fresh replica") },
            { assertEquals(leaderId, sim.leader()?.let { l -> ids.first { sim.nodes[it] === l } }, "the rig: the leader before the restart still leads") },
            { assertTrue(fresh.replica in fresh.node.enrolledReplicas(), "the restarted replica's enroll committed") },
            { assertEquals(2, forwards.size, "the rig: each incarnation forwarded once: $forwards") },
            { assertEquals(1, forwards.map { it.requestId }.toSet().size, "the rig: both under the same serial: $forwards") },
            { assertEquals(2, forwards.map { it.clientId }.toSet().size, "the two incarnations forwarded under two ids: $forwards") },
            {
                assertEquals(
                    ClientId.auto(old.raftId, Random(FIRST_START_SEEDS.getValue(nodeIndex))),
                    forwards.firstOrNull()?.clientId,
                    "the rig: the first incarnation forwarded under the first draw from its first-start seed",
                )
            },
            {
                assertEquals(
                    ClientId.auto(old.raftId, Random(FIRST_RESTART_SEEDS.getValue(nodeIndex))),
                    forwards.lastOrNull()?.clientId,
                    "the restarted peer forwarded under the first draw from its restart seed, not a re-mint",
                )
            },
        )
    }

    private companion object {
        /** `MULTI_NODE_SIM_SEED + nodeIndex` (485 + i): each node's first-start seed in a default sim. */
        val FIRST_START_SEEDS: Map<Int, Long> = mapOf(0 to 485L, 1 to 486L, 2 to 487L)

        /** `485 + i - 0x61c8864680b583eb`: each node's first-restart seed in a default sim, worked by hand. */
        val FIRST_RESTART_SEEDS: Map<Int, Long> =
            mapOf(0 to -7046029254386352646L, 1 to -7046029254386352645L, 2 to -7046029254386352644L)
    }
}
