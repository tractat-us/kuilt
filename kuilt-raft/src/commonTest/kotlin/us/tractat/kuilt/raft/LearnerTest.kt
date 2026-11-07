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
     * outside the config. Today such a node resolves to Follower and campaigns, and against a live
     * leader the only thing stopping it is leader stickiness, a voter that has heard from the leader
     * denying the pre-vote. Whether it should campaign at all is a separate question; this arm asserts
     * the outcome either way. The leader normally stops replicating to a removed learner before the
     * removal entry leaves it, so the entry is handed over by hand, and the rig is that the node has
     * applied it.
     *
     * Not covered: a removed node campaigning while the leader is down, where stickiness has lapsed.
     */
    @Test
    fun aRemovedLearnerThatAppliesItsRemovalNeverDeposesTheLiveLeader() = raftRunTest {
        val sim = simWithLearner()
        val leader = awaitLeader(sim)
        val leaderId = voterIds.first { sim.nodes[it] === leader }
        leader.propose(byteArrayOf(1))

        val deniedToLearner = mutableListOf<RaftTraceEvent.PreVoteDenied>()
        voterIds.forEach { id ->
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                sim.nodes.getValue(id).trace.filterIsInstance<RaftTraceEvent.PreVoteDenied>()
                    .filter { it.to == learnerId }
                    .collect { deniedToLearner += it }
            }
        }

        leader.changeMembership(ClusterConfig(voters = voterIds))
        val termsAfterRemoval = voterIds.associateWith { sim.storages.getValue(it).term() }

        // Hand the removed learner every entry it lacks, its own removal included.
        val have = sim.storages.getValue(learnerId).entries()
        val lastHave = have.lastOrNull()
        val missing = sim.storages.getValue(leaderId).entries().filter { it.index > (lastHave?.index ?: 0L) }
        sim.deliverAppendEntries(
            to = learnerId,
            from = leaderId,
            term = termsAfterRemoval.getValue(leaderId),
            prevLogIndex = lastHave?.index ?: 0L,
            prevLogTerm = lastHave?.term ?: 0L,
            entries = missing,
            leaderCommit = leader.commitIndex.value,
        )
        // The rig fired: the removed node now reads itself as outside the config.
        sim.awaitTrue("the removed learner has applied its own removal") {
            sim.nodes.getValue(learnerId).membership.value.let { learnerId !in it.learners && learnerId !in it.voters }
        }

        // Many election timeouts of the removed node campaigning against a live leader.
        delay(200)
        val after = leader.propose(byteArrayOf(2))
        sim.awaitCommit(after.index, on = voterIds)
        val termsAtEnd = voterIds.associateWith { sim.storages.getValue(it).term() }

        assertAll(
            {
                assertTrue(
                    deniedToLearner.all { DenyReason.LeaderAlive in it.reasons },
                    "any campaign it ran was denied by leader stickiness: ${deniedToLearner.map { it.reasons }}",
                )
            },
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
