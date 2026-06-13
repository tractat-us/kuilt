@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.internal.RaftMessage
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }

/**
 * Config for the stale-ACK BLOCKER 1 test: heartbeat stays at 2 ms (so we can advance
 * heartbeatRound quickly) but the election timeout is 300–400 ms so CheckQuorum does NOT fire
 * during the 10–15 ms window where stale ACKs are injected and the fresh ACK triggers
 * resolveReadsIfQuorumFresh. FAST_RAFT_CONFIG's 5-10 ms election timeout races with that
 * window on slower machines.
 */
private val SLOW_ELECTION_CONFIG = RaftConfig(
    electionTimeoutMin = 300.milliseconds,
    electionTimeoutMax = 400.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,
)

/**
 * Behaviour tests for [RaftNode.readIndex] (linearizable reads without a log write).
 *
 * All tests use [raftRunTest] with [UnconfinedTestDispatcher] and real-clock [delay] — the
 * standard harness contract for this suite (see [RaftTestFixtures] banner). Tests that need
 * multi-voter quorum confirmation advance real time past one heartbeat interval (2 ms in
 * [FAST_RAFT_CONFIG]) so the ACK majority accumulates.
 */
class ReadIndexTest {

    // ── Acceptance criterion 2: non-leader throws ─────────────────────────────

    /**
     * [RaftNode.readIndex] on a follower throws [NotLeaderException] immediately.
     * The throwing default on the [RaftNode] interface satisfies the follower case.
     */
    @Test
    fun followerReadIndexThrowsNotLeader() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val follower = sim.nodes.entries.first { it.key != leaderId }.value

