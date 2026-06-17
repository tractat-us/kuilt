package us.tractat.kuilt.quilter

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [WindowPolicy.byCount] — the prefix-selection logic in isolation.
 *
 * `byCount(n)` keeps the last `n` **visible** ids and targets the leading prefix: every id
 * (visible *or* tombstoned) that sorts before the start of the retained window. The coordinator
 * gates the returned candidates through the causal-stability barrier before any are dropped, so
 * this test only pins the *selection*, not the safety filter (covered by the integration suite).
 */
class WindowPolicyByCountTest {

    private fun id(seq: Long): RgaId = RgaId(lamport = seq, replicaId = ReplicaId("p"), seq = seq)

    @Test
    fun keepsLastNVisibleDropsLeadingVisiblePrefix() {
        val seq = listOf(id(1), id(2), id(3), id(4), id(5))
        val drop = WindowPolicy.byCount(2).idsToTruncate(seq, tombstones = emptySet())
        assertEquals(setOf(id(1), id(2), id(3)), drop, "keep last 2 visible — drop the first 3")
    }

    @Test
    fun tombstonesInPrefixAreSweptAlongWithVisible() {
        // id(2) and id(4) are tombstoned. Window keeps the last 2 VISIBLE (id(3), id(5)).
        val seq = listOf(id(1), id(2), id(3), id(4), id(5))
        val tombstones = setOf(id(2), id(4))
        val drop = WindowPolicy.byCount(2).idsToTruncate(seq, tombstones)
        // Walking back: id5 visible (1), id4 tombstone, id3 visible (2 — window full). Prefix: id1,id2.
        assertEquals(setOf(id(1), id(2)), drop, "prefix before the 2nd-from-last visible is dropped")
    }

    @Test
    fun windowLargerThanVisibleCountDropsNothing() {
        val seq = listOf(id(1), id(2))
        assertTrue(
            WindowPolicy.byCount(5).idsToTruncate(seq, emptySet()).isEmpty(),
            "n exceeding the visible count retains everything",
        )
    }

    @Test
    fun windowExactlyVisibleCountDropsNothing() {
        val seq = listOf(id(1), id(2), id(3))
        assertTrue(
            WindowPolicy.byCount(3).idsToTruncate(seq, emptySet()).isEmpty(),
            "n equal to the visible count retains everything",
        )
    }

    @Test
    fun zeroAndNegativeWindowTargetEntirePrefix() {
        val seq = listOf(id(1), id(2), id(3))
        val all = seq.toSet()
        assertEquals(all, WindowPolicy.byCount(0).idsToTruncate(seq, emptySet()), "n=0 drops everything")
        assertEquals(all, WindowPolicy.byCount(-7).idsToTruncate(seq, emptySet()), "n<0 drops everything")
    }

    @Test
    fun emptySequenceDropsNothing() {
        assertTrue(WindowPolicy.byCount(3).idsToTruncate(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun allTombstonedTargetsNothingDeferringToTombstoneGc() {
        // No visible ids at all; the window of n=2 can never be filled, so it spans the whole
        // sequence and targets nothing. The window forgets *visible* history; pure tombstone
        // garbage is left to the causal-stability GC path (which sweeps it independently).
        val seq = listOf(id(1), id(2), id(3))
        val tombstones = seq.toSet()
        assertTrue(WindowPolicy.byCount(2).idsToTruncate(seq, tombstones).isEmpty())
    }
}
