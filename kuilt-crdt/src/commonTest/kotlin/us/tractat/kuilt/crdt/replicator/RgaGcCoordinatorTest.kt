/**
 * Unit tests for [RgaGcCoordinator] against the eviction-safe causal-stability barrier
 * (ADR-003 addendum v3, #262).
 *
 * The coordinator consumes a [CutFrontier] (`stableCut` + `frontierMax`) and this replica's
 * contiguous `delivered` VV, exactly as [SeamReplicator] publishes them. These tests drive
 * those two flows directly as [MutableStateFlow]s (modelling the cut as version vectors, like
 * `RgaCompactEvictionSafeBarrierTest`) — no real replicator, no seq bridge.
 *
 * All tests use [UnconfinedTestDispatcher] so coroutine launches are eager.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Captures applied [Patch]es and folds them into a live state flow the coordinator reads. */
private class PatchSink<V>(initial: Rga<V>) {
    val patches = mutableListOf<Patch<Rga<V>>>()
    val stateFlow = MutableStateFlow(initial)

    fun apply(patch: Patch<Rga<V>>) {
        patches += patch
        stateFlow.value = stateFlow.value.piece(patch.delta)
    }
}

class RgaGcCoordinatorTest {

    private val alice = ReplicaId("alice")
    private val carol = ReplicaId("carol")

    private fun vv(vararg pairs: Pair<ReplicaId, Long>): VersionVector = VersionVector.of(mapOf(*pairs))

    // ---- empty cut guard ----

    @Test
    fun doesNotFireAtEmptyCut() = runTest(UnconfinedTestDispatcher()) {
        val sink = PatchSink(Rga.empty<String>())
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        RgaGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        testScheduler.advanceUntilIdle()
        assertEquals(0, sink.patches.size, "empty cut, no tombstones → no compaction")
    }

    @Test
    fun doesNotFireWhenNoTombstones() = runTest(UnconfinedTestDispatcher()) {
        val (state0, op) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "hello")
        val sink = PatchSink(state0)
        val seqA = op.id.seq
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        RgaGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        delivered.value = vv(alice to seqA)
        cut.value = CutFrontier(stableCut = vv(alice to seqA), frontierMax = vv(alice to seqA))
        testScheduler.advanceUntilIdle()

