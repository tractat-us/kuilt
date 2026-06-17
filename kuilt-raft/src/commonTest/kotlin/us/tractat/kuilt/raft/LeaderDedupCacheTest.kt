package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.LeaderDedupCache
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class LeaderDedupCacheTest {
    private val k = DedupKey(ClientId("c"), 1)
    private val entry = LogEntry(index = 5, term = 2, command = byteArrayOf(9), dedupKey = k)

    @Test
    fun firstSightReturnsNullThenRecordsResult() {
        val cache = LeaderDedupCache()
        assertNull(cache.lookup(k))      // miss: caller proceeds to append
        cache.record(k, entry)
        assertSame(entry, cache.lookup(k)) // retry of the same key coalesces onto the recorded result
    }

    @Test
    fun unkeyedEntriesAreNeverCached() {
        val cache = LeaderDedupCache()
        assertNull(cache.lookup(null))
    }

    @Test
    fun clearDropsAllEntriesOnLeadershipLoss() {
        val cache = LeaderDedupCache()
        cache.record(k, entry)
        cache.clear()
        assertNull(cache.lookup(k))
    }
}
