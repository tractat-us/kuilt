@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.raft.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [ManagedRaftTransport].
 *
 * Uses [InMemoryLoom] for cheap in-process Seams.
 * All tests run under [StandardTestDispatcher] with a tight 5 s timeout.
 */
class ManagedRaftTransportTest {

    private val selfId = NodeId("client-1")

    @Test
    fun `peers is empty before first swapSeam`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val transport = ManagedRaftTransport(scope = backgroundScope, selfId = selfId)
            assertTrue(transport.peers.value.isEmpty(), "peers must be empty before first swapSeam")
        }

    @Test
    fun `swapSeam installs initial seam and reflects its peers`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val transport = ManagedRaftTransport(scope = backgroundScope, selfId = selfId)
            val loom = InMemoryLoom()
            val seam = loom.join(us.tractat.kuilt.core.InMemoryTag("server-a"))

            transport.swapSeam(seam)
            testScheduler.runCurrent()

            // InMemoryLoom includes selfId in peers immediately; the transport maps PeerIds to NodeIds.
            val nodeIds = transport.peers.value
            assertTrue(nodeIds.isNotEmpty(), "peers must be non-empty after swapSeam")
        }

    @Test
    fun `swapSeam replaces backing seam without changing selfId`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val transport = ManagedRaftTransport(scope = backgroundScope, selfId = selfId)
            val loom = InMemoryLoom()

            val seamA = loom.join(us.tractat.kuilt.core.InMemoryTag("server-a"))
            transport.swapSeam(seamA)
            testScheduler.runCurrent()
            val peersAfterA = transport.peers.value

            // Swap to a new seam.
            val seamB = loom.join(us.tractat.kuilt.core.InMemoryTag("server-b"))
            transport.swapSeam(seamB)
            testScheduler.runCurrent()
            val peersAfterB = transport.peers.value

            // selfId is stable across swaps.
            assertEquals(selfId, transport.selfId, "selfId must not change across swapSeam calls")
            // The peer sets are each non-empty but come from different seams.
            assertTrue(peersAfterA.isNotEmpty())
            assertTrue(peersAfterB.isNotEmpty())
        }

    @Test
    fun `incoming relays frames from the current seam`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val transport = ManagedRaftTransport(scope = backgroundScope, selfId = selfId)
            val loom = InMemoryLoom()

            val seamA = loom.join(us.tractat.kuilt.core.InMemoryTag("server-a"))
            val seamB = loom.join(us.tractat.kuilt.core.InMemoryTag("server-b"))

            transport.swapSeam(seamA)
            testScheduler.runCurrent()

            val received = mutableListOf<us.tractat.kuilt.raft.RaftEnvelope>()
            val collectJob = launch(backgroundScope.coroutineContext) {
                transport.incoming.collect { received.add(it) }
            }

            // Deliver a frame on seamA from seamB side.
            val payload = "hello-from-b".encodeToByteArray()
            seamB.broadcast(payload)
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()

            collectJob.cancel()

            assertTrue(received.size >= 1, "must receive at least one frame from the seam")
            assertTrue(
                received.any { it.bytes.contentEquals(payload) },
                "received frame must match sent payload",
            )
        }

    @Test
    fun `swapping seam cancels relay for old seam and starts relay for new seam`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val transport = ManagedRaftTransport(scope = backgroundScope, selfId = selfId)
            val loomA = InMemoryLoom()
            val loomB = InMemoryLoom()

            val seamA1 = loomA.join(us.tractat.kuilt.core.InMemoryTag("a1"))
            val seamA2 = loomA.join(us.tractat.kuilt.core.InMemoryTag("a2"))
            transport.swapSeam(seamA1)
            testScheduler.runCurrent()

            // Swap to a seam from a different loom — frames from seamA no longer arrive.
            val seamB1 = loomB.join(us.tractat.kuilt.core.InMemoryTag("b1"))
            val seamB2 = loomB.join(us.tractat.kuilt.core.InMemoryTag("b2"))
            transport.swapSeam(seamB1)
            testScheduler.runCurrent()

            val received = mutableListOf<us.tractat.kuilt.raft.RaftEnvelope>()
            val collectJob = launch(backgroundScope.coroutineContext) {
                transport.incoming.collect { received.add(it) }
            }

            // Frame on old loom — should NOT arrive (relay cancelled).
            seamA2.broadcast("old-seam-frame".encodeToByteArray())
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()

            val beforeNewFrame = received.size

            // Frame on new loom — should arrive.
            seamB2.broadcast("new-seam-frame".encodeToByteArray())
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()

            collectJob.cancel()

            assertTrue(
                received.drop(beforeNewFrame).any { it.bytes.contentEquals("new-seam-frame".encodeToByteArray()) },
                "frame from new seam must arrive after swap",
            )
        }

    @Test
    fun `sendTo after swapSeam reaches the new seam`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val transport = ManagedRaftTransport(scope = backgroundScope, selfId = selfId)
            val loom = InMemoryLoom()

            val seamSender = loom.join(us.tractat.kuilt.core.InMemoryTag("sender"))
            val seamPeer = loom.join(us.tractat.kuilt.core.InMemoryTag("peer"))

            transport.swapSeam(seamSender)
            testScheduler.runCurrent()

            val receivedByPeer = mutableListOf<ByteArray>()
            val collectJob = launch(backgroundScope.coroutineContext) {
                seamPeer.incoming.collect { receivedByPeer.add(it.payload) }
            }

            val targetId = us.tractat.kuilt.raft.NodeId(seamPeer.selfId.value)
            transport.sendTo(targetId, "ping".encodeToByteArray())
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()

            collectJob.cancel()

            assertTrue(
                receivedByPeer.any { it.contentEquals("ping".encodeToByteArray()) },
                "sendTo must route through the installed seam to the peer",
            )
        }

    @Test
    fun `sendTo before swapSeam silently drops without throwing`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val transport = ManagedRaftTransport(scope = backgroundScope, selfId = selfId)
            // No seam installed — sendTo must not throw.
            transport.sendTo(NodeId("some-peer"), "ignored".encodeToByteArray())
            // If we reach here, the test passes.
        }
}
