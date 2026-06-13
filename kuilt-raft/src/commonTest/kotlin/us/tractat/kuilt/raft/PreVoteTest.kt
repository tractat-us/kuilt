@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertTrue

class PreVoteTest {

    // A follower that has NOT heard from a leader grants a pre-vote for an up-to-date candidate.
    @Test
    fun granterGrantsPreVoteWhenNoLeaderAndLogOk() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val v1 = NodeId("v1"); val v2 = NodeId("v2"); val v3 = NodeId("v3")
        val granterTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(v2).trace.collect { granterTrace += it } }
        sim.settle()  // let collector subscribe before injecting the message
        // Isolate all three nodes so no leader is ever established — leaderAlive stays false on v2.
        sim.partition(setOf(v1), setOf(v2))
        sim.partition(setOf(v1), setOf(v3))
        sim.partition(setOf(v2), setOf(v3))
        // Deliver a PreVote from v1 to v2 (empty logs are mutually up-to-date).
        sim.deliverPreVote(to = v2, from = v1, term = 1L, lastLogIndex = 0L, lastLogTerm = 0L)
        sim.settle()
        assertTrue(granterTrace.any { it is RaftTraceEvent.PreVoteGranted },
            "no-leader granter should grant: $granterTrace")
    }

    // After hearing AppendEntries from a leader, the same node DENIES pre-votes (leaderAlive).
    @Test
    fun granterDeniesPreVoteWhileLeaderAlive() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val follower = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L, on = setOf(follower))   // it has heard the leader → leaderAlive
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(follower).trace.collect { trace += it } }
        sim.settle()  // let collector subscribe before injecting the message
        sim.deliverPreVote(to = follower, from = leaderId, term = 99L, lastLogIndex = 99L, lastLogTerm = 99L)
        sim.settle()
        assertTrue(trace.any { it is RaftTraceEvent.PreVoteDenied },
            "follower hearing the leader must deny pre-votes: $trace")
    }

    // The headline fix: a partitioned voter does NOT inflate its term, so on heal it does not
    // depose the leader. Asserted via trace: the leader emits no BecomeFollower; the offline node
    // emits no real Timeout (term bump). And the offline node rejoins (commitIndex catches up).
    @Test
    fun partitionedVoterDoesNotDisruptLeaderOnRejoin() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val offline = sim.nodeIds.first { it != leaderId }
        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }
        sim.settle()  // let collector subscribe before partitioning

        sim.partition(setOf(offline), sim.nodeIds.filter { it != offline }.toSet())
        repeat(3) { leader.propose(byteArrayOf(it.toByte())) }
        sim.awaitCommit(3L, on = setOf(leaderId))
        // Wait long enough for the offline node to fire several election timeouts (electionTimeoutMax=10ms)
        // and inflate its term — the pre-vote fix gates this behind a quorum probe that will fail while
        // partitioned, keeping the term intact.
        delay(100)
        sim.heal()
        sim.awaitCommit(3L, on = setOf(offline))   // it rejoins

        assertTrue(leaderTrace.none { it is RaftTraceEvent.BecomeFollower },
            "healthy leader must not be deposed by the partitioned node: $leaderTrace")
    }

    // §4.2.3: a follower within its leader-lease rejects a higher-term RequestVote WITHOUT adopting
    // the term. Asserted via trace: a VoteDenied(LeaderAlive) is emitted and the follower does NOT
    // emit BecomeFollower (which a term adoption would trigger).
    @Test
    fun stickyFollowerRejectsHigherTermVoteWithoutAdopting() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val follower = sim.nodeIds.first { it != leaderId }
        val candidate = sim.nodeIds.first { it != leaderId && it != follower }
        sim.awaitCommit(1L, on = setOf(follower))   // follower has heard the leader → leaderAlive
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(follower).trace.collect { trace += it } }
        sim.settle()  // let collector subscribe before injecting the message
        sim.deliverRequestVote(to = follower, from = candidate, term = 99L, lastLogIndex = 99L, lastLogTerm = 99L)
        sim.settle()
        assertTrue(trace.any { it is RaftTraceEvent.VoteDenied && it.reason == DenyReason.LeaderAlive },
            "sticky follower must deny with LeaderAlive: $trace")
        assertTrue(trace.none { it is RaftTraceEvent.BecomeFollower },
            "sticky follower must NOT adopt the higher term: $trace")
    }

    // A node only bumps its term once a pre-vote quorum is reached: with peers reachable and no
    // leader, an election still completes (sanity that pre-vote doesn't deadlock normal elections).
    @Test
    fun electionStillCompletesViaPreVote() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)        // election must succeed through the pre-vote path
        assertTrue(leader.role.value is RaftRole.Leader)
    }

    // Single-voter still elects instantly (self pre-vote satisfies quorum).
    @Test
    fun singleVoterElectsInstantly() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 1)
        val leader = awaitLeader(sim)
        assertTrue(leader.role.value is RaftRole.Leader)
    }

    // A PreVoteResponse carrying a stale round nonce (from a previous probe cycle) must NOT
    // count toward the current round's quorum. Without the round nonce, a delayed grant from
    // round N is indistinguishable from one in round N+1 and can trigger a spurious real election.
    @Test
    fun stalePreVoteResponseFromPreviousRoundIsRejected() = raftRunTest {
        val v1 = NodeId("v1"); val v2 = NodeId("v2"); val v3 = NodeId("v3")
        val sim = raftSim(this, backgroundScope, n = 3)
        // Isolate v1 so its pre-vote probes never reach quorum — it can only collect its own self-vote.
        sim.partitionOff(v1)
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(v1).trace.collect { trace += it } }
        sim.settle()  // let collector subscribe before advancing time
        // Wait for v1 to fire two election timeouts, advancing its preVoteRound from 0 to 2.
        // Round 1: first ElectionTimeout → preVoteRound = 1. Round 2: second timeout → preVoteRound = 2.
        delay(50)   // well past two election cycles (electionTimeoutMax = 10ms)
        // v1 is now in round ≥ 2. Inject a PreVoteResponse that carries round=1 (stale).
        // voteGranted=true from v2 with term=0 (pre-votes don't bump term), proposedTerm = currentTerm+1.
        // The injected round is 1, but v1 is now in round 2 — this must be discarded.
        sim.deliverPreVoteResponse(
            to = v1,
            from = v2,
            term = 0L,
            voteGranted = true,
            proposedTerm = 1L,
            round = 1L,
        )
        sim.settle()
        assertTrue(
            trace.none { it is RaftTraceEvent.Timeout },
            "stale round pre-vote response must not trigger a real election (Timeout): $trace",
        )
    }
}
