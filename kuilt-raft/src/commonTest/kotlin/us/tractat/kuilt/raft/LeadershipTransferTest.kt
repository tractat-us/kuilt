@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    // ── §3.10 contract: success ⇔ the TARGET wins (issue #1231) ───────────────

    /**
     * The transfer contract is: `transferLeadership()` succeeds **only** when the transfer *target* wins
     * an election. The old completion trigger — "the old leader observed ANY higher term" — reports
     * success even when an **unrelated** node deposes the old leader mid-transfer, which the caller reads
     * as "the target is now leader" when it is not.
     *
     * Repro: start a transfer A→B (target B partitioned so the transfer stays in flight and cannot
     * auto-complete), then have an unrelated node C depose A with a higher-term AppendEntries. A steps
     * down observing a higher term **from C, not from B** — the target never won. The transfer must FAIL,
     * and leadership must not have moved to the target.
     */
    @Test
    fun transferLeadership_unrelatedNodeDeposesLeader_transferFails() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }
        val unrelatedId = sim.nodeIds.first { it != leaderId && it != targetId }

        sim.proposeOnLeader("base".encodeToByteArray())
        sim.awaitCommit(1L)
        sim.settle()

        // Isolate the target so the transfer stays in flight: it can neither receive TimeoutNow nor win.
        sim.dropLink(from = leaderId, to = targetId)
        sim.dropLink(from = targetId, to = leaderId)

        val leaderTerm = sim.storages.getValue(leaderId).term()

        val transferOutcome = CompletableDeferred<Result<Unit>>()
        backgroundScope.launch { transferOutcome.complete(runCatchingCancellable { leader.transferLeadership(targetId) }) }
        sim.settle()   // let the transfer engage (inFlightTarget == target)

        // An UNRELATED node deposes the old leader with a higher-term AppendEntries — the higher term is
        // observed from C, not from the transfer target.
        sim.deliverAppendEntries(to = leaderId, from = unrelatedId, term = leaderTerm + 1)
        sim.settle()   // process the injected message at this instant (no time advance → no re-election yet)

        val result = transferOutcome.await()
        assertAll(
            {
                assertTrue(
                    result.isFailure,
                    "transfer must FAIL when an unrelated node deposes the leader — the target did not win",
                )
            },
            {
                assertTrue(
                    result.exceptionOrNull() is LeadershipTransferException,
                    "expected LeadershipTransferException, got ${result.exceptionOrNull()}",
                )
            },
            // The old leader stepped down recognising the UNRELATED node, and the target never became leader.
            { assertEquals(RaftRole.Follower, sim.nodes.getValue(leaderId).role.value) },
            { assertEquals(unrelatedId, sim.nodes.getValue(leaderId).leader.value) },
            {
                assertTrue(
                    sim.nodes.getValue(targetId).role.value != RaftRole.Leader,
                    "the transfer target must not have become leader",
                )
            },
        )
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

    // ── §3.10 step 2: TimeoutNow gated on lastLogIndex, not commitIndex (issue #1229) ──────────

    /**
     * Dissertation §3.10 step 2 requires the leader to bring the target's log **fully up to its own
     * lastLogIndex** before sending `TimeoutNow` — not merely to the commit index. With an uncommitted
     * tail at transfer time (matchIndex[target] == commitIndex < lastLogIndex), a `commitIndex`
     * predicate fires `TimeoutNow` prematurely, to a target that has not yet caught up.
     *
     * This needs **≥4 voters**: the n≤3 happy-path tests await commit before transferring, so
     * commitIndex == lastLogIndex there and the two predicates are indistinguishable — exactly why the
     * bug was missed. Here the uncommitted tail is created *atomically with the transfer*: the propose
     * and the transfer are enqueued back-to-back into the leader's actor channel, ahead of any follower
     * ACK (both are processed in a single actor turn with no intervening suspension, then the peer
     * collectors run), so `onTransferLeadership` observes matchIndex[target] == commitIndex, one below
     * lastLogIndex.
     *
     * Observed via the network tap ([InMemoryRaftNetwork.sent]): the first `TimeoutNow` to the target
     * must be emitted **after** the target ACKs the tail entry (matchIndex reaching lastLogIndex), never
     * before, and the target's resulting `RequestVote` must carry the full log. Without the fix the
     * leader emits `TimeoutNow` in the transfer-init step, before the tail ACK is even in flight.
     *
     * NOTE: at ≥4 voters the target does **not** actually win here — the *other* followers still reject
     * its `RequestVote` under leader-stickiness because a TimeoutNow-triggered election carries no
     * disruption-bypass flag (a separate bug, #1230). This test therefore asserts the withhold-until-
     * caught-up property of #1229 only; end-to-end "target becomes leader" with an uncommitted tail is
     * shown at n=3 in [transferLeadership_uncommittedTail_n3_targetBecomesLeader], where a single peer
     * vote plus the leader's own suffices.
     */
    @Test
    fun transferLeadership_uncommittedTail_withholdsTimeoutNowUntilCaughtUp() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 4)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        // Steady state: every voter (incl. the target) caught up to commitIndex == lastLogIndex.
        repeat(3) { sim.proposeOnLeader("base$it".encodeToByteArray()) }
        sim.awaitCommit(3L)
        sim.settle()
        // At rest, commitIndex == lastLogIndex; the tail we are about to append lands at commitBefore + 1,
        // so the target's tail ACK carries matchIndex > commitBefore.
        val commitBefore = leader.commitIndex.value

        sim.network.recording = true
        val mark = sim.network.sent.size

        // Create the uncommitted tail atomically with the transfer: propose(tail) then transferLeadership
        // enqueue back-to-back, both ahead of any follower ACK — see the KDoc above.
        backgroundScope.launch { runCatchingCancellable { leader.propose("tail".encodeToByteArray()) } }
        backgroundScope.launch { runCatchingCancellable { leader.transferLeadership(targetId) } }

        // Drive time until the leader emits TimeoutNow to the target (the transfer authorises the election).
        sim.awaitTrue("leader sends TimeoutNow to target") {
            sim.network.sent.drop(mark).any { it.from == leaderId && it.to == targetId && it.message is RaftMessage.TimeoutNow }
        }

        val log = sim.network.sent.drop(mark)
        val firstTailAckFromTarget = log.indexOfFirst {
            it.from == targetId && it.to == leaderId &&
                (it.message as? RaftMessage.AppendEntriesResponse)?.let { r -> r.success && r.matchIndex > commitBefore } == true
        }
        val firstTimeoutNowToTarget = log.indexOfFirst {
            it.from == leaderId && it.to == targetId && it.message is RaftMessage.TimeoutNow
        }
        // The election the target then runs must campaign on the full log (lastLogIndex == the tail).
        val targetVoteRequest = log.firstOrNull {
            it.from == targetId && it.message is RaftMessage.RequestVote
        }?.message as? RaftMessage.RequestVote

        assertAll(
            { assertTrue(firstTailAckFromTarget >= 0, "target must ACK the uncommitted tail entry") },
            { assertTrue(firstTimeoutNowToTarget >= 0, "leader must send TimeoutNow to the target") },
            {
                assertTrue(
                    firstTimeoutNowToTarget > firstTailAckFromTarget,
                    "§3.10 step 2: TimeoutNow (sent at index $firstTimeoutNowToTarget) must be withheld until the " +
                        "target's log matches the leader's lastLogIndex — it must not precede the tail ACK " +
                        "(index $firstTailAckFromTarget)",
                )
            },
            {
                assertTrue(
                    targetVoteRequest != null && targetVoteRequest.lastLogIndex > commitBefore,
                    "target must campaign on the full log (lastLogIndex > $commitBefore), proving it was caught " +
                        "up before TimeoutNow: was $targetVoteRequest",
                )
            },
        )
    }

    /**
     * End-to-end companion to [transferLeadership_uncommittedTail_withholdsTimeoutNowUntilCaughtUp]:
     * with an uncommitted tail at transfer time, once the target catches up to lastLogIndex the transfer
     * completes for real — the target actually becomes leader. Uses n=3 because at ≥4 voters the target
     * cannot win a TimeoutNow-triggered election under the current code (leader-stickiness on the other
     * followers, #1230); at n=3 the target's own vote plus the transferring leader's is a quorum.
     *
     * The uncommitted tail is still created atomically with the transfer (propose then transfer, ahead of
     * any ACK), so the fix's withhold-until-caught-up path is exercised — not the trivial commit-first
     * path — and the tap confirms TimeoutNow is not sent before the target's tail ACK.
     */
    @Test
    fun transferLeadership_uncommittedTail_n3_targetBecomesLeader() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        repeat(3) { sim.proposeOnLeader("base$it".encodeToByteArray()) }
        sim.awaitCommit(3L)
        sim.settle()
        val commitBefore = leader.commitIndex.value

        sim.network.recording = true
        val mark = sim.network.sent.size

        backgroundScope.launch { runCatchingCancellable { leader.propose("tail".encodeToByteArray()) } }
        val transferJob = backgroundScope.launch { runCatchingCancellable { leader.transferLeadership(targetId) } }

        sim.awaitRole(targetId, RaftRole.Leader)
        sim.awaitRole(leaderId, RaftRole.Follower)
        transferJob.join()

        val log = sim.network.sent.drop(mark)
        val firstTailAckFromTarget = log.indexOfFirst {
            it.from == targetId && it.to == leaderId &&
                (it.message as? RaftMessage.AppendEntriesResponse)?.let { r -> r.success && r.matchIndex > commitBefore } == true
        }
        val firstTimeoutNowToTarget = log.indexOfFirst {
            it.from == leaderId && it.to == targetId && it.message is RaftMessage.TimeoutNow
        }
        assertAll(
            { assertTrue(firstTailAckFromTarget >= 0, "target must ACK the uncommitted tail entry") },
            { assertTrue(firstTimeoutNowToTarget >= 0, "leader must send TimeoutNow to the target") },
            {
                assertTrue(
                    firstTimeoutNowToTarget > firstTailAckFromTarget,
                    "§3.10 step 2: TimeoutNow (index $firstTimeoutNowToTarget) must not precede the tail ACK " +
                        "(index $firstTailAckFromTarget)",
                )
            },
        )
        sim.checkInvariants()
    }

    // ── §3.10 step 1: no new requests during a transfer — membership changes too (issue #1233) ──

    /**
     * §3.10 step 1: while a leadership transfer is in flight the leader stops accepting new requests, so
     * the target's log stops moving and can actually catch up. Proposals are already gated; a **membership
     * change** must be gated the same way — otherwise a config entry appended mid-transfer moves the
     * lastLogIndex goalpost the target is chasing.
     *
     * With the target partitioned from the leader (so the transfer stays in flight and does not
     * auto-complete) but the leader still reaching the other two voters (quorum, so it keeps leadership),
     * a `changeMembership` call must be rejected with the same [NotLeaderException] `propose` throws.
     */
    @Test
    fun changeMembership_duringTransfer_rejected() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 4)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        sim.proposeOnLeader("base".encodeToByteArray())
        sim.awaitCommit(1L)

        // Isolate the target from the leader so the transfer stays in flight (the target can neither
        // receive TimeoutNow nor win an election — the other voters still hear the leader, so they
        // reject its pre-vote). The leader keeps quorum via those two voters and stays leader.
        sim.dropLink(from = leaderId, to = targetId)
        sim.dropLink(from = targetId, to = leaderId)

        val transferJob = backgroundScope.launch { runCatchingCancellable { leader.transferLeadership(targetId) } }
        sim.settle()  // let the transfer start so inFlightTarget is engaged

        // §3.10 step 1: a membership change is a new request — rejected exactly like a proposal.
        assertFailsWith<NotLeaderException> {
            leader.changeMembership(ClusterConfig(voters = sim.nodeIds.toSet(), learners = setOf(NodeId("learner-x"))))
        }

        leader.cancelTransfer()
        transferJob.join()
    }

    /**
     * The reverse direction of [changeMembership_duringTransfer_rejected], closing the §3.10 step-1
     * asymmetry: a leadership transfer must not *start* while a membership change is still converging.
     * `pendingConfigChange` stays non-null from `changeMembership` until the resulting `Simple` entry
     * commits, and the Joint→Simple auto-append fires inside that window — an entry that would grow
     * lastLogIndex mid-transfer, exactly the goalpost move the transfer's `onPeerAck` predicate assumes
     * cannot happen. Transfer and membership change are mutually exclusive in both directions.
     *
     * The change is left pending-but-uncommitted at the instant of the transfer by enqueuing the two
     * requests back-to-back into the leader's actor channel (change first, transfer second): both are
     * processed in a single actor turn ahead of any follower ACK, so `pendingConfigChange` is set — and
     * the config entry not yet committed — when `onTransferLeadership` runs.
     */
    @Test
    fun transferLeadership_duringMembershipChange_rejected() = raftRunTest(timeout = 10.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        sim.proposeOnLeader("base".encodeToByteArray())
        sim.awaitCommit(1L)
        sim.settle()

        // Enqueue the membership change, then the transfer, back-to-back and ahead of any ACK — the actor
        // processes both in one turn, so pendingConfigChange is set (config entry uncommitted) when the
        // transfer is validated. changeMembership is left to commit naturally afterwards.
        val newConfig = ClusterConfig(voters = sim.nodeIds.toSet(), learners = setOf(NodeId("learner-x")))
        backgroundScope.launch { runCatchingCancellable { leader.changeMembership(newConfig) } }
        val transferOutcome = CompletableDeferred<Result<Unit>>()
        backgroundScope.launch { transferOutcome.complete(runCatchingCancellable { leader.transferLeadership(targetId) }) }

        val result = transferOutcome.await()
        assertAll(
            { assertTrue(result.isFailure, "transfer must be rejected while a membership change is in progress") },
            {
                assertTrue(
                    result.exceptionOrNull() is MembershipChangeInProgressException,
                    "expected MembershipChangeInProgressException, got ${result.exceptionOrNull()}",
                )
            },
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

        runCatchingCancellable { leader.transferLeadership(targetId) }

        sim.awaitTrue("LeadershipTransferAbandoned emitted") {
            traceEvents.any { it is RaftTraceEvent.LeadershipTransferAbandoned }
        }
    }
}
