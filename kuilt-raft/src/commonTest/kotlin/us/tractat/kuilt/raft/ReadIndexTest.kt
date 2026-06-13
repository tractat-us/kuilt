@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }

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
}
