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
import kotlin.time.Duration.Companion.milliseconds

private val fastConfig = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
)

private fun correctnessSim(
    scope: kotlinx.coroutines.CoroutineScope,
    nodeScope: kotlinx.coroutines.CoroutineScope,
    n: Int = 3,
): RaftSimulation {
    val ids = (1..n).map { NodeId("e$it") }
    val config = ClusterConfig(voters = ids.toSet())
    return RaftSimulation(
        nodeIds = ids,
        scope = scope,
        raftConfig = fastConfig,
        nodeScope = nodeScope,
        nodeFactory = { id, transport, storage, childScope ->
            childScope.raftNode(config, transport, storage, fastConfig)
        },
    )
}

private suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
    repeat(500) {
        sim.leader()?.let { return it }
        delay(1)
    }
    error("No leader elected within timeout")
}

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
        val sim = correctnessSim(this, backgroundScope)
        val leader = awaitLeader(sim)

        val collectedByLeader = mutableListOf<LogEntry>()
        val collectJob = launch { leader.committed.collect { collectedByLeader.add(it) } }

        (1..10).map { i -> async { leader.propose(byteArrayOf(i.toByte())) } }.awaitAll()
        delay(50)
        collectJob.cancel()

        // Filter out the leader's §5.4.2 no-op (empty command, index 1) so this test
        // asserts on the 10 user proposals regardless of where the no-op lands. The
        // race this guards against would surface as a duplicated user-entry index.
        val userIndices = collectedByLeader.filter { it.command.isNotEmpty() }.map { it.index }
        assertEquals(
            10,
            userIndices.size,
            "Expected exactly 10 committed user entries, got: $userIndices",
        )
        assertEquals(
            10,
            userIndices.distinct().size,
            "Duplicate indices in committed user entries: $userIndices",
        )
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
        val sim = correctnessSim(this, backgroundScope)
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
