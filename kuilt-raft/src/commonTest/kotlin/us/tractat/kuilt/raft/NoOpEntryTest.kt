@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
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
    fun priorTermEntries_commitAfterElection_withoutNewProposal() = raftRunTest {
        val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
        val config = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = this,
            raftConfig = FAST_RAFT_CONFIG,
            nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, nodeScope ->
                nodeScope.raftNode(config, transport, storage, FAST_RAFT_CONFIG)
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

        // New leader elected. Wait for commitIndex to advance past the prior-term entries.
        // We use commitIndex (StateFlow — always has the current value) rather than the
        // committed Flow (SharedFlow with replay=0), since the no-op may commit before
        // we subscribe to committed under UnconfinedTestDispatcher.
        val newLeader = awaitLeader(sim)
        val newLeaderId = sim.nodes.entries.first { it.value === newLeader }.key
        sim.awaitCommit(e2.index, on = listOf(newLeaderId))

        // commitIndex >= e2.index means all entries up to e2 committed without a new proposal.
        assertTrue(
            newLeader.commitIndex.value >= e2.index,
            "Expected prior-term entries (indices ${e1.index}, ${e2.index}) to commit via no-op " +
                "without a new proposal, but commitIndex=${newLeader.commitIndex.value}",
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
    fun committedEntry_survivesPartitionAndLeaderChange() = raftRunTest {
        val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
        val config = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = this,
            raftConfig = FAST_RAFT_CONFIG,
            nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, nodeScope ->
                nodeScope.raftNode(config, transport, storage, FAST_RAFT_CONFIG)
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

    /**
     * #136: the §5.4.2 election no-op is an internal log entry and must NOT surface on
     * [RaftNode.committed]. Consumers drive a state machine off this flow; leaking the
     * empty-command no-op forces them to filter it out and risks misaligned indices.
     *
     * We subscribe to `committed` *before* the node becomes leader (so the no-op, if it
     * leaked, would be captured at index 1), then confirm only the application command
     * surfaces while `commitIndex` still advances past the no-op.
     */
    @Test
    fun committed_doesNotEmitElectionNoOp() = raftRunTest {
        val id = NodeId("solo")
        val config = ClusterConfig(voters = setOf(id))
        val sim = RaftSimulation(
            nodeIds = listOf(id),
            scope = backgroundScope,
            raftConfig = FAST_RAFT_CONFIG,
        ) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, FAST_RAFT_CONFIG)
        }
        val node = sim.nodes.getValue(id)
        val seen = mutableListOf<LogEntry>()
        backgroundScope.launch { node.committed.collect { if (it is Committed.Entry) seen += it.entry } }

        // Election + no-op commit happen here, after the collector has subscribed.
        val leader = awaitLeader(sim)
        val userEntry = leader.propose(byteArrayOf(7))
        sim.awaitCommit(userEntry.index, on = listOf(id))

        assertAll(
            { assertTrue(node.commitIndex.value >= userEntry.index, "commitIndex must advance past the no-op") },
            { assertTrue(seen.none { it.index == 1L }, "committed leaked the index-1 election no-op: $seen") },
            { assertTrue(seen.none { it.command.isEmpty() }, "committed leaked an empty-command no-op: $seen") },
            { assertContentEquals(byteArrayOf(7), seen.first().command, "first committed entry should be the user command") },
        )
    }

    /**
     * #136 guard: a no-op must be suppressed by *provenance*, not by empty content.
     * A user that proposes a genuinely empty command (see EdgeCaseTest.emptyCommand…)
     * must still see that entry on `committed` — only the internal election no-op is hidden.
     */
    @Test
    fun committed_includesUserProposedEmptyCommand() = raftRunTest {
        val id = NodeId("solo")
        val config = ClusterConfig(voters = setOf(id))
        val sim = RaftSimulation(
            nodeIds = listOf(id),
            scope = backgroundScope,
            raftConfig = FAST_RAFT_CONFIG,
        ) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, FAST_RAFT_CONFIG)
        }
        val node = sim.nodes.getValue(id)
        val seen = mutableListOf<LogEntry>()
        backgroundScope.launch { node.committed.collect { if (it is Committed.Entry) seen += it.entry } }

        val leader = awaitLeader(sim)
        val emptyUserEntry = leader.propose(byteArrayOf()) // index 2 — empty, but user-proposed
        sim.awaitCommit(emptyUserEntry.index, on = listOf(id))

        assertAll(
            { assertTrue(seen.any { it.index == emptyUserEntry.index }, "user-proposed empty command must surface on committed: $seen") },
            { assertTrue(seen.none { it.index == 1L }, "the index-1 election no-op must still be suppressed: $seen") },
        )
    }
}


private suspend fun awaitLeaderAmong(sim: RaftSimulation, ids: Set<NodeId>): RaftNode {
    repeat(500) {
        sim.nodes.entries
            .firstOrNull { (id, node) -> id in ids && node.role.value is RaftRole.Leader }
            ?.let { return it.value }
        delay(1)
    }
    error("No leader elected within timeout among $ids")
}
