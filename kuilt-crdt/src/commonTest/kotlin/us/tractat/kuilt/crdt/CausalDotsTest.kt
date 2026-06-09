package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The [Quilted.causalDots] capability (#268) — the per-CRDT causal-dot export the
 * causal-stability GC barrier folds into a delivered version vector. [Rga] returns its
 * **`Insert`** op dots and excludes `Remove`/`Compact` (#269); every other delta-state
 * CRDT in the zoo inherits the empty default.
 */
class CausalDotsTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    @Test
    fun rgaExposesInsertDots() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(b, op1.id, "y")
        // removeAt tombstones op1's element; its Insert dot is still exported (the op stays
        // in the log), and the Remove — which reuses op1's id — adds nothing of its own.
        val (r3, _) = r2.removeAt(0)!!
        assertEquals(setOf(op1.id.dot, op2.id.dot), r3.causalDots())
    }

    @Test
    fun rgaExcludesOrphanRemoveDot() {
        // A Remove delivered out-of-order — before its target Insert — must NOT cause the
        // holder to claim it delivered that dot (it holds only the tombstone). #269: counting
        // Remove dots would over-claim and prematurely advance the stable cut (the #275 hazard).
        val (r1, insOp) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (_, remOp) = r1.removeAt(0)!! // remOp = Remove(insOp.id)
        val orphanRemove = Rga.empty<String>().apply(remOp) // has the Remove, NOT the Insert
        assertEquals(
            emptySet(),
            orphanRemove.causalDots(),
            "an orphan Remove (Insert absent) must not claim the target dot as delivered",
        )
    }

    @Test
    fun rgaIncludesCompactDotsForDeliveryTracking() {
        // I inserted, removed, compacted. The Compact RE-EMITS the GC'd dot: it was
        // delivered (GC fires only at causal stability), so dropping it would punch a
        // permanent hole in the contiguous delivered frontier (#262 / the regression bug).
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (a1, _) = a0.removeAt(0)!!
        val stable = VersionVector.of(mapOf(a to opI.id.seq))
        val (compacted, _) = a1.compact(stableCut = stable, frontierMax = stable, delivered = stable)!!
        assertEquals(
            setOf(opI.id.dot),
            compacted.causalDots(),
            "a Compact'd (delivered-then-GC'd) dot stays in causalDots so the frontier never regresses",
        )
    }

    @Test
    fun rgaCausalDotsReflectGaps() {
        // Deliver only seqs 1 and 3 from author a (skip 2): both surviving dots appear.
        val (_, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val op3 = RgaOp.Insert(RgaId(lamport = 9L, replicaId = a, seq = 3L), value = "z", after = op1.id)
        val rga = Rga.empty<String>().apply(op1).apply(op3)
        assertEquals(setOf(Dot(a, 1L), Dot(a, 3L)), rga.causalDots())
    }

    @Test
    fun nonRgaCrdtHasEmptyCausalDots() {
        val counter = GCounter.of(a to 3L, b to 5L)
        assertEquals(emptySet(), counter.causalDots(), "delta-state zoo inherits the empty default")
    }
}
