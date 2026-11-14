@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
private val voterIds = setOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
private val learnerId = NodeId("learner")
private val clusterConfig = ClusterConfig(voters = voterIds, learners = setOf(learnerId))

private fun TestScope.simWithLearner(): RaftSimulation {
    val config = fastRaftConfig()
    return RaftSimulation(
        nodeIds = voterIds.toList() + learnerId,
        scope = this,
        nodeScope = backgroundScope,
        nodeFactory = { _, transport, storage, nodeScope ->
            nodeScope.raftNode(clusterConfig, transport, storage, config)
        },
    )
}

class LearnerTest {

    @Test
    fun learner_receives_committed_entries() = raftRunTest {
        val sim = simWithLearner()
        val leader = awaitLeader(sim)
        val learner = sim.nodes[learnerId]
        assertNotNull(learner)
        assertIs<RaftRole.Learner>(learner.role.value)
        val received = async {
            learner.committed.filterIsInstance<Committed.Entry>().map { it.entry }
                .filter { it.command.isNotEmpty() }.first()
        }
        delay(1) // let collector subscribe before proposing
        leader.propose(byteArrayOf(42))
        assertContentEquals(byteArrayOf(42), received.await().command)
    }

    @Test
    fun learner_never_becomes_leader() = raftRunTest {
        val sim = simWithLearner()
        awaitLeader(sim)
        assertIs<RaftRole.Learner>(sim.nodes[learnerId]!!.role.value)
    }

    @Test
    fun learner_propose_forwardsToLeaderAndCommits() = raftRunTest {
        // Learner propose forwards to the leader (Raft §8) rather than throwing.
        val sim = simWithLearner()
        awaitLeader(sim)
        val entry = sim.nodes[learnerId]!!.propose(byteArrayOf(1))
        sim.awaitCommit(entry.index, on = voterIds.toList() + learnerId)
    }

    @Test
    fun learner_catchup_after_partition() = raftRunTest {
        val sim = simWithLearner()
        val leader = awaitLeader(sim)
        val learner = sim.nodes[learnerId]!!

        // Partition learner away from voters — quorum (2 of 3) still intact
        sim.partition(setOf(learnerId), voterIds)

        // Commit two entries while learner is isolated
        leader.propose(byteArrayOf(10))
        leader.propose(byteArrayOf(20))

        // Heal — AppendEntries backfill should deliver missed entries to learner
        sim.heal()

        val entries = mutableListOf<LogEntry>()
        val job = launch { learner.committed.collect { if (it is Committed.Entry) entries.add(it.entry) } }
        delay(100)
        job.cancel()

        assertTrue(entries.isNotEmpty(), "Learner received no entries after healing partition")
    }

    /**
     * Dissertation §4.2.1 (non-voting members) and §4.2.3 (disruptive servers). Removing a learner is
     * a learner-set-only change, so the voter set and its quorum are untouched and commit carries on
     * without it.
     *
     * The spec-critical case is a removed learner that applies its own removal: its log then shows it
     * outside the config, and it resolves to Follower. Before #3123 it then campaigned, and against a
     * live leader only leader stickiness stopped it. Since #3123 a node campaigns only while some
     * configuration it cannot rule out names it a **voter** (`promotable`), and no configuration ever
     * named this learner one. So it stays silent even while its removal is uncommitted, the window in
     * which a removed voter still campaigns (§4.2.2, Figure 4.6). This arm hands it the removal with
     * `leaderCommit` below the removal's index, the strongest case, and asserts it never campaigns. The
     * leader normally stops replicating to a removed learner before the removal entry leaves it, so the
     * entry is handed over by hand, and the rig is that the node has applied it and holds it uncommitted.
     *
     * Leader stickiness against a removed node that does campaign, a voter on the old side of a
     * committed joint, is pinned by `RemovedNodeCampaignTest.aNodeOnTheOldSideOfACommittedJointStillCampaigns`.
     * A removed node campaigning while the leader is down is covered by the rest of
     * `RemovedNodeCampaignTest`.
     */
    @Test
    fun aRemovedLearnerThatAppliesItsRemovalNeverDeposesTheLiveLeader() = raftRunTest {
        val sim = simWithLearner()
        val leader = awaitLeader(sim)
        val leaderId = voterIds.first { sim.nodes[it] === leader }
        leader.propose(byteArrayOf(1))

        val campaigns = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sim.nodes.getValue(learnerId).trace
                .filter { it is RaftTraceEvent.PreVoteStarted || it is RaftTraceEvent.Timeout }
                .collect { campaigns += it }
        }

        leader.changeMembership(ClusterConfig(voters = voterIds))
        val termsAfterRemoval = voterIds.associateWith { sim.storages.getValue(it).term() }

        // Hand the removed learner every entry it lacks, its own removal included.
        val have = sim.storages.getValue(learnerId).entries()
        val lastHave = have.lastOrNull()
        val missing = sim.storages.getValue(leaderId).entries().filter { it.index > (lastHave?.index ?: 0L) }
        val removal = missing.last { it.config != null }
        sim.deliverAppendEntries(
            to = learnerId,
            from = leaderId,
            term = termsAfterRemoval.getValue(leaderId),
            prevLogIndex = lastHave?.index ?: 0L,
            prevLogTerm = lastHave?.term ?: 0L,
            entries = missing,
            leaderCommit = removal.index - 1L,
        )
        // The rig fired: the removed node now reads itself as outside the config, and does not know the
        // removal is committed.
        sim.awaitTrue("the removed learner has applied its own removal") {
            sim.nodes.getValue(learnerId).membership.value.let { learnerId !in it.learners && learnerId !in it.voters }
        }
        val removed = sim.nodes.getValue(learnerId)
        assertTrue(
            removed.commitIndex.value < removal.index,
            "rig: the removal at ${removal.index} is uncommitted at the learner (commit ${removed.commitIndex.value})",
        )
        assertIs<RaftRole.Follower>(removed.role.value, "rig: the removed learner resolved to Follower, so its election timer is armed")

        // Many election timeouts of the removed node campaigning against a live leader.
        delay(200)
        val after = leader.propose(byteArrayOf(2))
        sim.awaitCommit(after.index, on = voterIds)
        val termsAtEnd = voterIds.associateWith { sim.storages.getValue(it).term() }

        assertAll(
            { assertEquals(emptyList(), campaigns, "a removed learner never campaigns (#3123)") },
            { assertIs<RaftRole.Leader>(leader.role.value, "the live leader was never deposed") },
            {
                assertEquals(
                    termsAfterRemoval,
                    termsAtEnd,
                    "no voter's term moved: a pre-vote is hypothesis-only",
                )
            },
            { assertEquals(voterIds, leader.membership.value.voters, "the voter set, and so the quorum, is untouched") },
        )
    }
}
