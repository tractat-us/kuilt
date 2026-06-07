@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestDispatcherGuardTest {

    @Test
    fun realRaftNodeUnderTestDispatcher_throwsWithActionableMessage_whenStrictGuardEnabled() =
        raftRunTest {
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
        raftRunTest {
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

    @Test
    fun realRaftNodeUnderTestDispatcher_doesNotWarnOrThrow_whenExpectVirtualTimeIsTrue() =
        raftRunTest {
            val network = InMemoryRaftNetwork()
            val cluster = ClusterConfig(voters = setOf(NodeId("a")))
            // strictTestGuard = true would normally throw; expectVirtualTime = true must take precedence
            val config = FAST_RAFT_CONFIG.copy(strictTestGuard = true, expectVirtualTime = true)

            // If expectVirtualTime did NOT take precedence, strictTestGuard = true would throw here
            val node = backgroundScope.raftNode(
                clusterConfig = cluster,
                transport = network.transport(NodeId("a")),
                storage = InMemoryRaftStorage(),
                raftConfig = config,
            )
            kotlin.test.assertNotNull(node)
        }
}
