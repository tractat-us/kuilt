package us.tractat.kuilt.raft

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Audit characterizations, not a promise that reusing a handle after a failed write is safe
 * (#2915). Each write is injected to land and then throw, so the handle's cache and the medium
 * disagree; these pin which way.
 */
class DurableStoreIndeterminateWriteTest {
    /**
     * The direction that matters: a destructive log write lands, so the cache is AHEAD of the
     * medium. A reused handle serves entries the medium no longer holds, and a write to another
     * key does not repair that. Only the next log write, which rewrites the whole cached log,
     * puts those entries back on the medium.
     */
    @Test
    fun aLandedTruncateLeavesTheCacheAttestingEntriesTheMediumDropped() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        for (cancel in listOf(false, true)) {
            val store = CommitThenFailStore()
            val storage = DurableStoreRaftStorage.open(store)
            storage.appendEntries((1L..10L).map { entry(it) })
            store.arm(DurableStoreRaftStorage.LOG_KEY, cancel)
            assertFailsWith<Exception> { storage.truncateFrom(8L) }
            val cached = storage.entries(1L).map { it.index }
            val medium = DurableStoreRaftStorage.open(store).entries(1L).map { it.index }
            // A successful write to a different key leaves the log divergence in place.
            storage.saveTerm(2L)
            val mediumAfterMetaWrite = DurableStoreRaftStorage.open(store).entries(1L).map { it.index }
            storage.appendEntries(listOf(entry(11L)))
            val mediumAfterLogWrite = DurableStoreRaftStorage.open(store).entries(1L).map { it.index }
            assertAll(
                { assertEquals(1, store.injections, "commit-before-failure injection must fire") },
                { assertEquals((1L..10L).toList(), cached, "audit: the reused handle still serves 8..10") },
                { assertEquals((1L..7L).toList(), medium, "the landed truncate dropped 8..10 from the medium") },
                { assertEquals((1L..7L).toList(), mediumAfterMetaWrite, "a meta write does not repair the log") },
                { assertEquals((1L..11L).toList(), mediumAfterLogWrite, "the next log write re-persists the cache") },
            )
        }
    }

    /** Snapshot and meta adopt only after acknowledgement too, so a landed write leaves the cache behind. */
    @Test
    fun aLandedSnapshotOrMetaWriteLeavesTheCacheBehindTheMedium() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        for (cancel in listOf(false, true)) {
            val store = CommitThenFailStore()
            val storage = DurableStoreRaftStorage.open(store)
            store.arm(DurableStoreRaftStorage.SNAPSHOT_KEY, cancel)
            assertFailsWith<Exception> { storage.saveSnapshot(SnapshotMeta(5L, 1L), byteArrayOf(9)) }
            store.arm(DurableStoreRaftStorage.META_KEY, cancel)
            assertFailsWith<Exception> { storage.saveTermAndVotedFor(7L, NodeId("a")) }
            val cachedSnapshot = storage.loadSnapshot()
            val cachedTerm = storage.term()
            val reopened = DurableStoreRaftStorage.open(store)
            val durableSnapshotIndex = reopened.loadSnapshot()?.meta?.lastIncludedIndex
            val durableTerm = reopened.term()
            assertAll(
                { assertEquals(2, store.injections, "both commit-before-failure injections must fire") },
                { assertNull(cachedSnapshot, "audit: the snapshot cache did not adopt the landed write") },
                { assertEquals(5L, durableSnapshotIndex, "the snapshot landed") },
                { assertEquals(0L, cachedTerm, "audit: the meta cache did not adopt the landed write") },
                { assertEquals(7L, durableTerm, "the term landed") },
            )
        }
    }

    private fun entry(index: Long) = LogEntry(index = index, term = 1L, command = byteArrayOf())

    /** Lands the next write to the armed key, then fails it once. */
    private class CommitThenFailStore : DurableStore {
        private val backing = InMemoryDurableStore()
        private var armed: StoreKey? = null
        private var cancel = false
        var injections = 0

        fun arm(key: StoreKey, cancel: Boolean) {
            armed = key
            this.cancel = cancel
        }

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun delete(key: StoreKey) = backing.delete(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            backing.write(key, bytes)
            if (key == armed) {
                armed = null
                injections++
                if (cancel) throw CancellationException("committed before cancellation")
                throw Exception("committed before failure")
            }
        }
    }
}
