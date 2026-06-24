/**
 * Integration tests for [us.tractat.kuilt.crdt.ResettableCounter]'s observed-reset
 * semantics over a live [Quilter] + [us.tractat.kuilt.test.ControllableLoom].
 *
 * The defining correctness property under test:
 * > A reset removes only the increments it causally observed. An increment concurrent
 * > with the reset — minted on a replica that had not yet received the reset — survives
 * > the merge.
 *
 * This property is tested in isolation in [us.tractat.kuilt.crdt.ResettableCounterTest];
 * these tests verify it end-to-end through [Quilter]'s delta exchange, gap detection,
 * and anti-entropy paths.
 *
 * Uses [ControllableLoom] (via [us.tractat.kuilt.test.ControllableLoom.holdDelivery] /
 * [us.tractat.kuilt.test.ControllableLoom.releaseDelivery]) to script the precise
 * interleaving that makes a reset concurrent with an increment. With
 * [us.tractat.kuilt.core.InMemoryLoom], broadcasts fan out atomically — there is no
 * window between "A resets" and "B receives the reset" where B can increment without
 * having seen the reset. [ControllableLoom] makes that window controllable.
 *
 * Follows the established quilter-test pattern: [UnconfinedTestDispatcher] with
 * [QuilterConfig.expectVirtualTime], `backgroundScope`, and bounded time-advance
 * for anti-entropy scenarios (never [kotlinx.coroutines.test.TestCoroutineScheduler.advanceUntilIdle]
 * for timer-driven loops).
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.ResettableCounter
import us.tractat.kuilt.test.ControllableLoom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

private val MSG_SER = QuiltMessage.serializer(ResettableCounter.serializer())

private val BASE_CONFIG = QuilterConfig(expectVirtualTime = true)

private fun replicatorFor(
    seam: Seam,
    scope: CoroutineScope,
    config: QuilterConfig = BASE_CONFIG,
    random: Random = Random.Default,
) = Quilter(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,
    initial = ResettableCounter.ZERO,
    messageSerializer = MSG_SER,
    scope = scope,
    config = config,
    random = random,
)

class QuilterResettableCounterTest {

    /**
     * Scenario 1: concurrent increment + reset converges to the surviving increment.
     *
     * Timeline (two peers; delivery to B is held so B's increment races with A's reset):
     * 1. A increments by 5. Both A and B receive the delta — B's state = 5.
     * 2. Delivery to B is held: A's subsequent broadcasts won't reach B yet.
     * 3. A resets based on its current state (which has seen +5). A's state = 0.
     * 4. B increments by 3. B has NOT yet seen A's reset — B's dot has no knowledge
     *    of the reset's causal context.
     * 5. Delivery to B is released: B receives A's reset and A receives B's +3.
     * 6. Convergence: A's +5 was causally observed by the reset → cleared.
     *    B's +3 was concurrent with the reset (B never received the reset when it
     *    incremented) → survives in both replicas. Final value = 3.
     */
    @Test
    fun concurrentIncrementAndResetConvergesToSurvivingIncrement() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val seamA = loom.host(Pattern("a"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = replicatorFor(seamA, backgroundScope)
        val repB = replicatorFor(seamB, backgroundScope)

        // Step 1: A increments by 5. Both peers receive the delta.
        repA.apply(repA.state.value.increment(repA.replica, 5L))
        testScheduler.advanceUntilIdle()

        assertEquals(5L, repA.state.value.value, "A: initial +5 applied")
        assertEquals(5L, repB.state.value.value, "B: received A's +5")

        // Step 2: Hold delivery TO B so A's reset won't reach B immediately.
        loom.holdDelivery(seamB.selfId)

        // Step 3: A resets — it has seen +5, so the reset retires that dot.
        //         A's broadcast is held in B's queue and not yet processed by B.
        repA.apply(repA.state.value.reset())
        testScheduler.advanceUntilIdle()

        // A's local state is now 0; B still sees 5 (reset not delivered yet).
        assertEquals(0L, repA.state.value.value, "A: locally reset to zero")
        assertEquals(5L, repB.state.value.value, "B: has not received A's reset yet")

        // Step 4: B increments by 3. B's causal context for this increment only knows
        //         about A's original +5 — not A's reset. B's +3 dot is therefore
        //         NOT in A's reset's causal context and will survive the merge.
        repB.apply(repB.state.value.increment(repB.replica, 3L))
        testScheduler.advanceUntilIdle()

        // B has not seen the reset, so B's total is 5 + 3 = 8.
        assertEquals(8L, repB.state.value.value, "B: concurrent increment — sees 5 + 3 = 8 without reset")

        // Step 5: Release held frames. B receives A's reset; A receives B's +3.
        loom.releaseDelivery(seamB.selfId)
        testScheduler.advanceUntilIdle()

        // Step 6: B's +3 survives (concurrent with reset). A's +5 is cleared (reset observed it).
        assertEquals(3L, repA.state.value.value, "A: only the concurrent +3 survives the merge")
        assertEquals(3L, repB.state.value.value, "B: converges — concurrent +3 preserved, +5 cleared by reset")
    }

    /**
     * Scenario 2: reset delta dropped, anti-entropy heals the partition.
     *
     * A and B each accumulate state. A resets — but A's reset broadcast is dropped so
     * B never receives it via the delta path. Anti-entropy (`reconcileWithRandomPeer`)
     * then pushes A's full state to B. B's concurrent +4 (incremented before any
     * knowledge of A's reset) survives; A's observed +10 does not.
     *
     * Because `InMemoryLoom` delivers broadcasts atomically, true concurrency between
     * A's reset and B's increment requires holding B's delivery window as in scenario 1.
     * Here we use a custom [Seam] wrapper to drop only the reset broadcast, then verify
     * that anti-entropy delivers the semantically correct merged state.
     *
     * Timeline:
     * 1. A increments by 10. B sees it (both at 10).
     * 2. Hold delivery to A so A won't receive B's increment before A resets.
     * 3. B increments by 4. B has delta (+4) queued to A, but A won't receive it yet.
     * 4. A resets — A has not seen B's +4, so B's +4 is concurrent with A's reset.
     *    A's reset broadcast is wrapped to be dropped (delta path never reaches B).
     * 5. Release delivery to A. A receives B's +4. A = +4 (only B's concurrent increment).
     * 6. Anti-entropy: A reconciles full state into B via FullState. B converges to +4.
     */
    @Test
    fun droppedResetDeltaHealedByAntiEntropy() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val rawSeamA = loom.host(Pattern("a"))
        val seamB = loom.join(InMemoryTag("b"))

        val config = QuilterConfig(
            antiEntropyInterval = 50.milliseconds,
            fullStateRetryLimit = 0, // isolate: no joiner full-state retries
            expectVirtualTime = true,
        )

        // Wrap A's seam to drop the next broadcast (the reset delta) while
        // still forwarding everything else (increments, Acks, FullState).
        var dropNextBroadcast = false
        val seamA = object : Seam by rawSeamA {
            override suspend fun broadcast(payload: ByteArray) {
                if (dropNextBroadcast) {
                    dropNextBroadcast = false
                } else {
                    rawSeamA.broadcast(payload)
                }
            }
        }

        val repA = replicatorFor(seamA, backgroundScope, config, random = Random(42))
        val repB = replicatorFor(seamB, backgroundScope, config)

        testScheduler.advanceUntilIdle() // settle join handshake

        // Step 1: A increments by 10. B sees it.
        repA.apply(repA.state.value.increment(repA.replica, 5L))
        repA.apply(repA.state.value.increment(repA.replica, 5L))
        testScheduler.advanceUntilIdle()
        assertEquals(10L, repA.state.value.value, "A: +10 applied")
        assertEquals(10L, repB.state.value.value, "B: received A's +10")

        // Step 2: Hold delivery TO A so A won't receive B's +4 before A resets.
        loom.holdDelivery(rawSeamA.selfId)

        // Step 3: B increments by 4. B's delta is queued for A but not yet delivered.
        repB.apply(repB.state.value.increment(repB.replica, 4L))
        testScheduler.advanceUntilIdle()
        // B's local total is 10 + 4 = 14; A has not yet seen B's delta.
        assertEquals(10L, repA.state.value.value, "A: has not received B's +4 yet")

        // Step 4: A resets. A has NOT seen B's +4, so B's +4 dot is concurrent with
        //         this reset and will survive the merge. The reset broadcast is dropped
        //         so B won't receive it via the delta path.
        dropNextBroadcast = true
        repA.apply(repA.state.value.reset())
        testScheduler.advanceUntilIdle()

        // A is locally at 0 (reset cleared what it observed: its own +10).
        assertEquals(0L, repA.state.value.value, "A: locally reset to zero")
        // B has not received the dropped reset delta — still at 14.
        assertEquals(14L, repB.state.value.value, "B: reset delta was dropped, still at 14")

        // Step 5: Release held frames. A receives B's +4.
        //         B's +4 was concurrent with A's reset → survives in A's state.
        loom.releaseDelivery(rawSeamA.selfId)
        testScheduler.advanceUntilIdle()
        assertEquals(4L, repA.state.value.value, "A: B's concurrent +4 survives the reset")
        // B has still not received the reset (it was dropped from the delta path).
        assertEquals(14L, repB.state.value.value, "B: still diverged — reset delta never arrived")

        // Step 6: Drive anti-entropy: A reconciles full state into B via FullState.
        testScheduler.advanceTimeBy(config.antiEntropyInterval.inWholeMilliseconds * 3 + 1)
        testScheduler.runCurrent()

        assertEquals(4L, repA.state.value.value, "A: unchanged after anti-entropy")
        assertEquals(4L, repB.state.value.value, "B: healed by anti-entropy — concurrent +4 survives, +10 cleared by reset")
    }

    /**
     * Scenario 3: idempotent re-delivery of a reset delta.
     *
     * A resets; B receives the reset delta. The same reset delta is then re-delivered
     * to B (simulating duplicate gossip). The final value must be unchanged — the
     * join-semilattice absorbs the duplicate without corrupting state.
     *
     * Verifies that [ResettableCounter.piece] (the join) is idempotent over a
     * wire-encoded delta, as required by the Quilter's duplicate-handling invariant.
     */
    @Test
    fun duplicateResetDeltaIsIdempotent() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val seamA = loom.host(Pattern("a"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = replicatorFor(seamA, backgroundScope)
        val repB = replicatorFor(seamB, backgroundScope)

        // A increments to 5; both peers see it.
        repA.apply(repA.state.value.increment(repA.replica, 5L))
        testScheduler.advanceUntilIdle()
        assertEquals(5L, repB.state.value.value, "B: received A's +5")

        // A resets; the reset delta reaches B normally.
        repA.apply(repA.state.value.reset())
        testScheduler.advanceUntilIdle()
        assertEquals(0L, repA.state.value.value, "A: reset to zero")
        assertEquals(0L, repB.state.value.value, "B: received reset delta, at zero")

        // Craft a duplicate of A's reset delta and re-deliver it via A's seam broadcast.
        // seq=2 is the reset (seq=1 was the +5 increment).
        // The reset delta: a counter that has seen +5 from A, then reset (cleared store, kept context).
        val counterWith5 = ResettableCounter.ZERO.piece(ResettableCounter.ZERO.increment(repA.replica, 5L).delta)
        val resetDelta = counterWith5.reset().delta
        val duplicateMsg = QuiltMessage.Delta(
            sender = repA.replica,
            seq = 2L,
            delta = resetDelta,
        )
        seamA.broadcast(Cbor.encodeToByteArray(MSG_SER, duplicateMsg))
        testScheduler.advanceUntilIdle()

        // State must remain 0 — the duplicate reset is absorbed idempotently.
        assertEquals(0L, repA.state.value.value, "A: unchanged after duplicate reset delivery")
        assertEquals(0L, repB.state.value.value, "B: duplicate reset absorbed idempotently, still zero")
    }
}
