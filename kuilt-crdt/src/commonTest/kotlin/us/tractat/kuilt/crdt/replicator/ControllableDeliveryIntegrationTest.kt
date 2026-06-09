/**
 * Demonstrating test for [us.tractat.kuilt.test.ControllableLoom].
 *
 * Scripts interleavings that [us.tractat.kuilt.core.InMemoryLoom] cannot express:
 * peer A acts on local state while peer C's concurrent frame is held, then C's frame
 * is released and the system must still converge.
 *
 * [us.tractat.kuilt.core.InMemoryLoom.dispatch] fans out atomically — there is no
 * moment between "C broadcasts" and "A receives C's frame" where A can act on a view
 * that doesn't include C's op. [us.tractat.kuilt.test.ControllableLoom] makes that
 * gap controllable.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.ControllableLoom
import kotlin.test.Test
import kotlin.test.assertEquals

private val CFG = SeamReplicatorConfig(expectVirtualTime = true)

private fun gcounterReplicator(
    seam: us.tractat.kuilt.core.Seam,
    scope: kotlinx.coroutines.CoroutineScope,
) = SeamReplicator(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,
    initial = GCounter.ZERO,
    messageSerializer = ReplicatorMessage.serializer(GCounter.serializer()),
    scope = scope,
    config = CFG,
)

class ControllableDeliveryIntegrationTest {

    /**
     * Delayed-delivery interleaving that [us.tractat.kuilt.core.InMemoryLoom] cannot script.
     *
     * Scenario (3-peer GCounter):
     * 1. A and B each increment their slots and converge normally.
     * 2. C's frames to A are held — A is now partitioned from C's writes.
     * 3. C increments its slot; B receives C's delta but A does not.
     * 4. A increments again — B and C receive A's delta.
     * 5. C's held frames are released to A.
     * 6. All three peers must converge to the same total: 4 increments (A×2, B×1, C×1).
     *
     * With [us.tractat.kuilt.core.InMemoryLoom], steps 2–5 are impossible to express: C's
     * broadcast reaches A atomically in step 3, leaving no window for A to diverge.
     */
    @Test
    fun heldDeliveryStillConverges() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val seamA = loom.host(Pattern("a"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val repA = gcounterReplicator(seamA, backgroundScope)
        val repB = gcounterReplicator(seamB, backgroundScope)
        val repC = gcounterReplicator(seamC, backgroundScope)

        // Step 1: A and B each increment. All peers receive.
        repA.apply(repA.state.value.inc(repA.replica))
        repB.apply(repB.state.value.inc(repB.replica))
        testScheduler.advanceUntilIdle()

        // Step 2: Hold delivery to A so A won't see C's subsequent ops.
        loom.holdDelivery(seamA.selfId)

        // Step 3: C increments — B gets it, A does not.
        repC.apply(repC.state.value.inc(repC.replica))
        testScheduler.advanceUntilIdle()

        // A's view of C's slot is still 0 at this point.
        assertEquals(0L, repA.state.value.count(repC.replica), "A must not yet see C's increment")

        // Step 4: A increments again — B and C receive A's new delta.
        repA.apply(repA.state.value.inc(repA.replica))
        testScheduler.advanceUntilIdle()

        // Step 5: Release C's held frames to A.
        loom.releaseDelivery(seamA.selfId)
        testScheduler.advanceUntilIdle()

        // Step 6: All peers must converge to total = 4 increments (A×2, B×1, C×1).
        val expectedTotal = 4L
        val totalA = repA.state.value.value
        val totalB = repB.state.value.value
        val totalC = repC.state.value.value

        assertEquals(expectedTotal, totalA, "A must converge to $expectedTotal after release")
        assertEquals(expectedTotal, totalB, "B must converge to $expectedTotal")
        assertEquals(expectedTotal, totalC, "C must converge to $expectedTotal")
    }

    /**
     * Out-of-order delivery via [us.tractat.kuilt.test.ControllableLoom.deliverNext]:
     * frames from A are queued at B; the hold is partially flushed then released.
     * B fully converges despite the non-atomic delivery.
     *
     * [us.tractat.kuilt.core.InMemoryLoom] always delivers in emission order and cannot
     * produce a "partial delivery then release" scenario.
     */
    @Test
    fun partialReleaseViaDeliverNextConverges() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val seamA = loom.host(Pattern("a"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = gcounterReplicator(seamA, backgroundScope)
        val repB = gcounterReplicator(seamB, backgroundScope)

        // Hold all delivery to B.
        loom.holdDelivery(seamB.selfId)

        // A emits two increments. Both land in B's hold queue.
        repA.apply(repA.state.value.inc(repA.replica))
        testScheduler.advanceUntilIdle()
        repA.apply(repA.state.value.inc(repA.replica))
        testScheduler.advanceUntilIdle()

        // B has seen nothing yet.
        assertEquals(0L, repB.state.value.count(repA.replica), "B must not see A's ops while held")

        // Deliver exactly one frame; hold stays active.
        val delivered = loom.deliverNext(seamB.selfId)
        assertEquals(true, delivered)
        testScheduler.advanceUntilIdle()

        // Release the remainder.
        loom.releaseDelivery(seamB.selfId)
        testScheduler.advanceUntilIdle()

        assertEquals(
            repA.state.value.count(repA.replica),
            repB.state.value.count(repA.replica),
            "B must converge to A's slot after full release",
        )
    }
}
