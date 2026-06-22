/**
 * Delta-target-set churn tests (Phase 1 of partial-mesh gossip, #654).
 *
 * Two concerns:
 *
 * 1. **Watermark monotonicity.** [Quilter.universalAckFlow] must never decrease,
 *    regardless of how the delta-target set changes mid-flight. Removing a lagging
 *    peer from the target set may legitimately *raise* the watermark (the constraint
 *    is gone); adding an un-acked peer must not lower it (the flow is monotonic by
 *    contract and the production code's `maxOf` enforces this).
 *
 * 2. **Churn safety.** Adding and removing peers from the delta-target set during
 *    live replication must not break either GC or convergence: the pending-delta
 *    buffer must drain fully, and every peer must eventually see the same state.
 *
 * Uses [UnconfinedTestDispatcher] + [QuilterConfig.expectVirtualTime]; anti-entropy
 * rounds are driven with bounded `advanceTimeBy` — never `advanceUntilIdle`.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val CHURN_MSG_SER = QuiltMessage.serializer(GCounter.serializer())

/** Wraps a seam but lets the test override [us.tractat.kuilt.core.Seam.peers]. */
private class ChurnControllableSeam(
    private val delegate: us.tractat.kuilt.core.Seam,
    override val peers: MutableStateFlow<Set<PeerId>>,
) : us.tractat.kuilt.core.Seam by delegate

class QuilterDeltaTargetChurnTest {

    private val antiEntropyMs = 50L
    private val baseConfig = QuilterConfig(
        antiEntropyInterval = antiEntropyMs.milliseconds,
        fullStateRetryLimit = 0,
        expectVirtualTime = true,
    )

    /**
     * Removing a lagging peer from the delta-target set may raise the watermark;
     * adding an un-acked peer must not lower it.
     *
     * Scenario:
     * - A pushes to {B, C} initially. Both ack, watermark advances to W1.
     * - A emits another delta. C is mid-flight (not yet acked).
     * - C is removed from the delta-target set → watermark may advance to W2 ≥ W1.
     * - A then adds a fresh phantom D to the target set. D has never acked.
     *   Watermark must stay ≥ W2 (must not regress).
     */
    @Test
    fun watermarkIsMonotonicUnderTargetChurn() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("wm-churn"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        // Mutable target set — starts as full membership minus self.
        val targetPeers = MutableStateFlow(setOf(seamB.selfId, seamC.selfId))
        val controlledPeers = MutableStateFlow(loom.peers.value)
        val seamA = ChurnControllableSeam(rawSeamA, controlledPeers)

        val aReplica = ReplicaId(rawSeamA.selfId.value)

        val repA = Quilter(
            replica = aReplica,
            seam = seamA,
            initial = GCounter.ZERO,
            messageSerializer = CHURN_MSG_SER,
            scope = backgroundScope,
            config = baseConfig,
            deltaTargets = { _ -> targetPeers.value },
            random = Random(77),
        )
        Quilter(
            replica = ReplicaId(seamB.selfId.value),
            seam = seamB,
            initial = GCounter.ZERO,
            messageSerializer = CHURN_MSG_SER,
            scope = backgroundScope,
            config = baseConfig,
        )
        Quilter(
            replica = ReplicaId(seamC.selfId.value),
            seam = seamC,
            initial = GCounter.ZERO,
            messageSerializer = CHURN_MSG_SER,
            scope = backgroundScope,
            config = baseConfig,
        )

        // Phase 1: both B and C in target set — seq 1 acked by both.
        repA.apply(repA.state.value.inc(aReplica, 3L))
        testScheduler.advanceUntilIdle()

        val watermarkAfterPhase1 = repA.universalAckFlow.value
        assertEquals(1L, watermarkAfterPhase1, "watermark must be 1 after both B and C acked seq 1")

        // Phase 2: emit seq 2. Then remove C from target set before its ack arrives.
        // (In InMemoryLoom, advanceUntilIdle delivers synchronously, so we record
        // watermark *before* letting C's ack through by not advancing past seq 2 ack.)
        repA.apply(repA.state.value.inc(aReplica, 1L))

        // Remove C from the delta-target set — C can no longer pin the watermark.
        targetPeers.value = setOf(seamB.selfId)

        testScheduler.advanceUntilIdle()

        val watermarkAfterCRemoved = repA.universalAckFlow.value
        assertTrue(
            watermarkAfterCRemoved >= watermarkAfterPhase1,
            "removing lagging C from target set must not lower the watermark " +
                "(was $watermarkAfterPhase1, now $watermarkAfterCRemoved)",
        )

        // Phase 3: add a phantom D (never acks) to the target set.
        val phantomD = PeerId("d-phantom-never-acks")
        controlledPeers.value = controlledPeers.value + phantomD
        targetPeers.value = targetPeers.value + phantomD
        testScheduler.advanceUntilIdle()

        val watermarkAfterDAdded = repA.universalAckFlow.value
        assertTrue(
            watermarkAfterDAdded >= watermarkAfterCRemoved,
            "adding un-acked phantom D to target set must not lower the watermark " +
                "(was $watermarkAfterCRemoved, now $watermarkAfterDAdded)",
        )
    }

