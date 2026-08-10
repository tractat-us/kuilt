package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [Rga.valueAt] — the piecewise reader, so a caller that wants the first few elements does not
 * pay for all of them.
 *
 * [Rga.toList] and [Rga.entries] each build two eager Theta(N) lists. A consumer reading only the
 * head — `WarpLogRecordExporter`'s eviction path, which took the leading ~128 of 10,000 records
 * per turn — discarded nearly all of that (#2219). These pin the contract that lets it walk
 * [Rga.sequence] lazily instead: same values as [Rga.entries] on the visible elements, a
 * tombstoned id still resolving, and a hard failure on an id that is genuinely gone.
 */
class RgaValueAtTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    @Test
    fun valueAtAgreesWithEntriesOnEveryVisibleElement() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(b, op1.id, "y")
        val (r3, _) = r2.insertAfter(a, op2.id, "z")

        assertEquals(
            r3.entries(),
            r3.sequence.filter { it !in r3.tombstones }.map { it to r3.valueAt(it) },
            "the lazy head-walk must reconstruct entries() exactly",
        )
    }

    /**
     * The property the eviction path rests on: it reads the values of the very records it is
     * about to tombstone, and on a *later* turn it walks over the tombstones an earlier one left.
     */
    @Test
    fun aTombstonedIdStillResolves() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(a, op1.id, "y")
        val (r3, _) = r2.removeAt(0)!!

        assertEquals(
            listOf("x", "y"),
            r3.sequence.map { r3.valueAt(it) },
            "a tombstone hides an element from entries(); it does not release its value",
        )
        assertEquals(listOf(op2.id to "y"), r3.entries(), "…while entries() still excludes it")
    }

    @Test
    fun anIdThatWasNeverInsertedThrows() {
        val (r1, _) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val absent = RgaId(lamport = 99L, replicaId = b, seq = 99L)

        assertFailsWith<NoSuchElementException> { r1.valueAt(absent) }
    }

    /**
     * Compaction removes the `Insert` op outright, so the value is genuinely gone — the one case
     * where [Rga.valueAt] fails on an id the log once held.
     */
    @Test
    fun aCompactedIdThrows() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(a, op1.id, "y")
        val (r3, _) = r2.removeAt(0)!!
        val (windowed, _) = checkNotNull(r3.dropWindow(self = a, dropped = setOf(op1.id)))

        assertNull(windowed.sequence.firstOrNull { it == op1.id }, "the compacted id leaves the sequence")
        assertEquals("y", windowed.valueAt(op2.id), "…and its survivor is untouched")
        assertFailsWith<NoSuchElementException> { windowed.valueAt(op1.id) }
    }
}
