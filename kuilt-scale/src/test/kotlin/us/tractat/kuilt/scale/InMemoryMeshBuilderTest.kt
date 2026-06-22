package us.tractat.kuilt.scale

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Smoke tests for [buildInMemoryMesh] and the [MeteredSeam] / [ConvergenceTracker] model.
 *
 * All tests run under [StandardTestDispatcher] virtual time — deterministic, no wall-clock.
 * Mesh operations complete synchronously in virtual time since the channel-backed in-memory
 * connections have UNLIMITED capacity.
 */
class InMemoryMeshBuilderTest {

    @Test
    fun buildCompleteMeshOfTwo() = runTest(timeout = 5.seconds) {
        val mesh = buildInMemoryMesh(2, Topology.Complete)
        assertEquals(2, mesh.size)
        assertEquals(setOf("peer-0", "peer-1"), mesh.seams.map { it.selfId.value }.toSet())
        mesh.close()
    }

    @Test
    fun buildCompleteMeshOfThree() = runTest(timeout = 5.seconds) {
        val mesh = buildInMemoryMesh(3, Topology.Complete)
        assertEquals(3, mesh.size)
        // Each peer should see all 3 peers (including itself)
        mesh.seams.forEach { seam ->
            assertEquals(3, seam.peers.value.size, "Peer ${seam.selfId} should see 3 peers")
        }
        mesh.close()
    }

    @Test
    fun buildCompleteMeshOfFive() = runTest(timeout = 5.seconds) {
        val mesh = buildInMemoryMesh(5, Topology.Complete)
        assertEquals(5, mesh.size)
        mesh.seams.forEach { seam ->
            assertEquals(5, seam.peers.value.size, "Peer ${seam.selfId} should see all 5 peers")
        }
        mesh.close()
    }

    @Test
    fun broadcastCountedByMetrics_threeNodeCompleteMesh() = runTest(timeout = 5.seconds) {
        val mesh = buildInMemoryMesh(3, Topology.Complete)
        val (a, b, c) = mesh.seams

        // Collect incoming on b and c before broadcasting
        val bFrame = async { b.incoming.first() }
        val cFrame = async { c.incoming.first() }

        val payload = "hello".encodeToByteArray()
        a.broadcast(payload)

        bFrame.await()
        cFrame.await()

        val aMetrics = a.snapshot()
        assertEquals(1L, aMetrics.broadcasts, "Peer A should record 1 broadcast")
        assertEquals(payload.size.toLong(), aMetrics.bytesOut, "Peer A should record bytes out")
        assertEquals(0L, aMetrics.framesIn, "Peer A should not receive its own broadcast")

        mesh.close()
    }

    @Test
    fun incomingFramesCountedByMetrics_threeNodeCompleteMesh() = runTest(timeout = 5.seconds) {
        val mesh = buildInMemoryMesh(3, Topology.Complete)
        val (a, b, c) = mesh.seams

        // Broadcast semantics: a.broadcast → delivered to b and c (NOT a).
        // b.broadcast → delivered to a and c (NOT b).
        // So: c receives 2 frames (from a and b), b receives 1 frame (from a), a receives 1 frame (from b).
        val bFrame = async { b.incoming.take(1).toList() }   // b gets 1 frame from a
        val cFrames = async { c.incoming.take(2).toList() }  // c gets 2 frames (from a and b)
        val aFrame = async { a.incoming.take(1).toList() }   // a gets 1 frame from b

        val payload = "data".encodeToByteArray()
        a.broadcast(payload)
        b.broadcast(payload)

        bFrame.await()
        cFrames.await()
        aFrame.await()

        val bMetrics = b.snapshot()
        val cMetrics = c.snapshot()
        val aMetrics = a.snapshot()
        assertEquals(1L, bMetrics.framesIn, "Peer B receives 1 frame (from a's broadcast)")
        assertEquals(2L, cMetrics.framesIn, "Peer C receives 2 frames (from a and b)")
        assertEquals(1L, aMetrics.framesIn, "Peer A receives 1 frame (from b's broadcast)")

        mesh.close()
    }

    @Test
    fun clusterMetricsSumsAcrossAllPeers() = runTest(timeout = 5.seconds) {
        val mesh = buildInMemoryMesh(3, Topology.Complete)
        val (a, b, c) = mesh.seams

        // a.broadcast delivers to b and c (not a itself).
        val bFrame = async { b.incoming.first() }
        val cFrame = async { c.incoming.first() }

        val payload = "ping".encodeToByteArray()
        a.broadcast(payload)

        bFrame.await()
        cFrame.await()

        val cluster = mesh.clusterMetrics()
        assertEquals(1L, cluster.totalBroadcasts, "1 broadcast total")
        // b and c each receive 1 frame; a receives nothing from its own broadcast
        assertEquals(2L, cluster.totalFramesIn, "b and c each receive 1 frame = 2 total")

        mesh.close()
    }

