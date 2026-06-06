@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs

class AwaitLeadershipTest {

    @Test
    fun awaitLeadership_returnsWhenNodeBecomesLeader() = runTest(UnconfinedTestDispatcher()) {
        val sim = raftSim(this, backgroundScope)
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
