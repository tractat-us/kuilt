package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.PlyId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeSendReceiveTest {
    @Test
    fun broadcastDeliversBarePayloadToTheOtherPeer() = runTest {
        val mem = InMemoryLoom()
        val loom = CompositeLoom(listOf(PlyId("mem") to mem), dispatcher = UnconfinedTestDispatcher(testScheduler))
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))
        host.peers.first { it.size == 2 } // wait for reconciliation

        host.broadcast(byteArrayOf(7, 8, 9))
        val got = joiner.incoming.first()
        assertTrue(byteArrayOf(7, 8, 9).contentEquals(got.payload))

        host.close()
        joiner.close()
    }

    @Test
    fun sendToUnknownPeerThrows() = runTest {
        val mem = InMemoryLoom()
        val loom = CompositeLoom(listOf(PlyId("mem") to mem), dispatcher = UnconfinedTestDispatcher(testScheduler))
        val host = loom.host(Pattern("host"))
        assertFailsWith<PeerNotConnected> { host.sendTo(PeerId("nobody"), byteArrayOf(1)) }

        host.close()
    }

    /**
     * With two plies, a broadcast travels over BOTH — the joiner receives two copies on
     * the wire. The [PlyInboundGate] must collapse them to exactly one delivery.
     */
    @Test
    fun broadcastOverTwoPliesDeliversBarePayloadExactlyOnce() = runTest {
        val plies = listOf(
            PlyId("a") to InMemoryLoom(),
            PlyId("b") to InMemoryLoom(),
        )
        val loom = CompositeLoom(plies, dispatcher = UnconfinedTestDispatcher(testScheduler))
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))
        host.peers.first { it.size == 2 } // wait for reconciliation on both plies

        host.broadcast(byteArrayOf(7))

        // First frame must arrive.
        val first = withTimeoutOrNull(2_000) { joiner.incoming.first() }
        assertNotNull(first, "expected exactly one frame but got none")
        assertTrue(byteArrayOf(7).contentEquals(first.payload), "payload mismatch: ${first.payload.toList()}")

        // No second frame — the gate deduplicated the ply-b copy.
        val second = withTimeoutOrNull(200) { joiner.incoming.first() }
        assertNull(second, "expected exactly one frame but gate delivered a duplicate")

        host.close()
        joiner.close()
    }
}
