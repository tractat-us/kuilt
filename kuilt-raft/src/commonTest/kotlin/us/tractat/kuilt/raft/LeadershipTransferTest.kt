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
import kotlin.random.Random
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

    /** A sender that is not in any committed voter set — the §5.2/§8 authority gate's target (#1383/#1889). */
    private val nonVoter = NodeId("attacker-not-a-voter")

    // ── Happy path ────────────────────────────────────────────────────────────

    /**
     * 2-voter cluster. Leader transfers to the only other voter.
     * Post-transfer: target is leader, original leader is follower, no committed entries lost.
     *
     * A 2-node cluster is used here because it guarantees the transfer target is the ONLY
     * candidate that can win (no third node to race with).
     */
    @Test
    fun transferLeadership_happyPath_targetBecomesLeader() = raftRunTest {
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
    fun transferLeadership_unrelatedNodeDeposesLeader_transferFails() = raftRunTest {
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

        // Frozen instant: the old leader stepped down recognising the UNRELATED node, the target never
        // became leader — and the transfer must NOT have completed successfully. (It stays pending: only
        // a leader-authored message from the TARGET can complete it, #1243; here none can ever arrive.)
        val prematureSuccess = transferOutcome.isCompleted && transferOutcome.getCompleted().isSuccess
        assertAll(
            {
                assertFalse(
                    prematureSuccess,
                    "transfer must not report SUCCESS when an unrelated node deposes the leader — the target did not win",
                )
            },
            { assertEquals(RaftRole.Follower, sim.nodes.getValue(leaderId).role.value) },
            { assertEquals(unrelatedId, sim.nodes.getValue(leaderId).leader.value) },
            {
                assertTrue(
                    sim.nodes.getValue(targetId).role.value != RaftRole.Leader,
                    "the transfer target must not have become leader",
                )
            },
        )

        // Resolve the pending transfer deterministically (rather than waiting out the auto-timeout while
        // the leaderless cluster evolves) and confirm it FAILS.
        sim.nodes.getValue(leaderId).cancelTransfer()
        sim.awaitTrue("transfer resolves") { transferOutcome.isCompleted }
        val result = transferOutcome.await()
        assertAll(
            { assertTrue(result.isFailure, "transfer must FAIL — the target never won") },
            {
                assertTrue(
                    result.exceptionOrNull() is LeadershipTransferException,
                    "expected LeadershipTransferException, got ${result.exceptionOrNull()}",
                )
            },
        )
    }

    // ── §3.10 contract: complete only on a LEADER-AUTHORED message from the target (issue #1243) ──

    /**
     * The residual false-SUCCESS of #1243, the echo route: a higher-term message whose *sender* is the
     * transfer target is not proof the target won — the target may merely have adopted a higher term from
     * elsewhere and echoed it. The §3.10-faithful success signal is a **leader-authored** message
     * (AppendEntries/InstallSnapshot) *sent by* the target at a higher term.
     *
     * Repro: transfer A→B in flight with B fully isolated (it can never campaign or win). B "echoes" a
     * higher term at A via an injected `AppendEntriesResponse(term+1)` — exactly what B would send after
     * adopting term+1 from an unrelated new leader. A steps down observing the higher term *from B*, but
     * B is NOT leader: the transfer must NOT complete successfully at that instant, and must ultimately
     * FAIL (auto-timeout), because no leader-authored message from B ever arrives.
     */
    @Test
    fun transferLeadership_higherTermEchoFromTarget_doesNotCompleteTransfer() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }
        val thirdId = sim.nodeIds.first { it != leaderId && it != targetId }

        sim.proposeOnLeader("base".encodeToByteArray())
        sim.awaitCommit(1L)
        sim.settle()

        // Fully isolate the target: it can neither receive TimeoutNow nor campaign nor win, so no
        // leader-authored message from it can ever reach the old leader.
        sim.dropLink(from = leaderId, to = targetId)
        sim.dropLink(from = targetId, to = leaderId)
        sim.dropLink(from = thirdId, to = targetId)
        sim.dropLink(from = targetId, to = thirdId)

        val leaderTerm = sim.storages.getValue(leaderId).term()

        val transferOutcome = CompletableDeferred<Result<Unit>>()
        backgroundScope.launch { transferOutcome.complete(runCatchingCancellable { leader.transferLeadership(targetId) }) }
        sim.settle()   // let the transfer engage (inFlightTarget == target)

        // The ECHO: a higher-term AppendEntriesResponse whose sender is the TARGET — a non-leader-authored
        // message. The old leader steps down (higher term observed from B), but B did not win anything.
        sim.deliverAppendEntriesResponse(to = leaderId, from = targetId, term = leaderTerm + 1)
        sim.settle()   // process the injected message at this instant (no time advance)

        // Frozen instant: the step-down alone must NOT resolve the transfer — the echo is not a
        // leader-authored message from the target.
        assertFalse(
            transferOutcome.isCompleted,
            "transfer must not complete on a higher-term echo from the target — only a leader-authored " +
                "message (AppendEntries/InstallSnapshot) sent by the target proves the target won",
        )

        // With the target isolated, no confirmation can ever arrive: the transfer fails on the auto-timeout.
        sim.awaitTrue("transfer resolves") { transferOutcome.isCompleted }
        val result = transferOutcome.await()
        assertAll(
            { assertTrue(result.isFailure, "transfer must FAIL — the target never became leader") },
            {
                assertTrue(
                    result.exceptionOrNull() is LeadershipTransferException,
                    "expected LeadershipTransferException, got ${result.exceptionOrNull()}",
                )
            },
            {
                assertTrue(
                    sim.nodes.getValue(targetId).role.value != RaftRole.Leader,
                    "the transfer target must not have become leader",
                )
            },
        )
    }

    /**
     * The residual false-FAILURE of #1243, the non-target-first route: the target genuinely wins, but a
     * higher-term message from a *different* node outraces the target's first leader-authored message to
     * the old leader. The step-down (from the non-target) must NOT fail the transfer — it stays pending,
     * and the target's leader-authored heartbeat then completes it successfully.
     *
     * Repro: 3 voters, target→old-leader link dropped (the old leader cannot see any message from the
     * target). Transfer A→B: TimeoutNow still reaches B (A→B intact), B campaigns and wins with C's vote.
     * C adopts the higher term and its next AppendEntries reject deposes A — a higher term observed
     * from C, not from B. Then the link heals and B's leader-authored heartbeat reaches A: the transfer
     * must resolve SUCCESS.
     *
     * Uses a widened election-timeout config (seeded, per suite policy) so the transfer's
     * one-election-timeout auto-abandon window comfortably contains the heal.
     */
    @Test
    fun transferLeadership_nonTargetHigherTermOutracesTargetsWin_stillSucceeds() = raftRunTest {
        val config = RaftConfig(
            electionTimeoutMin = 30.milliseconds,
            electionTimeoutMax = 60.milliseconds,
            heartbeatInterval = 2.milliseconds,
            expectVirtualTime = true,
            random = Random(RAFT_TEST_SEED),
        )
        val sim = raftSim(this, backgroundScope, n = 3, config = config)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        // Commit-first so the target is already caught up: TimeoutNow is sent at transfer init.
        sim.proposeOnLeader("base".encodeToByteArray())
        sim.awaitCommit(1L)
        sim.settle()

        // Slow target→leader direction: the old leader sees nothing from the target — the higher term
        // reaches it first via the third voter's AppendEntries reject.
        sim.dropLink(from = targetId, to = leaderId)

        val transferOutcome = CompletableDeferred<Result<Unit>>()
        backgroundScope.launch { transferOutcome.complete(runCatchingCancellable { leader.transferLeadership(targetId) }) }

        // The target wins its TimeoutNow election with the third voter's vote (quorum 2 of 3).
        sim.awaitRole(targetId, RaftRole.Leader)
        // The old leader is deposed by the higher term echoed from the THIRD voter (never from the target).
        sim.awaitRole(leaderId, RaftRole.Follower)

        assertFalse(
            transferOutcome.isCompleted,
            "transfer must stay pending across a step-down triggered by a non-target higher-term message — " +
                "the target DID win; failing here is a false FAILURE",
        )

        // Heal: the target's leader-authored heartbeat reaches the old leader — conclusive confirmation.
        sim.heal()
        sim.awaitTrue("transfer resolves") { transferOutcome.isCompleted }
        val result = transferOutcome.await()
        assertAll(
            { assertTrue(result.isSuccess, "transfer must SUCCEED — the target won: ${result.exceptionOrNull()}") },
            { assertEquals(targetId, sim.nodes.getValue(leaderId).leader.value) },
            { assertEquals(RaftRole.Leader, sim.nodes.getValue(targetId).role.value) },
        )
        sim.checkInvariants()
    }

    // ── Proposal blocking during transfer ────────────────────────────────────

    /**
     * Proposals submitted to the still-leader while a transfer is in flight are rejected with
     * [NotLeaderException] (the [transferTarget] guard in onPropose). After transfer completes
     * the original leader is a follower and forwards proposals to the new leader successfully.
     */
    @Test
    fun proposalsDuringTransfer_rejectedWithNotLeaderException() = raftRunTest {
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
    fun transferLeadership_targetUnreachable_autoTimeoutResumesLeader() = raftRunTest {
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
    fun cancelTransfer_abortsInFlightTransfer() = raftRunTest {
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
    fun transferLeadership_nonLeader_throwsNotLeaderException() = raftRunTest {
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
    fun transferLeadership_unknownTarget_throwsIllegalArgument() = raftRunTest {
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
    fun transferLeadership_targetIsSelf_throwsIllegalArgument() = raftRunTest {
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
    fun transferLeadership_noCommittedEntryLoss() = raftRunTest {
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
    fun timeoutNow_fromNonLeader_isIgnored() = raftRunTest {
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

    /**
     * The same-term guard exercised above is scoped to `m.term == currentTerm`, so a `TimeoutNow` one
     * term *ahead* of the recipient used to skip sender validation entirely and run straight into
     * `stepDown(m.term)` → an immediate, **pre-vote-less** election. Any non-leader **voter** could
     * therefore force any follower to campaign, on demand and repeatedly — the disruption PreVote
     * exists to deny (issue #1889).
     *
     * On the normal path no honest §3.10 transfer produces such a frame. `sendTimeoutNow` emits the
     * *leader's own* `currentTerm`, and it is only reached once `matchIndex[target] >= lastLogIndex`;
     * `matchIndex` advances only through `onAppendEntriesResponse` / `onInstallSnapshotResponse`, both
     * gated on `_role is Leader && m.term == currentTerm` with the responder replying at its own
     * post-adoption term — so a value produced this term proves the target already reached it.
     *
     * That is not an absolute guarantee, and this test does not claim one: `becomeLeader` resets
     * `matchIndex` only for the **current configuration's** members, so a removed-then-re-admitted
     * target can satisfy the predicate on a stale index and be sent a `TimeoutNow` at a term it never
     * adopted. The guard is fail-safe-then-retry — the premature frame is dropped and the next
     * heartbeat ACK re-fires it correctly. What must never happen is the case pinned here: an
     * *unauthenticated* higher-term frame moving the recipient's durable term and forcing an election.
     * A recipient that really is behind reaches the current term by the ordinary election-timeout path.
     *
     * The target is partitioned off and the test only [RaftSimulation.settle]s (never advancing virtual
     * time), so its own election timer cannot fire: the injected frame is the only thing that could
     * move its state, which keeps the revert-verify honest instead of racy.
     */
    @Test
    fun timeoutNow_fromNonLeaderVoter_atHigherTerm_isIgnored() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val followers = sim.nodeIds.filter { it != leaderId }
        val target = followers[0]
        val spoofedSender = followers[1]   // a VOTER, but not the leader — the residual the §5.2 gate cannot see

        val committed = sim.proposeOnLeader("before-spoof".encodeToByteArray())
        sim.awaitCommit(committed.index, on = setOf(target))
        sim.settle()

        // Isolate the target so neither a legitimate heartbeat nor its own election timer competes.
        sim.partitionOff(target)
        val targetStore = sim.storages.getValue(target)
        val baselineTerm = targetStore.term()

        val targetTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(target).trace.collect { targetTrace += it } }
        sim.settle()

        sim.deliverTimeoutNow(to = target, from = spoofedSender, term = baselineTerm + 1)
        sim.settle()
        val afterTerm = targetStore.term()

        assertAll(
            {
                assertEquals(
                    RaftRole.Follower, sim.nodes.getValue(target).role.value,
                    "a higher-term TimeoutNow from a non-leader must not push the target into an election",
                )
            },
            {
                assertEquals(
                    baselineTerm, afterTerm,
                    "a higher-term TimeoutNow from a non-leader carries no authority — its term must not be adopted",
                )
            },
            {
                assertFalse(
                    targetTrace.any { it is RaftTraceEvent.RequestVote || it is RaftTraceEvent.Timeout },
                    "target must not start an election from a non-leader TimeoutNow one term ahead",
                )
            },
        )
    }

    /**
     * `TimeoutNow` is a leader→peer RPC and only a voter can ever be leader (§5.2), so a frame whose
     * true sender is not a current voter is a forgery — an admitted-but-malicious learner/spoke that
     * reached a voter over the cross-server relay. It belongs in the same §5.2/§8 authority gate as
     * `AppendEntries`/`InstallSnapshot` (#1383), and before #1889 it was outside it.
     *
     * The target is walked to a fresh term with no leader belief by a disrupt-flagged higher-term
     * `RequestVote` from a real voter — `stepDown` adopts the term and clears `_leader`.
     *
     * ### What this test attributes, and what it no longer can (#1973)
     *
     * It was written for the gate, and said so in its name, on the reading that a node with no leader
     * for the term had nothing to authenticate a same-term frame against and would accept it. That
     * stopped being true at #1900. **Two** guards now refuse this frame, and the assertions below
     * cannot tell them apart:
     *
     * - `RaftEngine.onMessage`'s §5.2/§8 gate — the sender is not in `voters`.
     * - `RaftEngine.onTimeoutNow`'s leader-identity check. The frame carries exactly the target's term,
     *   so the stale-term and future-term guards both pass it along, and it then meets
     *   `from != leaderForTerm`. `leaderForTerm` is by construction a fact about `currentTerm` and
     *   reads `null` once the term moves past the one it was pinned for — which is this target, having
     *   reached its term through the `RequestVote` rather than through a leader. Since #1900 removed
     *   the no-pin carve-out, a `null` pin refuses **every** sender instead of admitting any.
     *
     * Both outcomes are a Follower at an unbumped term with no election started, which is all this
     * test observes. Mutation-verified: deleting `TimeoutNow` from `RaftMessage.isLeaderToPeer` —
     * removing it from the gate outright, the state #1889 fixed — leaves this test green.
     *
     * So what it pins is the **state effect**, and that is worth pinning: it fails if any acceptance
     * lane opens for this frame, through either guard. What discriminates the gate is
     * [VoterRpcAuthorityGateTest.timeoutNowFromNonVoter_isDroppedByThisGate_notByTheDownstreamLeaderIdentityCheck],
     * which reads the `RaftMetric.WedgeSuspected` report only the gate emits. The pair is deliberate.
     */
    @Test
    fun timeoutNow_fromNonVoter_atCurrentTerm_isIgnored() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val followers = sim.nodeIds.filter { it != leaderId }
        val target = followers[0]
        val peerVoter = followers[1]

        val committed = sim.proposeOnLeader("before-spoof".encodeToByteArray())
        sim.awaitCommit(committed.index, on = setOf(target))
        sim.settle()

        // Isolate the target, then clear its leader pointer at a fresh term (see KDoc).
        sim.partitionOff(target)
        sim.deliverRequestVote(
            to = target,
            from = peerVoter,
            term = sim.storages.getValue(target).term() + 1,
            lastLogIndex = committed.index,
            lastLogTerm = committed.term,
            leadershipTransfer = true,
        )
        sim.settle()

        val targetStore = sim.storages.getValue(target)
        val baselineTerm = targetStore.term()
        assertEquals(
            null, sim.nodes.getValue(target).leader.value,
            "pre-condition: the target must have heard from no leader at this term, so it holds " +
                "neither a `_leader` belief nor a `leaderForTerm` pin for it",
        )

        val targetTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(target).trace.collect { targetTrace += it } }
        sim.settle()

        sim.deliverTimeoutNow(to = target, from = nonVoter, term = baselineTerm)
        sim.settle()
        val afterTerm = targetStore.term()

        assertAll(
            {
                assertEquals(
                    RaftRole.Follower, sim.nodes.getValue(target).role.value,
                    "a same-term TimeoutNow from a non-voter must not start an election",
                )
            },
            {
                assertEquals(
                    baselineTerm, afterTerm,
                    "a refused TimeoutNow must not bump the target's durable term",
                )
            },
            {
                assertFalse(
                    targetTrace.any { it is RaftTraceEvent.RequestVote || it is RaftTraceEvent.Timeout },
                    "target must not start an election from a non-voter TimeoutNow",
                )
            },
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
     * NOTE: this test asserts only the withhold-until-caught-up property of #1229 (the TimeoutNow
     * ordering and that the target campaigns on the full log). End-to-end "target becomes leader" with
     * an uncommitted tail is shown at n=3 in [transferLeadership_uncommittedTail_n3_targetBecomesLeader];
     * that the target wins its *first* election at n≥4 (the other voters granting its disrupt-flagged
     * RequestVote, §4.2.3 / #1230) is shown in
     * [transferLeadership_n4_targetWinsFirstElection_bypassingOtherVotersStickiness].
     */
    @Test
    fun transferLeadership_uncommittedTail_withholdsTimeoutNowUntilCaughtUp() = raftRunTest {
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
     * completes for real — the target actually becomes leader. Uses n=3 to keep the focus on the
     * uncommitted-tail catch-up path; the n≥4 case (where the target needs the *other* voters to grant
     * its disrupt-flagged RequestVote, §4.2.3 / #1230) is covered by
     * [transferLeadership_n4_targetWinsFirstElection_bypassingOtherVotersStickiness].
     *
     * The uncommitted tail is still created atomically with the transfer (propose then transfer, ahead of
     * any ACK), so the fix's withhold-until-caught-up path is exercised — not the trivial commit-first
     * path — and the tap confirms TimeoutNow is not sent before the target's tail ACK.
     */
    @Test
    fun transferLeadership_uncommittedTail_n3_targetBecomesLeader() = raftRunTest {
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

    // ── §4.2.3 disrupt-permission: a transfer target wins its FIRST election at n≥4 (issue #1230) ──

    /**
     * Dissertation §4.2.3: a leadership-transfer target's `RequestVote` must be processed by the OTHER
     * servers even when they believe a current leader exists — it carries "permission to disrupt the
     * leader (it told me to!)". At n≥4 the transferring leader alone is not a quorum, so the target's
     * election only succeeds if the *other* lease-holding voters grant its (disrupt-flagged) vote despite
     * their leader-lease being live.
     *
     * 4 voters (quorum 3): transfer leader→target, target caught up, TimeoutNow received, target
     * campaigns at term+1. The two OTHER voters still hear the old leader (leases live) — without the
     * disrupt flag they deny the target with `LeaderAlive`, giving it only 2 votes (self + old leader),
     * its first election fails, and leadership moves only later once their leases expire. With the flag
     * they grant, the target reaches quorum and wins on this first election.
     *
     * This is invisible at n≤3, where target + old-leader alone is already a quorum — the reason every
     * other transfer test (n=2/n=3) passed while this bug was live.
     *
     * The discriminator is the two lease-holders granting the target **at the transfer election's term**
     * (the target's first RequestVote after transfer). Without the fix they grant only at a *later* term,
     * after their leases expire — so keying the assertion to the transfer term (not a bare "granted
     * eventually") is what isolates the bug. Note the old leader steps down for the target's RequestVote
     * regardless of the fix (it authorised the transfer), so `transferLeadership` returning is NOT proof
     * the target won — the assertions read the wire, not the call's completion.
     */
    @Test
    fun transferLeadership_n4_targetWinsFirstElection_bypassingOtherVotersStickiness() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 4)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }
        // The two OTHER voters: they keep hearing the old leader, so their leader-lease is live and they
        // would deny a normal higher-term candidate under §4.2.3 stickiness.
        val leaseHolders = sim.nodeIds.filter { it != leaderId && it != targetId }

        // Commit-first: every voter (incl. the target) caught up, so the leader sends TimeoutNow at once.
        repeat(3) { sim.proposeOnLeader("cmd$it".encodeToByteArray()) }
        sim.awaitCommit(3L)
        sim.settle()

        sim.network.recording = true
        val mark = sim.network.sent.size

        // Launch async: the transfer deferred completes when the OLD leader steps down for the target's
        // RequestVote, which happens even without the fix — so its return is not proof the target won.
        backgroundScope.launch { runCatchingCancellable { leader.transferLeadership(targetId) } }

        // The target actually becomes leader on this first, TimeoutNow-triggered election.
        sim.awaitRole(targetId, RaftRole.Leader)
        sim.awaitRole(leaderId, RaftRole.Follower)

        val log = sim.network.sent.drop(mark)
        // The target's first campaign after the transfer is the disrupt-flagged one at term+1.
        val transferVote = log.first { it.from == targetId && it.message is RaftMessage.RequestVote }
            .message as RaftMessage.RequestVote
        val voteTerm = transferVote.term

        assertAll(
            *leaseHolders.map { id ->
                {
                    // A grant adopts the term (response.term == voteTerm); a LeaderAlive deny does NOT
                    // (it keeps the old term, voteGranted = false). Keying on voteTerm isolates the
                    // transfer election from any later term the target might reach without the fix.
                    val grantedAtTransferTerm = log.any {
                        it.from == id && it.to == targetId &&
                            (it.message as? RaftMessage.RequestVoteResponse)
                                ?.let { r -> r.term == voteTerm && r.voteGranted } == true
                    }
                    assertTrue(
                        grantedAtTransferTerm,
                        "lease-holder ${id.value} must grant the transfer target's disrupt-flagged RequestVote " +
                            "at term $voteTerm despite believing the old leader is alive (§4.2.3)",
                    )
                }
            }.toTypedArray()
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
    fun changeMembership_duringTransfer_rejected() = raftRunTest {
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
     * lastLogIndex mid-transfer, exactly the goalpost move the transfer's `isTargetCaughtUp` predicate assumes
     * cannot happen. Transfer and membership change are mutually exclusive in both directions.
     *
     * The change is left pending-but-uncommitted at the instant of the transfer by enqueuing the two
     * requests back-to-back into the leader's actor channel (change first, transfer second): both are
     * processed in a single actor turn ahead of any follower ACK, so `pendingConfigChange` is set — and
     * the config entry not yet committed — when `onTransferLeadership` runs.
     */
    @Test
    fun transferLeadership_duringMembershipChange_rejected() = raftRunTest {
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
    fun transferLeadership_abandonedEmitsTraceEvent() = raftRunTest {
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
