@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineCorrectnessTest {

    /**
     * Burst 10 concurrent proposals — each log index must appear exactly once in `committed`.
     *
     * Catches the `scope.launch` double-emit race: two `AppendEntriesResponse` messages arriving
     * in quick succession can both pass the `majorityIdx > currentCommitIndex` check before either
     * has advanced the commit index, causing `advanceCommit` to run twice and emit the same
     * `LogEntry` twice.
     */
    @Test
    fun burstProposals_eachIndexEmittedExactlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)

        val collectedByLeader = mutableListOf<LogEntry>()
        val collectJob = launch { leader.committed.collect { collectedByLeader.add(it) } }

        (1..10).map { i -> async { leader.propose(byteArrayOf(i.toByte())) } }.awaitAll()
        delay(50)
        collectJob.cancel()

        val indices = collectedByLeader.map { it.index }
        // The no-op entry is emitted before the collector subscribes (SharedFlow, no replay),
        // so collectedByLeader holds exactly the 10 user proposals — verify they are
        // contiguous with no duplicates and no gaps.
        assertEquals(10, indices.size, "Expected 10 collected entries, got: $indices")
        assertEquals(
            10,
            indices.distinct().size,
            "Duplicate indices in committed: $indices",
        )
        val sorted = indices.sorted()
        for (i in 1 until sorted.size) {
            assertEquals(sorted[i - 1] + 1, sorted[i],
                "Gap in committed indices at position $i: $sorted")
        }
    }

    /**
     * Slow consumer + 300 proposals — no entries must be dropped.
     *
     * Catches the `MutableSharedFlow(extraBufferCapacity = 256)` + `DROP_OLDEST` policy:
     * when the consumer falls behind, entries are silently discarded. For consensus, every
     * committed entry must reach every collector.
     */
    @Test
    fun slowConsumer_noEntriesDropped() = runTest(UnconfinedTestDispatcher()) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)

        val received = mutableListOf<LogEntry>()
        val collectJob = launch {
            leader.committed.collect { entry ->
                delay(1) // simulate slow consumer
                received.add(entry)
            }
        }

        repeat(300) { i -> leader.propose(byteArrayOf(i.toByte())) }
        delay(500)
        collectJob.cancel()

        val indices = received.map { it.index }
        assertEquals(
            300,
            received.size,
            "Expected 300 committed entries, got ${received.size} — entries were dropped",
        )
        assertEquals(indices.sorted(), indices, "Entries out of order: $indices")
        assertEquals(
            300,
            indices.distinct().size,
            "Duplicate entries: ${indices.groupBy { it }.filter { (_, v) -> v.size > 1 }}",
        )
    }
}
