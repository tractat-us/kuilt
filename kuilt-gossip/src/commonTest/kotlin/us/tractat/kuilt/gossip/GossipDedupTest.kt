package us.tractat.kuilt.gossip

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [GossipDedup] (#675): the bounded relay dedup structure that replaces the
 * unbounded flat seen-set. A frame `(origin, seq)` is *new* (deliver + relay) the first
 * time it is seen and a *duplicate* thereafter, exactly as the flat set was — but memory
 * is bounded to **O(origins)** via a per-origin contiguous high-water mark plus a small
 * bounded reorder window, rather than O(total broadcasts).
 */
class GossipDedupTest {
    private val origin = PeerId("origin-x")

    @Test
    fun deliversEachSeqOnceInOrder() {
        val dedup = GossipDedup()
        assertAll(
            { assertTrue((1..5L).all { dedup.markSeenIfNew(origin, it) }, "each fresh seq is new") },
            { assertTrue((1..5L).none { dedup.markSeenIfNew(origin, it) }, "every re-seen seq is a duplicate") },
        )
    }

    @Test
    fun dropsDuplicate() {
        val dedup = GossipDedup()
        assertAll(
            { assertTrue(dedup.markSeenIfNew(origin, 5), "first sighting is new") },
            { assertFalse(dedup.markSeenIfNew(origin, 5), "second sighting is a duplicate") },
        )
    }

    @Test
    fun absorbsReorderingWithinWindow() {
        val dedup = GossipDedup()
        // Frames arrive out of order: 1, then 3 (gap), then 2 (fills the gap).
        assertAll(
            { assertTrue(dedup.markSeenIfNew(origin, 1), "1 is new") },
            { assertTrue(dedup.markSeenIfNew(origin, 3), "3 is new even though it skips 2") },
            { assertTrue(dedup.markSeenIfNew(origin, 2), "the late 2 is still new") },
            { assertFalse(dedup.markSeenIfNew(origin, 2), "but a second 2 is a duplicate") },
            { assertFalse(dedup.markSeenIfNew(origin, 3), "and the reordered 3 is now a duplicate") },
            // Once the gap is filled the reorder set drains — only the high-water remains.
            { assertEquals(1, dedup.trackedEntryCount, "tracked memory collapses to one per origin once contiguous") },
        )
    }

    @Test
    fun boundedMemoryUnderManyMessages() {
        val dedup = GossipDedup()
        val origins = (1..3).map { PeerId("origin-$it") }
        // 1000 in-order broadcasts per origin — the flat set would hold 3000 entries.
        for (o in origins) for (seq in 1..1000L) dedup.markSeenIfNew(o, seq)
        assertEquals(
            origins.size,
            dedup.trackedEntryCount,
            "in-order delivery keeps memory at O(origins), not O(messages)",
        )
    }

    @Test
    fun boundedMemoryUnderPersistentGap() {
        // A persistent gap (seq 1 never arrives, only even seqs do) would grow the reorder
        // set without bound. The window cap keeps it O(maxReorder).
        val dedup = GossipDedup(maxReorder = 8)
        for (seq in 2..200L step 2) dedup.markSeenIfNew(origin, seq)
        assertTrue(
            dedup.trackedEntryCount <= 1 + 8,
            "reorder window stays bounded under a persistent gap (was ${dedup.trackedEntryCount})",
        )
    }

    @Test
    fun forcedAdvancePastGapTreatsStragglerAsSeen() {
        // When the window overflows, the frontier is forced forward past the gap; a much
        // later straggler below the new frontier is treated as already-seen (dropped, not
        // re-delivered) — anti-entropy backstops anything dropped this way.
        val dedup = GossipDedup(maxReorder = 4)
        for (seq in 10..100L step 2) dedup.markSeenIfNew(origin, seq)
        assertFalse(
            dedup.markSeenIfNew(origin, 10),
            "a straggler below the forced-forward frontier is treated as already seen",
        )
    }
}
