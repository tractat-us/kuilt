/**
 * Tests for the **anti-entropy reconcile backstop** (Phase 1 of partial-mesh
 * gossip, #654).
 *
 * Each anti-entropy round a replica picks one random full-membership peer (via an
 * injected seeded RNG) and reconciles by sending its full state. Because every
 * delta-state CRDT is a join-semilattice, a full-state merge is idempotent and
 * order-independent — so a peer that missed a delta (it was outside the sender's
 * delta-target set, or the relayed delta was dropped) still converges. This is
 * what makes GC-against-a-sparse-delta-target-set safe.
 *
 * Uses [UnconfinedTestDispatcher] with [QuilterConfig.expectVirtualTime]; anti-
 * entropy rounds are driven with **bounded** `advanceTimeBy` (never
 * `advanceUntilIdle`, which spins on the re-arming timer).
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

private val MSG_SER = QuiltMessage.serializer(GCounter.serializer())

class QuilterAntiEntropyReconcileTest {

    /**
     * A peer that never receives a delta still converges via anti-entropy.
     *
     * A's delta broadcasts are 100% dropped (`ChaosConfig.dropProbability = 1.0`),
     * so B never sees A's increment through the normal path. With joiner full-state
     * retries disabled (`fullStateRetryLimit = 0`), the *only* way B can learn A's
     * state is the periodic anti-entropy reconcile — A picking B and merging full
     * state into it.
     */
    @Test
    fun peerMissingDeltaConvergesViaAntiEntropy() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("anti-entropy"))
        val seamB = loom.join(InMemoryTag("b"))

        val config = QuilterConfig(
            antiEntropyInterval = 50.milliseconds,
            fullStateRetryLimit = 0, // isolate: no joiner full-state retries
            expectVirtualTime = true,
        )

        // Drop every delta broadcast from A; full-state (sendTo) still gets through.
        val chaosA = ChaosSeam(rawSeamA, ChaosConfig(dropProbability = 1.0), backgroundScope, seed = 1L)

        val repA = Quilter(
            replica = ReplicaId(rawSeamA.selfId.value),
            seam = chaosA,
            initial = GCounter.ZERO,
            messageSerializer = MSG_SER,
            scope = backgroundScope,
            config = config,
            random = Random(42),
        )
        val repB = Quilter(
            replica = ReplicaId(seamB.selfId.value),
            seam = seamB,
            initial = GCounter.ZERO,
            messageSerializer = MSG_SER,
            scope = backgroundScope,
            config = config,
        )

        testScheduler.advanceUntilIdle() // settle the join handshake (empty full-state)

        repA.apply(repA.state.value.inc(repA.replica, 7L))
        testScheduler.advanceUntilIdle() // the delta broadcast is dropped — B unchanged

        assertEquals(
            0L,
            repB.state.value.value,
            "sanity: B must NOT have received the dropped delta via broadcast",
        )

        // Drive a few anti-entropy rounds; A reconciles full state into B.
        testScheduler.advanceTimeBy(config.antiEntropyInterval.inWholeMilliseconds * 3 + 1)
        testScheduler.runCurrent()

        assertEquals(
            7L,
            repB.state.value.value,
            "B must converge to A's state via the anti-entropy backstop",
        )
    }
}