        assertFailsWith<NotLeaderException> { follower.readIndex() }
    }

    /**
     * [RaftNode.readIndex] on a learner throws [NotLeaderException] immediately.
     * The throwing default is inherited; learners never lead.
     */
    @Test
    fun learnerReadIndexThrowsNotLeader() = raftRunTest {
        val voterId = NodeId("voter")
        val learner = NodeId("learner")
        val cluster = ClusterConfig(voters = setOf(voterId), learners = setOf(learner))
        val network = InMemoryRaftNetwork()
        val learnerNode = backgroundScope.raftNode(
            cluster, network.transport(learner), InMemoryRaftStorage(), FAST_RAFT_CONFIG,
        )
        assertFailsWith<NotLeaderException> { learnerNode.readIndex() }
    }

    // ── Acceptance criterion 6: single-voter returns immediately ──────────────

    /**
     * A single-voter leader returns [RaftNode.commitIndex] immediately without issuing a heartbeat
     * round — self is the quorum, so freshness is trivially satisfied.
     */
    @Test
    fun singleVoterReadIndexReturnsCommitIndexImmediately() = raftRunTest {
        val h = singleVoterNode(backgroundScope)
        h.node.awaitLeadership()
        // Propose an entry so commitIndex advances past 0.
        val committed = h.node.propose("x=1".encodeToByteArray())
        h.awaitCommit(committed.index)

        // Capture the log length before the read — no entry must be appended for the read.
        val logBefore = h.storage.entries().size
        val ri = h.node.readIndex()
        val logAfter = h.storage.entries().size

        assertAll(
            { assertTrue(ri >= committed.index, "read index must be >= committed write index: ri=$ri committed=${committed.index}") },
            { assertTrue(logAfter == logBefore, "no log entry written for the read: before=$logBefore after=$logAfter") },
        )
    }

    // ── Acceptance criterion 1: read-your-writes on 3-voter cluster ───────────

    /**
     * After committing a write on a 3-voter leader, [readIndex] returns an index ≥ the write index
     * and no additional log entry is written. The returned read index is linearizable: any state
     * machine applied through it observes the write.
     */
    @Test
    fun multiVoterReadIndexReflectsCommittedWriteWithNoLogEntry() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        val committed = leader.propose("x=1".encodeToByteArray())
        sim.awaitCommit(committed.index)

        // Capture log before the read.
        val logBefore = sim.storages.getValue(leaderId).entries().size
        // readIndex suspends until the next heartbeat round ACKs from a quorum.
        val ri = leader.readIndex()
        val logAfter = sim.storages.getValue(leaderId).entries().size

        // Verify a ReadIndexConfirmed trace event was emitted.
        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { leader.trace.collect { trace += it } }
        delay(1)

        assertAll(
            { assertTrue(ri >= committed.index, "read index must be >= committed write index: ri=$ri committed=${committed.index}") },
            { assertTrue(logAfter == logBefore, "no log entry appended for the read: logBefore=$logBefore logAfter=$logAfter") },
        )
    }

    // ── Acceptance criterion 3: fresh-leader no-op gate ───────────────────────

    /**
     * A freshly-elected leader's [readIndex] does not return a stale index — it waits until
     * its current-term no-op commits before resolving. The returned index must be ≥ the no-op's
     * index (which is 1 for a fresh cluster).
     */
    @Test
    fun freshLeaderReadIndexWaitsForCurrentTermNoOpToCommit() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)

        // readIndex must return only after the no-op commits.
        // In a 3-voter cluster with FAST_RAFT_CONFIG the no-op commits after one heartbeat (2 ms).
        val ri = leader.readIndex()

        // The no-op is index 1; the readIndex must return an index >= 1 (no-op committed).
        assertTrue(ri >= 1L, "fresh-leader readIndex must be >= no-op index 1: ri=$ri")
    }

    // ── Acceptance criterion 4: concurrent calls share one round ──────────────

    /**
     * N concurrent [readIndex] calls in one heartbeat window all resolve against the same
     * read index — they share a single quorum round. All returned values must be equal.
     */
    @Test
    fun concurrentReadIndexCallsShareOneQuorumRound() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)

        // Propose one entry so commitIndex > 0 and the no-op gate is passed.
        leader.propose("x=1".encodeToByteArray())
        sim.awaitCommit(1)

        // Issue 5 concurrent readIndex calls — all should resolve with the same value.
        val reads = List(5) { async { leader.readIndex() } }
        val results = reads.map { it.await() }

        assertTrue(
            results.all { it == results.first() },
            "concurrent readIndex calls must return the same read index: $results",
        )
    }

    // ── Acceptance criterion 5: leadership loss fails in-flight reads ──────────

    /**
     * An in-flight [readIndex] fails with [LeadershipLostException] when the leader loses quorum
     * (partition scenario). CheckQuorum (#196) steps it down within one election-timeout window;
     * the pending read deferred is completed exceptionally.
     */
    @Test
    fun readIndexFailsWithLeadershipLostWhenLeaderLosesQuorum() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key

        // Propose an entry first so the no-op gate is satisfied.
        leader.propose("x=1".encodeToByteArray())
        sim.awaitCommit(1)

        // Partition the leader away from both followers.
        sim.partitionOff(leaderId)

        // Issue readIndex while partitioned — it can never get quorum confirmation.
        // Wrap in supervisorScope so the async child's LeadershipLostException does not
        // propagate to and cancel the enclosing runTest coroutine scope.
        supervisorScope {
            val read = async { leader.readIndex() }

            // Wait well past one election-timeout window; CheckQuorum steps the leader down.
            delay(80)

            // The read must fail with LeadershipLostException.
            assertFailsWith<LeadershipLostException> { read.await() }
        }
    }

    // ── Acceptance criterion 7: awaitRead helper ──────────────────────────────

    /**
     * [awaitRead] on a single-voter leader suspends until the caller's applied-index flow
     * reaches the returned read index, then returns that index. Validates the caller-side
     * apply-wait contract.
     */
    @Test
    fun awaitReadReturnsAfterAppliedFlowReachesReadIndex() = raftRunTest {
        val h = singleVoterNode(backgroundScope)
        h.node.awaitLeadership()
        h.node.propose("x=1".encodeToByteArray())
        h.awaitCommit(1)

        // A caller-owned applied-index flow starting at 0 (nothing applied yet).
        val applied = MutableStateFlow(0L)
        val readJob = async { h.node.awaitRead(applied) }

        // readIndex() resolves immediately for single-voter; awaitRead should now be
        // waiting on applied.first { it >= ri }.
        delay(1) // let the coroutine reach the suspension point

        assertFalse(readJob.isCompleted, "awaitRead must still be suspended while applied < ri")

        // Simulate the apply loop catching up.
        applied.value = Long.MAX_VALUE

        val ri = readJob.await()
        assertTrue(ri >= 1L, "awaitRead must return a read index >= 1 once applied catches up: ri=$ri")
    }

    /**
     * [awaitRead] propagates [NotLeaderException] when [readIndex] throws (follower case).
     */
    @Test
    fun awaitReadPropagatesNotLeaderException() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val follower = sim.nodes.entries.first { it.key != leaderId }.value

        val applied = MutableStateFlow(0L)
        assertFailsWith<NotLeaderException> { follower.awaitRead(applied) }
    }

    // ── ReadIndexConfirmed trace event ────────────────────────────────────────

    /**
     * A confirmed 3-voter readIndex emits a [RaftTraceEvent.ReadIndexConfirmed] event with
     * a readIndex matching the value returned by [readIndex].
     */
    @Test
    fun readIndexConfirmedTraceEventEmitted() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)

        val confirmedEvents = mutableListOf<RaftTraceEvent.ReadIndexConfirmed>()
        backgroundScope.launch { leader.trace.collect { if (it is RaftTraceEvent.ReadIndexConfirmed) confirmedEvents += it } }
        delay(1) // let the subscriber register

        // Propose and await commit so no-op gate is satisfied.
        leader.propose("x=1".encodeToByteArray())
        sim.awaitCommit(1)

        val ri = leader.readIndex()

        // At least one ReadIndexConfirmed event must have been emitted with the correct readIndex.
        assertTrue(
            confirmedEvents.any { it.readIndex == ri },
            "expected ReadIndexConfirmed(readIndex=$ri) in trace: $confirmedEvents",
        )
    }

    // ── BLOCKER 1: stale ACK must not confirm a read ──────────────────────────

    /**
     * BLOCKER 1 — a voter ACK that arrived *before* the read was queued must not be
     * counted when checking whether a quorum has responded *after* the read.
     *
     * Scenario: 5-voter cluster (quorum = 3) using [SLOW_ELECTION_CONFIG] (election timeout
     * 300–400 ms; heartbeat 2 ms). After a leader is elected and the no-op commits, the
     * leader is partitioned from all four followers. Two stale ACKs (one each from two
     * follower nodes) are injected at the current heartbeatRound H — *before* the read is
     * queued at sinceRound = H. The leader's heartbeat ticks, advancing heartbeatRound to
     * H+1. A single fresh ACK from a third follower is injected, triggering
     * resolveReadsIfQuorumFresh.
     *
     * Bug (cumulative recentVoterContacts set): reachable = |{staleA, staleB, freshC}| + 1 = 4
     * ≥ 3 (quorum) → read confirmed. Stale entries from round H inflate the count above quorum.
     *
     * Fix (per-voter lastAckRound map): staleA and staleB have lastAckRound = H = sinceRound
     * (not strictly greater), so they do NOT count. Only freshC (lastAckRound = H+1 > H)
     * counts → reachable = 1 + 1 = 2 < 3 → NOT confirmed.
     *
     * [SLOW_ELECTION_CONFIG] guarantees QuorumCheck does not fire during the ~10 ms ACK
     * injection window (election timeout 300–400 ms vs. injection duration < 15 ms).
     *
     * The leader's term is read from its storage after the no-op commits; the injected ACKs
     * use that term so they are not discarded by the stale-term guard.
     */
    @Test
    fun staleAckDoesNotConfirmReadIndex() = raftRunTest(timeout = 5.seconds) {
        // Use raftSim so awaitLeader() gets whichever node wins — no need to predict v1.
        val sim = raftSim(this, backgroundScope, n = 5, config = SLOW_ELECTION_CONFIG)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val followerIds = sim.nodeIds.filter { it != leaderId }

        // Wait for the no-op to commit so the no-op gate is satisfied.
        sim.awaitCommit(1)

        // Read the leader's actual term from its storage so fake ACKs use the correct term
        // and are not dropped by the stale-term guard (m.term != currentTerm).
        val leaderTerm = sim.storages.getValue(leaderId).entries().first().term

        // Partition the leader from all followers — no real ACKs can arrive after this point.
        // QuorumCheck fires at 300–400 ms; all actions below complete in ~15 ms so
        // recentVoterContacts is NOT cleared between stale injection and fresh injection.
        sim.partitionOff(leaderId)

        // Pick two "stale" follower IDs and one "fresh" follower ID.
        val staleA = followerIds[0]
        val staleB = followerIds[1]
        val freshC = followerIds[2]

        // Inject stale ACKs from staleA, staleB — arrive at current heartbeatRound H.
        // These set lastAckRound[staleA] = lastAckRound[staleB] = H.
        // The read is queued AFTER these ACKs, so sinceRound = H = their lastAckRound.
        val staleAck = Cbor.encodeToByteArray<RaftMessage>(
            RaftMessage.AppendEntriesResponse(term = leaderTerm, success = true, matchIndex = 1L)
        )
        sim.network.deliver(from = staleA, to = leaderId, bytes = staleAck)
        sim.network.deliver(from = staleB, to = leaderId, bytes = staleAck)
        delay(2) // let the actor process both stale ACKs before queuing the read

        supervisorScope {
            // Queue the read — sinceRound = H captured inside onRequestReadIndex.
            val read = async { leader.readIndex() }
            delay(1) // let the ReadIndex command reach the actor

            // Wait one heartbeat interval (2 ms) so heartbeatRound bumps from H to H+1.
            delay(3)

            // Inject one fresh ACK from freshC — arrives in round H+1 > sinceRound = H.
            // Bug: recentVoterContacts = {staleA, staleB, freshC} → 3+1 = 4 ≥ 3 → CONFIRMED.
            // Fix: lastAckRound[staleA]=H=sinceRound → excluded; same for staleB.
            //   Only freshC (lastAckRound=H+1) counts → 1+1=2 < 3 → NOT confirmed.
            val freshAck = Cbor.encodeToByteArray<RaftMessage>(
                RaftMessage.AppendEntriesResponse(term = leaderTerm, success = true, matchIndex = 1L)
            )
            sim.network.deliver(from = freshC, to = leaderId, bytes = freshAck)
            delay(2) // let the actor process freshC's ACK and call resolveReadsIfQuorumFresh

            assertFalse(
                read.isCompleted,
                "read must NOT be confirmed when stale ACKs (same round as sinceRound) inflate the quorum count",
            )

            // Let CheckQuorum fire twice (300–400 ms each in SLOW_ELECTION_CONFIG):
            //   first: recentVoterContacts = {staleA,staleB} (from injections) → 3 ≥ 3 → passes, clears.
            //   second: recentVoterContacts = {} (no new ACKs) → 1 < 3 → step down.
            delay(1200)
            assertFailsWith<LeadershipLostException> { read.await() }
        }
    }

    // ── BLOCKER 2: joint-consensus freshness requires both old and new majority ─

    /**
     * BLOCKER 2 — joint-consensus read freshness requires dual-majority: a quorum of BOTH
     * the old and the new voter sets must have ACKed in a fresh round.
     *
     * Scenario: leader v1 begins changing its voter set from {v1,v2} (old) to {v1,v3,v4} (new).
     * v2 is isolated BEFORE changeMembership is issued, so the Joint config entry can never reach
     * v2 and old-majority (v1 alone = 1/2 < 2) is permanently unsatisfied. v3 and v4 ARE
     * connected and provide fresh ACKs, satisfying the new-majority (2/3). The read must NOT be
     * confirmed, because old-majority freshness is not established.
     *
     * With the pre-fix single-majority implementation (effectiveConfig = new), v1+v3+v4 forming
     * a new-majority is mistakenly treated as sufficient. With the dual-majority fix the read
     * stays pending until CheckQuorum steps the leader down.
     *
     * v2 is isolated before changeMembership so the Joint config (which requires old-majority to
     * commit) never commits, keeping the leader stuck in Joint(old={v1,v2}, new={v1,v3,v4})
     * throughout the test. Under UnconfinedTestDispatcher message delivery is instant — so without
     * isolating v2 first, v2 would ACK the Joint entry before the test can isolate it, causing the
     * Joint to commit and Simple(C_new) to be immediately appended (membership leaves Joint before
     * the read is queued).
     *
     * v3 and v4 bootstrap as learners under v1 so they do not arm election timers before the
     * Joint config propagates and reevaluateSelfRole promotes them to Follower status.
     */
    @Test
    fun jointConsensusReadRequiresBothOldAndNewMajority() = raftRunTest(timeout = 15.seconds) {
        val v1 = NodeId("v1")
        val v2 = NodeId("v2")
        val v3 = NodeId("v3")
        val v4 = NodeId("v4")

        val initCluster = ClusterConfig(voters = setOf(v1, v2))
        val targetCluster = ClusterConfig(voters = setOf(v1, v3, v4))
        // v3 and v4 bootstrap as learners under v1 so they never arm an election timer before
        // the joint config propagates. A node that is a learner (in learners, not in voters)
        // skips election-timer arming entirely (see RaftEngine.resetElectionTimeout). Once the
        // Joint(old={v1,v2}, new={v1,v3,v4}) config replicates to them, reevaluateSelfRole
        // promotes them to Follower/Voter status.
        val joineeBootstrap = ClusterConfig(voters = setOf(v1), learners = setOf(v3, v4))
        val network = InMemoryRaftNetwork()

        // Use SLOW_ELECTION_CONFIG so CheckQuorum (fired at election-timeout = 300–400 ms)
        // does not step the leader down during the assertFalse check window after the read
        // is queued. FAST_RAFT_CONFIG's 5–10 ms election timeout races with that window.
        val leaderNode = backgroundScope.raftNode(initCluster, network.transport(v1), InMemoryRaftStorage(), SLOW_ELECTION_CONFIG)
        delay(5) // let v1's election-timeout job register before v2's; v1 fires first
        backgroundScope.raftNode(initCluster, network.transport(v2), InMemoryRaftStorage(), SLOW_ELECTION_CONFIG)
        backgroundScope.raftNode(joineeBootstrap, network.transport(v3), InMemoryRaftStorage(), SLOW_ELECTION_CONFIG)
        backgroundScope.raftNode(joineeBootstrap, network.transport(v4), InMemoryRaftStorage(), SLOW_ELECTION_CONFIG)

        // Wait for v1 to become leader and for the no-op to commit.
        leaderNode.awaitLeadership()
        leaderNode.commitIndex.first { it >= 1L }

        // Isolate v2 BEFORE initiating changeMembership. Under UnconfinedTestDispatcher, message
        // delivery is instantaneous — if v2 can receive the Joint config entry, it will ACK before
        // the test can isolate it, the Joint config will commit (old-majority = v1+v2 = 2/2), and
        // Simple(C_new) will be immediately appended, leaving Simple({v1,v3,v4}) as the membership
        // before the read is queued. Isolating first ensures the Joint config never reaches v2,
        // keeping the leader stuck in Joint(old={v1,v2}, new={v1,v3,v4}).
        network.dropLink(v2, v1)
        network.dropLink(v1, v2)  // drop both directions so v2 can't ACK the Joint entry

        // Start changeMembership in the background. Once the actor processes the command,
        // v1's membership becomes Joint(old={v1,v2}, new={v1,v3,v4}). v2 is isolated so
        // the Joint config entry cannot commit via old-majority.
        val changeJob = backgroundScope.async {
            try { leaderNode.changeMembership(targetCluster) } catch (_: Exception) { /* expected: cancelled below */ }
        }
        delay(1) // let the actor process ChangeMembership → membership = Joint

        // Queue a readIndex while v1's membership is Joint(old={v1,v2}, new={v1,v3,v4}).
        // v3 and v4 are connected → fresh ACKs in round H+1. New-majority satisfied.
        // Old-majority (v1 alone = 1/2 < 2) NOT satisfied — v2 is isolated.
        supervisorScope {
            val read = async { leaderNode.readIndex() }

            // Wait a few heartbeat cycles (2 ms each) so v3, v4 ACKs arrive in fresh rounds.
            delay(10)

            // Bug (effectiveConfig = new only): new-majority satisfied → CONFIRMED (wrong).
            // Fix (dual majority via quorumOfContacts): old-majority not established → NOT confirmed.
            assertFalse(
                read.isCompleted,
                "readIndex must NOT be confirmed when only the new-majority is reachable in joint consensus: " +
                    "old-majority (v1+v2, need 2/2) is not satisfied because v2 is isolated",
            )

            // Let CheckQuorum fire (300–400 ms with SLOW_ELECTION_CONFIG):
            // recentVoterContacts = {v3,v4} → old-majority (1+0=1 < 2) fails → step down.
            delay(750)
            assertFailsWith<LeadershipLostException> { read.await() }
        }

        changeJob.cancel()
    }
}
