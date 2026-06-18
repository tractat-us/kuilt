package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftEngineDedupStampTest {
    @Test
    fun singleNodeLeaderStampsAutoSerialOnTheCommittedEntry() = raftRunTest {
        val h = singleVoterNode(backgroundScope, clientId = ClientId("c"))
        h.node.awaitLeadership()
        val first = h.node.propose("x".encodeToByteArray())
        val second = h.node.propose("y".encodeToByteArray())
        assertEquals(DedupKey(ClientId("c"), 1), first.dedupKey)
        assertEquals(DedupKey(ClientId("c"), 2), second.dedupKey)
    }

    @Test
    fun explicitRequestIdIsStampedUnchanged() = raftRunTest {
        val h = singleVoterNode(backgroundScope, clientId = ClientId("c"))
        h.node.awaitLeadership()
        val committed = h.node.propose("x".encodeToByteArray(), requestId = 77)
        assertEquals(DedupKey(ClientId("c"), 77), committed.dedupKey)
    }

    @Test
    fun autoClientIdCarriesTheNodeIdPrefixWhenNoneSupplied() = raftRunTest {
        val h = singleVoterNode(backgroundScope)
        h.node.awaitLeadership()
        val committed = h.node.propose("x".encodeToByteArray())
        // Auto id is "auto:$nodeId-$hex"; the single-voter harness names the node "solo".
        assertTrue(committed.dedupKey!!.clientId.value.startsWith("auto:solo-"))
        assertEquals(1L, committed.dedupKey!!.requestId)
    }
}
