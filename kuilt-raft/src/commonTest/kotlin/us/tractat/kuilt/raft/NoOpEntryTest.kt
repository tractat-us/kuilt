@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlin.time.Duration.Companion.milliseconds

private val fastConfig = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
)

class NoOpEntryTest {

    /**
     * §5.4.2: A newly elected leader must commit a no-op entry from the current term
     * so that prior-term entries can advance commitIndex.
     *
     * Scenario: leader A commits 2 entries in term 1, crashes. B is elected in term 2.
     * B inherits those 2 entries from prior term but receives no new client proposals.
     * Without the no-op fix, those prior-term entries never advance commitIndex.
     * With the fix, they commit promptly via the no-op that satisfies the term guard.
     */
    @Test
    fun priorTermEntries_commitAfterElection_withoutNewProposal() = runTest(UnconfinedTestDispatcher()) {
        val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
        val config = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = this,
            raftConfig = fastConfig,
            nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, nodeScope ->
                nodeScope.raftNode(config, transport, storage, fastConfig)
            },
        )

        // Elect initial leader and commit 2 entries with full quorum.
        val leader = awaitLeader(sim)
        val e1 = leader.propose(byteArrayOf(1))
        val e2 = leader.propose(byteArrayOf(2))
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        // Kill the leader — other nodes retain the two prior-term entries.
        sim.crash(leaderId)
        delay(80)

        // New leader elected. Collect entries it commits WITHOUT any new proposal.
        val newLeader = awaitLeader(sim)
        val committedByNewLeader = mutableListOf<LogEntry>()
        val collectJob: Job = launch {
            newLeader.committed.collect { committedByNewLeader.add(it) }
        }

        // Give the no-op time to trigger commit advancement of the prior-term entries.
        delay(100)
        collectJob.cancel()

        // Both prior-term user entries must appear committed (no-op itself may also appear).
        val userEntries = committedByNewLeader.filter { it.command.isNotEmpty() }
        assertTrue(
            userEntries.size >= 2,
            "Expected prior-term entries (indices ${e1.index}, ${e2.index}) to commit via no-op " +
                "without a new proposal, but only got: $userEntries",
        )
        sim.checkInvariants()
    }

    /**
     * Committed entries must survive a partition followed by a leader change.
     *
     * Entry X is committed with a full quorum. The old leader is then isolated.
     * The surviving majority elects a new leader and commits entry Y.
     * After healing, all nodes must have both X and Y in storage.
     */
    @Test
    fun committedEntry_survivesPartitionAndLeaderChange() = runTest(UnconfinedTestDispatcher()) {
        val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
        val config = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = this,
            raftConfig = fastConfig,
            nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, nodeScope ->
                nodeScope.raftNode(config, transport, storage, fastConfig)
            },
        )

        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        // Commit X with full quorum, then isolate the old leader.
        val entryX = leader.propose(byteArrayOf(10))
        assertContentEquals(byteArrayOf(10), entryX.command)

        val others = sim.nodes.keys.filter { it != leaderId }.toSet()
        sim.partition(setOf(leaderId), others)
        delay(80)

        // Majority elects a new leader — search only among the surviving partition.
        val newLeader = awaitLeaderAmong(sim, others)
        val entryY = newLeader.propose(byteArrayOf(20))
        assertContentEquals(byteArrayOf(20), entryY.command)

        // Heal — old leader rejoins the cluster.
        sim.heal()
        delay(100)
        sim.checkInvariants()

        // Both X and Y must exist in every node's storage.
        sim.storages.values.forEach { storage ->
            val entries = storage.entries().filter { it.command.isNotEmpty() }
            assertTrue(
                entries.any { it.command.contentEquals(byteArrayOf(10)) },
                "Entry X (10) missing from storage after partition heal",
            )
            assertTrue(
                entries.any { it.command.contentEquals(byteArrayOf(20)) },
                "Entry Y (20) missing from storage after partition heal",
            )
        }
    }
}

private suspend fun awaitLeader(sim: RaftSimulation): RaftNode =
    awaitLeaderAmong(sim, sim.nodes.keys)

private suspend fun awaitLeaderAmong(sim: RaftSimulation, ids: Set<NodeId>): RaftNode {
    repeat(500) {
        sim.nodes.entries
            .firstOrNull { (id, node) -> id in ids && node.role.value is RaftRole.Leader }
            ?.let { return it.value }
        delay(1)
    }
    error("No leader elected within timeout among $ids")
}
