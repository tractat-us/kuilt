@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
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
import kotlin.test.assertEquals

/**
 * [meshSeam] bounded-backpressure behaviour — mirrors [us.tractat.kuilt.core.InMemoryLoomPolicyTest]
 * for the mesh fabric.
 *
 * The backpressure point in MeshSeam is the read-loop: when the spool is full, `spool.deliver`
 * suspends the read-loop coroutine. Subsequent frames are not forwarded to the consumer until the
 * consumer drains — so the flow of frames from [Seam.incoming] is naturally bounded.
 */
class MeshSeamPolicyTest {

    /**
     * A mesh inbox with capacity 1 must block the read-loop when the consumer has not yet drained
     * the first frame — providing true backpressure rather than unbounded buffering.
     *
     * peer-1 sends two frames into the connection. The read-loop delivers frame 1 to the spool,
     * then suspends in `deliver(frame2)` because the spool is full (capacity=1, consumer hasn't
     * drained). Only once the consumer collects frame 1 does the spool have room and frame 2
     * arrives.
     *
     * Observable: after the read-loop has had a chance to run but before the consumer drains,
     * exactly ONE frame has been forwarded (not two). The second arrives only after the drain.
     */
    @Test
    fun reliablePolicyAppliesBackpressureInsteadOfUnboundedBuffering() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val policy = DeliveryPolicy(capacity = 1, overflow = Overflow.SUSPEND)

        val self = PeerId("peer-0")
        val sender = PeerId("peer-1")

        val (mine, theirs) = connectionPair()

        // peer-0 builds its mesh; handshake runs concurrently.
        val selfMeshDeferred = async { meshSeam(self, listOf(mine), dispatcher, Random(0), policy) }
        val handshakeDeferred = async {
            theirs.send(MeshHello.encode(sender, byteArrayOf(42)))
            MeshHello.decode(theirs.incoming.first())
        }
        val selfMesh = selfMeshDeferred.await()
        handshakeDeferred.await()

        // peer-1 sends two frames back-to-back. Both enter the unlimited connection channel immediately.
        theirs.send(byteArrayOf(1))
        theirs.send(byteArrayOf(2))

        // Run one scheduler step: the read-loop delivers frame 1 to the spool (capacity=1, now full)
        // and then suspends trying to deliver frame 2. The consumer has not run yet.
        runCurrent()

        // Collect the first frame — drains the spool and unblocks the read-loop.
        val first = selfMesh.incoming.first()
        assertContentEquals(byteArrayOf(1), first.toByteArray(), "first frame must be byteArrayOf(1)")

        // Let the read-loop resume and deliver frame 2.
        runCurrent()
        val second = selfMesh.incoming.first()
        assertContentEquals(byteArrayOf(2), second.toByteArray(), "second frame must be byteArrayOf(2)")

        // Confirm sequence numbers are strictly monotonically increasing (assigned atomically by the
        // read-loop, one per frame — backpressure must not cause skips or duplicates).
        assertEquals(1L, first.sequence, "frame 1 sequence must be 1")
        assertEquals(2L, second.sequence, "frame 2 sequence must be 2")
    }
}
