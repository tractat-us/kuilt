package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import us.tractat.kuilt.test.assertAll

/**
 * Complexity pin for #1211: local [Fugue.insertAt]/[Fugue.removeAt] must
 * *thread* one incremental [FugueSeqState] forward across the whole edit
 * chain — never rebuild the tree per insert. This is asserted deterministically
 * by object identity (no timing): the state handle observed on the final
 * instance of an N-edit chain is the **same object** the empty instance was
 * seeded with, which is only possible if zero rebuilds happened along the way.
 * (The pre-#1211 implementation rebuilt the tree — O(n log n) — on every
 * single insert.)
 */
class FugueIncrementalStateThreadingTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun appendRunThreadsOneStateWithZeroRebuilds() {
        val f0 = Fugue.empty<Int>()
        val seeded = assertNotNull(f0.seqStateForTest(), "empty() must seed an incremental state")
        var f = f0
        repeat(1_000) { i ->
            f = f.insertAt(a, i, i).first
        }
        assertAll(
            { assertSame(seeded, f.seqStateForTest(), "state must be moved, not rebuilt, across 1000 appends") },
            { assertNull(f0.seqStateForTest(), "origin instance must relinquish the stolen state") },
            { assertEquals((0 until 1_000).toList(), f.toList()) },
            { assertSame(seeded, f.seqStateForTest(), "reads must return the state to the slot") },
        )
    }

    @Test
    fun prependAndRemoveRunThreadsOneState() {
        val f0 = Fugue.empty<Int>()
        val seeded = assertNotNull(f0.seqStateForTest())
        var f = f0
        repeat(300) { i ->
            f = f.insertAt(a, 0, i).first
        }
        repeat(100) {
            f = checkNotNull(f.removeAt(f.size / 2)).first
        }
        assertAll(
            { assertSame(seeded, f.seqStateForTest(), "prepends and removes must thread the same state") },
            { assertEquals(200, f.size) },
            {
                assertEquals(
                    Fugue.fromOps(f.ops, f.lamport).toList(),
                    f.toList(),
                    "threaded state must match from-scratch rebuild",
                )
            },
        )
    }

    @Test
    fun remoteApplyDropsStateAndNextLocalEditRebuildsOnce() {
        val (fA, _) = Fugue.empty<Int>().insertAt(a, 0, 0)
        val (_, opB) = Fugue.empty<Int>().insertAt(b, 0, 100)

        val merged = fA.apply(opB)
        val stateAfterApply = merged.seqStateForTest()

        val (edited, _) = merged.insertAt(a, merged.size, 1)
        val rebuilt = assertNotNull(edited.seqStateForTest(), "local edit after remote apply must rebuild the state")

        val (edited2, _) = edited.insertAt(a, edited.size, 2)
        assertAll(
            { assertNull(stateAfterApply, "remote apply must not thread the incremental state") },
            { assertSame(rebuilt, edited2.seqStateForTest(), "incremental maintenance must resume after one rebuild") },
            {
                assertEquals(
                    Fugue.fromOps(edited2.ops, edited2.lamport).toList(),
                    edited2.toList(),
                )
            },
        )
    }

    /**
     * A large local append run — the exact workload #1211 flagged as
     * pathological (each insert paid a full O(n log n) tree rebuild, quadratic
     * overall). With the incremental state this completes in a bounded time as
     * a side effect; the hard, timing-free gate is the zero-rebuild identity
     * assertion plus resolved-order equality with a from-scratch rebuild.
     */
    @Test
    fun largeAppendRunResolvesIdenticallyToRebuild() {
        val f0 = Fugue.empty<Int>()
        val seeded = assertNotNull(f0.seqStateForTest())
        var f = f0
        val n = 4_000
        repeat(n) { i ->
            f = f.insertAt(a, i, i).first
        }
        assertAll(
            { assertSame(seeded, f.seqStateForTest(), "zero rebuilds across $n appends") },
            { assertEquals((0 until n).toList(), f.toList()) },
            {
                assertEquals(
                    Fugue.fromOps(f.ops, f.lamport).toList(),
                    f.toList(),
                    "threaded state must match from-scratch rebuild at scale",
                )
            },
        )
    }
}
