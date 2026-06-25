package us.tractat.kuilt.raft

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue


@OptIn(ExperimentalCoroutinesApi::class)
class SeamRaftTransportTest {
    @Test
    fun selfIdMapsFromPeerId() = raftRunTest {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("test"))
        val transport = SeamRaftTransport(seam)
        assertEquals(NodeId(seam.selfId.value), transport.selfId)
    }

    @Test
    fun deliversMessageToSender() = raftRunTest {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("test"))
        val seamB = loom.join(InMemoryTag("joiner"))
        val tA = SeamRaftTransport(seamA)
        val tB = SeamRaftTransport(seamB)
        val payload = byteArrayOf(9, 8, 7)
        var got: RaftEnvelope? = null
        val job = launch { got = tB.incoming.first() }
        tA.sendTo(tB.selfId, payload)
        job.join()
        assertAll(
            { assertEquals(tA.selfId, got?.from) },
            { assertContentEquals(payload, got?.bytes) },
        )
    }

    @Test
    fun peersReflectsSeamPeers() = raftRunTest {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("test"))
        val seamB = loom.join(InMemoryTag("joiner"))
        val tA = SeamRaftTransport(seamA)
        val tB = SeamRaftTransport(seamB)
        assertAll(
            { assertTrue(NodeId(seamB.selfId.value) in tA.peers.value) },
            { assertTrue(NodeId(seamA.selfId.value) in tB.peers.value) },
        )
    }

    @Test
    fun sendToAbsentPeerSilentlyDropsPerContract() = raftRunTest {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("test"))
        val transport = SeamRaftTransport(seam)
        // No peer named "ghost" is connected, so the underlying Seam.sendTo throws
        // PeerNotConnected. The transport contract says sendTo "may silently drop if peer
        // is unreachable" — a Raft voter dropping off the fabric must NOT crash the engine.
        transport.sendTo(NodeId("ghost"), byteArrayOf(1, 2, 3))
        // Reaching here without an exception is the assertion.
    }
}
