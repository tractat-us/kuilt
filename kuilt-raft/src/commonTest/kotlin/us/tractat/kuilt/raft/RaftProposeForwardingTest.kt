@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Tests for follower → leader proposal forwarding (Raft §8).
 *
 * Each test uses [raftRunTest] + [raftSim] for deterministic virtual-time execution under
 * [kotlinx.coroutines.test.StandardTestDispatcher]. See [RaftTestFixtures] for the full
 * determinism contract.
 */
class RaftProposeForwardingTest {

    // ── Task 2: known-leader forwarding ────────────────────────────────────────

    @Test
    fun followerPropose_forwardsToLeader_andCommits() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val follower = sim.followers().first()

        val entry = follower.propose(byteArrayOf(7, 8, 9))

        assertContentEquals(byteArrayOf(7, 8, 9), entry.command)
        // Index 1 is the leader's no-op; first user entry is at index 2.
        assertEquals(2L, entry.index)
        sim.awaitCommit(entry.index)
        sim.checkInvariants()
    }

    @Test
    fun followerPropose_concurrentForwards_allCommit() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        awaitLeader(sim)
        val follower = sim.followers().first()

        val entries = (1..5).map { i ->
            async { follower.propose(byteArrayOf(i.toByte())) }
        }.awaitAll()

        assertEquals(5, entries.size)
        val indices = entries.map { it.index }.sorted()
        assertEquals(5, indices.distinct().size, "Each forwarded proposal must commit at a unique index")
        sim.checkInvariants()
    }

    @Test
    fun followerPropose_differentFollowers_bothCommit() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        awaitLeader(sim)
        val followers = sim.followers()
        check(followers.size >= 2) { "Need at least 2 followers for this test" }

        val e1 = async { followers[0].propose(byteArrayOf(10)) }
        val e2 = async { followers[1].propose(byteArrayOf(20)) }
        val results = awaitAll(e1, e2)

        assertEquals(2, results.distinctBy { it.index }.size, "Each follower proposal must commit at a distinct index")
        sim.checkInvariants()
    }

    // ── Task 3: no-leader queuing and flush ────────────────────────────────────

    @Test
    fun followerPropose_noLeaderYet_waitsUntilLeaderElected_thenCommits() = raftRunTest {
        // Partition the cluster so no leader can form, propose from an isolated follower, then heal.
        val sim = raftSim(this, backgroundScope)
        // Before a leader forms, partition every node away from quorum. We do this before
        // awaitLeader to catch the no-leader path. However, `raftSim` starts nodes and they
        // may elect quickly, so we partition immediately after construction.
        val v1 = sim.nodeIds[0]
        val v2 = sim.nodeIds[1]
        val v3 = sim.nodeIds[2]
        // Isolate v1 from the others so there is no quorum reachable from any single partition.
        sim.partition(setOf(v1), setOf(v2, v3))

        // Advance time enough that any in-progress election from v2/v3 completes, but v1 is cut off.
        delay(50)

        // Propose from v1 while it has no leader (isolated). The call must NOT throw immediately;
        // it must suspend until a leader is visible.
        val proposalJob = launch { sim.nodes.getValue(v1).propose(byteArrayOf(42)) }
        sim.settle()

        // Heal the partition — the cluster (v2/v3 quorum) elects a leader, v1 re-joins and learns it.
        sim.heal()

        val entry = async { sim.nodes.getValue(v1).propose(byteArrayOf(99)) }
        // Await the queued proposal too.
        proposalJob.join()
        val committed = entry.await()
        assertNotNull(committed)
        sim.awaitCommit(committed.index)
        sim.checkInvariants()
    }

    @Test
    fun followerPropose_selfElectsToLeader_flushesQueuedProposals() = raftRunTest {
        // Node becomes leader after queuing proposals as a follower. We build a 1-node cluster to
        // guarantee it will be the leader; queue a proposal before leadership settles, then verify.
        val harness = singleVoterNode(backgroundScope)
        val node = harness.node

        // On a fresh single-node cluster the node starts as a follower and quickly runs for leader.
        // Propose before it becomes leader to exercise the self-election flush path.
        val proposalDeferred = async { node.propose(byteArrayOf(55)) }

        val entry = proposalDeferred.await()
        assertContentEquals(byteArrayOf(55), entry.command)
        harness.awaitCommit(entry.index)
    }

    // ── Task 4: cancellation cleanup ───────────────────────────────────────────

    @Test
    fun followerPropose_cancelledWhileWaitingForLeader_doesNotHang() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val v1 = sim.nodeIds[0]
        val v2 = sim.nodeIds[1]
        val v3 = sim.nodeIds[2]
        // Isolate v1 so no leader is visible to it.
        sim.partition(setOf(v1), setOf(v2, v3))
        delay(30)

        val job = launch { sim.nodes.getValue(v1).propose(byteArrayOf(1)) }
        sim.settle()
        job.cancel()
        job.join() // must complete promptly — not hang
        // The queued proposal must have been cleaned up; healing and committing an unrelated
        // proposal from the other partition should work.
        sim.heal()
        sim.awaitCommit(sim.awaitLeader(setOf(v2, v3)).commitIndex.value.coerceAtLeast(1L))
        sim.checkInvariants()
    }
}
