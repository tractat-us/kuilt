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

/** Audit characterizations, not a promise that reusing a stale handle is safe (#2915). */
class DurableStoreIndeterminateWriteTest {
    @Test
    fun committedFailureLeavesStaleMetaAndReuseOverwritesTheLandedTerm() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        for (cancel in listOf(false, true)) {
            val store = CommitThenFailStore(cancel)
            val storage = DurableStoreRaftStorage.open(store)
            assertFailsWith<Exception> { storage.saveTermAndVotedFor(7L, NodeId("a")) }
            val reopened = DurableStoreRaftStorage.open(store)
            val cachedTerm = storage.term()
            val durableTerm = reopened.term()
            // A later field update is encoded from the old meta, losing the landed term/vote.
            storage.saveLeaderForTerm(7L, NodeId("a"))
            val overwritten = DurableStoreRaftStorage.open(store)
            val overwrittenTerm = overwritten.term()
            assertAll(
                { assertEquals(1, store.injections, "commit-before-failure injection must fire") },
                { assertEquals(0L, cachedTerm, "audit: the original cache remains stale") },
                { assertEquals(7L, durableTerm, "reopen sees the landed write") },
                { assertEquals(0L, overwrittenTerm, "audit: cache reuse overwrites the landed term") },
            )
        }
    }

    private class CommitThenFailStore(private val cancel: Boolean) : DurableStore {
        private val backing = InMemoryDurableStore()
        var injections = 0
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun delete(key: StoreKey) = backing.delete(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            backing.write(key, bytes)
            if (injections == 0) {
                injections++
                if (cancel) throw CancellationException("committed before cancellation")
                throw Exception("committed before failure")
            }
        }
    }
}
