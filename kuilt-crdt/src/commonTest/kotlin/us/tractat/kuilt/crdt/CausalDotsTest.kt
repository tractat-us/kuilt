package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The [Quilted.causalDots] capability (#268) — the per-CRDT causal-dot export the
 * causal-stability GC barrier folds into a delivered version vector. [Rga] returns its
 * `Insert`/`Remove` op dots and **excludes** `Compact`; every other delta-state CRDT in
 * the zoo inherits the empty default.
 */
class CausalDotsTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    @Test
    fun rgaExposesInsertAndRemoveDots() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(b, op1.id, "y")
        val (r3, _) = r2.removeAt(0)!!
        assertEquals(setOf(op1.id.dot, op2.id.dot), r3.causalDots())
    }

    @Test
    fun rgaExcludesCompactDots() {
        // I inserted, removed, compacted; the Compact op contributes no dot.
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (a1, _) = a0.removeAt(0)!!
        val stable = VersionVector.of(mapOf(a to opI.id.seq))
        val (compacted, _) = a1.compact(stableCut = stable, frontierMax = stable, delivered = stable)!!
        assertEquals(emptySet(), compacted.causalDots(), "compacted Insert/Remove dots are gone; Compact mints none")
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