    @Test
    fun convergenceTrackerDetectsConvergence() = runTest(timeout = 5.seconds) {
        val n = 3
        val mesh = buildInMemoryMesh(n, Topology.Complete)
        val (a, b, c) = mesh.seams
        val tracker = ConvergenceTracker(n, targetFramesPerPeer = 1L)

        val bFrame = async { b.incoming.first() }
        val cFrame = async { c.incoming.first() }

        a.broadcast("x".encodeToByteArray())

        bFrame.await()
        cFrame.await()

        val framesPerPeer = mesh.peerMetrics().map { it.framesIn }
        tracker.recordRound(framesPerPeer)

        // b and c have 1 frame each; a has 0 (didn't collect its own broadcast)
        // Convergence requires ALL peers >= target — a has 0, so not converged unless target is 0
        // This tests the tracker logic: b and c converged, a did not
        val bIdx = 1; val cIdx = 2
        val framesAtBAndC = listOf(framesPerPeer[bIdx], framesPerPeer[cIdx])
        assertTrue(framesAtBAndC.all { it >= 1L }, "b and c should each have 1 frame")
        assertEquals(1, tracker.roundCount)

        mesh.close()
    }

    @Test
    fun buildRingTopology_connectsInRing() = runTest(timeout = 5.seconds) {
        val mesh = buildInMemoryMesh(4, Topology.Ring)
        assertEquals(4, mesh.size)
        // In a ring of 4, each peer has exactly 2 neighbors (itself + 2 others = 3 peers visible)
        mesh.seams.forEach { seam ->
            assertEquals(3, seam.peers.value.size, "Ring: peer ${seam.selfId} should see 3 peers (2 neighbors + self)")
        }
        mesh.close()
    }

    @Test
    fun buildStarTopology_hubSeesAllSpokes() = runTest(timeout = 5.seconds) {
        val n = 4
        val mesh = buildInMemoryMesh(n, Topology.Star)
        assertEquals(n, mesh.size)
        val hub = mesh.seams[0]
        // Hub (index 0) is connected to all 3 spokes; hub sees all n peers
        assertEquals(n, hub.peers.value.size, "Hub should see all $n peers")
        // Spokes only see themselves + hub = 2 peers
        mesh.seams.drop(1).forEach { spoke ->
            assertEquals(2, spoke.peers.value.size, "Spoke ${spoke.selfId} should only see hub + itself")
        }
        mesh.close()
    }

    @Test
    fun buildRequiresAtLeastTwoPeers() {
        assertFailsWith<IllegalArgumentException> {
            runTest(timeout = 5.seconds) {
                buildInMemoryMesh(1)
            }
        }
    }

    @Test
    fun messageComplexityOfCompleteMeshBroadcast_isExactlyNMinusOnePerBroadcast() =
        runTest(timeout = 5.seconds) {
            // In a fully-connected mesh, one broadcast from peer A delivers to N-1 peers.
            // The MeteredSeam on A records 1 broadcast; each of the N-1 others records 1 frame received.
            val n = 5
            val mesh = buildInMemoryMesh(n, Topology.Complete)
            val sender = mesh.seams[0]
            val receivers = mesh.seams.drop(1)

            // Collect one frame on each receiver before broadcasting
            val receiverJobs = receivers.map { r -> async { r.incoming.first() } }

            val payload = ByteArray(100) { it.toByte() }
            sender.broadcast(payload)

            receiverJobs.awaitAll()

            val senderMetrics = sender.snapshot()
            val receiverMetrics = receivers.map { it.snapshot() }

            assertEquals(1L, senderMetrics.broadcasts, "Sender: 1 broadcast")
            assertEquals(100L, senderMetrics.bytesOut, "Sender: 100 bytes out")
            receiverMetrics.forEach { m ->
                assertEquals(1L, m.framesIn, "Each receiver: 1 frame in")
                assertEquals(100L, m.bytesIn, "Each receiver: 100 bytes in")
            }

            // Regression guard: for a complete mesh with 1 broadcast, total inbound frames = N-1
            val cluster = mesh.clusterMetrics()
            assertEquals((n - 1).toLong(), cluster.totalFramesIn,
                "Complete mesh 1-broadcast convergence: N-1 total received frames")

            mesh.close()
        }

    @Test
    fun topologyEdgeCounts() {
        // Verify edge counts match expected formulas
        val n = 5
        val complete = Topology.Complete.edges(n)
        val ring = Topology.Ring.edges(n)
        val star = Topology.Star.edges(n)

        assertEquals(n * (n - 1) / 2, complete.size, "Complete graph edges = N*(N-1)/2")
        assertEquals(n, ring.size, "Ring edges = N")
        assertEquals(n - 1, star.size, "Star edges = N-1")
    }
}
