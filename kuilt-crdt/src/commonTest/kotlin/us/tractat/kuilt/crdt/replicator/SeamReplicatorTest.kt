@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun gcounterSer() = ReplicatorMessage.serializer(GCounter.serializer())

private fun orSetSer() =
    ReplicatorMessage.serializer(ORSet.serializer(kotlinx.serialization.serializer<String>()))

private fun gcounterReplicator(
    seam: us.tractat.kuilt.core.Seam,
    scope: CoroutineScope,
) = SeamReplicator(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,
    initial = GCounter.ZERO,
    messageSerializer = gcounterSer(),
    scope = scope,
)

class SeamReplicatorTest {

    /**
     * Two peers independently increment their GCounter slots; after round-trip
     * delta exchange both replicas must agree on the total.
     */
    @Test
    fun twoPeerGCounterConverges() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("test"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = gcounterReplicator(seamA, backgroundScope)
        val repB = gcounterReplicator(seamB, backgroundScope)

        repA.apply(repA.state.value.inc(repA.replica, 3L))
        repA.apply(repA.state.value.inc(repA.replica, 2L))
        repB.apply(repB.state.value.inc(repB.replica, 4L))

        testScheduler.advanceUntilIdle()

        assertEquals(9L, repA.state.value.value)
        assertEquals(9L, repB.state.value.value)
    }

    /**
     * Three-peer ORSet: each peer adds a fruit; A also removes "banana" after
     * seeing B's add. The remove wins (B's dot was witnessed before A removed).
     * All three replicas converge to the same set.
     */
    @Test
    fun threePeerOrSetConverges() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("test"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val msgSer = orSetSer()
        fun orSetRep(seam: us.tractat.kuilt.core.Seam) = SeamReplicator(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = ORSet.empty<String>(),
            messageSerializer = msgSer,
            scope = backgroundScope,
        )

        val repA = orSetRep(seamA)
        val repB = orSetRep(seamB)
        val repC = orSetRep(seamC)

        repA.apply(Patch(repA.state.value.add(repA.replica, "apple")))
        repB.apply(Patch(repB.state.value.add(repB.replica, "banana")))
        repC.apply(Patch(repC.state.value.add(repC.replica, "cherry")))

        // Let B's add propagate to A before A removes "banana".
        testScheduler.advanceUntilIdle()

        // A removes "banana" — the remove wins because A has seen B's dot.
        repA.apply(Patch(repA.state.value.remove("banana")))

        testScheduler.advanceUntilIdle()

        val expected = setOf("apple", "cherry")
        assertEquals(expected, repA.state.value.elements)
        assertEquals(expected, repB.state.value.elements)
        assertEquals(expected, repC.state.value.elements)
    }

    /**
     * A and B accumulate state; C joins late and should converge via FullState
     * without replaying any delta history.
     */
    @Test
    fun lateJoinerReceivesFullState() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("test"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = gcounterReplicator(seamA, backgroundScope)
        val repB = gcounterReplicator(seamB, backgroundScope)

        repA.apply(repA.state.value.inc(repA.replica, 10L))
        repB.apply(repB.state.value.inc(repB.replica, 5L))
        testScheduler.advanceUntilIdle()

        // C joins after A and B have already accumulated state.
        val seamC = loom.join(InMemoryTag("c"))
        val repC = gcounterReplicator(seamC, backgroundScope)

        testScheduler.advanceUntilIdle()

        // C must have received FullState from A and B and converged to 15.
        assertEquals(15L, repA.state.value.value)
        assertEquals(15L, repC.state.value.value)
    }

    /**
     * After B has acked all of A's deltas, A's pending delta buffer must be empty.
     */
    @Test
    fun pendingDeltasClearedAfterUniversalAck() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("test"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = gcounterReplicator(seamA, backgroundScope)
        gcounterReplicator(seamB, backgroundScope)

        repA.apply(repA.state.value.inc(repA.replica, 1L))
        repA.apply(repA.state.value.inc(repA.replica, 1L))
        repA.apply(repA.state.value.inc(repA.replica, 1L))

        testScheduler.advanceUntilIdle()

        assertTrue(
            repA.pendingDeltasForTest.isEmpty(),
            "pendingDeltas should be empty after universal ack but was: ${repA.pendingDeltasForTest.keys}",
        )
    }
}
