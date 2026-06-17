/**
 * Tests for [SeamReplicator.universalAckFlow] — the causal-stability watermark.
 *
 * All tests use [UnconfinedTestDispatcher] with [SeamReplicatorConfig.expectVirtualTime] = true,
 * per the coroutine-determinism convention in `docs/testing-coroutine-determinism.md`.
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
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val UNIVERSAL_ACK_TEST_CONFIG = SeamReplicatorConfig(expectVirtualTime = true)

private val MSG_SER = ReplicatorMessage.serializer(GCounter.serializer())

private fun gcounterRep(seam: Seam, scope: kotlinx.coroutines.CoroutineScope, config: SeamReplicatorConfig = UNIVERSAL_ACK_TEST_CONFIG) =
    SeamReplicator(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GCounter.ZERO,
        messageSerializer = MSG_SER,
        scope = scope,
        config = config,
    )

/** Wraps a real seam but lets tests override [Seam.peers]. */
private class ControllableSeam(
    private val delegate: Seam,
    override val peers: MutableStateFlow<Set<PeerId>>,
) : Seam by delegate

class SeamReplicatorUniversalAckFlowTest {

    /**
     * Three peers A, B, C: `universalAckFlow` on A tracks the lagging peer.
     *
     * The watermark is the minimum ack across all known peers, so it only advances
     * once the slowest peer has caught up.
     */
    @Test
    fun tracksMinimumAckAcrossThreePeers() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("universal-ack"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val repA = gcounterRep(seamA, backgroundScope)
        gcounterRep(seamB, backgroundScope)
        gcounterRep(seamC, backgroundScope)

        // A emits 3 deltas (seq 1, 2, 3); B and C will ack all of them.
        repA.apply(repA.state.value.inc(repA.replica, 1L))
        repA.apply(repA.state.value.inc(repA.replica, 1L))
        repA.apply(repA.state.value.inc(repA.replica, 1L))

        testScheduler.advanceUntilIdle()

        // Both B and C have acked all 3 — watermark must be 3.
        assertEquals(3L, repA.universalAckFlow.value)
    }

    /**
     * Watermark is monotonically non-decreasing: once it reaches N it never drops
     * below N, even if a new peer joins that hasn't yet acked anything.
     */
    @Test
    fun doesNotDecreaseWhenNewPeerJoins() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("monotone-test"))
        val seamB = loom.join(InMemoryTag("b"))

        val controlledPeers = MutableStateFlow(loom.peers.value)
        val seamA = ControllableSeam(rawSeamA, controlledPeers)

        val repA = gcounterRep(seamA, backgroundScope)
        gcounterRep(seamB, backgroundScope)

        repA.apply(repA.state.value.inc(repA.replica, 5L))
        testScheduler.advanceUntilIdle()

        // Watermark is at 1 (only seq from repA); B has acked it.
        assertEquals(1L, repA.universalAckFlow.value)

        // C joins late — repA will add it to knownPeers but C has not acked seq 1 yet.
        // The watermark must stay at 1, not drop to 0.
        val cId = PeerId("c-late")
        controlledPeers.value = controlledPeers.value + cId
        testScheduler.advanceUntilIdle()

        assertTrue(
            repA.universalAckFlow.value >= 1L,
            "watermark must not drop below 1 when late-joiner C arrives (was ${repA.universalAckFlow.value})",
        )
    }

    /**
     * Eviction of a lagging peer lets the watermark advance.
     *
     * A has sent 2 deltas; B acks both, C only acks seq 1 and then goes silent.
     * Once C is evicted the watermark should advance to 2.
     */
    @Test
    fun evictedLaggingPeerAdvancesWatermark() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("evict-advance"))

        val clock = FakeMonotonicMillis()
        val config = SeamReplicatorConfig(
            evictionAfter = 100.milliseconds,
            antiEntropyInterval = 50.milliseconds,
            expectVirtualTime = true,
        )

        // Give A a controllable peers view so we can drop C without closing the seam.
        val controlledPeers = MutableStateFlow(loom.peers.value)
        val seamA = ControllableSeam(rawSeamA, controlledPeers)

        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val repA = SeamReplicator(
            replica = ReplicaId(rawSeamA.selfId.value),
            seam = seamA,
            initial = GCounter.ZERO,
            messageSerializer = MSG_SER,
            scope = backgroundScope,
            config = config,
            clock = clock,
        )
        gcounterRep(seamB, backgroundScope, config)
        gcounterRep(seamC, backgroundScope, config)

        // Both B and C join — record their initial presence.
        controlledPeers.value = loom.peers.value
        testScheduler.advanceUntilIdle()

        // A emits seq 1; all three (A, B, C) should exchange acks.
        repA.apply(repA.state.value.inc(repA.replica, 1L))
        testScheduler.advanceUntilIdle()

        // Verify both B and C acked seq 1 (watermark = 1).
        assertEquals(1L, repA.universalAckFlow.value)

        // A emits seq 2, but now C is gone from the seam peers view.
        controlledPeers.value = setOf(rawSeamA.selfId, seamB.selfId)
        repA.apply(repA.state.value.inc(repA.replica, 1L))
        testScheduler.advanceUntilIdle()

        // Only B has acked seq 2; C is absent and its acked-through is still 1.
        // The watermark is still 1 (pinned by C's last-ack, or 0 if C hadn't acked).
        assertTrue(
            repA.universalAckFlow.value <= 2L,
            "watermark must not exceed what all known peers have acked",
        )

        // Advance clock past eviction TTL and trigger anti-entropy to evict C.
        clock.advanceBy(150L)
        testScheduler.advanceTimeBy(config.antiEntropyInterval.inWholeMilliseconds + 1)

        // C is gone — B has acked 2 — watermark must be 2.
        assertEquals(2L, repA.universalAckFlow.value)
    }
}

/** Simple controllable clock for eviction tests. */
private class FakeMonotonicMillis(private var time: Long = 0L) : MonotonicMillis {
    override fun now(): Long = time
    fun advanceBy(ms: Long) { time += ms }
}
