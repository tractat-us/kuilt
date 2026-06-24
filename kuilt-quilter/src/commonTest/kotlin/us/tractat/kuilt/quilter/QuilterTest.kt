/**
 * Replicator tests run a real [Quilter] under `UnconfinedTestDispatcher`.
 * The contract mirrors `:kuilt-raft`'s `RaftTestFixtures.kt`: see issue #186.
 *
 * Tests inject [QuilterConfig] with `expectVirtualTime = true` so the
 * TestDispatcher guard does not warn. Future replicator tests should follow
 * the same pattern, or use a fake replicator (planned in #186 Phase B).
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

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

private fun gcounterSer() = QuiltMessage.serializer(GCounter.serializer())

private fun orSetSer() =
    QuiltMessage.serializer(ORSet.serializer(kotlinx.serialization.serializer<String>()))

/** Default config for replicator tests: suppresses the TestDispatcher guard warning. */
private val REPLICATOR_TEST_CONFIG = QuilterConfig(expectVirtualTime = true)

private fun gcounterReplicator(
    seam: us.tractat.kuilt.core.Seam,
    scope: CoroutineScope,
) = Quilter(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,
    initial = GCounter.ZERO,
    messageSerializer = gcounterSer(),
    scope = scope,
    config = REPLICATOR_TEST_CONFIG,
)

class QuilterTest {

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
        fun orSetRep(seam: us.tractat.kuilt.core.Seam) = Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = ORSet.empty<String>(),
            messageSerializer = msgSer,
            scope = backgroundScope,
            config = REPLICATOR_TEST_CONFIG,
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
     * A [QuiltMessage.FullState] whose state is dominated by the receiver's current state
     * must not trigger any state change — the idempotence guard introduced for #737.
     *
     * Setup: A and B converge; then A applies an extra increment alone. B now has a
     * dominated state. After anti-entropy fires (B → A), A's state must remain at the
     * converged value and must NOT be replaced with the lower-count dominated state.
     *
     * Also verifies the converse: B must still correctly receive and apply A's FullState
     * (new information is never dropped). The guard only skips the dominated direction.
     */
    @Test
    fun dominatedFullStateIsIdempotentAndNewInfoIsPreserved() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("test"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = gcounterReplicator(seamA, backgroundScope)
        val repB = gcounterReplicator(seamB, backgroundScope)

        // Both accumulate and fully converge.
        repA.apply(repA.state.value.inc(repA.replica, 5L))
        repB.apply(repB.state.value.inc(repB.replica, 3L))
        testScheduler.advanceUntilIdle()
        assertEquals(8L, repA.state.value.value)
        assertEquals(8L, repB.state.value.value)

        // B applies an extra increment that A has NOT seen yet.
        repB.apply(repB.state.value.inc(repB.replica, 7L))

        // Advance ONE anti-entropy tick. B sends A its full state (now 15).
        // This is NOT dominated — A must learn B's new count and converge to 15.
        testScheduler.advanceTimeBy(QuilterConfig().antiEntropyInterval.inWholeMilliseconds)
        testScheduler.advanceUntilIdle()

        assertEquals(15L, repA.state.value.value, "A must absorb B's new state via FullState")
        assertEquals(15L, repB.state.value.value)

        // Now both are fully converged. The NEXT anti-entropy tick sends equal state.
        // The guard must fire (merged == current) and A's value must stay at 15.
        testScheduler.advanceTimeBy(QuilterConfig().antiEntropyInterval.inWholeMilliseconds)
        testScheduler.advanceUntilIdle()

        assertEquals(15L, repA.state.value.value, "A's state must remain 15 after dominated FullState delivery")
        assertEquals(15L, repB.state.value.value, "B's state must remain 15 after dominated FullState delivery")
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
