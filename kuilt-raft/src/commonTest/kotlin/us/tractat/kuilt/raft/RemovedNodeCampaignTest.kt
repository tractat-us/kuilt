@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * When a node outside the membership may start an election (#3123).
 *
 * Dissertation §4.2.2, last paragraph: "a server that is not part of its own latest configuration should
 * still start new elections, as it might still be needed until the Cnew entry is committed (as in Figure
 * 4.6). It does not count its own vote in elections unless it is part of its latest configuration." So the
 * point at which a removed node must stop campaigning is the commit of a configuration that excludes it,
 * not its append: §4.2.2 calls that commit "the first point when the new configuration can definitely
 * operate without participation from the removed leader". §4.1 keeps the node answering votes and appends
 * either way, and §4.3 makes a node on either side of a joint configuration a legitimate candidate.
 *
 * Each arm counts campaign attempts, a [RaftTraceEvent.PreVoteStarted] or a [RaftTraceEvent.Timeout]
 * (a real election's start) emitted by the node under test, so an arm that asserts an absence first
 * shows that its rig reached the state, and an arm that asserts a presence fails if the node goes quiet.
 */
class RemovedNodeCampaignTest {

    private val v1 = NodeId("v1")
    private val v2 = NodeId("v2")
    private val v3 = NodeId("v3")
    private val v4 = NodeId("v4")

    /** Campaign attempts and leaderships per node, recorded from the moment [watch] is called. */
    private class CampaignLog {
        val attempts = mutableMapOf<NodeId, Int>()
        val becameLeader = mutableSetOf<NodeId>()
        fun attemptsBy(id: NodeId): Int = attempts[id] ?: 0
        fun reset() { attempts.clear(); becameLeader.clear() }
    }

    private fun TestScope.watch(sim: RaftSimulation, ids: Collection<NodeId>): CampaignLog {
        val log = CampaignLog()
        ids.forEach { id ->
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                sim.nodes.getValue(id).trace.collect { e ->
                    when (e) {
                        is RaftTraceEvent.PreVoteStarted -> log.attempts[e.node] = log.attemptsBy(e.node) + 1
                        is RaftTraceEvent.Timeout -> log.attempts[e.node] = log.attemptsBy(e.node) + 1
                        is RaftTraceEvent.BecomeLeader -> log.becameLeader += e.node
                        else -> Unit
                    }
                }
            }
        }
        return log
    }

    /**
     * Hand [to] every entry of [leaderId]'s log it lacks, with the leader's commit index, so [to] applies a
     * configuration the leader would no longer replicate to it and learns that it is committed. The leader
     * stops replicating to a removed node in the same step that appends its removal, so the honest path
     * never delivers it; a lagging in-flight batch or a restart from that log are the ways a real node gets
     * here.
     */
    private suspend fun RaftSimulation.handOverTail(to: NodeId, leaderId: NodeId) {
        val have = storages.getValue(to).entries().lastOrNull()
        val missing = storages.getValue(leaderId).entries().filter { it.index > (have?.index ?: 0L) }
        deliverAppendEntries(
            to = to,
            from = leaderId,
            term = storages.getValue(leaderId).term(),
            prevLogIndex = have?.index ?: 0L,
            prevLogTerm = have?.term ?: 0L,
            entries = missing,
            leaderCommit = nodes.getValue(leaderId).commitIndex.value,
        )
    }

    /**
     * A removed voter that knows its removal is committed never campaigns, even with the leader down and
     * stickiness lapsed, and the remaining members elect a leader among themselves.
     *
     * Before #3123 it resolved to Follower, kept its election timer, and ran PreVote against the
     * leaderless cluster, whose voters' logs were no more up to date than its own, so it could win and
     * lead a membership that excludes it.
     */
    @Test
    fun aNodeWhoseRemovalIsCommittedNeverCampaignsWhenTheLeaderIsDown() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 4)
        val all = listOf(v1, v2, v3, v4)
        val leader = sim.awaitLeader(among = setOf(v1, v2, v3))
        val leaderId = all.first { sim.nodes[it] === leader }
        val remaining = setOf(v1, v2, v3)
        sim.changeMembershipOnLeader(ClusterConfig(voters = remaining))

        sim.handOverTail(to = v4, leaderId = leaderId)
        sim.awaitTrue("v4 has applied its removal and knows it is committed") {
            val v4Node = sim.nodes.getValue(v4)
            v4 !in v4Node.membership.value.voters && v4Node.commitIndex.value >= leader.commitIndex.value
        }

        val log = watch(sim, all)
        sim.crash(leaderId)
        val survivors = remaining - leaderId
        val newLeader = sim.awaitLeader(among = survivors)
        // Many of v4's election timeouts with no leader heartbeat and stickiness lapsed.
        delay(200)

        assertAll(
            { assertEquals(0, log.attemptsBy(v4), "v4 started no PreVote and no election") },
            { assertTrue(v4 !in log.becameLeader, "v4 never became leader") },
            { assertIs<RaftRole.Follower>(sim.nodes.getValue(v4).role.value, "v4 still rests as a follower") },
            { assertTrue(newLeader.role.value is RaftRole.Leader, "a member of C_new leads") },
            { assertTrue(survivors.any { log.attemptsBy(it) > 0 }, "the rig ran: the survivors did campaign") },
        )
    }

    /**
     * Dissertation Figure 4.6, after a crash: removing leader v1 from {v1, v2}. The joint entry reached
     * both, the trailing Simple({v2}) reached only v1, and both restarted. v2 still needs v1's vote for a
     * majority of C_old and v1 denies it, since v2's log is behind, so v1 is the only node that can make
     * progress. v1 must campaign although its latest configuration excludes it, because that
     * configuration is not committed. It wins on v2's vote alone, commits Simple({v2}) and steps down, and
     * v2 then leads {v2}.
     *
     * This pins the gate to the commit of the removal rather than its append: gating on the latest
     * configuration alone wedges this cluster permanently.
     */
    @Test
    fun aNodeWhoseRemovalIsUncommittedStillCampaignsAndUnwedgesFigure4_6() = raftRunTest {
        val config = fastRaftConfig()
        val sim = RaftSimulation(
            nodeIds = listOf(v1, v2),
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { _, transport, storage, nodeScope ->
                nodeScope.raftNode(ClusterConfig(voters = setOf(v1, v2)), transport, storage, config)
            },
        )
        // Both nodes stop before running anything, and restart from the crafted durable state.
        sim.crash(v1)
        sim.crash(v2)
        val joint = LogEntry(1, 1, byteArrayOf(), config = ConfigPayload(ClusterConfig(setOf(v1, v2)), ClusterConfig(setOf(v2))))
        val simple = LogEntry(2, 1, byteArrayOf(), config = ConfigPayload(null, ClusterConfig(setOf(v2))))
        sim.storages.getValue(v1).apply { saveTermAndVotedFor(1, v1); appendEntries(listOf(joint, simple)) }
        sim.storages.getValue(v2).apply { saveTermAndVotedFor(1, v1); appendEntries(listOf(joint)) }
        sim.restart(v1)
        sim.restart(v2)
        val log = watch(sim, listOf(v1, v2))

        sim.awaitTrue("v1 led once, committed its removal and stood down") {
            v1 in log.becameLeader && sim.nodes.getValue(v1).role.value !is RaftRole.Leader &&
                sim.nodes.getValue(v1).commitIndex.value >= simple.index
        }
        val v2Leader = sim.awaitLeader(among = setOf(v2))
        log.reset()
        // v1 now knows its removal is committed: many election timeouts with v2 leading.
        delay(200)

        assertAll(
            { assertEquals(setOf(v2), v2Leader.membership.value.voters, "v2 leads C_new = {v2}") },
            { assertEquals(0, log.attemptsBy(v1), "once its removal committed, v1 stopped campaigning") },
            { assertTrue(v1 !in log.becameLeader, "and never led again") },
        )
    }

    /**
     * A node on the old side only of a committed joint configuration is still a member (§4.3: either side
     * may be elected), so it keeps campaigning.
     *
     * v3 is being removed: Joint(old = {v1, v2, v3}, new = {v1, v2}) commits, the leader appends
     * Simple({v1, v2}) and stops replicating to v3 in the same step. v3 is then told the joint entry is
     * committed and nothing more. With no heartbeat it times out; the live leader's stickiness denies it.
     * A gate that read only C_new, or that treated the committed joint as excluding v3, would silence it.
     */
    @Test
    fun aNodeOnTheOldSideOfACommittedJointStillCampaigns() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val all = listOf(v1, v2, v3)
        val leader = sim.awaitLeader(among = setOf(v1, v2))
        val leaderId = all.first { sim.nodes[it] === leader }
        sim.changeMembershipOnLeader(ClusterConfig(voters = setOf(v1, v2)))

        val jointIndex = sim.storages.getValue(leaderId).entries().last { it.config?.old != null }.index
        val jointEntries = sim.storages.getValue(leaderId).entries().filter { it.index <= jointIndex }
        val have = sim.storages.getValue(v3).entries().lastOrNull()
        sim.deliverAppendEntries(
            to = v3,
            from = leaderId,
            term = sim.storages.getValue(leaderId).term(),
            prevLogIndex = have?.index ?: 0L,
            prevLogTerm = have?.term ?: 0L,
            entries = jointEntries.filter { it.index > (have?.index ?: 0L) },
            leaderCommit = jointIndex,
        )
        sim.awaitTrue("v3 holds the committed joint entry as its latest configuration") {
            val v3Node = sim.nodes.getValue(v3)
            v3Node.commitIndex.value >= jointIndex && v3 !in v3Node.membership.value.voters
        }
        val log = watch(sim, all)
        delay(200)

        assertAll(
            { assertTrue(log.attemptsBy(v3) > 0, "v3 kept campaigning: ${log.attempts}") },
            { assertIs<RaftRole.Leader>(leader.role.value, "stickiness kept the live leader") },
        )
    }

    /**
     * A node whose removal committed and which is then added back campaigns again. The gate is read
     * from live state at each election timeout, so there is no disarmed timer to re-arm.
     *
     * The node also proves the check reads the latest configuration and not only the committed one: its
     * re-admission is first appended, then committed, and it is a candidate from the append on.
     */
    @Test
    fun aNodeRemovedThenReAddedCampaignsAgain() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 4)
        val all = listOf(v1, v2, v3, v4)
        val leader = sim.awaitLeader(among = setOf(v1, v2, v3))
        val leaderId = all.first { sim.nodes[it] === leader }
        sim.changeMembershipOnLeader(ClusterConfig(voters = setOf(v1, v2, v3)))
        sim.handOverTail(to = v4, leaderId = leaderId)
        sim.awaitTrue("v4 knows its removal is committed") {
            val v4Node = sim.nodes.getValue(v4)
            v4 !in v4Node.membership.value.voters && v4Node.commitIndex.value >= leader.commitIndex.value
        }
        val log = watch(sim, all)
        delay(100)
        val whileRemoved = log.attemptsBy(v4)

        sim.changeMembershipOnLeader(ClusterConfig(voters = setOf(v1, v2, v3, v4)))
        sim.awaitTrue("v4 is a voter again") { v4 in sim.nodes.getValue(v4).membership.value.voters }
        log.reset()
        // Cut v4 off so it hears no heartbeat: its election timer is then the only thing that can move it.
        sim.partitionOff(v4)
        delay(200)

        assertAll(
            { assertEquals(0, whileRemoved, "while removed, v4 was silent") },
            { assertTrue(log.attemptsBy(v4) > 0, "re-admitted, v4 campaigns again: ${log.attempts}") },
        )
    }
}
