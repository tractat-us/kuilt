@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [meshSeam] bounded-backpressure behaviour — mirrors [us.tractat.kuilt.core.InMemoryLoomPolicyTest]
 * for the mesh fabric.
 *
 * A sender flooding frames faster than the receiver drains them must SUSPEND (not pile up without
 * limit) when the mesh is constructed with a [DeliveryPolicy] whose overflow is [Overflow.SUSPEND].
 */
class MeshSeamPolicyTest {

    /**
     * A mesh inbox with capacity 1 must block the sending read-loop when the receiver has not yet
     * drained the first frame — providing true backpressure rather than unbounded buffering.
     *
     * Setup: peer-0 hosts a mesh with one link to peer-1. peer-1 broadcasts two frames rapidly.
     * With capacity=1, the second frame must NOT arrive in peer-0's inbox until peer-0 drains
     * the first one.
     */
    @Test
    fun reliablePolicyAppliesBackpressureInsteadOfUnboundedBuffering() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val policy = DeliveryPolicy(capacity = 1, overflow = Overflow.SUSPEND)

        val self = PeerId("peer-0")
        val sender = PeerId("peer-1")

        val (mine, theirs) = connectionPair()

        // peer-0 builds its mesh; peer-1 performs the handshake from the other end.
        val selfMeshDeferred = async { meshSeam(self, listOf(mine), dispatcher, Random(0), policy) }
        val handshakeDeferred = async {
            theirs.send(MeshHello.encode(sender, byteArrayOf(42)))
            MeshHello.decode(theirs.incoming.first())
        }

        val selfMesh = selfMeshDeferred.await()
        handshakeDeferred.await()

        // peer-1 sends two frames in quick succession. With capacity=1, the second send must
        // suspend inside the read-loop until peer-0 drains the first frame.
        var sentBoth = false
        val senderJob = backgroundScope.launch {
            theirs.send(byteArrayOf(1))
            theirs.send(byteArrayOf(2))
            sentBoth = true
        }
        runCurrent()
        assertFalse(sentBoth, "second send must suspend: peer-0's inbox (cap 1) is full")

        // Drain the first frame from peer-0's inbox — this unblocks the read-loop.
        val first = selfMesh.incoming.first()
        assertContentEquals(byteArrayOf(1), first.toByteArray(), "first frame must be byteArrayOf(1)")

        runCurrent()
        assertTrue(sentBoth, "second send completes once peer-0 drains the first frame")
        senderJob.cancel()
    }
}
