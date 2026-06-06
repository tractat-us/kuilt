@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

private val fastConfig = RaftConfig(5.milliseconds, 10.milliseconds, 2.milliseconds)

private suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
    repeat(500) { sim.leader()?.let { return it }; delay(1) }
    error("No leader elected within timeout")
}

class EdgeCaseTest {

    /**
     * Single-voter cluster: quorum = 1, so the node is always leader of itself.
     * propose() must commit immediately without waiting for any peer acknowledgements.
     *
     * Exercises the edge in tryAdvanceLeaderCommit where the leader is the sole voter
     * and voterMatches is empty.
     */
    @Test
    fun singleVoter_becomesLeaderAndCommitsImmediately() = runTest(UnconfinedTestDispatcher()) {
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
        assertEquals(1L, entry.index)
    }

    /**
     * Large payload: 256 KB command. CBOR serialization and in-memory transport
     * must handle it without truncation.
     */
    @Test
    fun largePayload_committedCorrectly() = runTest(UnconfinedTestDispatcher()) {
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
    fun emptyCommand_commitsSuccessfully() = runTest(UnconfinedTestDispatcher()) {
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
    fun proposalIndices_areMonotonicallyIncreasing() = runTest(UnconfinedTestDispatcher()) {
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
     * A voter-follower (not a learner) must throw NotLeaderException when propose() is called.
     * Distinct from the learner case in LearnerTest — this confirms voters enforce the same rule.
     */
    @Test
    fun voterFollower_propose_throwsNotLeaderException() = runTest(UnconfinedTestDispatcher()) {
        val ids = listOf(NodeId("f1"), NodeId("f2"), NodeId("f3"))
        val config = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(ids, backgroundScope, fastConfig) { _, transport, storage, nodeScope ->
            nodeScope.raftNode(config, transport, storage, fastConfig)
        }
        awaitLeader(sim)
        val follower = sim.followers().first()
        assertIs<RaftRole.Follower>(follower.role.value)
        assertFailsWith<NotLeaderException> { follower.propose(byteArrayOf(1)) }
    }

    /**
     * Two-voter cluster: quorum = 2. Crashing the follower must prevent further commits
     * because the leader alone cannot satisfy quorum.
     */
    @Test
    fun twoVoter_crashFollower_noProgress() = runTest(UnconfinedTestDispatcher()) {
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
            try { leader.propose(byteArrayOf(99)); committed = true } catch (_: Exception) {}
        }
        delay(100)
        job.cancel()
        assertFalse(committed, "Should not commit with only 1 of 2 voters alive")
    }
}
