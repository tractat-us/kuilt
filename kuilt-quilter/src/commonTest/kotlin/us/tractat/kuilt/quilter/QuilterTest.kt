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
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private fun gcounterSer() = QuiltMessage.serializer(GCounter.serializer())

private fun orSetSer() =
    QuiltMessage.serializer(ORSet.serializer(kotlinx.serialization.serializer<String>()))

/** Default config for replicator tests: suppresses the TestDispatcher guard warning. */
private val REPLICATOR_TEST_CONFIG = QuilterConfig(expectVirtualTime = true)

/** Short anti-entropy interval for the test that drives individual ticks by hand. */
private const val ANTI_ENTROPY_MS = 50L

private fun gcounterReplicator(
    seam: us.tractat.kuilt.core.Seam,
    scope: CoroutineScope,
    config: QuilterConfig = REPLICATOR_TEST_CONFIG,
) = Quilter(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,
    initial = GCounter.ZERO,
    messageSerializer = gcounterSer(),
    scope = scope,
    config = config,
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

        repA.mutate { it.add(repA.replica, "apple") }
        repB.mutate { it.add(repB.replica, "banana") }
        repC.mutate { it.add(repC.replica, "cherry") }

        // Let B's add propagate to A before A removes "banana".
        testScheduler.advanceUntilIdle()

        // A removes "banana" — the remove wins because A has seen B's dot.
        repA.mutate { it.remove("banana") }

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
     * must not trigger any state change — the idempotence guard introduced for #737 — and
     * the receiver must instead push its own state back so the lagging sender heals (#828).
     *
     * ## Why the setup has to work this hard (#2002)
     *
     * The delta path must be **dead** for the anti-entropy path to be observable at all.
     * [Quilter.apply] broadcasts to every peer in the room; `deltaTargets` only chooses whom
     * a replica GCs against. So the earlier version of this test — which simply applied a
     * mutation and advanced a tick — had already delivered that mutation by broadcast before
     * the tick fired. It asserted a value that was true one line earlier, stayed green with
     * the whole anti-entropy reconcile deleted, and pinned nothing.
     *
     * And post-#1955 the tick ships a [QuiltMessage.RootDigest], not state: two converged
     * peers now agree on the root and **no [QuiltMessage.FullState] is sent at all**. A
     * dominated FullState is only reachable when the two states genuinely differ, so this
     * test must construct that divergence rather than assume it.
     *
     * ## The trajectory
     *
     * 1. Gate open: A and B converge to 8 over the ordinary delta path.
     * 2. Gate closed ([BroadcastGateSeam]): A applies +7 alone. A is 15, B is stranded at 8 —
     *    a *genuine* dominated state (B's own 3 is already inside A's 15), not an empty one.
     * 3. Exactly one tick, on B only (A's interval is set far outside the window), so the
     *    exchange runs in the direction that puts the dominated FullState **into A**:
     *    B `RootDigest` → A's root differs → A `FullStateRequest` → B `FullState(8)` → A.
     *
     * A's guard sees `merged == current` and must (a) leave A at 15 and (b) take the
     * `else if` branch, pushing A's own state back to B. That push-back is B's **only**
     * route to 15 — the broadcast gate is shut and A never ticks — so `repB == 15` is a
     * direct assertion on the guard, and deleting either the guard or the reconcile
     * strands B at 8.
     */
    @Test
    fun dominatedFullStateIsIdempotentAndNewInfoIsPreserved() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("test"))
        val seamB = loom.join(InMemoryTag("b"))

        // A's delta broadcasts are silenced from step 2 on; sendTo (anti-entropy) stays live.
        var deltaPathClosed = false
        val seamA = BroadcastGateSeam(rawSeamA) { deltaPathClosed }

        // Only B ticks inside the test window: A's interval is an order of magnitude beyond it.
        // fullStateRetryLimit = 0 so no first-contact retry can heal B behind the guard's back.
        val repA = gcounterReplicator(
            seamA,
            backgroundScope,
            QuilterConfig(
                antiEntropyInterval = 1.minutes,
                fullStateRetryLimit = 0,
                expectVirtualTime = true,
            ),
        )
        val repB = gcounterReplicator(
            seamB,
            backgroundScope,
            QuilterConfig(
                antiEntropyInterval = ANTI_ENTROPY_MS.milliseconds,
                fullStateRetryLimit = 0,
                expectVirtualTime = true,
            ),
        )

        // 1. Both accumulate and fully converge over the delta path.
        repA.apply(repA.state.value.inc(repA.replica, 5L))
        repB.apply(repB.state.value.inc(repB.replica, 3L))
        testScheduler.advanceUntilIdle()
        assertEquals(8L, repA.state.value.value)
        assertEquals(8L, repB.state.value.value)

        // 2. Shut the delta path, then advance A alone. B cannot learn this by broadcast.
        deltaPathClosed = true
        repA.apply(repA.state.value.inc(repA.replica, 7L))
        testScheduler.advanceUntilIdle()
        assertEquals(15L, repA.state.value.value)
        assertEquals(
            8L,
            repB.state.value.value,
            "premise: the delta path must be dead, or the anti-entropy exchange below proves nothing",
        )

        // 3. One tick, B only. B's dominated FullState(8) lands on A.
        testScheduler.advanceTimeBy(ANTI_ENTROPY_MS + 1)
        testScheduler.advanceUntilIdle()

        assertEquals(
            15L,
            repA.state.value.value,
            "A must ignore B's dominated FullState(8) and stay at 15",
        )
        assertEquals(
            15L,
            repB.state.value.value,
            "B must heal to 15 via the guard's push-back — the only route open to it",
        )

        // Converged: the roots now match, so the next tick ships a digest and no state at all
        // (#1955). Nothing may move.
        testScheduler.advanceTimeBy(ANTI_ENTROPY_MS + 1)
        testScheduler.advanceUntilIdle()

        assertEquals(15L, repA.state.value.value, "A must be unmoved by a matched-root round")
        assertEquals(15L, repB.state.value.value, "B must be unmoved by a matched-root round")
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
