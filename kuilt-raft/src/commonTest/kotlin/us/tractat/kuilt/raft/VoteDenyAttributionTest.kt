@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #2052: a vote/pre-vote denial must attribute **every** clause of the decision that failed, not
 * just the first one a priority-ordered `when` happens to reach.
 *
 * The decision is a conjunction, and a candidate can fail several of its clauses on one ordinary
 * trajectory. Re-deriving a single reason from state then reports whichever clause the arm order
 * reaches first, so the others are unobservable *in the field that exists to attribute them* — and a
 * mutation weakening one of them is invisible to any test asserting through this channel.
 *
 * ### What each test here is for
 *
 * The isolating tests ([staleTermAlone], [alreadyVotedAlone], [logNotUpToDateAlone],
 * [preVoteLeaderAliveAlone], [preVoteLogNotUpToDateAlone], [preVoteStaleTermAlone]) each fail exactly
 * ONE clause. That separation is the point: a fixture tripping three clauses at once measures only
 * whichever the arm order reaches first, and leaves the other two individually deletable — the same
 * "obviously invalid fixture" hole that left three of `isWellFormedBatch`'s bounds unpinned (#2022).
 *
 * The two-clause tests ([alreadyVotedAndLogNotUpToDate], [preVoteLeaderAliveAndLogNotUpToDate]) are
 * where the bug actually lived, and they are the ones that carry the mutation receipt: weakening
 * `isLogUpToDate` must redden them. Before this fix they reported only `AlreadyVoted` / `LeaderAlive`
 * and the mutation was invisible.
 */
class VoteDenyAttributionTest {

    /**
     * A voter with a non-empty log, optionally isolated from its cluster so its leader lease lapses.
     *
     * Isolation matters twice: it lets the §4.2.3 leader-stickiness deny (which short-circuits before
     * the log comparison ever runs) stop pre-empting the clauses under test, and it keeps the voter's
     * own term still, since a partitioned node's elections never get past pre-vote.
     */
    private class Probe(
        val sim: RaftSimulation,
        val voter: NodeId,
        val candidateA: NodeId,
        val candidateB: NodeId,
        val trace: MutableList<RaftTraceEvent>,
    ) {
        fun denials() = trace.filterIsInstance<RaftTraceEvent.VoteDenied>()
        fun preVoteDenials() = trace.filterIsInstance<RaftTraceEvent.PreVoteDenied>()
    }

    /** The voter's log is at least `(term 1, index 1)`, so `(0, 0)` is strictly behind it. */
    private val behindEveryone = 0L

    /** Far ahead of any term the simulation reaches, so §5.4.1 is satisfied. */
    private val aheadOfEveryone = 99L

    /** Above the voter's term yet well inside `RaftConfig.maxTermJump`, so the frame is admitted. */
    private val stagedTerm = 50L

