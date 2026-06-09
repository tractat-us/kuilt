@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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
import kotlin.time.Duration.Companion.seconds

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }

// ── Node IDs ─────────────────────────────────────────────────────────────────

private val v1 = NodeId("v1")
private val v2 = NodeId("v2")
private val v3 = NodeId("v3")
private val learnerNode = NodeId("L1")
private val learnerNode2 = NodeId("L2")

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

private val twoLearners = setOf(learnerNode, learnerNode2)
private val fiveVoters = voterSet + twoLearners

/**
 * Build a [RaftSimulation] with 3 voters + 2 learners present from start.
 *
 * Each learner bootstraps with a config that lists it as a learner of the 3 voters —
 * the same pattern as [simWithVotersAndBootstrappedLearner], generalised to two learners.
 * The voter nodes start with a voters-only config; the learners are wired in via
 * [RaftNode.changeMembership] (the behavior under test).
 */
private fun TestScope.simWithVotersAndTwoBootstrappedLearners(): RaftSimulation {
    val voterConfig = ClusterConfig(voters = voterSet)
    val learnerConfig = ClusterConfig(voters = voterSet, learners = twoLearners)
    return RaftSimulation(
        nodeIds = voterSet.toList() + learnerNode + learnerNode2,
        scope = this,
        raftConfig = FAST_RAFT_CONFIG,
        nodeScope = backgroundScope,
        nodeFactory = { id, transport, storage, nodeScope ->
            val config = if (id in twoLearners) learnerConfig else voterConfig
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
     * B1 — FAILING TEST: grow a 3-voter cluster to 5 voters via §6 joint consensus.
     *
     * Setup:
     * - 3 voters (v1, v2, v3) boot with a voters-only config.
     * - 2 learner nodes (L1, L2) are present from the start with a config listing them as
     *   learners of the 3 voters (same bootstrap pattern as [simWithVotersAndBootstrappedLearner]).
     * - The leader first calls changeMembership to wire the 2 learners into the voters' effective
     *   membership (learner-set-only change — PR A path). This lets the learners replicate the log.
     * - After the learners are caught up, the leader calls changeMembership to promote both to
     *   voters (voter-set change — the joint consensus PR B path that FAILS today).
     *
     * Joint-phase observation strategy:
     * - Collect ConfigChange trace events from the leader over the whole voter promotion.
     * - A voter-set change produces TWO ConfigChange events:
     *     1. Joint(old=C_old, new=C_new) adopted on append → effectiveConfig = C_new voters.
     *     2. Simple(C_new) adopted on append when onConfigCommitted appends C_new → same effectiveConfig.
     *   Both events report new.voters == fiveVoters. To distinguish joint from simple we assert
     *   EXACTLY TWO ConfigChange events fired for the voter promotion (one Joint append, one Simple append),
     *   rather than racing to catch a transient internal membership representation.
     * - The behavioral dual-majority assertion: changeMembership suspends until C_new commits,
     *   so its return proves C_new committed. After that, assert any 3-of-5 partition can still
     *   elect a leader and commit entries; confirm a partition leaving only 2 nodes cannot.
     *
     * Made to pass by the joint-consensus wiring in [onChangeMembership] / [onConfigCommitted].
     */
    @Test
    fun grow3VotersTo5_viaJointConsensus() = raftRunTest(timeout = 10.seconds) {
        val sim = simWithVotersAndTwoBootstrappedLearners()
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        // Step 1: wire both learners into the voters' effective membership (PR A learner-set path).
        val withLearnersConfig = ClusterConfig(voters = voterSet, learners = twoLearners)
        leader.changeMembership(withLearnersConfig)
        // Learners are now being replicated to. Wait until they're caught up to the leader's
        // commit index before promoting — ensures the new majority is reachable immediately.
        sim.awaitCommit(leader.commitIndex.value, on = voterSet + twoLearners)

        // Collect ConfigChange events on the leader for the voter-promotion step.
        val configChanges = mutableListOf<RaftTraceEvent.ConfigChange>()
        backgroundScope.launch {
            sim.nodes.getValue(leaderId).trace
                .filterIsInstance<RaftTraceEvent.ConfigChange>()
                .collect { configChanges += it }
        }
        delay(1) // let collector register before the promotion

        // Step 2: promote both learners to voters via joint consensus. FAILS today.
        val fiveVoterConfig = ClusterConfig(voters = fiveVoters)
        val result = leader.changeMembership(fiveVoterConfig)

        // changeMembership suspends until C_new commits — if we get here, the transition
        // completed end-to-end.
        assertAll(
            { assertEquals(fiveVoters, result.voters, "returned config must have all 5 voters") },
            { assertTrue(result.learners.isEmpty(), "no learners in C_new") },
        )

        // Two ConfigChange events for the voter promotion: Joint adopted, then Simple(C_new) adopted.
        // Both report new.voters == fiveVoters (effectiveConfig is always C_new during joint phase).
        assertTrue(configChanges.size >= 2,
            "expected at least 2 ConfigChange events for a voter-set change (Joint + Simple), " +
                "got ${configChanges.size}: $configChanges")
        configChanges.forEach { change ->
            assertEquals(fiveVoters, change.new.voters,
                "ConfigChange.new.voters must be the 5-voter set at every step; got ${change.new.voters}")
        }

        // Behavioral: after C_new commits, a partition leaving any 3 of the 5 can commit.
        // We use the 3 original voters as the surviving partition.
        sim.awaitCommit(leader.commitIndex.value, on = fiveVoters) // all 5 caught up
        val partitionedOut = twoLearners // L1 and L2 are isolated
        val survivingVoters = voterSet   // v1, v2, v3 stay connected
        sim.partition(partitionedOut, survivingVoters)

        // A leader must emerge among the survivors (3-of-5 = quorum) and be able to commit. We scope
        // the await to survivingVoters: the pre-partition leader may itself be one of the now-isolated
        // promoted voters (L1/L2), transiently still in Leader role until its CheckQuorum step-down —
        // it cannot commit (no 3-of-5 quorum), but awaitLeader() unscoped would race and return it.
        val newLeader = sim.awaitLeader(among = survivingVoters)

        val committedEntry = newLeader.propose(byteArrayOf(0xBE.toByte()))
        assertTrue(committedEntry.index > 0L, "entry must commit on the surviving 3-of-5 partition")

        // Dual-majority negative: the isolated 2-of-5 partition {L1, L2} cannot commit the new entry —
        // it never reaches their logs, so their commitIndex stays below it. (Deterministic: they are
        // partitioned away from the leader, not merely slow.)
        partitionedOut.forEach { id ->
            assertTrue(sim.nodes.getValue(id).commitIndex.value < committedEntry.index,
                "$id is isolated in a 2-of-5 partition and must not commit ${committedEntry.index}")
        }
    }

    /**
     * B3 — promote a learner to a voter. A learner-set change wires L1 in (PR A path); then a
     * voter-set change promotes it to a voter via joint consensus.
     *
     * Asserts the §6 + Fix-#5 behavior: once C_new commits, L1 leaves [RaftRole.Learner] (it is
     * election-eligible — `reevaluateSelfRole` arms its election timer on the Learner→voter flip,
     * rather than leaving it passive), and the 4-voter cluster commits with L1 counted in the new
     * quorum (quorum of 4 = 3).
     */
    @Test
    fun promoteLearnerToVoter_leavesLearnerRole_andJoinsQuorum() = raftRunTest(timeout = 10.seconds) {
        val sim = simWithVotersAndBootstrappedLearner()
        awaitLeader(sim)
        assertIs<RaftRole.Learner>(sim.nodes.getValue(learnerNode).role.value)

        // Wire the learner in (learner-set path), then promote it to a 4th voter (voter-set change →
        // joint consensus). Leadership-tolerant: a freshly-promoted voter may itself contend.
        sim.changeMembershipOnLeader(ClusterConfig(voters = voterSet, learners = setOf(learnerNode)))
        val fourVoters = voterSet + learnerNode
        val result = sim.changeMembershipOnLeader(ClusterConfig(voters = fourVoters))
        assertAll(
            { assertEquals(fourVoters, result.voters, "C_new must have all 4 voters") },
            { assertTrue(result.learners.isEmpty(), "no learners in C_new") },
        )

        // The promoted node adopts C_new and leaves the Learner role (election-eligible, Fix #5).
        sim.awaitNode(learnerNode) { it.role.value !is RaftRole.Learner }

        // L1 now counts toward quorum: a proposal commits under the 4-voter membership.
        val entry = sim.proposeOnLeader(byteArrayOf(0x11))
        assertTrue(entry.index > 0L, "4-voter cluster must commit with the promoted voter in quorum")
    }

    /**
     * B3 — shrink a 5-voter cluster back to 3. Grows 3→5 (B1 path) to set up, then removes the two
     * promoted voters. Once C_new (the 3 original voters) is in force, the quorum is majority-of-3 = 2;
     * the removed pair is no longer needed and, partitioned away, cannot block commit — and being out
     * of the config, cannot commit the new entry themselves.
     */
    @Test
    fun shrink5To3_removedVotersDropFromQuorum() = raftRunTest(timeout = 10.seconds) {
        val sim = simWithVotersAndTwoBootstrappedLearners()
        awaitLeader(sim)

        // Grow 3 → 5 first (wire learners, then promote).
        sim.changeMembershipOnLeader(ClusterConfig(voters = voterSet, learners = twoLearners))
        sim.changeMembershipOnLeader(ClusterConfig(voters = fiveVoters))

        // Shrink 5 → 3, dropping two voters that are NOT the current leader. (Removing the leader is
        // its own scenario — removeLeader_…; keeping the leader among the survivors means it shepherds
        // the shrink without a mid-transition step-down.) After the grow, a promoted learner is often
        // the leader, so the dropped pair is chosen relative to whoever leads.
        val leaderId = sim.nodes.entries.first { it.value.role.value is RaftRole.Leader }.key
        val removed = (fiveVoters - leaderId).take(2).toSet()
        val survivors = fiveVoters - removed
        val result = sim.changeMembershipOnLeader(ClusterConfig(voters = survivors))
        assertEquals(survivors, result.voters, "C_new must be the 3 surviving voters")
        sim.awaitCommit(sim.awaitLeader(among = survivors).commitIndex.value, on = survivors)

        // The dropped pair is decommissioned (partition them off). The surviving 3 (quorum 2) commit.
        sim.partition(removed, survivors)
        val entry = sim.proposeOnLeader(byteArrayOf(0x22), among = survivors)
        assertTrue(entry.index > 0L, "shrunk 3-voter cluster must commit with majority 2-of-3")
        removed.forEach { id ->
            assertTrue(sim.nodes.getValue(id).commitIndex.value < entry.index,
                "$id was removed from the config and partitioned — must not commit ${entry.index}")
        }
    }

    /**
     * B3 — replace a voter. A learner is wired in, then a single voter-set change swaps one
     * (non-leader) voter out for the learner — same voter-set size, different membership. After C_new
     * is in force, the replaced voter — partitioned off — is no longer in any majority, and the new
     * triple commits. A non-leader voter is replaced so the leader shepherds the change to completion
     * (removing the leader is covered by removeLeader_…).
     */
    @Test
    fun replaceVoter_swapForLearner() = raftRunTest(timeout = 10.seconds) {
        val sim = simWithVotersAndBootstrappedLearner()
        awaitLeader(sim)

        // Wire the learner in, then replace a non-leader voter with L1 (same size, different set).
        sim.changeMembershipOnLeader(ClusterConfig(voters = voterSet, learners = setOf(learnerNode)))
        val leaderId = sim.nodes.entries.first { it.value.role.value is RaftRole.Leader }.key
        val replaced = (voterSet - leaderId).first()
        val newVoters = (voterSet - replaced) + learnerNode
        val result = sim.changeMembershipOnLeader(ClusterConfig(voters = newVoters))
        assertEquals(newVoters, result.voters, "C_new must replace $replaced with L1")
        sim.awaitCommit(sim.awaitLeader(among = newVoters).commitIndex.value, on = newVoters)

        // The replaced voter is partitioned away. The new triple (quorum 2) still commits.
        sim.partition(setOf(replaced), newVoters)
        val entry = sim.proposeOnLeader(byteArrayOf(0x33), among = newVoters)
        assertTrue(entry.index > 0L, "replaced cluster $newVoters must commit with majority 2-of-3")
        assertTrue(sim.nodes.getValue(replaced).commitIndex.value < entry.index,
            "replaced voter $replaced was partitioned and is out of the config — must not commit ${entry.index}")
    }

    /**
     * B3 — remove the leader itself (§6.4.1). Removing the current leader from a 3-voter cluster makes
     * C_new the two surviving voters. The leader shepherds the joint transition (it owns the joint
     * entries) until C_new is durable on the new majority, then steps down with
     * [StepDownReason.RemovedFromConfig]. The survivors (quorum 2-of-2) elect a new leader and commit.
     *
     * The removed leader is partitioned off after the change to keep the post-step-down leaderless gap
     * deterministic — without leadership transfer (out of scope), a removed node can briefly contend
     * before PreVote settles; isolating it tests the in-scope outcome (clean survivor cluster).
     */
    @Test
    fun removeLeader_stepsDownRemovedFromConfig_survivorsElect() = raftRunTest(timeout = 10.seconds) {
        val sim = simWithVotersAndBootstrappedLearner() // learner unused here; 3 voters in play
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val survivors = voterSet - leaderId

        // Observe the removed leader's step-down reason.
        val stepDowns = mutableListOf<RaftTraceEvent.BecomeFollower>()
        backgroundScope.launch {
            sim.nodes.getValue(leaderId).trace
                .filterIsInstance<RaftTraceEvent.BecomeFollower>()
                .collect { stepDowns += it }
        }
        delay(1)

        // Remove the leader: C_new = the two surviving voters.
        val result = leader.changeMembership(ClusterConfig(voters = survivors))
        assertEquals(survivors, result.voters, "C_new must be the two surviving voters")

        // §6.4.1: the removed leader steps down with RemovedFromConfig once C_new commits.
        sim.awaitRole(leaderId, RaftRole.Follower)
        assertTrue(stepDowns.any { it.reason == StepDownReason.RemovedFromConfig },
            "removed leader must step down RemovedFromConfig; saw ${stepDowns.map { it.reason }}")

        // Isolate the removed leader; the survivors (quorum 2-of-2) elect and commit among themselves.
        sim.partition(setOf(leaderId), survivors)
        val entry = sim.proposeOnLeader(byteArrayOf(0x44), among = survivors)
        assertTrue(entry.index > 0L, "surviving 2-voter cluster must commit after the leader is removed")
    }

    /**
     * B4 — the leader crashes mid-joint, before C_new commits. The caller's [RaftNode.changeMembership]
     * deferred fails with [LeadershipLostException] (the actor's cancellation path fails any in-flight
     * change), and the surviving voters converge on a single consistent membership (C_old, which they
     * never left) and keep committing — no split-brain from the dead leader's uncommitted Joint.
     *
     * Determinism: the leader is isolated first, so its Joint entry is appended locally (adopt-on-append
     * emits the ConfigChange) but can never commit; crashing it then fails the deferred without any race
     * against the change completing.
     */
    @Test
    fun crashLeaderMidJoint_callerSeesLeadershipLost_survivorsConverge() = raftRunTest(timeout = 10.seconds) {
        val sim = simWithVotersAndTwoBootstrappedLearners()
        awaitLeader(sim)
        // Wire the learners in first so all five nodes share the pre-change config.
        sim.changeMembershipOnLeader(ClusterConfig(voters = voterSet, learners = twoLearners))
        sim.awaitCommit(sim.awaitLeader().commitIndex.value, on = voterSet + twoLearners)

        val leaderId = sim.nodes.entries.first { it.value.role.value is RaftRole.Leader }.key
        val leaderNode = sim.nodes.getValue(leaderId)
        val survivors = (voterSet + twoLearners) - leaderId

        // Pre-subscribe to the leader's config transitions (before the change, to avoid missing the
        // Joint emission).
        val leaderConfigs = mutableListOf<RaftTraceEvent.ConfigChange>()
        backgroundScope.launch {
            leaderNode.trace.filterIsInstance<RaftTraceEvent.ConfigChange>().collect { leaderConfigs += it }
        }
        delay(1)

        // Isolate the leader, then start a voter-set change on it: it appends the Joint locally
        // (adopt-on-append) but can never commit it — no reachable majority.
        sim.partition(setOf(leaderId), survivors)
        val change = backgroundScope.async {
            runCatching { leaderNode.changeMembership(ClusterConfig(voters = fiveVoters)) }
        }
        // Wait until the leader has appended & adopted the Joint (effective voters = the 5-voter target).
        sim.awaitTrue("leader appends the joint mid-transition") {
            leaderConfigs.any { it.new.voters == fiveVoters }
        }

        // Crash the leader mid-joint. The caller's deferred fails (the dead leader can neither commit
        // C_new nor hand off the in-flight change).
        sim.crash(leaderId)
        val outcome = change.await()
        assertTrue(outcome.exceptionOrNull() is LeadershipLostException,
            "caller's changeMembership must fail with LeadershipLostException when the leader dies mid-joint; got $outcome")

        // The survivors never saw the Joint (the leader was isolated): the two original voters left
        // (a majority of the 3-voter C_old) elect and keep committing — no split-brain.
        val survivingVoters = voterSet - leaderId
        val entry = sim.proposeOnLeader(byteArrayOf(0x55), among = survivingVoters)
        assertTrue(entry.index > 0L, "survivors must converge on C_old and keep committing")
    }

    /**
     * Snapshots carry the effective configuration (spec §"Snapshots must carry the configuration").
     *
     * A node that rejoins across a compaction boundary can learn a membership change **only** from the
     * InstallSnapshot it receives, when the config log entries that produced it were discarded by
     * compaction on every node that would replicate to it. If `SnapshotMeta`/`InstallSnapshot` do not
     * carry the config, the installer's `recomputeMembership` finds no config entry and reverts to its
     * bootstrap config — silently losing every committed membership change.
     *
     * Setup: 3 voters {v1,v2,v3}. v3 goes offline; the survivors demote it from voter to **learner**
     * (a voter-set change → joint consensus), commit well past the change, and compact **both** live
     * voters past it — so the config entries are gone everywhere and the only surviving record is each
     * snapshot's [SnapshotMeta.config]. v3 then rejoins via InstallSnapshot.
     *
     * The adoption is observed via v3's **stable** [RaftNode.role] (not a transient trace event): v3
     * booted with a voters-only bootstrap, so it is a voter/[RaftRole.Follower] by default; it can only
     * become a [RaftRole.Learner] by adopting the snapshot's config. This is race-free — `role` is a
     * StateFlow we can poll until it settles.
     *
     * FAILS before the foundation fix: the install carries no config, so v3 reverts to its bootstrap
     * voter config and never enters the Learner role.
     */
    @Test
    fun installSnapshot_adoptsConfigCompactedAwayFromTheLog() = raftRunTest(timeout = 10.seconds) {
        val sim = simWithVotersAndBootstrappedLearner() // 3 voters in play; the bootstrapped L1 is unused
        awaitLeader(sim)
        val demoted = v3                          // bootstrap config: a voter of {v1,v2,v3}
        val survivors = setOf(v1, v2)

        // Offline across the compaction boundary (crash, not partition — no term inflation).
        sim.crash(demoted)
        // Demote v3 voter → learner while it is offline (voter-set change via joint consensus). The
        // survivors {v1,v2} are a majority of both the old {v1,v2,v3} and the new {v1,v2} voter sets.
        val demotedConfig = ClusterConfig(voters = survivors, learners = setOf(demoted))
        sim.changeMembershipOnLeader(demotedConfig)
        repeat(20) { sim.proposeOnLeader(byteArrayOf(it.toByte()), among = survivors) }

        // Compact BOTH live voters past the config entries, so no node can replay them to v3 — its only
        // possible source for the demotion is an InstallSnapshot's config.
        survivors.forEach { id ->
            val node = sim.nodes.getValue(id)
            val through = sim.compactionFloorCandidate(id)
            node.snapshots.value = Snapshot(through, sim.stateBytes(id, through))
            node.compactionFloor.first { it == through }
            // Each compacted snapshot must record the demotion as of its cut (onCompact stamps config).
            val storedConfig = sim.storages.getValue(id).loadSnapshot()!!.meta.config
            assertNotNull(storedConfig, "compacted snapshot on $id must carry a ConfigPayload")
            assertAll(
                { assertEquals(survivors, storedConfig.new.voters, "snapshot voters must be the demoted-down set") },
                { assertEquals(setOf(demoted), storedConfig.new.learners, "snapshot must record v3 as a learner") },
            )
        }
        val finalCommit = sim.nodes.getValue(sim.awaitLeader(among = survivors).let { l ->
            sim.nodes.entries.first { it.value === l }.key
        }).commitIndex.value

        // Rejoin: v3 boots fresh (voters-only bootstrap) and catches up via InstallSnapshot.
        sim.restart(demoted)
        sim.awaitCommit(finalCommit, on = setOf(demoted))   // only reachable via the snapshot install
        // The stable, race-free signal: v3 entered the Learner role, which is only possible by adopting
        // the snapshot's config (its bootstrap makes it a voter).
        val rejoined = sim.awaitNode(demoted) { it.role.value is RaftRole.Learner }
        assertIs<RaftRole.Learner>(rejoined.role.value)
    }

    // NOTE: an explicit adopt-on-append *rollback* test (follower adopts an uncommitted Joint, a new
    // leader overwrites the suffix, membership reverts) is deferred to PR C (#194 chaos/PBT). A
    // deterministic version must pin the election outcome: the follower holding the doomed Joint has
    // the most up-to-date log, so by §5.4.1 it can itself WIN the election and preserve the Joint
    // instead of being overwritten. Forcing the majority to win (keep the Joint-holder partitioned
    // until the majority has elected AND committed past the Joint index, then heal) needs the chaos
    // harness — and that harness must be resilient to a non-converging election cycle, which otherwise
    // spins the virtual-time scheduler CPU-bound and defeats runTest's wall-clock timeout (see #273).
    // Rollback-on-truncate is still exercised indirectly by the leadership churn in the B3 tests.
}
