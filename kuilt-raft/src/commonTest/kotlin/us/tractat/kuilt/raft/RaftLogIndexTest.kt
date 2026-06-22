package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.logEntryAt
import us.tractat.kuilt.raft.internal.logSliceFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for O(1) Raft log index-arithmetic helpers: [logEntryAt] and [logSliceFrom].
 *
 * These are pure functions that operate on a flat [List<LogEntry>] using index arithmetic
 * relative to the compaction base ([snapshotIndex]). Tests cover:
 * - No compaction (snapshotIndex = 0, log starts at index 1)
 * - Post-compaction (snapshotIndex > 0, log starts mid-sequence)
 * - Boundary conditions: below base, at base, at end, beyond end
 */
class RaftLogIndexTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun entry(index: Long, term: Long = 1L) =
        LogEntry(index = index, term = term, command = byteArrayOf())

    private fun logFrom(firstIndex: Long, count: Int, term: Long = 1L) =
        (0 until count).map { entry(firstIndex + it, term) }

    // ── logEntryAt — no compaction ────────────────────────────────────────────

    @Test
    fun logEntryAt_noCompaction_firstEntry() {
        val log = logFrom(firstIndex = 1L, count = 5)
        assertEquals(entry(1L), logEntryAt(log, snapshotIndex = 0L, index = 1L))
    }

    @Test
    fun logEntryAt_noCompaction_lastEntry() {
        val log = logFrom(firstIndex = 1L, count = 5)
        assertEquals(entry(5L), logEntryAt(log, snapshotIndex = 0L, index = 5L))
    }

    @Test
    fun logEntryAt_noCompaction_middleEntry() {
        val log = logFrom(firstIndex = 1L, count = 5)
        assertEquals(entry(3L), logEntryAt(log, snapshotIndex = 0L, index = 3L))
    }

    @Test
    fun logEntryAt_noCompaction_belowBase_returnsNull() {
        val log = logFrom(firstIndex = 1L, count = 5)
        // index 0 is below base (snapshotIndex + 1 = 1)
        assertNull(logEntryAt(log, snapshotIndex = 0L, index = 0L))
    }

    @Test
    fun logEntryAt_noCompaction_beyondEnd_returnsNull() {
        val log = logFrom(firstIndex = 1L, count = 5)
        assertNull(logEntryAt(log, snapshotIndex = 0L, index = 6L))
    }

    @Test
    fun logEntryAt_emptyLog_returnsNull() {
        assertNull(logEntryAt(emptyList(), snapshotIndex = 0L, index = 1L))
    }

    // ── logEntryAt — post-compaction ──────────────────────────────────────────

    @Test
    fun logEntryAt_postCompaction_atBase() {
        // Compacted through index 10; log starts at 11.
        val log = logFrom(firstIndex = 11L, count = 5)
        assertEquals(entry(11L), logEntryAt(log, snapshotIndex = 10L, index = 11L))
    }

    @Test
    fun logEntryAt_postCompaction_lastEntry() {
        val log = logFrom(firstIndex = 11L, count = 5)
        assertEquals(entry(15L), logEntryAt(log, snapshotIndex = 10L, index = 15L))
    }

    @Test
    fun logEntryAt_postCompaction_midEntry() {
        val log = logFrom(firstIndex = 11L, count = 5)
        assertEquals(entry(13L), logEntryAt(log, snapshotIndex = 10L, index = 13L))
    }

    @Test
    fun logEntryAt_postCompaction_belowBase_returnsNull() {
        // Index 10 == snapshotIndex — compacted, not in the live log.
        val log = logFrom(firstIndex = 11L, count = 5)
        assertNull(logEntryAt(log, snapshotIndex = 10L, index = 10L))
    }

    @Test
    fun logEntryAt_postCompaction_farBelowBase_returnsNull() {
        val log = logFrom(firstIndex = 11L, count = 5)
        assertNull(logEntryAt(log, snapshotIndex = 10L, index = 1L))
    }

    @Test
    fun logEntryAt_postCompaction_beyondEnd_returnsNull() {
        val log = logFrom(firstIndex = 11L, count = 5)
        assertNull(logEntryAt(log, snapshotIndex = 10L, index = 16L))
    }

    @Test
    fun logEntryAt_postCompaction_emptyLog_returnsNull() {
        // Log fully consumed; all entries are in the snapshot.
        assertNull(logEntryAt(emptyList(), snapshotIndex = 20L, index = 15L))
    }

    // ── logSliceFrom — no compaction ──────────────────────────────────────────

    @Test
    fun logSliceFrom_noCompaction_fromFirst_returnsAll() {
        val log = logFrom(firstIndex = 1L, count = 4)
        assertEquals(log, logSliceFrom(log, snapshotIndex = 0L, fromIndex = 1L))
    }

    @Test
    fun logSliceFrom_noCompaction_fromMiddle_returnsTail() {
        val log = logFrom(firstIndex = 1L, count = 4)
        assertEquals(log.drop(2), logSliceFrom(log, snapshotIndex = 0L, fromIndex = 3L))
    }

    @Test
    fun logSliceFrom_noCompaction_fromBeyondEnd_returnsEmpty() {
        val log = logFrom(firstIndex = 1L, count = 4)
        assertEquals(emptyList<LogEntry>(), logSliceFrom(log, snapshotIndex = 0L, fromIndex = 5L))
    }

    @Test
    fun logSliceFrom_noCompaction_emptyLog_returnsEmpty() {
        assertEquals(emptyList<LogEntry>(), logSliceFrom(emptyList(), snapshotIndex = 0L, fromIndex = 1L))
    }

    // ── logSliceFrom — post-compaction ────────────────────────────────────────

    @Test
    fun logSliceFrom_postCompaction_fromBase_returnsAll() {
        val log = logFrom(firstIndex = 11L, count = 5)
        assertEquals(log, logSliceFrom(log, snapshotIndex = 10L, fromIndex = 11L))
    }

    @Test
    fun logSliceFrom_postCompaction_fromMiddle_returnsTail() {
        val log = logFrom(firstIndex = 11L, count = 5)
        assertEquals(log.drop(2), logSliceFrom(log, snapshotIndex = 10L, fromIndex = 13L))
    }

    @Test
    fun logSliceFrom_postCompaction_fromBelowBase_returnsAll() {
        // fromIndex below base: clamp to base, return entire log.
        val log = logFrom(firstIndex = 11L, count = 5)
        assertEquals(log, logSliceFrom(log, snapshotIndex = 10L, fromIndex = 5L))
    }

    @Test
    fun logSliceFrom_postCompaction_fromBeyondEnd_returnsEmpty() {
        val log = logFrom(firstIndex = 11L, count = 5)
        assertEquals(emptyList<LogEntry>(), logSliceFrom(log, snapshotIndex = 10L, fromIndex = 16L))
    }

    @Test
    fun logSliceFrom_postCompaction_emptyLog_returnsEmpty() {
        assertEquals(emptyList<LogEntry>(), logSliceFrom(emptyList(), snapshotIndex = 20L, fromIndex = 15L))
    }
}
