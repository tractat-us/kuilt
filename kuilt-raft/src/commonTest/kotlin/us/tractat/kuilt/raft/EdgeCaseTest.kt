@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

private val fastConfig = FAST_RAFT_CONFIG

class EdgeCaseTest {

    /**
     * Single-voter cluster: quorum = 1, so the node is always leader of itself.
     * propose() must commit immediately without waiting for any peer acknowledgements.
     *
     * Exercises the edge in tryAdvanceLeaderCommit where the leader is the sole voter
     * and voterMatches is empty.
     */
    @Test
    fun singleVoter_becomesLeaderAndCommitsImmediately() = raftRunTest {
        val id = NodeId("solo")
        val config = ClusterConfig(voters = setOf(id))
        val sim = RaftSimulation(
            nodeIds = listOf(id),
            scope = backgroundScope,
            raftConfig = fastConfig,
        ) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, fastConfig)
        }
        val leader = awaitLeader(sim)
        assertIs<RaftRole.Leader>(leader.role.value)
        val entry = leader.propose(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), entry.command)
        // Index 1 is the leader's no-op entry; the first user proposal lands at index 2.
        assertEquals(2L, entry.index)
    }

    /**
     * Large payload: 256 KB command. CBOR serialization and in-memory transport
     * must handle it without truncation.
     */
    @Test
    fun largePayload_committedCorrectly() = raftRunTest {
        val ids = listOf(NodeId("x"), NodeId("y"), NodeId("z"))
        val config = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(ids, backgroundScope, fastConfig) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, fastConfig)
        }
        val leader = awaitLeader(sim)
        val bigPayload = ByteArray(256 * 1024) { it.toByte() }
        val entry = leader.propose(bigPayload)
        assertContentEquals(bigPayload, entry.command)
    }

    /**
     * Empty command (zero bytes) must commit cleanly.
     * This is the same shape as a no-op entry — external callers must also be able
     * to propose empty commands.
     */
    @Test
    fun emptyCommand_commitsSuccessfully() = raftRunTest {
        val ids = listOf(NodeId("e1"), NodeId("e2"), NodeId("e3"))
        val config = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(ids, backgroundScope, fastConfig) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, fastConfig)
        }
        val leader = awaitLeader(sim)
        val entry = leader.propose(byteArrayOf())
        assertEquals(0, entry.command.size)
    }

    /**
     * 50 sequential proposals must produce strictly contiguous, monotonically
     * increasing indices with no gaps.
     */
    @Test
    fun proposalIndices_areMonotonicallyIncreasing() = raftRunTest {
        val ids = listOf(NodeId("p1"), NodeId("p2"), NodeId("p3"))
        val config = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(ids, backgroundScope, fastConfig) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, fastConfig)
        }
        val leader = awaitLeader(sim)
        val indices = (1..50).map { leader.propose(byteArrayOf(it.toByte())).index }
        val firstIndex = indices.first()
        for (i in indices.indices) {
            assertEquals(
                firstIndex + i,
                indices[i],
                "Non-contiguous index at position $i: expected ${firstIndex + i}, got ${indices[i]}",
            )
        }
    }

    /**
     * A voter-follower forwards propose() to the leader and commits.
     * Leader forwarding (Raft §8): any non-leader role (Follower, Candidate, Learner)
     * forwards the command to the current leader rather than throwing.
     */
    @Test
    fun voterFollower_propose_forwardsToLeaderAndCommits() = raftRunTest {
        val ids = listOf(NodeId("f1"), NodeId("f2"), NodeId("f3"))
        val config = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(ids, backgroundScope, fastConfig) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, fastConfig)
        }
        awaitLeader(sim)
        val follower = sim.followers().first()
        assertIs<RaftRole.Follower>(follower.role.value)
        val entry = follower.propose(byteArrayOf(1))
        sim.awaitCommit(entry.index)
    }

    /**
     * Two-voter cluster: quorum = 2. Crashing the follower must prevent further commits
     * because the leader alone cannot satisfy quorum.
     */
    @Test
    fun twoVoter_crashFollower_noProgress() = raftRunTest {
        val ids = listOf(NodeId("a"), NodeId("b"))
        val config = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(ids, backgroundScope, fastConfig) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, fastConfig)
        }
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val followerId = sim.nodes.keys.first { it != leaderId }
        sim.crash(followerId)
        var committed = false
        val job = launch {
            try { leader.propose(byteArrayOf(99)); committed = true }
            catch (_: NotLeaderException) {}
            catch (_: LeadershipLostException) {}
        }
        delay(100)
        job.cancel()
        assertFalse(committed, "Should not commit with only 1 of 2 voters alive")
    }
}
