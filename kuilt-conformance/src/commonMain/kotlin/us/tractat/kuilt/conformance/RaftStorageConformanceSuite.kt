package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reusable contract test suite for [RaftStorage] implementations.
 *
 * Subclass and implement [newStorage] to bind any storage under test.
 * Every [Test] in this class encodes a required invariant of the [RaftStorage]
 * contract — a conforming implementation must pass all of them.
 *
 * Lives in `commonMain` of `:kuilt-conformance` (not a module's `commonTest`)
 * so every storage adapter can subclass it from its own test source set.
 *
 * ```kotlin
 * class SqliteRaftStorageConformanceTest : RaftStorageConformanceSuite() {
 *     override fun newStorage(): RaftStorage = SqliteRaftStorage(inMemoryDb())
 * }
 * ```
 *
 * [newStorage] must return a **fresh, empty** instance on every call — term `0`,
 * no vote, empty log.
 */
public abstract class RaftStorageConformanceSuite {

    /**
     * Returns a fresh, empty [RaftStorage] instance.
     *
     * Called once per test — each test drives its own independent storage.
     * The instance must start with `term() == 0L`, `votedFor() == null`, and
     * `entries() == emptyList()`.
     */
    public abstract fun newStorage(): RaftStorage

    // ── Term / vote ──────────────────────────────────────────────────────────

    @Test
    public fun initialTermIsZero(): TestResult = runTest {
        assertEquals(0L, newStorage().term())
    }

    @Test
    public fun initialVotedForIsNull(): TestResult = runTest {
        assertNull(newStorage().votedFor())
    }

    @Test
    public fun savesAndLoadsTerm(): TestResult = runTest {
        val storage = newStorage()
        storage.saveTerm(7L)
        assertEquals(7L, storage.term())
    }

    @Test
    public fun savesAndLoadsVotedFor(): TestResult = runTest {
        val storage = newStorage()
        storage.saveVotedFor(NodeId("node-1"))
        assertEquals(NodeId("node-1"), storage.votedFor())
    }

    @Test
    public fun saveVotedForNull_clearsVote(): TestResult = runTest {
        val storage = newStorage()
        storage.saveVotedFor(NodeId("node-1"))
        storage.saveVotedFor(null)
        assertNull(storage.votedFor())
    }

    // ── saveTermAndVotedFor atomicity ────────────────────────────────────────

    /**
     * Verifies the §5.1/§5.2 atomicity contract: after [RaftStorage.saveTermAndVotedFor]
     * both `term` and `votedFor` are visible together. Persistent implementations
     * must write both in a single transaction so a mid-write crash cannot leave
     * a node with an advanced term but stale vote (or vice-versa), which would
     * allow it to vote twice in the same term.
     */
    @Test
    public fun saveTermAndVotedFor_persistsBoth(): TestResult = runTest {
        val storage = newStorage()
        storage.saveTermAndVotedFor(5L, NodeId("node-a"))
        val term = storage.term()
        val votedFor = storage.votedFor()
        assertAll(
            { assertEquals(5L, term, "term must be persisted") },
            { assertEquals(NodeId("node-a"), votedFor, "votedFor must be persisted") },
        )
    }

    @Test
    public fun saveTermAndVotedFor_withNullVote(): TestResult = runTest {
        val storage = newStorage()
        storage.saveTermAndVotedFor(7L, null)
        val term = storage.term()
        val votedFor = storage.votedFor()
        assertAll(
            { assertEquals(7L, term, "term must be persisted") },
            { assertNull(votedFor, "votedFor must be null") },
        )
    }

    @Test
    public fun saveTermAndVotedFor_overwritesPriorVote(): TestResult = runTest {
        val storage = newStorage()
        storage.saveTermAndVotedFor(3L, NodeId("node-a"))
        storage.saveTermAndVotedFor(4L, null)
        val term = storage.term()
        val votedFor = storage.votedFor()
        assertAll(
            { assertEquals(4L, term, "term must be updated") },
            { assertNull(votedFor, "prior vote must be cleared") },
        )
    }

    // ── Log ─────────────────────────────────────────────────────────────────

    @Test
    public fun entriesOnEmptyLog_isEmpty(): TestResult = runTest {
        assertEquals(emptyList(), newStorage().entries())
    }

    @Test
    public fun appendsAndRetrievesEntries(): TestResult = runTest {
        val storage = newStorage()
        val toAppend = listOf(
            LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
            LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
            LogEntry(index = 3L, term = 2L, command = byteArrayOf(3)),
        )
        storage.appendEntries(toAppend)
        val retrieved = storage.entries()
        assertAll(
            { assertEquals(3, retrieved.size, "should have 3 entries") },
            { assertEquals(toAppend[0], retrieved[0], "entry at index 1") },
            { assertEquals(toAppend[1], retrieved[1], "entry at index 2") },
            { assertEquals(toAppend[2], retrieved[2], "entry at index 3") },
        )
    }

    @Test
    public fun entriesFromIndex_filters(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
                LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
                LogEntry(index = 3L, term = 2L, command = byteArrayOf(3)),
            )
        )
        val fromTwo = storage.entries(fromIndex = 2L)
        assertAll(
            { assertEquals(2, fromTwo.size, "entries from index 2 should have 2 items") },
            { assertEquals(2L, fromTwo[0].index, "first item index") },
            { assertEquals(3L, fromTwo[1].index, "second item index") },
        )
    }

    @Test
    public fun truncateFrom_removesTailEntries(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
                LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
                LogEntry(index = 3L, term = 2L, command = byteArrayOf(3)),
            )
        )
        storage.truncateFrom(2L)
        val remaining = storage.entries()
        assertAll(
            { assertEquals(1, remaining.size, "only index 1 should remain") },
            { assertEquals(1L, remaining[0].index, "remaining entry index") },
        )
    }

    @Test
    public fun truncateFrom_belowAllEntries_clearsLog(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
                LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
                LogEntry(index = 3L, term = 2L, command = byteArrayOf(3)),
            )
        )
        storage.truncateFrom(1L)
        assertEquals(emptyList(), storage.entries())
    }

    @Test
    public fun appendAfterTruncate_works(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
                LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
                LogEntry(index = 3L, term = 2L, command = byteArrayOf(3)),
            )
        )
        storage.truncateFrom(2L)
        val replacement = LogEntry(index = 2L, term = 3L, command = byteArrayOf(99))
        storage.appendEntries(listOf(replacement))
        val entries = storage.entries()
        assertAll(
            { assertEquals(2, entries.size, "should have 2 entries after re-append") },
            { assertEquals(1L, entries[0].index, "first entry index unchanged") },
            { assertEquals(replacement, entries[1], "replacement entry at index 2") },
        )
    }
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
