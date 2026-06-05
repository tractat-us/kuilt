package us.tractat.kuilt.raft

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryRaftStorageTest {
    private fun storage() = InMemoryRaftStorage()

    @Test fun initialTermIsZero() = runTest { assertEquals(0L, storage().term()) }

    @Test fun savesAndLoadsTerm() = runTest {
        val s = storage(); s.saveTerm(3L); assertEquals(3L, s.term())
    }

    @Test fun initialVotedForIsNull() = runTest { assertNull(storage().votedFor()) }

    @Test fun savesAndLoadsVotedFor() = runTest {
        val s = storage(); val id = NodeId("a"); s.saveVotedFor(id); assertEquals(id, s.votedFor())
    }

    @Test fun appendsAndRetrievesEntries() = runTest {
        val s = storage()
        s.appendEntries(listOf(LogEntry(1, 1, byteArrayOf(1)), LogEntry(2, 1, byteArrayOf(2))))
        val loaded = s.entries()
        assertEquals(2, loaded.size)
        assertContentEquals(byteArrayOf(1), loaded[0].command)
        assertContentEquals(byteArrayOf(2), loaded[1].command)
    }

    @Test fun entriesFromIndexFilters() = runTest {
        val s = storage()
        s.appendEntries(listOf(LogEntry(1,1,byteArrayOf()), LogEntry(2,1,byteArrayOf()), LogEntry(3,1,byteArrayOf())))
        assertEquals(2, s.entries(fromIndex = 2L).size)
        assertEquals(2L, s.entries(fromIndex = 2L).first().index)
    }

    @Test fun truncateFromRemovesTailEntries() = runTest {
        val s = storage()
        s.appendEntries(listOf(LogEntry(1,1,byteArrayOf()), LogEntry(2,1,byteArrayOf()), LogEntry(3,1,byteArrayOf())))
        s.truncateFrom(2L)
        val remaining = s.entries()
        assertEquals(1, remaining.size)
        assertEquals(1L, remaining.first().index)
    }
}
