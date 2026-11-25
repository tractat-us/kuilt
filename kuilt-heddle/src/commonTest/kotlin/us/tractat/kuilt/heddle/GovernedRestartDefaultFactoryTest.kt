package us.tractat.kuilt.heddle

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * A governed peer restarted through [MultiNodeRaftSim]'s **default** node factory forwards again
 * (#3425). Its Raft node runs on `ClientIdentity.Auto`, the default, and mints its client id from
 * its seeded RNG; before #3425 the default factory reseeded a restart with its first seed, so the
 * restarted peer's enroll reused the old incarnation's `(clientId, 1)` with new bytes, and the
 * leader, still holding the old result in its cache, refused it with `RequestIdConflictException`.
 *
 * The rig needs the old incarnation to have forwarded under serial 1 (its bootstrap enroll) and the
 * **same** leader on both sides of the restart; it asserts both rather than assuming them.
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
        val forwards = sim.storages.getValue(leaderId).entries(1L)
            .mapNotNull { it.dedupKey }
            .filter { it.clientId.value.startsWith("auto:${old.raftId.value}-") }
        assertAll(
            { assertNotSame(oldRaft, sim.nodes.getValue(old.raftId), "the rig: the Raft node restarted") },
            { assertNotEquals(old.replica, fresh.replica, "the rig: the restarted process is a fresh replica") },
            { assertEquals(leaderId, sim.leader()?.let { l -> ids.first { sim.nodes[it] === l } }, "the rig: the leader before the restart still leads") },
            { assertTrue(fresh.replica in fresh.node.enrolledReplicas(), "the restarted replica's enroll committed") },
            { assertEquals(listOf(1L, 1L), forwards.map { it.requestId }, "the rig: each incarnation forwarded serial 1: $forwards") },
            { assertEquals(2, forwards.map { it.clientId }.toSet().size, "the two incarnations forwarded under two ids: $forwards") },
        )
    }
}
