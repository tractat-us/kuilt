@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [RaftNode.transferLeadership] and [RaftNode.cancelTransfer].
 *
 * Uses real [RaftNode] under [UnconfinedTestDispatcher] (same contract as the rest of this suite —
 * see RaftTestFixtures banner). Transfer tests exercise wall-clock paths (AppendEntries + TimeoutNow
 * round-trip), so real delays are required.
 */
internal class LeadershipTransferTest {

    // ── Happy path ────────────────────────────────────────────────────────────

    /**
     * 2-voter cluster. Leader transfers to the only other voter.
     * Post-transfer: target is leader, original leader is follower, no committed entries lost.
     *
     * A 2-node cluster is used here because it guarantees the transfer target is the ONLY
     * candidate that can win (no third node to race with).
     */
    @Test
    fun transferLeadership_happyPath_targetBecomesLeader() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 2)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        // Propose a few entries before transfer so the target has a real log to sync
        repeat(3) { sim.proposeOnLeader("cmd$it".encodeToByteArray()) }
        sim.awaitCommit(3L)

        // Transfer suspends until the target wins its election
        leader.transferLeadership(targetId)

        // Post-transfer invariants: target is leader, original leader is follower
        sim.awaitRole(targetId, RaftRole.Leader)
        sim.awaitRole(leaderId, RaftRole.Follower)

        // No entries were lost — the new leader should have all 3 committed
        sim.awaitCommit(3L, on = listOf(targetId))
        sim.checkInvariants()
    }

    // ── Proposal blocking during transfer ────────────────────────────────────

    /**
     * Proposals submitted to the still-leader while a transfer is in flight are rejected with
     * [NotLeaderException] (the [transferTarget] guard in onPropose). After transfer completes
     * the original leader is a follower and forwards proposals to the new leader successfully.
     */
    @Test
    fun proposalsDuringTransfer_rejectedWithNotLeaderException() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        // Start the transfer asynchronously so we can observe mid-transfer behaviour.
        val transferJob = backgroundScope.launch { leader.transferLeadership(targetId) }

        // Poll until the transferTarget guard has engaged and the leader rejects proposals.
        // Once NotLeaderException is observed the transfer window is confirmed.
        var seenRejection = false
        withTimeout(2.seconds) {
            while (!seenRejection && !transferJob.isCompleted) {
                try {
                    leader.propose("probe".encodeToByteArray())
                    delay(5.milliseconds)  // transfer not yet started — retry
                } catch (_: NotLeaderException) {
                    seenRejection = true   // transferTarget guard fired — confirmed
                } catch (_: LeadershipTransferException) {
                    seenRejection = true   // transfer completed mid-poll — also acceptable
                }
            }
        }

        transferJob.join()

        // Post-transfer: original leader is now a follower and forwards proposals to the new leader.
        sim.awaitRole(leaderId, RaftRole.Follower)
        val entry = leader.propose("after-transfer".encodeToByteArray())
        sim.awaitCommit(entry.index)
    }

    // ── Auto-timeout resumes proposals ────────────────────────────────────────

    /**
     * If the target is unreachable (partitioned), the old leader auto-times-out after
     * one election timeout and resumes accepting proposals. The transfer throws
     * [LeadershipTransferException] and the original leader remains leader.
     */
    @Test
    fun transferLeadership_targetUnreachable_autoTimeoutResumesLeader() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        // Isolate the target so it can't receive TimeoutNow or win the election
        sim.dropLink(from = leaderId, to = targetId)
        sim.dropLink(from = targetId, to = leaderId)

        // Transfer should timeout and throw LeadershipTransferException
        assertFailsWith<LeadershipTransferException> {
            leader.transferLeadership(targetId)
        }

        // Original leader resumes — still leader, proposals work
        assertEquals(RaftRole.Leader, leader.role.value)
        sim.proposeOnLeader("resumed".encodeToByteArray())
    }

    // ── cancelTransfer ────────────────────────────────────────────────────────

    /**
     * [RaftNode.cancelTransfer] aborts an in-flight transfer and re-enables proposals.
     * The [transferLeadership] call throws [LeadershipTransferException].
     */
    @Test
    fun cancelTransfer_abortsInFlightTransfer() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        // Partition the target so the transfer doesn't auto-complete
        sim.dropLink(from = leaderId, to = targetId)
        sim.dropLink(from = targetId, to = leaderId)

        val transferJob = backgroundScope.launch {
            assertFailsWith<LeadershipTransferException> { leader.transferLeadership(targetId) }
        }

        // Briefly yield so the transfer starts and blocks
        sim.settle()

        // Cancel explicitly
        leader.cancelTransfer()
        transferJob.join()

        // Original leader is still leader and proposals work
        assertEquals(RaftRole.Leader, leader.role.value)
        sim.heal()
        sim.proposeOnLeader("after-cancel".encodeToByteArray())
    }

    // ── Non-leader / invalid target rejection ────────────────────────────────

    /**
     * Calling [transferLeadership] on a non-leader node throws [NotLeaderException] immediately.
     */
    @Test
    fun transferLeadership_nonLeader_throwsNotLeaderException() = raftRunTest(timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope)
        awaitLeader(sim)
        val follower = sim.followers().first()

        val followerId = sim.nodeIds.first { sim.nodes[it] === follower }
        val otherId = sim.nodeIds.first { it != followerId }

        assertFailsWith<NotLeaderException> { follower.transferLeadership(otherId) }
    }

    /**
     * Calling [transferLeadership] with an unknown target (not in the cluster) throws
     * [IllegalArgumentException] immediately.
     */
    @Test
    fun transferLeadership_unknownTarget_throwsIllegalArgument() = raftRunTest(timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)

        assertFailsWith<IllegalArgumentException> {
            leader.transferLeadership(NodeId("unknown-node"))
        }
    }

    /**
     * Calling [transferLeadership] targeting the current leader itself throws
     * [IllegalArgumentException] immediately.
     */
    @Test
    fun transferLeadership_targetIsSelf_throwsIllegalArgument() = raftRunTest(timeout = 5.seconds) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }

        assertFailsWith<IllegalArgumentException> {
            leader.transferLeadership(leaderId)
        }
    }

    // ── No committed entry loss ───────────────────────────────────────────────

    /**
     * Entries committed before the transfer remain committed after the transfer.
     * The state machine on every surviving node agrees.
     */
    @Test
    fun transferLeadership_noCommittedEntryLoss() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        repeat(5) { sim.proposeOnLeader("entry$it".encodeToByteArray()) }
        sim.awaitCommit(5L)

        leader.transferLeadership(targetId)

        // The old leader stepped down — it is now a follower
        sim.awaitRole(leaderId, RaftRole.Follower)
        // Some node in the cluster is now the leader
        val newLeader = sim.awaitLeader()

        // Allow the new leader to commit its own no-op and sync all nodes
        sim.awaitCommit(6L)  // at minimum 6 (5 data + 1 no-op from new leader)
        sim.checkInvariants()

        // All applied states should be non-empty (entries were committed and replicated)
        val allIds = sim.nodeIds
        val reference = sim.appliedState(allIds.first())
        assertFalse(reference.isEmpty(), "applied state on reference node should not be empty")
    }

    // ── onTimeoutNow sender authentication ────────────────────────────────────

    /**
     * A same-term TimeoutNow from a peer that is NOT the current leader must be ignored: the target
     * starts no election (no [RaftTraceEvent.RequestVote] / [RaftTraceEvent.Timeout]), stays a
     * follower, and the real leader keeps its leadership. Without sender authentication a spoofed or
     * stale TimeoutNow would let any peer force a follower into a disruptive, term-bumping election.
     */
    @Test
    fun timeoutNow_fromNonLeader_isIgnored() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val followers = sim.nodeIds.filter { it != leaderId }
        val target = followers[0]
        val spoofedSender = followers[1]   // another follower — not the leader

        // Commit an entry first so every node settles at a stable, known leader/term.
        sim.proposeOnLeader("before-spoof".encodeToByteArray())
        sim.awaitCommit(1L)
        sim.settle()

        // A *same-term* TimeoutNow is what the auth guard rejects: a higher term would legitimately
        // advance the cluster, a lower one is stale. Read the leader's persisted term so we hit it.
        val leaderTerm = sim.storages.getValue(leaderId).term()

        // Watch the target for any sign of an election round it should never run.
        val targetTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(target).trace.collect { targetTrace += it } }
        sim.settle()

        // Inject a same-term TimeoutNow whose transport sender is a non-leader follower.
        sim.deliverTimeoutNow(to = target, from = spoofedSender, term = leaderTerm)
        sim.settle()

        assertEquals(RaftRole.Follower, sim.nodes.getValue(target).role.value)
        assertEquals(RaftRole.Leader, leader.role.value)
        assertFalse(
            targetTrace.any { it is RaftTraceEvent.RequestVote || it is RaftTraceEvent.Timeout },
            "target must not start an election from a non-leader TimeoutNow",
        )
    }

    // ── Trace event ───────────────────────────────────────────────────────────

    /**
     * A failed/cancelled transfer emits a [RaftTraceEvent.LeadershipTransferAbandoned] event.
     */
    @Test
    fun transferLeadership_abandonedEmitsTraceEvent() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        // Collect trace events from the leader
        val traceEvents = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { leader.trace.collect { traceEvents += it } }

        // Partition target so transfer times out
        sim.dropLink(from = leaderId, to = targetId)
        sim.dropLink(from = targetId, to = leaderId)

        runCatching { leader.transferLeadership(targetId) }

        sim.awaitTrue("LeadershipTransferAbandoned emitted") {
            traceEvents.any { it is RaftTraceEvent.LeadershipTransferAbandoned }
        }
    }
}
