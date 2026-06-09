/**
 * Unit tests for [RgaGcCoordinator].
 *
 * All tests use [UnconfinedTestDispatcher] so coroutine launches are eager.
 * [SeamReplicatorConfig.expectVirtualTime] suppresses the TestDispatcher guard.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)
@file:Suppress("DEPRECATION")

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Counts how many [Patch]es have been applied. */
private class PatchSink<V> {
    val patches = mutableListOf<Patch<Rga<V>>>()
    val stateFlow = MutableStateFlow(Rga.empty<V>())

    fun apply(patch: Patch<Rga<V>>) {
        patches += patch
        stateFlow.value = stateFlow.value.piece(patch.delta)
    }
}

class RgaGcCoordinatorTest {

    private val replicaId = ReplicaId("test-replica")

    // ---- watermark guard ----

    @Test
    fun doesNotFireAtWatermarkZero() = runTest(UnconfinedTestDispatcher()) {
        val sink = PatchSink<String>()
        val ackFlow = MutableStateFlow(0L)

        RgaGcCoordinator(
            replicaId = replicaId,
            state = sink.stateFlow,
            universalAck = ackFlow,
            localSeq = MutableStateFlow(0L),
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        testScheduler.advanceUntilIdle()
        assertEquals(0, sink.patches.size, "watermark=0 must not trigger compaction")
    }

    @Test
    fun doesNotFireWhenNoTombstones() = runTest(UnconfinedTestDispatcher()) {
        val alice = ReplicaId("alice")
        val (state0, _) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "hello")
        val stateFlow = MutableStateFlow(state0)
        val ackFlow = MutableStateFlow(0L)
        val sink = PatchSink<String>()

        RgaGcCoordinator(
            replicaId = replicaId,
            state = stateFlow,
            universalAck = ackFlow,
            localSeq = MutableStateFlow(0L),
            applyCompaction = sink::apply,
            scope = backgroundScope,
        )

        ackFlow.value = 10L
        testScheduler.advanceUntilIdle()

        assertEquals(0, sink.patches.size, "no tombstones → no compaction patch")
    }

    // ---- basic compaction ----

    @Test
    fun firesCompactionWhenWatermarkAdvancesCoversTombstone() = runTest(UnconfinedTestDispatcher()) {
        val alice = ReplicaId("alice")
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!! // tombstone "a"

        val stateFlow = MutableStateFlow(s1)
        val ackFlow = MutableStateFlow(0L)
        val sink = PatchSink<String>()
        sink.stateFlow.value = s1

        RgaGcCoordinator(
            replicaId = replicaId,
            state = stateFlow,
            universalAck = ackFlow,
            localSeq = MutableStateFlow(0L),
            applyCompaction = { patch ->
                sink.apply(patch)
                stateFlow.value = stateFlow.value.piece(patch.delta)
            },
            scope = backgroundScope,
        )

        ackFlow.value = opA.id.lamport
        testScheduler.advanceUntilIdle()

        assertEquals(1, sink.patches.size, "one compaction patch expected")
        val applied = s1.piece(sink.patches[0].delta)
        assertEquals(emptyList(), applied.toList())
    }

    @Test
    fun doesNotFireWhenTombstoneIsAboveWatermark() = runTest(UnconfinedTestDispatcher()) {
        val alice = ReplicaId("alice")
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!!

        val stateFlow = MutableStateFlow(s1)
        val ackFlow = MutableStateFlow(0L)
        val sink = PatchSink<String>()
        sink.stateFlow.value = s1

        RgaGcCoordinator(
            replicaId = replicaId,
            state = stateFlow,
            universalAck = ackFlow,
            localSeq = MutableStateFlow(0L),
            applyCompaction = { patch ->
                sink.apply(patch)
                stateFlow.value = stateFlow.value.piece(patch.delta)
            },
            scope = backgroundScope,
        )

        // watermark below opA.lamport — tombstone not covered
        ackFlow.value = opA.id.lamport - 1L
        testScheduler.advanceUntilIdle()

        assertEquals(0, sink.patches.size, "tombstone above watermark must not be compacted")
    }

    // ---- loop-until-stable: chain GC ----

