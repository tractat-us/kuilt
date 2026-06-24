/**
 * Unit tests for [FugueGcCoordinator] against the eviction-safe causal-stability barrier
 * (ADR-003 addendum v3, #262).
 *
 * Mirrors [RgaGcCoordinatorTest] exactly, substituting [Fugue] for [Rga].
 *
 * The coordinator consumes a [CutFrontier] (`stableCut` + `frontierMax`) and this replica's
 * contiguous `delivered` VV, exactly as [Quilter] publishes them. These tests drive
 * those two flows directly as [MutableStateFlow]s — no real replicator, no seq bridge.
 *
 * All tests use [UnconfinedTestDispatcher] so coroutine launches are eager.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Captures applied [Patch]es and folds them into a live state flow the coordinator reads. */
private class FuguePatchSink<V>(initial: Fugue<V>) {
    val patches = mutableListOf<Patch<Fugue<V>>>()
    val stateFlow = MutableStateFlow(initial)

    fun apply(patch: Patch<Fugue<V>>) {
        patches += patch
        stateFlow.value = stateFlow.value.piece(patch.delta)
    }
}

class FugueGcCoordinatorTest {

    private val alice = ReplicaId("alice")
    private val carol = ReplicaId("carol")

    private fun vv(vararg pairs: Pair<ReplicaId, Long>): VersionVector = VersionVector.of(mapOf(*pairs))

    // ---- empty cut guard ----

    @Test
    fun doesNotFireAtEmptyCut() = runTest(UnconfinedTestDispatcher()) {
        val sink = FuguePatchSink(Fugue.empty<String>())
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        FugueGcCoordinator(
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
        val (state0, op) = Fugue.empty<String>().insertAt(alice, 0, "hello")
        val sink = FuguePatchSink(state0)
        val seqA = op.id.seq
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        FugueGcCoordinator(
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
        val (s0, opA) = Fugue.empty<String>().insertAt(alice, 0, "a")
        val (s1, _) = s0.removeAt(0)!!  // tombstone "a"
        val seqA = opA.id.seq

        val sink = FuguePatchSink(s1)
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        FugueGcCoordinator(
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
        assertEquals(emptyList<String>(), sink.stateFlow.value.toList())
    }

    // ---- the key diagnostic: refuses when frontier-incomplete ----

    @Test
    fun refusesWhenFrontierIncomplete() = runTest(UnconfinedTestDispatcher()) {
        // A tombstoned "I" is stable, but Carol's frontier witnesses a dot (carol, 1) alice has NOT
        // delivered — frontierMax includes it, delivered does not dominate → GC refused (#275).
        val (s0, opI) = Fugue.empty<String>().insertAt(alice, 0, "I")
        val (s1, _) = s0.removeAt(0)!!
        val seqI = opI.id.seq

        val sink = FuguePatchSink(s1)
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        FugueGcCoordinator(
            state = sink.stateFlow,
            cutFrontier = cut,
            delivered = delivered,
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        delivered.value = vv(alice to seqI, carol to 0L) // alice has not delivered carol's dot
        cut.value = CutFrontier(
            stableCut = vv(alice to seqI),
            frontierMax = vv(alice to seqI, carol to 1L), // known-but-undelivered (carol, 1)
        )
        testScheduler.advanceUntilIdle()

        assertEquals(0, sink.patches.size, "frontier-incomplete → GC refused (known-but-undelivered dot)")
    }

    // ---- loop-until-stable: chain GC ----

    @Test
    fun loopsUntilStableForChainedTombstones() = runTest(UnconfinedTestDispatcher()) {
        // Insert "a", "b" after "a" (b is tree-child of a), remove both.
        // First pass GCs "b" (no surviving tree child of b), then second pass GCs "a".
        val (s0, opA) = Fugue.empty<String>().insertAt(alice, 0, "a")
        val (s1, opB) = s0.insertAt(alice, 1, "b")
        val (s2, _) = s1.removeAt(1)!!  // remove "b"
        val (s3, _) = s2.removeAt(0)!!  // remove "a"
        val seqB = opB.id.seq

        val sink = FuguePatchSink(s3)
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        FugueGcCoordinator(
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
        assertEquals(emptyList<String>(), finalState.toList())
        assertTrue(sink.patches.size >= 1, "expected at least 1 compaction pass")
    }

    // ---- compaction patch bridges correctly ----

    @Test
    fun compactionDeltaIsMinimalFugueContainingCompactOp() = runTest(UnconfinedTestDispatcher()) {
        val (s0, opA) = Fugue.empty<String>().insertAt(alice, 0, "a")
        val (s1, _) = s0.removeAt(0)!!
        val seqA = opA.id.seq

        val sink = FuguePatchSink(s1)
        val cut = MutableStateFlow(CutFrontier.EMPTY)
        val delivered = MutableStateFlow(VersionVector.EMPTY)

        FugueGcCoordinator(
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
        assertEquals(emptyList<String>(), delta.toList(), "minimal Fugue with only a Compact op is empty")
        val merged = s1.piece(delta)
        assertEquals(emptyList<String>(), merged.toList())
    }

    // Lifecycle (close) tests follow the ScopedCloseable contract inherently via backgroundScope cancellation.
}
