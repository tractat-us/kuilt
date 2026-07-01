package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Rga.entries] — the id-carrying form of [Rga.toList]: visible `(RgaId, value)`
 * pairs in sequence order, so a consumer can recover each element's producer
 * ([RgaId.replicaId]) and total-order key ([RgaId.compareTo] / [RgaId.dot]).
 */
class RgaEntriesTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    @Test
    fun entriesPairsEachValueWithItsInsertId() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(a, op1.id, "y")

        assertEquals(
            listOf(op1.id to "x", op2.id to "y"),
            r2.entries(),
            "entries carries the minting RgaId alongside each value, in order",
        )
    }

    @Test
    fun entriesValuesAgreeWithToList() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(b, op1.id, "y")
        val (r3, _) = r2.insertAfter(a, op2.id, "z")

        assertEquals(r3.toList(), r3.entries().map { it.second }, "toList == entries().map { it.second }")
    }

    @Test
    fun entriesExcludesTombstones() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(a, op1.id, "y")
        val (r3, _) = r2.removeAt(0)!!

        assertEquals(listOf(op2.id to "y"), r3.entries(), "a tombstoned element is not an entry")
    }

    @Test
    fun entriesIsEmptyForEmptyRga() {
        assertEquals(emptyList(), Rga.empty<String>().entries())
    }

    @Test
    fun entryIdsAreTotallyOrderedAcrossReplicas() {
        // Concurrent inserts after HEAD from two replicas: entries() reports them in
        // the RGA's deterministic order, and that order is exactly RgaId descending.
        val (ra, opA) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "a")
        val fromB = Rga.empty<String>().insertAfter(b, RgaId.HEAD, "b").second
        val merged = ra.apply(fromB)

        val ids = merged.entries().map { it.first }
        assertEquals(ids, ids.sortedDescending(), "entry order matches RgaId total order (larger id first after HEAD)")
        assertEquals(setOf(opA.id, fromB.id), ids.toSet())
    }
}
