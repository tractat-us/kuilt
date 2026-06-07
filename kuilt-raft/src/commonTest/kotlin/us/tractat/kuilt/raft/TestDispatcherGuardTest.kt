@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestDispatcherGuardTest {

    @Test
    fun realRaftNodeUnderTestDispatcher_throwsWithActionableMessage_whenStrictGuardEnabled() =
        runTest(UnconfinedTestDispatcher()) {
            val network = InMemoryRaftNetwork()
            val cluster = ClusterConfig(voters = setOf(NodeId("a")))
            val config = FAST_RAFT_CONFIG.copy(strictTestGuard = true)

            val ex = assertFailsWith<IllegalStateException> {
                backgroundScope.raftNode(
                    clusterConfig = cluster,
                    transport = network.transport(NodeId("a")),
                    storage = InMemoryRaftStorage(),
                    raftConfig = config,
                )
            }

            assertTrue(
                "TestDispatcher" in ex.message!! || "virtual time" in ex.message!!,
                "Expected diagnostic to mention TestDispatcher or virtual time, got: ${ex.message}",
            )
            assertTrue(
                "FakeRaftNode" in ex.message!!,
                "Expected diagnostic to recommend FakeRaftNode, got: ${ex.message}",
            )
        }

    @Test
    fun realRaftNodeUnderTestDispatcher_doesNotThrow_whenStrictGuardDisabled() =
        runTest(UnconfinedTestDispatcher()) {
            val network = InMemoryRaftNetwork()
            val cluster = ClusterConfig(voters = setOf(NodeId("a")))
            val config = FAST_RAFT_CONFIG // strictTestGuard defaults to false

            // Must not throw — just emits a warning log
            backgroundScope.raftNode(
                clusterConfig = cluster,
                transport = network.transport(NodeId("a")),
                storage = InMemoryRaftStorage(),
                raftConfig = config,
            )
        }
}
