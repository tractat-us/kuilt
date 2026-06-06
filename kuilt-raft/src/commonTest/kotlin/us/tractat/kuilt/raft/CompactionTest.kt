@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompactionTest {

    @Test
    fun publishingSnapshot_advancesCompactionFloorAndDiscardsPrefix() = runTest(UnconfinedTestDispatcher()) {
        val h = singleVoterNode(backgroundScope)
        h.node.awaitLeadership()
        // In a single-voter cluster the no-op lands at index 1; 5 proposals land at 2..6.
        repeat(5) { h.node.propose(byteArrayOf(it.toByte())) }
        h.node.commitIndex.first { it >= 6L }   // wait for all 5 proposals to commit

        // Snapshot through index 4 (the no-op + first 3 proposals); entries 5..6 remain.
        h.node.snapshots.value = Snapshot(throughIndex = 4L, state = byteArrayOf(99))
        h.node.compactionFloor.first { it == 4L }   // suspends until compaction runs

        assertEquals(4L, h.node.compactionFloor.value)
        assertEquals(listOf(5L, 6L), h.storage.entries().map { it.index }, "prefix <= 4 discarded")
        assertEquals(4L, h.storage.loadSnapshot()!!.meta.lastIncludedIndex)
    }

    @Test
    fun snapshotBeyondCommitIndex_isIgnoredUntilCommitCatchesUp() = runTest(UnconfinedTestDispatcher()) {
        val h = singleVoterNode(backgroundScope)
        h.node.awaitLeadership()
        h.node.propose(byteArrayOf(1))
        h.node.commitIndex.first { it >= 1L }
        h.node.snapshots.value = Snapshot(throughIndex = 99L, state = byteArrayOf(0)) // beyond commit
        // floor must NOT advance to 99
        repeat(3) { h.node.propose(byteArrayOf(it.toByte())) }
        assertTrue(h.node.compactionFloor.value < 99L)
    }

    @Test
    fun committedFrom_belowFloor_leadsWithInstall() = runTest(UnconfinedTestDispatcher()) {
        val h = singleVoterNode(backgroundScope)
        h.node.awaitLeadership()
        repeat(5) { h.node.propose(byteArrayOf(it.toByte())) }
        h.node.commitIndex.first { it >= 5L }
        h.node.snapshots.value = Snapshot(3L, byteArrayOf(123))
        h.node.compactionFloor.first { it == 3L }

        val seen = h.node.committedFrom(1L).take(3).toList()   // Install + entries 4, 5
        assertTrue(seen.first() is Committed.Install, "below floor must lead with Install")
        assertEquals(Snapshot(3L, byteArrayOf(123)), (seen.first() as Committed.Install).snapshot)
        assertEquals(listOf(4L, 5L),
            seen.drop(1).filterIsInstance<Committed.Entry>().map { it.entry.index })
    }
}
