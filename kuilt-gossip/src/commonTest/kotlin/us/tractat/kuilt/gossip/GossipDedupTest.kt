package us.tractat.kuilt.gossip

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [GossipDedup] (#675, #1272): the bounded relay dedup + per-origin reorder
 * buffer. A frame `(origin, seq)` is *new* (relay it) the first time it is seen and a
 * *duplicate* thereafter; delivery is released **contiguously in per-origin send order**,
 * holding reordered frames until the gap below them fills — or the gap is abandoned by
 * the bounded window (space) or the reorder grace (time). Memory stays **O(origins)**
 * steady-state via the per-origin contiguous high-water mark.
 */
class GossipDedupTest {
    private val origin = PeerId("origin-x")

    private fun frame(
        seq: Long,
        from: PeerId = origin,
    ): GossipFrame = GossipFrame.origin(from, seq, ttl = 5, payload = byteArrayOf(seq.toByte()))

    /** Admits [seq] at [at] ms, returning the released seqs (asserting nothing about newness). */
    private fun GossipDedup.admitSeq(
        seq: Long,
        at: Long = 0,
    ): List<Long> = admit(frame(seq), nowMs = at).deliverable.map { it.seq }

    @Test
    fun deliversEachSeqOnceInOrder() {
        val dedup = GossipDedup()
        assertAll(
            { assertTrue((1..5L).all { dedup.admit(frame(it), nowMs = 0).isNew }, "each fresh seq is new") },
            { assertTrue((1..5L).none { dedup.admit(frame(it), nowMs = 0).isNew }, "every re-seen seq is a duplicate") },
        )
    }

    @Test
    fun inOrderFramesReleaseImmediately() {
        val dedup = GossipDedup()
        assertAll(
            { assertEquals(listOf(1L), dedup.admitSeq(1), "an in-order frame is released at once") },
            { assertEquals(listOf(2L), dedup.admitSeq(2), "and so is the next") },
        )
    }

    @Test
    fun dropsDuplicate() {
        val dedup = GossipDedup()
        assertAll(
            { assertTrue(dedup.admit(frame(5), nowMs = 0).isNew, "first sighting is new") },
            { assertFalse(dedup.admit(frame(5), nowMs = 0).isNew, "second sighting is a duplicate") },
            {
                assertTrue(
                    dedup.admit(frame(5), nowMs = 0).deliverable.isEmpty(),
                    "a duplicate never releases anything",
                )
            },
        )
    }

    @Test
    fun holdsReorderedFrameUntilTheGapFills() {
        val dedup = GossipDedup()
        // Frames arrive out of order: 1, then 3 (gap), then 2 (fills the gap).
        assertAll(
            { assertEquals(listOf(1L), dedup.admitSeq(1), "1 releases at once") },
            { assertEquals(emptyList(), dedup.admitSeq(3), "3 is held — 2 is outstanding") },
            { assertEquals(listOf(2L, 3L), dedup.admitSeq(2), "the late 2 releases itself and the held 3, in order") },
            { assertFalse(dedup.admit(frame(2), nowMs = 0).isNew, "a second 2 is a duplicate") },
            { assertFalse(dedup.admit(frame(3), nowMs = 0).isNew, "and the reordered 3 is now a duplicate") },
            // Once the gap is filled the window drains — only the high-water remains.
            { assertEquals(1, dedup.trackedEntryCount, "tracked memory collapses to one per origin once contiguous") },
        )
    }

    @Test
    fun ordersAreIndependentPerOrigin() {
        val dedup = GossipDedup()
        val other = PeerId("origin-y")
        assertAll(
            { assertEquals(emptyList(), dedup.admitSeq(2), "origin-x's 2 is held for its gap") },
            {
                assertEquals(
                    listOf(1L),
                    dedup.admit(frame(1, from = other), nowMs = 0).deliverable.map { it.seq },
                    "origin-y's in-order 1 is not held hostage by origin-x's gap",
                )
            },
        )
    }

    @Test
    fun boundedMemoryUnderManyMessages() {
        val dedup = GossipDedup()
        val origins = (1..3).map { PeerId("origin-$it") }
        // 1000 in-order broadcasts per origin — the flat set would hold 3000 entries.
        for (o in origins) for (seq in 1..1000L) dedup.admit(frame(seq, from = o), nowMs = 0)
        assertEquals(
            origins.size,
            dedup.trackedEntryCount,
            "in-order delivery keeps memory at O(origins), not O(messages)",
        )
    }

    @Test
    fun boundedMemoryUnderPersistentGap() {
        // A persistent gap (seq 1 never arrives, only even seqs do) would grow the window
        // without bound. The cap keeps it O(maxReorder).
        val dedup = GossipDedup(maxReorder = 8)
        for (seq in 2..200L step 2) dedup.admit(frame(seq), nowMs = 0)
        assertTrue(
            dedup.trackedEntryCount <= 1 + 8,
            "reorder window stays bounded under a persistent gap (was ${dedup.trackedEntryCount})",
        )
    }

    @Test
    fun windowOverflowReleasesHeldFramesInOrderAndAbandonsTheGap() {
        val dedup = GossipDedup(maxReorder = 3)
        val released = mutableListOf<Long>()
        // seqs 1 and 2 never arrive; 3/5/4 fill the window (out of order), then 6 overflows it.
        for (seq in listOf(3L, 5L, 4L)) released += dedup.admitSeq(seq)
        assertEquals(emptyList(), released, "all held while 1 and 2 are outstanding")

        released += dedup.admitSeq(6)
        assertAll(
            {
                assertEquals(
                    listOf(3L, 4L, 5L, 6L),
                    released,
                    "overflow force-forwards past the abandoned gap, releasing the held run in send order",
                )
            },
            {
                assertFalse(
                    dedup.admit(frame(1), nowMs = 0).isNew,
                    "a straggler below the forced-forward frontier is treated as already seen",
                )
            },
        )
    }

    @Test
    fun graceExpiryReleasesHeldFramesInOrder() {
        val dedup = GossipDedup()
        assertAll(
            { assertEquals(emptyList(), dedup.admitSeq(3, at = 0), "3 is held — 1 and 2 outstanding") },
            { assertEquals(emptyList(), dedup.admitSeq(4, at = 500), "4 joins the held run; the blocking gap is unchanged") },
            {
                assertEquals(
                    emptyList(),
                    dedup.releaseExpired(nowMs = 1_999, graceMs = 2_000).map { it.seq },
                    "the gap is still within its grace",
                )
            },
            {
                assertEquals(
                    listOf(3L, 4L),
                    dedup.releaseExpired(nowMs = 2_000, graceMs = 2_000).map { it.seq },
                    "grace expired: the gap is abandoned and the held run releases in send order",
                )
            },
            {
                assertFalse(
                    dedup.admit(frame(1), nowMs = 2_001).isNew,
                    "a straggler below the abandoned gap is treated as already seen",
                )
            },
        )
    }

    @Test
    fun eachSuccessiveGapGetsItsOwnGrace() {
        val dedup = GossipDedup()
        dedup.admitSeq(3, at = 0)
        dedup.admitSeq(6, at = 0)
        assertAll(
            {
                assertEquals(
                    listOf(3L),
                    dedup.releaseExpired(nowMs = 2_000, graceMs = 2_000).map { it.seq },
                    "only the run behind the expired gap releases; 6 stays held behind the 4–5 gap",
                )
            },
            {
                assertEquals(
                    emptyList(),
                    dedup.releaseExpired(nowMs = 3_999, graceMs = 2_000).map { it.seq },
                    "the new blocking gap's grace restarted when it became the blocker",
                )
            },
            {
                assertEquals(
                    listOf(6L),
                    dedup.releaseExpired(nowMs = 4_000, graceMs = 2_000).map { it.seq },
                    "and it releases when its own grace expires",
                )
            },
        )
    }

    @Test
    fun forcedAdvancePastGapTreatsStragglerAsSeen() {
        // When the window overflows, the frontier is forced forward past the gap; a much
        // later straggler below the new frontier is treated as already-seen (dropped, not
        // re-delivered) — anti-entropy backstops anything dropped this way.
        val dedup = GossipDedup(maxReorder = 4)
        for (seq in 10..100L step 2) dedup.admit(frame(seq), nowMs = 0)
        assertFalse(
            dedup.admit(frame(10), nowMs = 0).isNew,
            "a straggler below the forced-forward frontier is treated as already seen",
        )
    }
}
