@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * #137: [RaftNode.committedFrom] lets a late subscriber replay already-committed
 * application entries from a given index, then tail the live stream — with no gap
 * or duplicate at the seam, and without surfacing the §5.4.2 no-op.
 */
class CommittedReplayTest {

    private fun soloSim(scope: kotlinx.coroutines.CoroutineScope): RaftSimulation {
        val id = NodeId("solo")
        val config = ClusterConfig(voters = setOf(id))
        return RaftSimulation(
            nodeIds = listOf(id),
            scope = scope,
            raftConfig = FAST_RAFT_CONFIG,
        ) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, FAST_RAFT_CONFIG)
        }
    }

    /**
     * A subscriber that joins *after* N commits must still replay all N application
     * entries via committedFrom(0) — in index order, with the no-op excluded.
     */
    @Test
    fun committedFrom_replaysPriorCommitsForLateSubscriber() = raftRunTest {
        val sim = soloSim(backgroundScope)
        val leader = awaitLeader(sim)
        val e1 = leader.propose(byteArrayOf(1))
        val e2 = leader.propose(byteArrayOf(2))
        val e3 = leader.propose(byteArrayOf(3))

        // Subscribe only now — committed (replay=0) would have missed all three.
        val replayed = leader.committedFrom(0).filterIsInstance<Committed.Entry>().map { it.entry }
            .take(3).toList()

        assertContentEquals(
            listOf(e1.index, e2.index, e3.index),
            replayed.map { it.index },
            "committedFrom(0) must replay every prior application commit in index order",
        )
        assertTrue(replayed.none { it.isNoOp }, "no-op must not be replayed: $replayed")
        assertContentEquals(byteArrayOf(1), replayed[0].command)
        assertContentEquals(byteArrayOf(3), replayed[2].command)
    }

    /**
     * committedFrom must honour [fromIndex]: entries below it are not replayed.
     */
    @Test
    fun committedFrom_skipsEntriesBelowFromIndex() = raftRunTest {
        val sim = soloSim(backgroundScope)
        val leader = awaitLeader(sim)
        leader.propose(byteArrayOf(1)) // index 2
        val e2 = leader.propose(byteArrayOf(2)) // index 3
        val e3 = leader.propose(byteArrayOf(3)) // index 4

        val replayed = leader.committedFrom(e2.index).filterIsInstance<Committed.Entry>().map { it.entry }
            .take(2).toList()

        assertContentEquals(
            listOf(e2.index, e3.index),
            replayed.map { it.index },
            "committedFrom(${e2.index}) must skip entries below the requested index",
        )
    }

    /**
     * After replaying the committed prefix, committedFrom must tail live commits —
     * each exactly once (no duplicate at the replay/live seam).
     */
    @Test
    fun committedFrom_replaysThenTailsLiveWithoutDuplicates() = raftRunTest {
        val sim = soloSim(backgroundScope)
        val leader = awaitLeader(sim)
        val e1 = leader.propose(byteArrayOf(1)) // index 2
        val e2 = leader.propose(byteArrayOf(2)) // index 3

        val seen = mutableListOf<LogEntry>()
        backgroundScope.launch {
            leader.committedFrom(e1.index).collect { if (it is Committed.Entry) seen += it.entry }
        }
        // Under StandardTestDispatcher the launched collector does not run until virtual time
        // advances; settle lets it subscribe (and replay [e1, e2]) before we propose e3, so the
        // "tail the live stream" half of the seam is actually exercised. See RaftTestFixtures.
        sim.settle()

        // Propose a third entry *after* the subscription is live — it must tail through.
        val e3 = leader.propose(byteArrayOf(3)) // index 4
        sim.awaitCommit(e3.index)
        sim.settle() // let the collector drain the live e3 emission before asserting

        assertContentEquals(
            listOf(e1.index, e2.index, e3.index),
            seen.map { it.index },
            "committedFrom must replay [e1, e2] then tail e3, each exactly once: $seen",
        )
        assertTrue(seen.none { it.isNoOp }, "no-op must never surface: $seen")
    }
}
