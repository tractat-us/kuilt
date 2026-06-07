@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryRaftNetworkTest {
    @Test fun deliversMessage() = raftRunTest {
        val net = InMemoryRaftNetwork()
        val a = net.transport(NodeId("a"))
        val b = net.transport(NodeId("b"))
        val payload = byteArrayOf(1, 2, 3)
        var got: RaftEnvelope? = null
        val job = launch { got = b.incoming.first() }
        a.sendTo(NodeId("b"), payload)
        job.join()
        assertEquals(NodeId("a"), got?.from)
        assertContentEquals(payload, got?.bytes)
    }

    @Test fun selfIdIsCorrect() = runTest {
        assertEquals(NodeId("x"), InMemoryRaftNetwork().transport(NodeId("x")).selfId)
    }

    @Test fun peersContainsAllRegistered() = runTest {
        val net = InMemoryRaftNetwork()
        net.transport(NodeId("a")); net.transport(NodeId("b")); net.transport(NodeId("c"))
        val peers = net.transport(NodeId("a")).peers.value
        assertTrue(NodeId("b") in peers && NodeId("c") in peers)
    }

    @Test fun partitionDropsMessages() = raftRunTest {
        val net = InMemoryRaftNetwork()
        val a = net.transport(NodeId("a"))
        val b = net.transport(NodeId("b"))
        net.partition(setOf(NodeId("a")), setOf(NodeId("b")))
        a.sendTo(NodeId("b"), byteArrayOf(42))
        var received = false
        val job = launch { b.incoming.first(); received = true }
        job.cancel()
        assertTrue(!received)
    }
}
