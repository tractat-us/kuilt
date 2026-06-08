package us.tractat.kuilt.raft

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class InMemoryRaftStorageSnapshotTest {

    @Test
    fun loadSnapshot_isNullBeforeAnySave() = runTest {
        assertNull(InMemoryRaftStorage().loadSnapshot())
    }

    @Test
    fun saveThenLoad_roundTripsMetaAndBytes() = runTest {
        val s = InMemoryRaftStorage()
        s.saveSnapshot(SnapshotMeta(lastIncludedIndex = 5L, lastIncludedTerm = 2L), byteArrayOf(1, 2, 3))
        val loaded = s.loadSnapshot()!!
        assertEquals(SnapshotMeta(5L, 2L), loaded.meta)
        assertContentEquals(byteArrayOf(1, 2, 3), loaded.state)
    }

    @Test
    fun saveSnapshot_overwritesPrevious() = runTest {
        val s = InMemoryRaftStorage()
        s.saveSnapshot(SnapshotMeta(5L, 2L), byteArrayOf(1))
        s.saveSnapshot(SnapshotMeta(9L, 3L), byteArrayOf(2))
        assertEquals(SnapshotMeta(9L, 3L), s.loadSnapshot()!!.meta)
    }

    @Test
    fun discardLogPrefix_removesEntriesUpToAndIncludingIndex() = runTest {
        val s = InMemoryRaftStorage()
        s.appendEntries((1L..5L).map { LogEntry(it, term = 1L, command = byteArrayOf(it.toByte())) })
        s.discardLogPrefix(throughIndex = 3L)
        val remaining = s.entries()
        assertEquals(listOf(4L, 5L), remaining.map { it.index })
    }

    @Test
    fun discardLogPrefix_isIdempotentAndToleratesGapBelowFloor() = runTest {
        val s = InMemoryRaftStorage()
        s.appendEntries((4L..5L).map { LogEntry(it, 1L, byteArrayOf()) })
        s.discardLogPrefix(3L) // floor below the first retained entry — no-op
        assertEquals(listOf(4L, 5L), s.entries().map { it.index })
        s.discardLogPrefix(5L)
        assertTrue(s.entries().isEmpty())
    }
}