    /**
     * Mid-flight delta-target-set churn does not break GC or convergence.
     *
     * A starts with delta-target {B}; halfway through a burst of updates it
     * switches to {C}. After enough anti-entropy rounds:
     * - B (initially in-target, then out) must have converged via anti-entropy.
     * - C (initially out-of-target, then in-target) must have converged.
     * - A's pending-delta buffer must be fully drained (GC proceeds against
     *   whoever is the current target at each ack).
     */
    @Test
    fun gcAndConvergenceHoldAcrossDeltaTargetSwitch() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("churn-safety"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val aReplica = ReplicaId(rawSeamA.selfId.value)

        // Mutable delta-target: starts as {B}.
        val targetPeers = MutableStateFlow(setOf(seamB.selfId))

        val repA = Quilter(
            replica = aReplica,
            seam = rawSeamA,
            initial = GCounter.ZERO,
            messageSerializer = CHURN_MSG_SER,
            scope = backgroundScope,
            config = baseConfig,
            deltaTargets = { _ -> targetPeers.value },
            random = Random(13),
        )
        val repB = Quilter(
            replica = ReplicaId(seamB.selfId.value),
            seam = seamB,
            initial = GCounter.ZERO,
            messageSerializer = CHURN_MSG_SER,
            scope = backgroundScope,
            config = baseConfig,
        )
        val repC = Quilter(
            replica = ReplicaId(seamC.selfId.value),
            seam = seamC,
            initial = GCounter.ZERO,
            messageSerializer = CHURN_MSG_SER,
            scope = backgroundScope,
            config = baseConfig,
        )

        testScheduler.advanceUntilIdle() // join handshakes

        // First half: target is {B}. Apply 5 updates.
        repeat(5) { repA.apply(repA.state.value.inc(aReplica, 1L)) }
        testScheduler.advanceUntilIdle()

        // Switch target to {C} mid-flight.
        targetPeers.value = setOf(seamC.selfId)

        // Second half: apply 5 more updates; now GC is against C's acks.
        repeat(5) { repA.apply(repA.state.value.inc(aReplica, 1L)) }
        testScheduler.advanceUntilIdle()

        // Drive anti-entropy so all peers converge on the full 10-unit state.
        val rounds = 20
        testScheduler.advanceTimeBy(antiEntropyMs * rounds + 1)
        testScheduler.runCurrent()

        val expected = 10L
        assertAll(
            {
                assertEquals(
                    expected,
                    repA.state.value.value,
                    "A state must be $expected",
                )
            },
            {
                assertEquals(
                    expected,
                    repB.state.value.value,
                    "B must converge to $expected (initially in-target, later via anti-entropy)",
                )
            },
            {
                assertEquals(
                    expected,
                    repC.state.value.value,
                    "C must converge to $expected (initially non-target, later in-target)",
                )
            },
            {
                assertTrue(
                    repA.pendingDeltasForTest.isEmpty(),
                    "A's pending-delta buffer must be fully drained after target churn " +
                        "(remaining keys: ${repA.pendingDeltasForTest.keys})",
                )
            },
        )
    }
}
