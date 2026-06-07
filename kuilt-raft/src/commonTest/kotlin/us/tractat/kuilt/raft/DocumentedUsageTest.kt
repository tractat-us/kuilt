@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compile-checked mirror of the quick-start example documented on [RaftNode]
 * and in `docs/usage.md`.
 *
 * The point of this test is to make the documented API **drift-proof**: if a
 * public signature changes (as happened when `ClusterConfig.ofVoters` moved from
 * `vararg` to `Collection`), this test stops compiling and the docs get fixed in
 * the same change. It exercises the exact call shapes the docs show —
 * [ClusterConfig.ofVoters] with a list, [CoroutineScope.raftNode], the
 * [RaftNode.awaitLeadership] convenience, [RaftNode.propose], and
 * [RaftNode.committed] collection.
 *
 * Production code wraps a kuilt `Seam` with [SeamRaftTransport]; this test uses
 * the in-process [InMemoryRaftNetwork] so it runs without a real fabric, but the
 * [RaftNode] API surface is identical.
 */
class DocumentedUsageTest {

    @Test
    fun documentedQuickStart_compilesAndCommits() = raftRunTest {
        // 1. Describe the cluster — exactly as documented.
        val cluster = ClusterConfig.ofVoters(listOf(NodeId("a"), NodeId("b"), NodeId("c")))

        // 2. Wire one node per voter. Production: `SeamRaftTransport(seam)`.
        val network = InMemoryRaftNetwork()
        val nodes: Map<NodeId, RaftNode> = cluster.voters.associateWith { id ->
            backgroundScope.raftNode(cluster, network.transport(id), InMemoryRaftStorage(), FAST_RAFT_CONFIG)
        }

        // 3. Apply committed entries on every node (documented step 5).
        val appliedOnA = mutableListOf<ByteArray>()
        backgroundScope.launch {
            nodes.getValue(NodeId("a")).committed.collect { entry ->
                if (entry.command.isNotEmpty()) appliedOnA.add(entry.command)
            }
        }

        // 4. Find the leader and propose on it (documented step 6, using awaitLeadership).
        val leader = withTimeout(2_000) {
            var l: RaftNode? = null
            while (l == null) {
                l = nodes.values.firstOrNull { it.role.value is RaftRole.Leader }
                if (l == null) kotlinx.coroutines.delay(1)
            }
            l.awaitLeadership() // returns immediately once leader — documented call shape
            l
        }

        val committed = leader.propose("set x=1".encodeToByteArray())
        assertTrue(committed.index >= 1, "proposal should commit at a positive log index")
        assertEquals("set x=1", committed.command.decodeToString())
    }

    @Test
    fun documentedClusterConfigBuilders_compile() {
        // Pins the two factory shapes the docs advertise.
        val votersOnly = ClusterConfig.ofVoters(listOf(NodeId("a"), NodeId("b")))
        assertEquals(setOf(NodeId("a"), NodeId("b")), votersOnly.voters)
        assertTrue(votersOnly.learners.isEmpty())

        val withLearner = ClusterConfig.withLearner(
            voters = listOf(NodeId("a"), NodeId("b"), NodeId("c")),
            learner = NodeId("observer"),
        )
        assertEquals(setOf(NodeId("observer")), withLearner.learners)
        assertEquals(3, withLearner.voters.size)
    }
}
