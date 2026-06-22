/**
 * Tests for delta-GC against an injected **delta-target set** (Phase 1 of the
 * partial-mesh gossip work, #654).
 *
 * The default delta-target set is full membership, so existing behaviour is
 * unchanged (covered by [QuilterUniversalAckFlowTest]). A *sparse* delta-target
 * set lets GC proceed against the neighbours a peer actually pushes deltas to,
 * rather than waiting on acks from the whole room — the O(N²)→O(k) unlock.
 *
 * Uses [UnconfinedTestDispatcher] with [QuilterConfig.expectVirtualTime] per the
 * coroutine-determinism convention in `docs/testing-coroutine-determinism.md`.
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val DELTA_TARGET_CONFIG = QuilterConfig(expectVirtualTime = true)

private val MSG_SER = QuiltMessage.serializer(GCounter.serializer())

/** Wraps a real seam but lets tests override [us.tractat.kuilt.core.Seam.peers]. */
private class ControllablePeersSeam(
    private val delegate: us.tractat.kuilt.core.Seam,
    override val peers: MutableStateFlow<Set<PeerId>>,
) : us.tractat.kuilt.core.Seam by delegate

class QuilterDeltaTargetGcTest {

    /**
     * GC proceeds against a sparse delta-target set: a peer that is in full
     * membership but **outside** the delta-target set cannot pin the watermark.
     *
     * A pushes to B only. A phantom peer C is present in A's `peers` view but
     * never acks. Under full-membership GC, C's absent ack would pin the
     * watermark at 0 and `pendingDeltas` would never drain. With the
     * delta-target set restricted to {B}, B's ack alone advances the watermark
     * and the pending delta is GC'd.
     */
    @Test
    fun gcProceedsAgainstSparseDeltaTargetSet() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("sparse-gc"))
        val seamB = loom.join(InMemoryTag("b"))

        // A sees B plus a phantom C that never acks (no real Quilter behind it).
        val phantomC = PeerId("c-phantom")
        val controlledPeers = MutableStateFlow(loom.peers.value + phantomC)
        val seamA = ControllablePeersSeam(rawSeamA, controlledPeers)

        val repA = Quilter(
            replica = ReplicaId(rawSeamA.selfId.value),
            seam = seamA,
            initial = GCounter.ZERO,
            messageSerializer = MSG_SER,
            scope = backgroundScope,
            config = DELTA_TARGET_CONFIG,
            deltaTargets = { peers -> peers.filterTo(mutableSetOf()) { it == seamB.selfId } },
        )
        Quilter(
            replica = ReplicaId(seamB.selfId.value),
            seam = seamB,
            initial = GCounter.ZERO,
            messageSerializer = MSG_SER,
            scope = backgroundScope,
            config = DELTA_TARGET_CONFIG,
        )

        repA.apply(repA.state.value.inc(repA.replica, 1L))
        testScheduler.advanceUntilIdle()

        // B (the only delta target) acked seq 1; phantom C is excluded from the
        // GC watermark, so the pending delta is pruned despite C's missing ack.
        assertEquals(1L, repA.universalAckFlow.value)
        assertTrue(
            repA.pendingDeltasForTest.isEmpty(),
            "pending delta must be GC'd against the delta-target set {B} without phantom C's ack " +
                "(pending=${repA.pendingDeltasForTest.keys})",
        )
    }

    /**
     * The pending-delta buffer stays bounded under sustained updates even though a
     * full-membership peer (phantom C) never acks. Under full-membership GC the buffer
     * would grow without bound (C pins the watermark at 0); with the delta-target set
     * restricted to {B}, B's acks drain the buffer continuously.
     *
     * Anti-entropy rounds fire throughout (interval crossed repeatedly) — confirming the
     * backstop's full-state sends don't interfere with GC of the outbound buffer.
     */
    @Test
    fun pendingDeltasStayBoundedUnderSustainedUpdates() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("bounded-buffer"))
        val seamB = loom.join(InMemoryTag("b"))

        val phantomC = PeerId("c-phantom")
        val controlledPeers = MutableStateFlow(loom.peers.value + phantomC)
        val seamA = ControllablePeersSeam(rawSeamA, controlledPeers)

        val config = QuilterConfig(antiEntropyInterval = 50.milliseconds, expectVirtualTime = true)

        val repA = Quilter(
            replica = ReplicaId(rawSeamA.selfId.value),
            seam = seamA,
            initial = GCounter.ZERO,
            messageSerializer = MSG_SER,
            scope = backgroundScope,
            config = config,
            deltaTargets = { peers -> peers.filterTo(mutableSetOf()) { it == seamB.selfId } },
            random = Random(7),
        )
        Quilter(
            replica = ReplicaId(seamB.selfId.value),
            seam = seamB,
            initial = GCounter.ZERO,
            messageSerializer = MSG_SER,
            scope = backgroundScope,
            config = config,
        )

        val updates = 50
        repeat(updates) {
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            // 10ms steps cross the 50ms anti-entropy interval repeatedly, so reconcile
            // rounds run concurrently with the sustained update stream.
            testScheduler.advanceTimeBy(10)
            testScheduler.runCurrent()
        }
        testScheduler.advanceTimeBy(config.antiEntropyInterval.inWholeMilliseconds + 1)
        testScheduler.runCurrent()

        assertEquals(updates.toLong(), repA.universalAckFlow.value)
        assertTrue(
            repA.pendingDeltasForTest.size <= 1,
            "pendingDeltas must stay bounded under $updates sustained updates " +
                "(was ${repA.pendingDeltasForTest.size})",
        )
    }
}