        assertEquals(0, sink.patches.size, "no tombstones → no compaction patch")
    }

    // ---- basic compaction: fires when frontier-complete AND stableCut covers the tombstone ----

    @Test
    fun firesCompactionWhenFrontierCompleteAndStableCovers() = runTest(UnconfinedTestDispatcher()) {
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!! // tombstone "a"
        val seqA = opA.id.seq

        val sink = PatchSink(s1)
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        RgaGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        // delivered dominates frontierMax (frontier-complete) AND stableCut covers (alice, seqA).
        delivered.value = vv(alice to seqA)
        cut.value = CutFrontier(stableCut = vv(alice to seqA), frontierMax = vv(alice to seqA))
        testScheduler.advanceUntilIdle()

        assertEquals(1, sink.patches.size, "one compaction patch expected")
        assertEquals(emptyList(), sink.stateFlow.value.toList())
        assertTrue(sink.stateFlow.value.tombstones.isEmpty(), "tombstone GC'd")
    }

    // ---- the key diagnostic: refuses when frontier-incomplete ----

    @Test
    fun refusesWhenFrontierIncomplete() = runTest(UnconfinedTestDispatcher()) {
        // A tombstoned "I" is stable, but Carol's frontier witnesses a dot (carol, 1) A has NOT
        // delivered — frontierMax includes it, delivered does not dominate → GC refused (#275).
        val (s0, opI) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "I")
        val (s1, _) = s0.removeAt(0)!!
        val seqI = opI.id.seq

        val sink = PatchSink(s1)
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        RgaGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        delivered.value = vv(alice to seqI, carol to 0L) // A has not delivered carol's dot
        cut.value = CutFrontier(
            stableCut = vv(alice to seqI),
            frontierMax = vv(alice to seqI, carol to 1L), // known-but-undelivered (carol, 1)
        )
        testScheduler.advanceUntilIdle()

        assertEquals(0, sink.patches.size, "frontier-incomplete → GC refused (known-but-undelivered dot)")
        assertTrue(sink.stateFlow.value.tombstones.contains(opI.id), "I survives")
    }

    // ---- loop-until-stable: chain GC ----

    @Test
    fun loopsUntilStableForChainedTombstones() = runTest(UnconfinedTestDispatcher()) {
        // Insert "a", "b" after "a", remove both. First pass GCs "b" (no successor),
        // second pass GCs "a" (predecessor freed). One cut emission → both gone.
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, opB) = s0.insertAfter(alice, opA.id, "b")
        val (s2, _) = s1.removeAt(0)!! // remove "a"
        val (s3, _) = s2.removeAt(0)!! // remove "b"
        val seqB = opB.id.seq

        val sink = PatchSink(s3)
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        RgaGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        delivered.value = vv(alice to seqB)
        cut.value = CutFrontier(stableCut = vv(alice to seqB), frontierMax = vv(alice to seqB))
        testScheduler.advanceUntilIdle()

        val finalState = sink.stateFlow.value
        assertEquals(emptyList(), finalState.toList())
        assertTrue(sink.patches.size >= 2, "expected at least 2 compaction passes for chain GC")
        assertFalse(finalState.tombstones.contains(opA.id), "opA should be GC'd")
        assertFalse(finalState.tombstones.contains(opB.id), "opB should be GC'd")
    }

    // ---- compaction patch bridges correctly ----

    @Test
    fun compactionDeltaIsMinimalRgaContainingCompactOp() = runTest(UnconfinedTestDispatcher()) {
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!!
        val seqA = opA.id.seq

        val sink = PatchSink(s1)
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        RgaGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        delivered.value = vv(alice to seqA)
        cut.value = CutFrontier(stableCut = vv(alice to seqA), frontierMax = vv(alice to seqA))
        testScheduler.advanceUntilIdle()

        val delta = sink.patches.single().delta
        assertEquals(emptyList<String>(), delta.toList(), "minimal Rga with only a Compact op is empty")
        val merged = s1.piece(delta)
        assertEquals(emptyList<String>(), merged.toList())
        assertFalse(merged.tombstones.contains(opA.id), "opA tombstone cleared after piece")
    }

    // ---- WindowPolicy ----

    @Test
    fun neverPolicyReturnsEmptySet() {
        val ids = WindowPolicy.never().idsToTruncate(emptyList(), emptySet())
        assertEquals(emptySet(), ids)
    }

    @Test
    fun windowPolicyTruncatesAdditionalTombstones() = runTest(UnconfinedTestDispatcher()) {
        // A tombstone that is NOT yet causally stable (stableCut does not cover it) is still
        // dropped when the WindowPolicy names it — proving the window path is honoured.
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!!
        val seqA = opA.id.seq

        val sink = PatchSink(s1)
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)
        val windowPolicy = WindowPolicy { _, tombstones -> tombstones }

        RgaGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            windowPolicy = windowPolicy,
            scope = backgroundScope,
        )

        // frontier-complete (delivered dominates frontierMax) but stableCut does NOT cover opA.
        delivered.value = vv(alice to seqA)
        cut.value = CutFrontier(stableCut = VersionVector.EMPTY, frontierMax = VersionVector.EMPTY)
        testScheduler.advanceUntilIdle()

        assertEquals(1, sink.patches.size, "WindowPolicy must truncate even when not causally stable")
        assertFalse(sink.stateFlow.value.tombstones.contains(opA.id), "opA dropped by window")
    }

    @Test
    fun neverPolicyDoesNotBlockGcCompaction() = runTest(UnconfinedTestDispatcher()) {
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!!
        val seqA = opA.id.seq

        val sink = PatchSink(s1)
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        RgaGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            windowPolicy = WindowPolicy.never(),
            scope = backgroundScope,
        )

        delivered.value = vv(alice to seqA)
        cut.value = CutFrontier(stableCut = vv(alice to seqA), frontierMax = vv(alice to seqA))
        testScheduler.advanceUntilIdle()

        assertEquals(1, sink.patches.size, "never() policy must still allow causal-stability GC")
    }

    // Lifecycle (close) tests live in RgaGcCoordinatorLifecycleTest via CloseableLifecycleConformanceSuite.
}
