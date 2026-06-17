package us.tractat.kuilt.raft.test

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FakeRaftNodeDedupTest {
    @Test
    fun autoStampsMonotonicSerialsUnderTheNodeClientId() = runTest {
        val node = FakeRaftNode(NodeId("a"), clientId = ClientId("fixed"))
        node.setRole(RaftRole.Leader)
        val first = node.propose(byteArrayOf(1))
        val second = node.propose(byteArrayOf(2))
        assertEquals(DedupKey(ClientId("fixed"), 1), first.dedupKey)
        assertEquals(DedupKey(ClientId("fixed"), 2), second.dedupKey)
    }

    @Test
    fun explicitRequestIdIsHonoured() = runTest {
        val node = FakeRaftNode(NodeId("a"), clientId = ClientId("fixed"))
        node.setRole(RaftRole.Leader)
        val entry = node.propose(byteArrayOf(1), requestId = 99)
        assertEquals(99L, entry.dedupKey?.requestId)
    }

    @Test
    fun defaultClientIdIsPresentAndStablePerInstance() = runTest {
        val node = FakeRaftNode(NodeId("a"))
        node.setRole(RaftRole.Leader)
        val a = node.propose(byteArrayOf(1)).dedupKey?.clientId
        val b = node.propose(byteArrayOf(2)).dedupKey?.clientId
        assertNotNull(a); assertEquals(a, b)
    }
}
