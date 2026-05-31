package us.tractat.kuilt.core.composite

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.PlyId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompositeSendReceiveTest {
    @Test
    fun broadcastDeliversBarePayloadToTheOtherPeer() = runTest {
        val mem = InMemoryLoom()
        val loom = CompositeLoom(listOf(PlyId("mem") to mem))
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))
        host.peers.first { it.size == 2 } // wait for reconciliation

        host.broadcast(byteArrayOf(7, 8, 9))
        val got = joiner.incoming.first()
        assertTrue(byteArrayOf(7, 8, 9).contentEquals(got.payload))
    }

    @Test
    fun sendToUnknownPeerThrows() = runTest {
        val mem = InMemoryLoom()
        val loom = CompositeLoom(listOf(PlyId("mem") to mem))
        val host = loom.host(Pattern("host"))
        assertFailsWith<PeerNotConnected> { host.sendTo(PeerId("nobody"), byteArrayOf(1)) }
    }
}