    @Test
    fun loopsUntilStableForChainedTombstones() = runTest(UnconfinedTestDispatcher()) {
        // Insert "a", insert "b" after "a" (b.after = a.id), remove both.
        // At high watermark: first compact removes opB (no successor), second removes opA.
        // The coordinator must loop to stable — both should be GC'd in one watermark advance.
        val alice = ReplicaId("alice")
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, opB) = s0.insertAfter(alice, opA.id, "b")
        val (s2, _) = s1.removeAt(0)!! // remove "a"
        val (s3, _) = s2.removeAt(0)!! // remove "b"

        val stateFlow = MutableStateFlow(s3)
        val ackFlow = MutableStateFlow(0L)
        val sink = PatchSink<String>()

        RgaGcCoordinator(
            replicaId = replicaId,
            state = stateFlow,
            universalAck = ackFlow,
            localSeq = MutableStateFlow(0L),
            applyCompaction = { patch ->
                sink.apply(patch)
                stateFlow.value = stateFlow.value.piece(patch.delta)
            },
            scope = backgroundScope,
        )

        ackFlow.value = opB.id.lamport // watermark covers both
        testScheduler.advanceUntilIdle()

        // Both opA and opB should be GC'd after the loop-until-stable
        val finalState = stateFlow.value
        assertEquals(emptyList(), finalState.toList())
        assertTrue(sink.patches.size >= 2, "expected at least 2 compaction passes for chain GC")
        assertFalse(finalState.tombstones.contains(opA.id), "opA should be GC'd")
        assertFalse(finalState.tombstones.contains(opB.id), "opB should be GC'd")
    }

    // ---- compaction patch bridges correctly ----

    @Test
    fun compactionDeltaIsMinimalRgaContainingCompactOp() = runTest(UnconfinedTestDispatcher()) {
        // The delta passed to applyCompaction must be Rga.empty().apply(compactOp),
        // which merges correctly via piece with any existing state.
        val alice = ReplicaId("alice")
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!!

        val stateFlow = MutableStateFlow(s1)
        val ackFlow = MutableStateFlow(0L)
        var capturedDelta: Rga<String>? = null

        RgaGcCoordinator(
            replicaId = replicaId,
            state = stateFlow,
            universalAck = ackFlow,
            localSeq = MutableStateFlow(0L),
            applyCompaction = { patch ->
                capturedDelta = patch.delta
                stateFlow.value = stateFlow.value.piece(patch.delta)
            },
            scope = backgroundScope,
        )

        ackFlow.value = opA.id.lamport
        testScheduler.advanceUntilIdle()

        val delta = capturedDelta!!
        // A minimal Rga containing only a Compact op has empty toList() and tombstones
        assertEquals(emptyList<String>(), delta.toList())
        // Merging the delta into the original tombstoned state should remove the tombstone
        val merged = s1.piece(delta)
        assertEquals(emptyList<String>(), merged.toList())
        assertFalse(merged.tombstones.contains(opA.id), "opA tombstone should be cleared after piece")
    }

    // ---- WindowPolicy.never ----

    @Test
    fun neverPolicyReturnsEmptySet() {
        val policy = WindowPolicy.never()
        val ids = policy.idsToTruncate(emptyList(), emptySet())
        assertEquals(emptySet(), ids)
    }

    @Test
    fun neverPolicyDoesNotBlockGcCompaction() = runTest(UnconfinedTestDispatcher()) {
        val alice = ReplicaId("alice")
        val (s0, opA) = Rga.empty<String>().insertAfter(alice, RgaId.HEAD, "a")
        val (s1, _) = s0.removeAt(0)!!

        val stateFlow = MutableStateFlow(s1)
        val ackFlow = MutableStateFlow(0L)
        val sink = PatchSink<String>()

        RgaGcCoordinator(
            replicaId = replicaId,
            state = stateFlow,
            universalAck = ackFlow,
            localSeq = MutableStateFlow(0L),
            applyCompaction = { patch ->
                sink.apply(patch)
                stateFlow.value = stateFlow.value.piece(patch.delta)
            },
            windowPolicy = WindowPolicy.never(),
            scope = backgroundScope,
        )

        ackFlow.value = opA.id.lamport
        testScheduler.advanceUntilIdle()

        assertEquals(1, sink.patches.size, "never() policy must still allow GC compaction")
    }
}
