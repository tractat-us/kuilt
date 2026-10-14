@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.examples

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.cluster.clusterClientWithNode
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.raft.test.MULTI_NODE_SIM_BASE_CONFIG
import us.tractat.kuilt.raft.test.MULTI_NODE_SIM_SEED
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ClusterClient → learner forward → voter commit/cache → consumer dedup, over the shared sim. */
class CachedResultClusterE2ETest {
    @Test
    fun committedClientRetrySurvivesSmallerLeaderBudgetWithoutDoubleApply() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val voter = NodeId("voter")
            val learner = NodeId("client")
            val ids = listOf(voter, learner)
            val config = ClusterConfig(voters = setOf(voter), learners = setOf(learner))
            val budgets = ids.associateWith { 4096 }.toMutableMap()
            val dropped = mutableListOf<Int>()
            val sim = MultiNodeRaftSim(
                nodeIds = ids,
                scope = this,
                nodeScope = backgroundScope,
                nodeFactory = { id, transport, storage, scope ->
                    val bounded = object : RaftTransport by transport {
                        override val maxPayloadBytes: Int get() = budgets.getValue(id)
                        override suspend fun sendTo(peer: NodeId, message: ByteArray) {
                            if (message.size > maxPayloadBytes) dropped += message.size
                            else transport.sendTo(peer, message)
                        }
                    }
                    scope.raftNode(
                        config, bounded, storage,
                        MULTI_NODE_SIM_BASE_CONFIG.copy(random = Random(MULTI_NODE_SIM_SEED + ids.indexOf(id))),
                        ClientIdentity.Durable(ClientId(id.value)),
                    )
                },
            )
            sim.awaitLeader()
            val client = clusterClientWithNode(sim.nodes.getValue(learner))
            val command = ByteArray(1024) { it.toByte() }
            val first = client.propose(command, requestId = 41L)
            sim.awaitCommit(first.index)
            val before = sim.storages.getValue(voter).entries(1L)
            budgets[voter] = 1024

            val outcome = runCatchingCancellable { client.propose(command, requestId = 41L) }
            val retry = assertNotNull(outcome.getOrNull(), "cached client retry must succeed: ${outcome.exceptionOrNull()}")
            assertFailsWith<PayloadTooLarge> { client.propose(command, requestId = 42L) }
            val after = sim.storages.getValue(voter).entries(1L)
            val table = ClientSessionTable()
            val applied = after.filter { !it.isNoOp && table.shouldApply(it.dedupKey) }
            assertAll(
                { assertEquals(first, retry, "ClusterClient receives the original committed entry") },
                { assertEquals(before, after, "neither retry nor refused fresh request appends") },
                { assertEquals(listOf(first), applied, "consumer applies the command once") },
                { assertTrue(dropped.isEmpty(), "the bounded transports must carry every emitted frame") },
            )
        }
}
