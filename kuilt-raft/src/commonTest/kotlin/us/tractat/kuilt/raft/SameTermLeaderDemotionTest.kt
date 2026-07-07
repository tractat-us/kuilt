@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Defense-in-depth (#1250): a same-term AppendEntries / InstallSnapshot reaching a node that is
 * currently Leader is unreachable while Election Safety holds (one leader per term). If Election
 * Safety is ever violated (an F1-class bug, storage misbehaviour), the leader must still tear down
 * cleanly — cancel its heartbeat/quorum-check/lease jobs, FAIL its pending proposals/config-changes/
 * reads, and clear its dedup cache — instead of flipping its role with a bare assignment that leaks
 * all of them.
 *
 * These tests deliver a raw same-term message to a live leader (a state the harness can construct but
 * a correct cluster never reaches) and assert the leader relinquishes through the proper path: its
 * pending proposal fails with [LeadershipLostException] and it emits
 * BecomeFollower([StepDownReason.AppendEntriesFromLeader]) — proving the same-term relinquish ran,
 * not a bare role flip (nor CheckQuorum's LostQuorum).
 *
 * The scenario only ever [RaftSimulation.settle]s (yields at the current virtual instant, never
 * advancing the clock), so CheckQuorum — which fires on a delay of one election timeout — never runs;
 * the ONLY thing that can resolve the pending proposal is the injected same-term message. That keeps
 * the revert-verify honest: with the bare-assignment demotion restored, nothing resolves the pending
 * proposal, so the test fails fast on the `proposeOutcome.isCompleted` assertion — it never awaits the
 * deferred, so it does not hang.
 */
class SameTermLeaderDemotionTest {

    @Test
    fun sameTermAppendEntriesWhileLeader_relinquishesCleanly_failingPendingProposal() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val otherId = sim.nodeIds.first { it != leaderId }

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        // Isolate the leader so its proposal can never reach a quorum and stays PENDING.
        sim.partitionOff(leaderId)
        val leaderTerm = sim.storages.getValue(leaderId).term()

        val proposeOutcome = CompletableDeferred<Throwable?>()
        backgroundScope.launch {
            // Capture only the exception the test asserts. A CancellationException (teardown of the
            // backgroundScope coroutine) must propagate, not be swallowed into a completed "outcome";
            // any other unexpected throwable surfaces as an uncaught-exception test failure.
            try {
                leader.propose(byteArrayOf(9))
                proposeOutcome.complete(null)   // committed — impossible while partitioned
            } catch (e: LeadershipLostException) {
                proposeOutcome.complete(e)
            }
        }
        sim.settle()   // let the propose be accepted and recorded as pending at this instant

        // Inject a same-term AppendEntries from a NON-leader — the Election-Safety-violating state.
        sim.deliverAppendEntries(to = leaderId, from = otherId, term = leaderTerm)
        sim.settle()   // process it at this instant: a proper relinquish must fail the pending proposal

        val error = if (proposeOutcome.isCompleted) proposeOutcome.getCompleted() else null
        val relinquishedViaLeaderContact = leaderTrace
            .filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .any { it.reason == StepDownReason.AppendEntriesFromLeader }

        assertAll(
            {
                assertTrue(
                    proposeOutcome.isCompleted,
                    "pending proposal must be RESOLVED by the same-term demotion teardown, not left hanging — " +
                        "a bare _role assignment skips relinquishToFollower and leaks it. trace=$leaderTrace",
                )
            },
            {
                assertTrue(
                    error is LeadershipLostException,
                    "pending proposal must fail with LeadershipLostException (the relinquish cause), was: $error",
                )
            },
            { assertTrue(leader.role.value is RaftRole.Follower, "leader must demote to Follower, was ${leader.role.value}") },
            {
                assertTrue(
                    relinquishedViaLeaderContact,
                    "leader must relinquish via BecomeFollower(AppendEntriesFromLeader) — proving the proper " +
                        "step-down path ran, not a bare role flip (nor CheckQuorum's LostQuorum). trace=$leaderTrace",
                )
            },
        )
    }

    @Test
    fun sameTermInstallSnapshotWhileLeader_relinquishesCleanly_failingPendingProposal() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val otherId = sim.nodeIds.first { it != leaderId }

        val leaderTrace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }

        sim.partitionOff(leaderId)
        val leaderTerm = sim.storages.getValue(leaderId).term()

        val proposeOutcome = CompletableDeferred<Throwable?>()
        backgroundScope.launch {
            // Capture only the asserted exception; let CancellationException propagate on teardown.
            try {
                leader.propose(byteArrayOf(7))
                proposeOutcome.complete(null)
            } catch (e: LeadershipLostException) {
                proposeOutcome.complete(e)
            }
        }
        sim.settle()

        // Same-term InstallSnapshot from a non-leader: the second same-term Leader demotion site.
        sim.deliverInstallSnapshot(
            to = leaderId,
            from = otherId,
            term = leaderTerm,
            lastIncludedIndex = 1L,
            lastIncludedTerm = leaderTerm,
        )
        sim.settle()

        val error = if (proposeOutcome.isCompleted) proposeOutcome.getCompleted() else null
        val relinquishedViaLeaderContact = leaderTrace
            .filterIsInstance<RaftTraceEvent.BecomeFollower>()
            .any { it.reason == StepDownReason.AppendEntriesFromLeader }

        assertAll(
            {
                assertTrue(
                    proposeOutcome.isCompleted,
                    "pending proposal must be resolved by the same-term InstallSnapshot demotion teardown, " +
                        "not left hanging. trace=$leaderTrace",
                )
            },
            {
                assertTrue(
                    error is LeadershipLostException,
                    "pending proposal must fail with LeadershipLostException, was: $error",
                )
            },
            { assertTrue(leader.role.value is RaftRole.Follower, "leader must demote to Follower, was ${leader.role.value}") },
            {
                assertTrue(
                    relinquishedViaLeaderContact,
                    "leader must relinquish via BecomeFollower(AppendEntriesFromLeader). trace=$leaderTrace",
                )
            },
        )
    }
}
