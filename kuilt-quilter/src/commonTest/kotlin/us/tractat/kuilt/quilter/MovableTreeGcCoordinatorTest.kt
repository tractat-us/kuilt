/**
 * Unit tests for [MovableTreeGcCoordinator] — the causal-stability GC driver for
 * [MovableTree] under [Quilter] replication (#725).
 *
 * Drives [CutFrontier] and [Quilter.deliveredLocal] flows directly as [MutableStateFlow]s;
 * no real [Quilter] or network involved. Uses [UnconfinedTestDispatcher] so launches are eager.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.MovableTree
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Captures applied [Patch]es and folds them into a live state flow the coordinator reads. */
private class TreePatchSink<V>(initial: MovableTree<V>) {
    val patches = mutableListOf<Patch<MovableTree<V>>>()
    val stateFlow = MutableStateFlow(initial)

    fun apply(patch: Patch<MovableTree<V>>) {
        patches += patch
        stateFlow.value = stateFlow.value.piece(patch.delta)
    }
}

class MovableTreeGcCoordinatorTest {

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")

    private fun vv(vararg pairs: Pair<ReplicaId, Long>): VersionVector = VersionVector.of(mapOf(*pairs))

    // ── guard conditions ──────────────────────────────────────────────────────

    @Test
    fun doesNotFireAtEmptyCut() = runTest(UnconfinedTestDispatcher()) {
        val sink = TreePatchSink(MovableTree.empty<String>())
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        MovableTreeGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        testScheduler.advanceUntilIdle()
        assertEquals(0, sink.patches.size, "empty cut → no compaction")
    }

    @Test
    fun doesNotCompactWhenFrontierNotComplete() = runTest(UnconfinedTestDispatcher()) {
        // Build a tree with a superseded move, but advertise an incomplete frontier.
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = MovableTree.ROOT_ID)

        val sink = TreePatchSink(t4)
        // alice has delivered 4 ops, but frontier claims bob has op 1 that alice hasn't seen.
        val cut = MutableStateFlow(CutFrontier(stableCut = vv(alice to 4L), frontierMax = vv(alice to 4L, bob to 1L)))
        val delivered = MutableStateFlow(vv(alice to 4L)) // does NOT dominate frontierMax (missing bob to 1)

        MovableTreeGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        testScheduler.advanceUntilIdle()
        assertEquals(0, sink.patches.size, "frontier not complete → compact() blocked")
    }

    // ── GC fires on stable superseded ops ────────────────────────────────────

    @Test
    fun compactsSupersededMoveOnceStableAndFrontierComplete() = runTest(UnconfinedTestDispatcher()) {
        // addA(seq=1), addB(seq=2), move(A→B, seq=3), move(A→ROOT, seq=4).
        // After seq=4 is stable: move(A→B, seq=3) is superseded and should be GC'd.
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = MovableTree.ROOT_ID)
        val logSizeBefore = t4.moveLogSize

        val sink = TreePatchSink(t4)
        val stableVv = vv(alice to 4L)
        val cut = MutableStateFlow(CutFrontier(stableCut = stableVv, frontierMax = stableVv))
        val delivered = MutableStateFlow(stableVv)

        MovableTreeGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        testScheduler.advanceUntilIdle()

        assertTrue(sink.patches.isNotEmpty(), "at least one compact patch must have been applied")
        assertTrue(sink.stateFlow.value.moveLogSize < logSizeBefore, "log must shrink after GC")
        // Tree shape must be correct after GC.
        assertEquals(MovableTree.ROOT_ID, sink.stateFlow.value.parentOf(idA))
        assertEquals(MovableTree.ROOT_ID, sink.stateFlow.value.parentOf(idB))
    }

    @Test
    fun gcTriggersWhenCutAdvances() = runTest(UnconfinedTestDispatcher()) {
        // Build the superseded-move tree; start with cut too low to trigger GC.
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = MovableTree.ROOT_ID)
        val logSizeBefore = t4.moveLogSize

        val sink = TreePatchSink(t4)
        // Initial cut only covers seq=2 — seq=3 and seq=4 not yet stable.
        val cut = MutableStateFlow(CutFrontier(stableCut = vv(alice to 2L), frontierMax = vv(alice to 4L)))
        val delivered = MutableStateFlow(vv(alice to 4L))

        MovableTreeGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        testScheduler.advanceUntilIdle()
        assertEquals(0, sink.patches.size, "cut too low — no GC yet")

        // Advance cut to include all ops.
        cut.value = CutFrontier(stableCut = vv(alice to 4L), frontierMax = vv(alice to 4L))
        testScheduler.advanceUntilIdle()

        assertTrue(sink.patches.isNotEmpty(), "GC must fire when cut advances")
        assertTrue(sink.stateFlow.value.moveLogSize < logSizeBefore, "log must shrink")
    }

    @Test
    fun gcFiresWhenNewOpArrivesAndCutAlreadyCovers() = runTest(UnconfinedTestDispatcher()) {
        // A new op arrives via piece() after the cut already covers it — the state-change
        // trigger must re-evaluate and compact the superseded predecessor.
        val tree = MovableTree.empty<String>()
        val (t1, idA) = tree.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
        val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
        val (t3, _) = t2.move(alice, ts = 3L, node = idA, newParent = idB)

        val sink = TreePatchSink(t3) // only 3 ops — no superseded move yet
        val cut = MutableStateFlow(CutFrontier(stableCut = vv(alice to 4L), frontierMax = vv(alice to 4L)))
        val delivered = MutableStateFlow(vv(alice to 4L))

        MovableTreeGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        testScheduler.advanceUntilIdle()
        // No superseded op in t3 — nothing to compact yet.
        assertEquals(0, sink.patches.size)

        // Now a new move arrives (ts=4) that supersedes ts=3 — the state-trigger fires GC.
        val (t4, _) = t3.move(alice, ts = 4L, node = idA, newParent = MovableTree.ROOT_ID)
        sink.stateFlow.value = t4

        testScheduler.advanceUntilIdle()
        assertTrue(sink.patches.isNotEmpty(), "state-change trigger must fire GC on newly superseded op")
    }

    @Test
    fun closeCancelsGcJob() = runTest(UnconfinedTestDispatcher()) {
        val sink = TreePatchSink(MovableTree.empty<String>())
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        val coordinator = MovableTreeGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        coordinator.close()
        testScheduler.advanceUntilIdle()
        assertTrue(coordinator.gcJobForTest.isCancelled, "GC job must be cancelled on close")
    }
}
