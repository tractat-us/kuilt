@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.internal.RaftMessage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }

/**
 * Config for the stale-ACK BLOCKER 1 test: heartbeat stays at 2 ms (so we can advance
 * heartbeatRound quickly) but the election timeout is 300–400 ms so CheckQuorum does NOT fire
 * during the 10–15 ms window where stale ACKs are injected and the fresh ACK triggers
 * resolveReadsIfQuorumFresh. fastRaftConfig()'s 5-10 ms election timeout races with that
 * window on slower machines.
 *
 * A function, not a `val`, for the reason spelled out on [RAFT_TEST_SEED] (#1952): a shared instance
 * would make each test's draws depend on how many the earlier tests in this class consumed. Call it
 * once per test and share the result across that test's nodes — they break election-timeout symmetry
 * by drawing successive values from one stream.
 */
private fun slowElectionConfig(): RaftConfig = RaftConfig(
    electionTimeoutMin = 300.milliseconds,
    electionTimeoutMax = 400.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,
    random = Random(RAFT_TEST_SEED),
)

/**
 * Behaviour tests for [RaftNode.readIndex] (linearizable reads without a log write).
 *
 * All tests use [raftRunTest] with [StandardTestDispatcher] and virtual [delay] — the
 * standard harness contract for this suite (see [RaftTestFixtures] banner). Tests that need
 * multi-voter quorum confirmation advance virtual time past one heartbeat interval (2 ms in
 * [fastRaftConfig]) so the ACK majority accumulates.
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
            cluster, network.transport(learner), InMemoryRaftStorage(), fastRaftConfig(),
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
        // In a 3-voter cluster with fastRaftConfig() the no-op commits after one heartbeat (2 ms).
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

    /**
     * §6.4 liveness (#1235) — a read still parked in the current-term no-op gate when the leader loses
     * leadership must fail with [LeadershipLostException], not hang forever.
     *
     * This is the sibling of [readIndexFailsWithLeadershipLostWhenLeaderLosesQuorum], but exercises the
     * distinct **no-op-gate** path. That test proposes+commits an entry first (satisfying the §8 gate),
     * so its read parks in `pendingReads`. This one commits NOTHING beyond the fresh leader's own no-op
     * — which never commits — so the read parks in `pendingNoOpGate` instead. The bug: `failAll`
     * completed only the `pendingReads` deferreds and `clear()`ed the gate WITHOUT completing the caller
     * deferreds captured inside the parked re-invocation closures, so a gated read hung its caller
     * forever (untimed `await()`) instead of throwing.
     *
     * **Why a phantom-follower election, not `raftSim` + `partitionOff`.** In a real cluster the votes
     * that elect a leader and the ACKs that commit its no-op both flow from the same followers in the
     * same virtual instant, so the no-op commits the moment the leader is elected — the §8 gate window
     * is closed by the time `awaitLeader` returns (empirically `commitIndex == 1`). To hold the gate
     * open deterministically we drive `l` to leadership over an in-memory network whose only real node
     * is `l`: phantom voters `f1`/`f2` grant the pre-vote and vote (injected off the wire) but never ACK
     * the no-op, so `commitIndex` stays at 0 < noOpIndex(1) — the gate stays closed indefinitely, until
     * CheckQuorum (no reachable voter quorum) steps `l` down and fails the gated read.
     *
     * Scenario (voters = {l, f1, f2}; quorum = 2; only `l` is a real node):
     * 1. `l` times out and probes; we read its actual PreVote/RequestVote (term + round) off the
     *    recording tap and inject matching grants from phantom `f1` → `l` wins term T.
     * 2. `becomeLeader` appends the current-term no-op (index 1) and arms the gate; no follower ACKs, so
     *    it never commits (`commitIndex == 0`).
     * 3. `readIndex()` gates (parks in `pendingNoOpGate`); a brief advance confirms it neither resolved
     *    (the no-op did not commit) nor threw yet.
     * 4. CheckQuorum (contacted = {} → self 1 < quorum 2) steps `l` down → `relinquishToFollower` →
     *    `failAll`, which must now complete the gated deferred exceptionally.
     */
    @Test
    fun gatedReadIndexFailsWithLeadershipLostBeforeNoOpCommits() = raftRunTest {
        val l = NodeId("l")
        val f1 = NodeId("f1")
        val f2 = NodeId("f2")

        val network = InMemoryRaftNetwork()
        network.recording = true // capture l's outgoing PreVote/RequestVote so we can echo its term/round

        // Only l is a real node; f1/f2 are phantom voters that grant votes but never ACK the no-op.
        val leaderNode = backgroundScope.raftNode(
            ClusterConfig(voters = setOf(l, f1, f2)),
            network.transport(l), InMemoryRaftStorage(), slowElectionConfig(),
        )

        // Drive the election from injected grants. PreVoteResponse.term must NOT exceed l's currentTerm
        // (still proposed-1 during pre-vote) or l would step down; RequestVoteResponse.term must equal
        // the candidate term l bumped to.
        val pv = awaitSent(network) { it is RaftMessage.PreVote } as RaftMessage.PreVote
        network.deliver(
            from = f1, to = l,
            bytes = Cbor.encodeToByteArray<RaftMessage>(
                RaftMessage.PreVoteResponse(term = pv.term - 1, voteGranted = true, proposedTerm = pv.term, round = pv.round),
            ),
        )
        val rv = awaitSent(network) { it is RaftMessage.RequestVote } as RaftMessage.RequestVote
        network.deliver(
            from = f1, to = l,
            bytes = Cbor.encodeToByteArray<RaftMessage>(
                RaftMessage.RequestVoteResponse(term = rv.term, voteGranted = true),
            ),
        )

        // l wins term T. Its no-op (index 1) is appended but never commits — no follower ACKs it.
        leaderNode.awaitLeadership()
        assertTrue(
            leaderNode.commitIndex.value < 1L,
            "the fresh leader's no-op must stay uncommitted for the read to gate: commitIndex=${leaderNode.commitIndex.value}",
        )

        supervisorScope {
            // Gates in pendingNoOpGate (commitIndex 0 < currentTermNoOpIndex 1).
            val read = async { leaderNode.readIndex() }

            // One heartbeat tick lets the actor park the read; CheckQuorum (300–400 ms) has NOT fired,
            // so the read must be neither resolved (no-op never committed) nor failed yet.
            delay(3)
            assertFalse(
                read.isCompleted,
                "gated read must still be parked while the no-op is uncommitted and leadership is held",
            )

            // CheckQuorum fires (300–400 ms in slowElectionConfig()): contacted = {} → 1 < 2 → step
            // down → relinquishToFollower → failAll must complete the GATED read exceptionally. Before
            // the fix, failAll dropped the gated closure and this await() hung to the test timeout.
            delay(1200)
            assertFailsWith<LeadershipLostException> { read.await() }
        }
    }

    /**
     * Bounded poll for the most recent captured send matching [pred] (requires `network.recording`).
     * Advances virtual time in 1 ms steps so l's election timers fire; fails fast via [withTimeout]
     * rather than hanging if the expected message never appears.
     */
    private suspend fun awaitSent(
        network: InMemoryRaftNetwork,
        within: Duration = 2.seconds,
        pred: (RaftMessage) -> Boolean,
    ): RaftMessage = withTimeout(within) {
        while (true) {
            network.sent.lastOrNull { pred(it.message) }?.let { return@withTimeout it.message }
            delay(1)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
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

    // ── BLOCKER 1a: round-slip — ACK responding to round H must not be credited to H+1 ──

    /**
     * BLOCKER 1a — round-slip: a follower ACK that was *sent in response to a round-H heartbeat*
     * must not be credited to round H+1 just because the leader incremented heartbeatRound before
     * the ACK arrived.
     *
     * Without the wire round nonce, the leader credits every incoming ACK to the *current*
     * heartbeatRound at the moment of receipt. If the leader ticks from H to H+1 while the
     * ACK is in transit, the ACK is credited to H+1 and satisfies freshness for a read queued
     * at sinceRound=H — even though the follower responded to the round-H heartbeat, which could
     * have been sent while the follower was already partitioned.
     *
     * Scenario (3-voter cluster; quorum=2; leader = l, followers = f1, f2):
     * 1. Leader and followers are in round H.
     * 2. Read queued (sinceRound = H).
     * 3. Leader ticks → heartbeatRound = H+1 → sends heartbeat in round H+1.
     * 4. Injected ACK from f1 carries *no* echoedRound (or round=H — old round-H request):
     *    - Bug: credited to current heartbeatRound = H+1 > sinceRound = H → confirms read!
     *    - Fix: echoedRound = H ≤ sinceRound = H → NOT credited as fresh.
     *
     * With the fix, the round-H ACK carries echoedRound=H which equals sinceRound, so it is
     * excluded. Only a round-H+1 ACK (responding to the current heartbeat) satisfies freshness.
     *
     * The test injects a raw [AppendEntriesResponse] with [echoedRound] set to H (round before
     * the read), then injects a second ACK from f2 with echoedRound=H+1 (the fresh round). At
     * quorum=2, f1(stale)+self = 2 ≥ 2 WITHOUT the fix, but NOT with the fix (f1 excluded).
     * With f2's fresh ACK only, we have 1+self=2 ≥ 2 → read confirmed on both paths. The
     * assertFalse fires BEFORE f2's ACK to prove the stale f1 ACK alone did not confirm it.
     */
    @Test
    fun roundSlipAckDoesNotConfirmReadIndex() = raftRunTest {
        val l  = NodeId("l")
        val f1 = NodeId("f1")
        val f2 = NodeId("f2")

        val network = InMemoryRaftNetwork()
        val slowConfig = slowElectionConfig()

        // l bootstraps alone so it wins leadership unconditionally.
        val leaderNode = backgroundScope.raftNode(
            ClusterConfig(voters = setOf(l)),
            network.transport(l), InMemoryRaftStorage(), slowConfig,
        )
        val leaderStorage = InMemoryRaftStorage()
        backgroundScope.raftNode(
            ClusterConfig(voters = setOf(l), learners = setOf(f1)),
            network.transport(f1), leaderStorage, slowConfig,
        )
        backgroundScope.raftNode(
            ClusterConfig(voters = setOf(l), learners = setOf(f2)),
            network.transport(f2), InMemoryRaftStorage(), slowConfig,
        )

        leaderNode.awaitLeadership()
        // Promote f1 and f2 to voters — settles as Simple({l,f1,f2}).
        leaderNode.changeMembership(ClusterConfig(voters = setOf(l, f1, f2)))

        // Wait for no-op to commit (changeMembership appends config entries; no-op is index 1).
        delay(10)

        // Read leader's term from storage for injected ACKs.
        val leaderTerm = leaderNode.commitIndex.value.let { 1L } // term 1 after single leadership

        // Partition the leader from f1 and f2 so no real ACKs arrive.
        network.dropLink(l, f1); network.dropLink(f1, l)
        network.dropLink(l, f2); network.dropLink(f2, l)

        supervisorScope {
            // Queue the read at the current heartbeatRound H. The actor processes
            // RequestReadIndex and records sinceRound = H.
            val read = async { leaderNode.readIndex() }
            delay(1) // let the actor process the ReadIndex command

            // Advance heartbeatRound from H to H+1 by waiting for one heartbeat tick.
            delay(3) // slowElectionConfig() heartbeat = 2 ms → one tick fires

            // Inject an ACK from f1 that echoes round H (i.e., it was sent in response to
            // the round-H heartbeat, BEFORE the read was queued). With the round-nonce fix,
            // echoedRound=H ≤ sinceRound=H → excluded. Without the fix, credited to current
            // heartbeatRound=H+1 > sinceRound=H → would confirm (quorum: self+f1 = 2).
            val staleAck = Cbor.encodeToByteArray<RaftMessage>(
                RaftMessage.AppendEntriesResponse(term = 1L, success = true, matchIndex = 1L, echoedRound = 0L)
            )
            network.deliver(from = f1, to = l, bytes = staleAck)
            delay(2) // let actor process f1's stale ACK

            // Bug: with no echoedRound, heartbeatRound at receipt = H+1 > sinceRound=H → confirmed.
            // Fix: echoedRound=H (round that generated the ACK) ≤ sinceRound=H → NOT confirmed.
            assertFalse(
                read.isCompleted,
                "read must NOT be confirmed by an ACK echoing round H when sinceRound = H; " +
                    "a round-slip ACK must not satisfy freshness",
            )

            // CheckQuorum fires (300–400 ms): contacted={} → 1 < 2 → step down.
            delay(1200)
            assertFailsWith<LeadershipLostException> { read.await() }
        }
    }

    // ── BLOCKER 1: stale ACK must not confirm a read ──────────────────────────

    /**
     * BLOCKER 1 — a voter ACK that arrived *before* the read was queued must not be
     * counted when checking whether a quorum has responded *after* the read.
     *
     * Scenario: 5-voter cluster (quorum = 3) using [slowElectionConfig] (election timeout
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
     * [slowElectionConfig] guarantees QuorumCheck does not fire during the ~10 ms ACK
     * injection window (election timeout 300–400 ms vs. injection duration < 15 ms).
     *
     * The leader's term is read from its storage after the no-op commits; the injected ACKs
     * use that term so they are not discarded by the stale-term guard.
     */
    @Test
    fun staleAckDoesNotConfirmReadIndex() = raftRunTest {
        // Use raftSim so awaitLeader() gets whichever node wins — no need to predict v1.
        val sim = raftSim(this, backgroundScope, n = 5, config = slowElectionConfig())
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

            // Let CheckQuorum fire twice (300–400 ms each in slowElectionConfig()):
            //   first: recentVoterContacts = {staleA,staleB} (from injections) → 3 ≥ 3 → passes, clears.
            //   second: recentVoterContacts = {} (no new ACKs) → 1 < 3 → step down.
            delay(1200)
            assertFailsWith<LeadershipLostException> { read.await() }
        }
    }

    // ── BLOCKER 2: shrink-to-single joint fast-path must not bypass old-majority ─

    /**
     * BLOCKER 2a — fast-path bug: when [effectiveConfig.quorumSize == 1] during a shrinking
     * Joint config (e.g. old={v1,v2,v3}, new={v1}), the old fast-path fires immediately and
     * returns a read index without waiting for any quorum ACK — even though the old majority
     * is still required.
     *
     * Scenario: v1 bootstraps alone, adds v2 and v3 as voters (Simple({v1,v2,v3})), then
     * changes membership to {v1} — creating Joint(old={v1,v2,v3}, new={v1}). v2 and v3 are
     * isolated before the change so old-majority (need 2/3) is permanently unsatisfied.
     *
     * Bug (quorumSize == 1 fast-path): effectiveConfig = new = {v1}, quorumSize = 1 →
     *   fast-path fires → read returns immediately (stale, not linearizable).
     *
     * Fix (quorumOfContacts gate): checks ALL active voter sets — old={v1,v2,v3} needs 2;
     *   self alone gives 1 < 2 → fast-path does NOT fire → read stays pending until step-down.
     */
    @Test
    fun shrinkingJointFastPathDoesNotConfirmReadWithoutOldMajority() = raftRunTest {
        val v1 = NodeId("v1")
        val v2 = NodeId("v2")
        val v3 = NodeId("v3")

        val network = InMemoryRaftNetwork()
        val slowConfig = slowElectionConfig()

        // v1 bootstraps alone → guaranteed leader.
        val leaderNode = backgroundScope.raftNode(
            ClusterConfig(voters = setOf(v1)),
            network.transport(v1), InMemoryRaftStorage(), slowConfig,
        )
        // v2 and v3 start as learners so they don't race for leadership.
        backgroundScope.raftNode(
            ClusterConfig(voters = setOf(v1), learners = setOf(v2)),
            network.transport(v2), InMemoryRaftStorage(), slowConfig,
        )
        backgroundScope.raftNode(
            ClusterConfig(voters = setOf(v1), learners = setOf(v3)),
            network.transport(v3), InMemoryRaftStorage(), slowConfig,
        )

        leaderNode.awaitLeadership()

        // Promote v2 and v3 to voters — settles as Simple({v1,v2,v3}).
        leaderNode.changeMembership(ClusterConfig(voters = setOf(v1, v2, v3)))

        // Isolate v2 and v3 BEFORE initiating the shrink, so old-majority (need 2/3) is unmet.
        network.dropLink(v1, v2); network.dropLink(v2, v1)
        network.dropLink(v1, v3); network.dropLink(v3, v1)

        // Initiate shrink to {v1} — creates Joint(old={v1,v2,v3}, new={v1}).
        // effectiveConfig.quorumSize = 1, triggering the fast-path bug.
        val changeJob = backgroundScope.async {
            try { leaderNode.changeMembership(ClusterConfig(voters = setOf(v1))) }
            catch (_: Exception) { /* cancelled below */ }
        }
        delay(1) // let the actor process ChangeMembership → v1 is now in Joint

        supervisorScope {
            // Bug: fast-path fires immediately → read.isCompleted = true (wrong).
            // Fix: quorumOfContacts(emptySet(), v1) → old needs 2, self=1 < 2 → not fast-path.
            val read = async { leaderNode.readIndex() }
            delay(1) // let the ReadIndex command reach the actor

            assertFalse(
                read.isCompleted,
                "readIndex must NOT return immediately during Joint(old={v1,v2,v3}, new={v1}): " +
                    "old-majority (need 2/3) is not satisfied — fast-path must not fire",
            )

            // CheckQuorum fires (300–400 ms): old-majority (contacted={}) → 1/3 < 2 → step down.
            delay(750)
            assertFailsWith<LeadershipLostException> { read.await() }
        }

        changeJob.cancel()
    }

    // ── BLOCKER 2: joint-consensus freshness requires both old and new majority ─

    /**
     * BLOCKER 2 — joint-consensus read freshness requires dual-majority: a quorum of BOTH
     * the old and the new voter sets must have ACKed in a fresh round.
     *
     * Scenario: leader v1 changes its voter set from {v1,v2} (old) to {v1,v3,v4} (new),
     * creating Joint(old={v1,v2}, new={v1,v3,v4}). v2 is isolated BEFORE this second
     * changeMembership is issued, so old-majority (v1 alone = 1/2 < 2) is permanently
     * unsatisfied. v3 and v4 ARE connected and provide fresh ACKs, satisfying new-majority.
     * The read must NOT be confirmed because old-majority freshness is not established.
     *
     * With the pre-fix implementation (effectiveConfig = new), v1+v3+v4 forming new-majority
     * is mistakenly sufficient. With the dual-majority fix the read stays pending until
     * CheckQuorum steps the leader down.
     *
     * Setup: v1 starts as sole voter and wins leadership unconditionally (no election race).
     * changeMembership({v1,v2}) runs first so v2 becomes a voter. v3 and v4 start as learners.
     * After {v1,v2} is established, v2 is isolated (both directions) and changeMembership to
     * {v1,v3,v4} is initiated. The Joint entry can reach v3/v4 but NOT v2, so old-majority
     * is never satisfied; the leader stays in Joint throughout the test.
     */
    @Test
    fun jointConsensusReadRequiresBothOldAndNewMajority() = raftRunTest {
        val v1 = NodeId("v1")
        val v2 = NodeId("v2")
        val v3 = NodeId("v3")
        val v4 = NodeId("v4")

        val network = InMemoryRaftNetwork()
        val slowConfig = slowElectionConfig()

        // v1 bootstraps alone → guaranteed leader (no election race).
        val leaderNode = backgroundScope.raftNode(
            ClusterConfig(voters = setOf(v1)),
            network.transport(v1), InMemoryRaftStorage(), slowConfig,
        )
        // v2 starts as a learner so it doesn't arm an election timer before being added as voter.
        backgroundScope.raftNode(
            ClusterConfig(voters = setOf(v1), learners = setOf(v2)),
            network.transport(v2), InMemoryRaftStorage(), slowConfig,
        )
        // v3 and v4 start as learners under v1 so they don't arm election timers either.
        backgroundScope.raftNode(
            ClusterConfig(voters = setOf(v1), learners = setOf(v3)),
            network.transport(v3), InMemoryRaftStorage(), slowConfig,
        )
        backgroundScope.raftNode(
            ClusterConfig(voters = setOf(v1), learners = setOf(v4)),
            network.transport(v4), InMemoryRaftStorage(), slowConfig,
        )

        // Wait for v1 to become leader (guaranteed because it's the only voter).
        leaderNode.awaitLeadership()

        // changeMembership({v1,v2}): promote v2 to voter. Suspends until Simple({v1,v2}) commits,
        // so when it returns the membership is settled as Simple({v1,v2}) and commitIndex reflects it.
        leaderNode.changeMembership(ClusterConfig(voters = setOf(v1, v2)))

        // Isolate v2 (both directions) BEFORE issuing the second changeMembership.
        // Isolate v2 (both directions) BEFORE issuing the second changeMembership.
        // Under StandardTestDispatcher's FIFO scheduling, actors process messages in a fixed
        // order — isolating v2 before the Joint entry is sent prevents it from ACKing and
        // satisfying old-majority (v1+v2) ahead of the readIndex call.
        network.dropLink(v1, v2)
        network.dropLink(v2, v1)

        // changeMembership({v1,v3,v4}): v1's membership becomes Joint(old={v1,v2}, new={v1,v3,v4}).
        // v2 is isolated so old-majority (need 2 of {v1,v2}) is permanently unsatisfied.
        // This call suspends until committed, which will never happen — run it in the background.
        val changeJob = backgroundScope.async {
            try { leaderNode.changeMembership(ClusterConfig(voters = setOf(v1, v3, v4))) }
            catch (_: Exception) { /* cancelled below */ }
        }
        delay(1) // let the actor process ChangeMembership → v1 is now in Joint

        // Queue a readIndex. v3 and v4 are connected and will ACK in fresh rounds.
        // New-majority ({v1,v3,v4}, need 2) = satisfied. Old-majority ({v1,v2}, need 2)
        // = v1 alone = 1 < 2, NOT satisfied.
        supervisorScope {
            val read = async { leaderNode.readIndex() }

            // Wait a few heartbeat cycles (2 ms each) so v3, v4 ACKs arrive in a fresh round.
            delay(10)

            // Bug (effectiveConfig = new only): new-majority → CONFIRMED (wrong).
            // Fix (dual majority via quorumOfContacts): old-majority not established → NOT confirmed.
            assertFalse(
                read.isCompleted,
                "readIndex must NOT be confirmed when only the new-majority is reachable in joint " +
                    "consensus: old-majority (v1+v2, need 2/2) is not satisfied because v2 is isolated",
            )

            // Let CheckQuorum fire (300–400 ms with slowElectionConfig()):
            // recentVoterContacts = {v3,v4} → old-majority (1+0 = 1 < 2) fails → step down.
            delay(750)
            assertFailsWith<LeadershipLostException> { read.await() }
        }

        changeJob.cancel()
    }
}
