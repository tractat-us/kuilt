@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }

// ── Node IDs ─────────────────────────────────────────────────────────────────

private val v1 = NodeId("v1")
private val v2 = NodeId("v2")
private val v3 = NodeId("v3")
private val learnerNode = NodeId("L1")

private val voterSet = setOf(v1, v2, v3)

/**
 * Build a [RaftSimulation] where the 3 voters boot with a voters-only config and the
 * learner boots present-from-start with a config listing itself as a learner of those
 * voters. This is the correct per-node-bootstrap approach: the learner never participates
 * in the voters' quorum, and [RaftNode.changeMembership] is what wires the learner into
 * the voters' effective membership — that is the behavior under test.
 */
private fun TestScope.simWithVotersAndBootstrappedLearner(): RaftSimulation {
    val voterConfig = ClusterConfig(voters = voterSet)
    val learnerConfig = ClusterConfig(voters = voterSet, learners = setOf(learnerNode))
    return RaftSimulation(
        nodeIds = voterSet.toList() + learnerNode,
        scope = this,
        raftConfig = FAST_RAFT_CONFIG,
        nodeScope = backgroundScope,
        nodeFactory = { id, transport, storage, nodeScope ->
            val config = if (id == learnerNode) learnerConfig else voterConfig
            nodeScope.raftNode(config, transport, storage, FAST_RAFT_CONFIG)
        },
    )
}

class MembershipTest {

    /**
     * A1: 3 voters elect a leader, then [RaftNode.changeMembership] adds a learner.
     *
     * Assertions:
     * 1. The leader emits a [RaftTraceEvent.ConfigChange] event for the new config.
     * 2. After the config entry commits, the learner receives committed entries on its
     *    [RaftNode.committed] flow (replication began because the leader added it to
     *    its effective replication target set).
     * 3. Commit still requires only the original voter majority (3 voters → quorum 2):
     *    the learner is excluded from quorum, so proposals still commit without the
     *    learner's explicit ack.
     *
     * This test FAILS before A2 is implemented because [RaftNode.changeMembership]
     * does not exist (it will throw [NotImplementedError] or fail to compile).
     */
    @Test
    fun addLearner_receivesCommittedEntries_andExcludedFromQuorum() = raftRunTest {
        val sim = simWithVotersAndBootstrappedLearner()
        val leader = awaitLeader(sim)

        val learner = sim.nodes[learnerNode]
        assertNotNull(learner)
        assertIs<RaftRole.Learner>(learner.role.value)

        // Collect ConfigChange trace events from the leader's trace
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val leaderNode = sim.nodes.getValue(leaderId)
        val configChanges = mutableListOf<RaftTraceEvent.ConfigChange>()
        backgroundScope.launch {
            leaderNode.trace.filterIsInstance<RaftTraceEvent.ConfigChange>().collect { configChanges += it }
        }
        delay(1) // let collector subscribe

        // Add the learner to the cluster via changeMembership
        val targetConfig = ClusterConfig(voters = voterSet, learners = setOf(learnerNode))
        val committedConfig = leader.changeMembership(targetConfig)

        // changeMembership suspends until the config entry commits and returns the new config
        assertEquals(targetConfig.voters, committedConfig.voters)
        assertEquals(targetConfig.learners, committedConfig.learners)

        // A ConfigChange trace event must have been emitted for the config entry append
        assertTrue(configChanges.isNotEmpty(),
            "expected a ConfigChange trace event after changeMembership, got none")
        val configChange = configChanges.first()
        assertEquals(targetConfig.voters, configChange.new.voters)
        assertEquals(targetConfig.learners, configChange.new.learners)

        // Now propose an entry — the learner should receive it via replication
        val receivedByLearner = async {
            learner.committed
                .filterIsInstance<Committed.Entry>()
                .map { it.entry }
                .first { it.command.isNotEmpty() }
        }
        delay(1) // let collector subscribe before proposing
        leader.propose(byteArrayOf(0x42))

        val learnerEntry = receivedByLearner.await()
        assertEquals(0x42.toByte(), learnerEntry.command[0],
            "learner received an entry with unexpected command byte")

        // Commit required only voter majority: partition the learner away and confirm
        // the voters can still commit — learner is NOT in the quorum. propose() suspends
        // until commit, so this proves the entry committed on the 3 voters alone (quorum=2);
        // were the learner wrongly in quorum, propose() would never return and the test
        // would time out. Make the advance explicit rather than asserting the always-true
        // `commitIndex > 0`.
        val commitBeforePartition = leader.commitIndex.value
        sim.partition(setOf(learnerNode), voterSet)
        val committedEntry = leader.propose(byteArrayOf(0x43))
        assertAll(
            { assertTrue(committedEntry.index > commitBeforePartition,
                "post-partition entry index ${committedEntry.index} did not advance past pre-partition commit $commitBeforePartition") },
            { assertTrue(leader.commitIndex.value >= committedEntry.index,
                "entry ${committedEntry.index} did not commit on the voter majority while the learner was partitioned (learner wrongly in quorum)") },
        )
    }

