@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.raft.internal.PeerStateKeys
import us.tractat.kuilt.raft.internal.RaftEngine
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val voters = listOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
private val learners = (1..3).map { NodeId("x$it") }

private fun TestScope.pruneSim(): RaftSimulation {
    val config = fastRaftConfig()
    val seated = ClusterConfig(voters = voters.toSet())
    return RaftSimulation(
        nodeIds = voters + learners,
        scope = this,
        nodeScope = backgroundScope,
        nodeFactory = { id, transport, storage, nodeScope ->
            // A learner joins from the learner seed until the leader's config seats it.
            val bootstrap = if (id in learners) ClusterConfig(voters = emptySet(), learners = setOf(id)) else seated
            nodeScope.raftNode(bootstrap, transport, storage, config)
        },
    )
}

private suspend fun RaftNode.peerStateKeys(): PeerStateKeys = (this as RaftEngine).inspectPeerState()

/**
 * #3124 at engine level: a leader that admits peers and then removes them keeps no per-peer state for
 * them. [PeerStatePruneTest] pins each holder's `retain` on its own; this pins that the engine calls
 * it. The cross-term trajectories in [StaleMatchIndexReAddTest] cannot: the reset at election and the
 * prune in `recomputeMembership` each close those alone, so deleting either leaves them green.
 *
 * One leader, no election in between, so `becomeLeader`'s reset never runs and only the prune can
 * drop a key. The learners join after a compaction, so each catches up through InstallSnapshot and
 * also leaves an entry in the snapshot sender.
 */
class PeerStatePruneEngineTest {

    @Test
    fun aLeaderKeepsNoPerPeerStateForPeersItRemoved() = raftRunTest {
        val sim = pruneSim()
        val leader = sim.awaitLeader(among = voters.toSet())
        val leaderId = voters.first { sim.nodes[it] === leader }
        val otherVoters = voters.toSet() - leaderId
        val term = sim.storages.getValue(leaderId).term()

        // Compact past everything a fresh learner holds, so admission must go through InstallSnapshot.
        repeat(10) { leader.propose(byteArrayOf(it.toByte())) }
        val through = sim.compactionFloorCandidate(leaderId)
        leader.snapshots.value = Snapshot(through, sim.stateBytes(leaderId, through))
        leader.compactionFloor.first { it == through }

        val installs = learners.associateWith { sim.collectInstalls(it) }
        sim.changeMembershipOnLeader(ClusterConfig(voters = voters.toSet(), learners = learners.toSet()))
        val admitted = leader.propose("caught-up".encodeToByteArray())
        sim.awaitCommit(admitted.index, on = learners)
        val beforeRemoval = leader.peerStateKeys()

        // The rig fired: every learner was admitted, caught up through a snapshot, and is tracked.
        assertAll(
            { assertEquals(otherVoters + learners, leader.membership.value.allMembers - leaderId, "rig: learners admitted") },
            { assertTrue(installs.values.all { it.isNotEmpty() }, "rig: every learner installed a snapshot: ${installs.mapValues { it.value.size }}") },
            { assertEquals(otherVoters + learners, beforeRemoval.nextIndex, "rig: nextIndex tracks the learners") },
            { assertEquals(otherVoters + learners, beforeRemoval.matchIndex, "rig: matchIndex tracks the learners") },
            { assertTrue(beforeRemoval.snapshotPeers.containsAll(learners), "rig: the snapshot sender tracks the learners: $beforeRemoval") },
            { assertTrue(beforeRemoval.readAckPeers.containsAll(learners), "rig: read-ack evidence tracks the learners: $beforeRemoval") },
        )

        sim.changeMembershipOnLeader(ClusterConfig(voters = voters.toSet()))
        // A heartbeat round or two, so any late response from a removed learner has landed.
        val after = leader.propose("after-removal".encodeToByteArray())
        sim.awaitCommit(after.index, on = voters)
        val afterRemoval = leader.peerStateKeys()
        val termAtEnd = sim.storages.getValue(leaderId).term()

        assertAll(
            { assertIs<RaftRole.Leader>(leader.role.value, "one leader throughout, so only the prune can drop a key") },
            { assertEquals(term, termAtEnd, "no election in between: the reset never ran") },
            { assertEquals(otherVoters, leader.membership.value.allMembers - leaderId, "the learners are out of the config") },
            { assertEquals(otherVoters, afterRemoval.nextIndex, "nextIndex is keyed by the replication targets") },
            { assertEquals(otherVoters, afterRemoval.matchIndex, "matchIndex is keyed by the replication targets") },
            { assertTrue(otherVoters.containsAll(afterRemoval.snapshotPeers), "snapshot sender: ${afterRemoval.snapshotPeers}") },
            { assertTrue(otherVoters.containsAll(afterRemoval.readAckPeers), "read-ack evidence: ${afterRemoval.readAckPeers}") },
        )
    }
}
