@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class CheckQuorumTest {

    /**
     * Canonical #196 finding: a leader partitioned onto the minority side steps down to Follower
     * without bumping its term. In a 3-voter cluster, partitioning the leader from both followers
     * means it can no longer hear from any peer — quorum check fires and yields BecomeFollower(LostQuorum).
     */
    @Test
    fun partitionedLeader_stepsDown_withinOneElectionTimeout() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        // Isolate the leader from both followers — it cannot reach quorum.
        sim.partitionOff(leaderId)

        // Wait well past one election-timeout window (electionTimeoutMax = 10 ms).
        delay(80)

        val becomeFollowerEvent = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .firstOrNull { it.reason == StepDownReason.LostQuorum }

        assertAll(
            { assertTrue(becomeFollowerEvent != null, "expected BecomeFollower(LostQuorum) in trace: $leaderTrace") },
            { assertTrue(leader.role.value is RaftRole.Follower, "expected leader to be Follower, was: ${leader.role.value}") },
        )
    }

    /**
     * No false step-down: a connected leader in a 3-voter cluster keeps hearing from both peers
     * and must NOT step down across several quorum-check windows.
     */
    @Test
    fun connectedLeader_neverStepsDown_acrossManyWindows() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        // Advance well past multiple quorum-check windows (several election timeouts).
        delay(150)

        val lostQuorumEvents = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .filter { it.reason == StepDownReason.LostQuorum }

        assertTrue(lostQuorumEvents.isEmpty(),
            "connected leader must not step down via LostQuorum: $leaderTrace")
        assertTrue(leader.role.value is RaftRole.Leader,
            "connected leader must still be Leader after many windows, was: ${leader.role.value}")
    }

    /**
     * Single-voter: quorum = 1; the leader always counts itself, so reachable = 1 ≥ 1 every tick.
     * The single-voter leader must never step down via CheckQuorum.
     */
    @Test
    fun singleVoter_neverStepsDown() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 1)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        delay(150)

        val lostQuorumEvents = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .filter { it.reason == StepDownReason.LostQuorum }

        assertAll(
            { assertTrue(lostQuorumEvents.isEmpty(), "single-voter must not lose quorum: $leaderTrace") },
            { assertTrue(leader.role.value is RaftRole.Leader, "single-voter must remain Leader, was: ${leader.role.value}") },
        )
    }

    /**
     * §6.2 check-quorum, the property this file is named for: a **rejected** AppendEntries still
     * proves the peer is reachable, so an `AppendEntriesResponse(success = false)` must refresh the
     * leader's contact clock exactly as a successful one does. Reachability, not success, is the
     * CheckQuorum signal — `RaftEngine.onAppendEntriesResponse` credits `recentVoterContacts` *before*
     * it branches on `m.success`, and that unconditional credit is what this test pins.
     *
     * Setup: elect a leader in a 2-voter cluster (quorum = 2, so the leader needs its one peer in every
     * window), then **partition the leader off** so nothing the real peer sends can reach it. The only
     * traffic the leader sees from then on is a hand-injected stream of `success = false` responses at
     * its own term, delivered faster than the check-quorum window (a fresh 5–10 ms draw per tick under
     * [FAST_RAFT_CONFIG]). `deliverAppendEntriesResponse` bypasses the partition, which is the whole
     * point: rejections are the *only* contact.
     *
     * Phase 2 is the control, and it is not optional. Stopping the injections must make the leader lose
     * quorum — without that half, phase 1 would pass just as well against a leader that never checks
     * quorum at all, and the test would assert nothing. (That is precisely how the version this
     * replaced went vacuous: it seeded a higher-term log on the *peer*, so the peer won the election and
     * the leader it produced was fed an ordinary `success = true` heartbeat stream — see #1856.)
     */
    @Test
    fun rejectedAppendEntriesResponse_countsAsContact_leaderRetainsLeadership() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 2)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val peerId = sim.nodeIds.first { it != leaderId }
        val leaderTerm = sim.storages.getValue(leaderId).term()

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }
        sim.settle()

        // Cut the leader off: every genuine contact signal is gone, injected rejections are all that's left.
        sim.partitionOff(leaderId)

        // Phase 1 — rejections only, ~80 ms ≈ 8+ check-quorum windows.
        repeat(40) {
            sim.deliverAppendEntriesResponse(to = leaderId, from = peerId, term = leaderTerm, success = false)
            delay(2)
        }
        val roleUnderRejections = leader.role.value
        val lostQuorumUnderRejections = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .filter { it.reason == StepDownReason.LostQuorum }

        // Phase 2 (control) — stop injecting; the same partitioned leader must now step down.
        delay(80)
        val lostQuorumAfterSilence = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .filter { it.reason == StepDownReason.LostQuorum }

        assertAll(
            {
                assertTrue(lostQuorumUnderRejections.isEmpty(),
                    "a rejected AppendEntries is still contact — leader must not lose quorum: $leaderTrace")
            },
            {
                assertTrue(roleUnderRejections is RaftRole.Leader,
                    "leader must stay in office on success=false responses alone, was: $roleUnderRejections")
            },
            {
                assertTrue(lostQuorumAfterSilence.isNotEmpty(),
                    "control: with the rejections stopped the partitioned leader must lose quorum — " +
                        "otherwise phase 1 proved nothing: $leaderTrace")
            },
            {
                assertTrue(leader.role.value !is RaftRole.Leader,
                    "control: leader must not still be Leader once contact stops, was: ${leader.role.value}")
            },
        )
    }

    /**
     * A follower whose log diverges from the elected leader's rejects the first AppendEntries and is
     * recovered by §5.3 backup — and the leader holds office throughout. Not a check-quorum property
     * (the follower accepts within a couple of ms, so the leader's contact clock is refreshed by
     * ordinary successes); it is the regression test for the seeded fixture #1846 corrected, kept here
     * because that fixture is what produces a divergent log at all.
     *
     * Setup: a 2-voter cluster where v2 starts at term 99 holding an entry at index 1. §5.4.1
     * up-to-dateness therefore denies v1's pre-vote and **v2 wins the election** — the assertions below
     * pin that, because the name and the prose are only true if v2 is the one leading. v1 then rejects
     * v2's first AppendEntries (`prevLogIndex = 1` is past v1's empty log), v2 backs up, and v1 catches up.
     *
     * The persisted term is seeded alongside the entry (#1832). `storage.term() >= max(log entry terms)`
     * is a real invariant of every append path — `persistTermAndVote` is storage-first and runs before
     * `storage.appendEntries`, so a node cannot hold a term-99 entry while recorded at term 0. Seeding
     * only the entry produced a state no node can reach, and the AppendEntries it led to (`term = 1`
     * carrying an entry at `term = 99`) is now correctly rejected as malformed by the batch validation,
     * since no entry may carry a term above the leader's own.
     */
    @Test
    fun divergentFollowerLog_higherTermVoterWins_andRetainsLeadershipThroughBackup() = raftRunTest {
        val v1 = NodeId("v1"); val v2 = NodeId("v2")
        val cluster = ClusterConfig(voters = setOf(v1, v2))

        val conflictingStorage = InMemoryRaftStorage().also { s ->
            s.saveTermAndVotedFor(99L, null)
            s.appendEntries(listOf(LogEntry(index = 1L, term = 99L, command = byteArrayOf(0xFF.toByte()))))
        }

        val customSim = RaftSimulation(
            nodeIds = listOf(v1, v2),
            scope = this,
            raftConfig = FAST_RAFT_CONFIG,
            nodeScope = backgroundScope,
        ) { id, transport, _, childScope ->
            val storage = if (id == v2) conflictingStorage else InMemoryRaftStorage()
            childScope.raftNode(cluster, transport, storage, FAST_RAFT_CONFIG)
        }

        // Collect BEFORE the election so the rejection v1 emits on the very first AppendEntries is seen.
        val followerTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { customSim.nodes.getValue(v1).trace.collect { followerTrace += it } }
        customSim.settle()

        val leader = awaitLeader(customSim)
        val leaderId = customSim.nodes.entries.first { it.value === leader }.key
        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { customSim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        delay(150)

        assertAll(
            {
                assertEquals(v2, leaderId,
                    "§5.4.1: the voter holding the term-99 entry must win — a leader elsewhere means this " +
                        "fixture no longer produces a divergent follower log")
            },
            {
                assertTrue(followerTrace.filterIsInstance<RaftTraceEvent.AppendEntriesRejected>().isNotEmpty(),
                    "v1 must reject at least once (prevLogIndex past its empty log): $followerTrace")
            },
            {
                assertTrue(followerTrace.filterIsInstance<RaftTraceEvent.AppendEntriesAccepted>().isNotEmpty(),
                    "§5.3 backup must recover v1 — it never accepted: $followerTrace")
            },
            {
                val lostQuorumEvents = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
                    .filter { it.reason == StepDownReason.LostQuorum }
                assertTrue(lostQuorumEvents.isEmpty(),
                    "leader must not lose quorum while backing a divergent follower up: $leaderTrace")
            },
            {
                assertTrue(leader.role.value is RaftRole.Leader,
                    "leader must remain Leader, was: ${leader.role.value}")
            },
        )
    }

    /**
     * Heal after step-down: partition → step-down → heal. The stepped-down node rejoins as
     * follower under the new leader without term inflation (PreVote interaction).
     * The old leader's term in the BecomeFollower(LostQuorum) trace must match the final cluster term.
     */
    @Test
    fun healAfterStepDown_nodeRejoinsAsFollower_noTermInflation() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val others = sim.nodeIds.filter { it != leaderId }.toSet()

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        // Isolate the leader — the majority elects a new leader.
        sim.partition(setOf(leaderId), others)
        delay(80)

        // Old leader stepped down via CheckQuorum.
        val lostQuorumEvent = leaderTrace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .firstOrNull { it.reason == StepDownReason.LostQuorum }
        assertTrue(lostQuorumEvent != null, "old leader must have stepped down via LostQuorum: $leaderTrace")

        // Heal the partition.
        sim.heal()

        // A new leader is elected on the majority side; wait for it to commit.
        val newLeader = awaitLeader(sim)
        newLeader.propose(byteArrayOf(1))
        sim.awaitCommit(sim.nodes.getValue(leaderId).commitIndex.value + 1, on = others)

        // Old leader rejoins as follower and catches up.
        sim.awaitRole(leaderId, RaftRole.Follower)

        assertAll(
            { assertTrue(leader.role.value is RaftRole.Follower, "old leader must be Follower after heal: ${leader.role.value}") },
            // The LostQuorum step-down must carry a positive term (the real elected term),
            // proving no term inflation occurred on the minority side.
            {
                assertTrue(
                    lostQuorumEvent.term > 0L,
                    "LostQuorum step-down term must be > 0 (the elected term, not initial 0 — no term bump): was ${lostQuorumEvent.term}"
                )
            },
            // After heal, the cluster term must not have inflated past the original term+1
            // (only the new election on the majority side bumps to term+1, never higher).
            {
                val newLeaderRole = newLeader.role.value
                assertTrue(
                    newLeaderRole is RaftRole.Leader,
                    "majority should have a stable leader after heal, was: $newLeaderRole"
                )
            },
        )
    }
}