    private suspend fun TestScope.probe(isolate: Boolean = true): Probe {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val voter = sim.nodeIds.first { it != leaderId }
        val other = sim.nodeIds.first { it != leaderId && it != voter }

        sim.proposeOnLeader(byteArrayOf(1))
        sim.awaitCommit(1L, on = setOf(voter))

        if (isolate) {
            sim.partitionOff(voter)
            delay(100)   // let the leader lease expire → leaderAlive = false
        }
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(voter).trace.collect { trace += it } }
        sim.settle()
        return Probe(sim, voter, candidateA = leaderId, candidateB = other, trace)
    }

    /**
     * Spend the voter's vote on [Probe.candidateA] at [stagedTerm], leaving `currentTerm == stagedTerm`
     * and `votedFor == candidateA`. Asserts the staging vote was granted, so a later denial cannot be
     * mistaken for a fixture that never set up the state it claims to.
     */
    private suspend fun Probe.spendVoteOnA() {
        sim.deliverRequestVote(
            to = voter, from = candidateA,
            term = stagedTerm, lastLogIndex = aheadOfEveryone, lastLogTerm = aheadOfEveryone,
        )
        sim.settle()
        assertTrue(trace.any { it is RaftTraceEvent.VoteGranted },
            "fixture: the staging vote must be GRANTED or the votedFor clause is not armed: $trace")
        trace.clear()
    }

    // ── RequestVote ──────────────────────────────────────────────────────────────

    // Below our term, log fine, and the vote is still available to this very candidate → StaleTerm only.
    @Test
    fun staleTermAlone() = raftRunTest {
        val p = probe()
        p.spendVoteOnA()
        // Re-asking as candidateA keeps `votedFor == from`, so the already-voted clause stays satisfied.
        p.sim.deliverRequestVote(
            to = p.voter, from = p.candidateA,
            term = stagedTerm - 1, lastLogIndex = aheadOfEveryone, lastLogTerm = aheadOfEveryone,
        )
        p.sim.settle()
        assertEquals(listOf(setOf(DenyReason.StaleTerm)), p.denials().map { it.reasons }, "trace=${p.trace}")
    }

    // Our term, log fine, but the vote is spent on someone else → AlreadyVoted only.
    @Test
    fun alreadyVotedAlone() = raftRunTest {
        val p = probe()
        p.spendVoteOnA()
        p.sim.deliverRequestVote(
            to = p.voter, from = p.candidateB,
            term = stagedTerm, lastLogIndex = aheadOfEveryone, lastLogTerm = aheadOfEveryone,
        )
        p.sim.settle()
        assertEquals(listOf(setOf(DenyReason.AlreadyVoted)), p.denials().map { it.reasons }, "trace=${p.trace}")
    }

    // Above our term (so the step-down frees the vote), but the log is behind → LogNotUpToDate only.
    @Test
    fun logNotUpToDateAlone() = raftRunTest {
        val p = probe()
        p.spendVoteOnA()
        p.sim.deliverRequestVote(
            to = p.voter, from = p.candidateB,
            term = stagedTerm + 1, lastLogIndex = behindEveryone, lastLogTerm = behindEveryone,
        )
        p.sim.settle()
        assertEquals(listOf(setOf(DenyReason.LogNotUpToDate)), p.denials().map { it.reasons }, "trace=${p.trace}")
    }

    /**
     * TWO clauses fail together — an ordinary split vote against a lagging candidate — and both must
     * be reported.
     *
     * This is the trajectory the fix exists for. It previously reported `AlreadyVoted` alone, so
     * weakening `isLogUpToDate` changed nothing observable here: the vote was denied either way, for a
     * reason that never consulted the log. `reason` is asserted alongside `reasons` to pin that the
     * first-failing projection is unchanged by the fix.
     */
    @Test
    fun alreadyVotedAndLogNotUpToDate() = raftRunTest {
        val p = probe()
        p.spendVoteOnA()
        p.sim.deliverRequestVote(
            to = p.voter, from = p.candidateB,
            term = stagedTerm, lastLogIndex = behindEveryone, lastLogTerm = behindEveryone,
        )
        p.sim.settle()

        val denied = p.denials().single()
        assertAll(
            { assertEquals(setOf(DenyReason.AlreadyVoted, DenyReason.LogNotUpToDate), denied.reasons,
                "both clauses failed and both must be attributed: ${p.trace}") },
            { assertTrue(DenyReason.LogNotUpToDate in denied.reasons,
                "§5.4.1 must stay observable when a second clause fails alongside it: ${p.trace}") },
            { assertEquals(DenyReason.AlreadyVoted, denied.reason,
                "first-failing projection is unchanged: ${p.trace}") },
        )
    }

    // ── PreVote ──────────────────────────────────────────────────────────────────

    // Not isolated, so the lease still holds; term and log both fine → LeaderAlive only.
    @Test
    fun preVoteLeaderAliveAlone() = raftRunTest {
        val p = probe(isolate = false)
        p.sim.deliverPreVote(
            to = p.voter, from = p.candidateB,
            term = stagedTerm, lastLogIndex = aheadOfEveryone, lastLogTerm = aheadOfEveryone,
        )
        p.sim.settle()
        assertEquals(listOf(setOf(DenyReason.LeaderAlive)), p.preVoteDenials().map { it.reasons }, "trace=${p.trace}")
    }

    // Isolated (no live leader), term above ours, log behind → LogNotUpToDate only.
    @Test
    fun preVoteLogNotUpToDateAlone() = raftRunTest {
        val p = probe()
        p.sim.deliverPreVote(
            to = p.voter, from = p.candidateB,
            term = stagedTerm, lastLogIndex = behindEveryone, lastLogTerm = behindEveryone,
        )
        p.sim.settle()
        assertEquals(listOf(setOf(DenyReason.LogNotUpToDate)), p.preVoteDenials().map { it.reasons }, "trace=${p.trace}")
    }

    // Isolated, log fine, but the proposed term does not exceed ours → StaleTerm only.
    @Test
    fun preVoteStaleTermAlone() = raftRunTest {
        val p = probe()
        p.spendVoteOnA()   // pins currentTerm at stagedTerm so `term = stagedTerm` is not above it
        p.sim.deliverPreVote(
            to = p.voter, from = p.candidateB,
            term = stagedTerm, lastLogIndex = aheadOfEveryone, lastLogTerm = aheadOfEveryone,
        )
        p.sim.settle()
        assertEquals(listOf(setOf(DenyReason.StaleTerm)), p.preVoteDenials().map { it.reasons }, "trace=${p.trace}")
    }

    /**
     * The pre-vote path's own two-clause trajectory: a live leader AND a log behind ours.
     *
     * `LeaderAlive` leads this handler's arm order, so it previously masked §5.4.1 here exactly as
     * `AlreadyVoted` did on the RequestVote path.
     */
    @Test
    fun preVoteLeaderAliveAndLogNotUpToDate() = raftRunTest {
        val p = probe(isolate = false)
        p.sim.deliverPreVote(
            to = p.voter, from = p.candidateB,
            term = stagedTerm, lastLogIndex = behindEveryone, lastLogTerm = behindEveryone,
        )
        p.sim.settle()

        val denied = p.preVoteDenials().single()
        assertAll(
            { assertEquals(setOf(DenyReason.LeaderAlive, DenyReason.LogNotUpToDate), denied.reasons,
                "both clauses failed and both must be attributed: ${p.trace}") },
            { assertEquals(DenyReason.LeaderAlive, denied.reason,
                "first-failing projection is unchanged: ${p.trace}") },
        )
    }

    /**
     * The §4.2.3 stickiness deny is an early return taken before any other clause is evaluated, so its
     * attribution is a singleton by construction — not by an arm order that could mask a sibling.
     */
    @Test
    fun stickinessDenyIsAttributedAloneEvenThoughTheLogIsAlsoBehind() = raftRunTest {
        val p = probe(isolate = false)
        p.sim.deliverRequestVote(
            to = p.voter, from = p.candidateB,
            term = stagedTerm, lastLogIndex = behindEveryone, lastLogTerm = behindEveryone,
        )
        p.sim.settle()
        assertEquals(listOf(setOf(DenyReason.LeaderAlive)), p.denials().map { it.reasons }, "trace=${p.trace}")
    }
}
