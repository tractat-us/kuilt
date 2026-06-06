@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

class AwaitLeadershipTest {

    private val fastConfig = RaftConfig(5.milliseconds, 10.milliseconds, 2.milliseconds)

    @Test
    fun awaitLeadership_returnsWhenNodeBecomesLeader() = runTest(UnconfinedTestDispatcher()) {
        val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
        val config = ClusterConfig.ofVoters(ids)
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = this,
            raftConfig = fastConfig,
            nodeScope = backgroundScope,
            nodeFactory = { _, transport, storage, nodeScope ->
                nodeScope.raftNode(config, transport, storage, fastConfig)
            },
        )
        withTimeout(2000) {
            // Wait for any node to reach leadership
            var leaderNode: RaftNode? = null
            while (leaderNode == null) {
                leaderNode = sim.nodes.values.firstOrNull { it.role.value is RaftRole.Leader }
                if (leaderNode == null) delay(1)
            }
            // awaitLeadership() on an already-leader node must return immediately
            leaderNode.awaitLeadership()
            assertIs<RaftRole.Leader>(leaderNode.role.value)
        }
    }
}