    /**
     * Adopt-on-append is observable on followers, not just the leader: a follower that
     * receives the config entry via AppendEntries recomputes its membership and emits its
     * own [RaftTraceEvent.ConfigChange]. This is the contract the rest of the stack relies
     * on to observe follower-side membership transitions (config/term are private), so it is
     * pinned here.
     */
    @Test
    fun addLearner_followerAlsoEmitsConfigChange() = raftRunTest {
        val sim = simWithVotersAndBootstrappedLearner()
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        // Pick a voter that is NOT the leader and subscribe to its trace before the change.
        val followerId = voterSet.first { it != leaderId }
        val follower = sim.nodes.getValue(followerId)
        val followerConfigChanges = mutableListOf<RaftTraceEvent.ConfigChange>()
        backgroundScope.launch {
            follower.trace.filterIsInstance<RaftTraceEvent.ConfigChange>().collect { followerConfigChanges += it }
        }
        delay(1) // let the collector subscribe before the change

        val targetConfig = ClusterConfig(voters = voterSet, learners = setOf(learnerNode))
        leader.changeMembership(targetConfig)
        // Let the config entry replicate to the follower and be adopted on append.
        sim.awaitCommit(leader.commitIndex.value, on = voterSet)

        val adopted = followerConfigChanges.firstOrNull { it.new.learners == targetConfig.learners }
        assertNotNull(adopted,
            "follower $followerId never emitted a ConfigChange for the adopted config (adopt-on-append not observable)")
        assertAll(
            { assertEquals(followerId, adopted.node) },
            { assertEquals(targetConfig.voters, adopted.new.voters) },
            { assertEquals(targetConfig.learners, adopted.new.learners) },
        )
    }

    /**
     * changeMembership is a leader-only operation: a follower throws [NotLeaderException].
     */
    @Test
    fun changeMembership_onFollower_throwsNotLeaderException() = raftRunTest {
        val sim = simWithVotersAndBootstrappedLearner()
        awaitLeader(sim)

        val follower = sim.nodes.values.first { it.role.value is RaftRole.Follower }
        val targetConfig = ClusterConfig(voters = voterSet, learners = setOf(learnerNode))

        assertFailsWith<NotLeaderException> { follower.changeMembership(targetConfig) }
    }

    /**
     * changeMembership rejects empty voter sets with [IllegalArgumentException].
     */
    @Test
    fun changeMembership_emptyVoters_throwsIllegalArgumentException() = raftRunTest {
        val sim = simWithVotersAndBootstrappedLearner()
        val leader = awaitLeader(sim)

        assertFailsWith<IllegalArgumentException> {
            leader.changeMembership(ClusterConfig(voters = emptySet()))
        }
    }

    /**
     * changeMembership rejects a voter-set change (PR A only supports learner-set changes).
     * A voter-set change throws [IllegalArgumentException] with a clear message.
     */
    @Test
    fun changeMembership_voterSetChange_rejectedInPrA() = raftRunTest {
        val sim = simWithVotersAndBootstrappedLearner()
        val leader = awaitLeader(sim)

        val newVoter = NodeId("v4")
        val targetConfig = ClusterConfig(voters = voterSet + newVoter)

        assertFailsWith<IllegalArgumentException> {
            leader.changeMembership(targetConfig)
        }
    }
}
