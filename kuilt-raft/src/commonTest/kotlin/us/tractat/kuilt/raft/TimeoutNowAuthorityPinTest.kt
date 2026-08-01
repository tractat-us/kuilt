@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.launch
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * §3.10 `TimeoutNow` authenticates its sender against the leader this node recognises. Which
 * *leader* it reads is the whole of this suite. Issue #1900, slice 2 of the #1906 track.
 *
 * `_leader` answers "who is a live leader I can talk to", so it is cleared on **every** step-down —
 * including [StepDownReason.LostQuorum] and [StepDownReason.RemovedFromConfig], neither of which
 * changes the term. A node that led term `T` and stood down *at* `T` therefore sits at
 * `_leader == null` for the rest of that term, where the guard's `null` carve-out accepts a
 * `TimeoutNow` from **any** voter. [steppedDownLeaderRefusesSameTermTimeoutNowFromAnotherVoter]
 * drives that lane.
 *
 * `leaderForTerm` (#1906, landed in #1938) answers "who was *established* as leader for this term",
 * which §5.2 Election Safety makes unique and which nothing clears — staleness is decided at the
 * read, by comparing the pinned term to `currentTerm`. A stepped-down leader still holds
 * `leaderForTerm == self`, so reading the pin instead closes that window.
 *
 * **What this does not close, and the reason the `null` carve-out stays.** The pin is in-memory by
 * construction: after a restart it is `-1` / `null`, exactly as `_leader` is. A transfer target that
 * ACKed at term `T`, restarted, and came back at `T` as a caught-up Follower holds neither, so its
 * honest `TimeoutNow` is still accepted — and so is the forgery that shares that window. Requiring
 * `from == leaderForTerm` outright would break the honest half
 * ([timeoutNowSurvivesARestartOfTheTransferTarget] is that regression), which is why the restart
 * residual is left open on #1900 rather than closed here.
 *
 * The `timeout` on each test is a **generous wedge backstop, not an assertion**: it is wall-clock
 * over a virtual-time trajectory, so it measures the host rather than the code (#1891). Fast failure
 * comes from the bounded `await*` / [RaftSimulation.settle] helpers, which are bounded in *virtual*
 * time and so are load-independent.
 */
class TimeoutNowAuthorityPinTest {

    /**
     * The lane this change closes. `v1` wins term `T`, is partitioned off, and check-quorum steps it
     * down **at `T`** — `relinquishToFollower` nulls `_leader` without touching the term, so the
     * `_leader == null` carve-out has no leader to compare against and admits any voter's frame.
     *
     * The node is a partition of one for the rest of the test, so its own pre-vote can never reach
     * quorum (it stays a Follower at `T`) and the injected frame is the only thing that can move it.
     * The body only [RaftSimulation.settle]s after the injection — never advancing virtual time — so
     * an election timer cannot launder the result either way.
     */
    @Test
    fun steppedDownLeaderRefusesSameTermTimeoutNowFromAnotherVoter() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val forgerId = sim.nodeIds.first { it != leaderId }
        val termWhileLeading = sim.storages.getValue(leaderId).term()

        // Isolate the leader: check-quorum fires, it stands down at the same term, and nothing can
        // repair `_leader` afterwards.
        sim.partitionOff(leaderId)
        sim.awaitTrue("$leaderId steps down and forgets its leader pointer") {
            leader.role.value is RaftRole.Follower && leader.leader.value == null
        }
        val term = sim.storages.getValue(leaderId).term()
        assertEquals(
            termWhileLeading, term,
            "precondition: LostQuorum is a SAME-term step-down — a term bump would move the pin too " +
                "and there would be no window to close",
        )

        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { leader.trace.collect { trace += it } }
        sim.settle()   // let the trace collector subscribe before the frame is injected

        sim.deliverTimeoutNow(to = leaderId, from = forgerId, term = term)
        sim.settle()
        val termAfter = sim.storages.getValue(leaderId).term()

        assertAll(
            {
                assertTrue(
                    leader.role.value is RaftRole.Follower,
                    "a TimeoutNow from $forgerId must not campaign a node that itself holds term $term " +
                        "as its established leader — role was ${leader.role.value}",
                )
            },
            {
                assertEquals(
                    term, termAfter,
                    "a refused TimeoutNow must not bump the durable term",
                )
            },
            {
                assertFalse(
                    trace.any { it is RaftTraceEvent.RequestVote || it is RaftTraceEvent.Timeout },
                    "no election round may start from the refused frame: $trace",
                )
            },
        )
    }

    // ── The honest paths this must not break ─────────────────────────────────
    // Both are green before the change as well as after — that is the point. If either goes red the
    // guard has been tightened past what §3.10 can pay for, not merely re-sourced.

    /**
     * §3.10 across a restart of the transfer target, and the reason the `null` carve-out stays.
     *
     * [RaftSimulation.restart] preserves the storage, so the node comes back at the term it durably
     * held — but the init-restore path assigns neither `_leader` nor the per-term pin, so both read
     * `null` at that term. An honest target that ACKed at `T` and restarted is exactly this state,
     * and its `TimeoutNow` must still be accepted or the transfer fails on its auto-timeout.
     *
     * Isolated *before* the crash so the sitting leader's heartbeat cannot re-establish either value
     * between the restart and the injection — which would make the test pass for the wrong reason.
     */
    @Test
    fun timeoutNowSurvivesARestartOfTheTransferTarget() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        sim.awaitTrue("$targetId recognises $leaderId") { sim.nodes.getValue(targetId).leader.value == leaderId }
        val term = sim.storages.getValue(targetId).term()

        sim.partitionOff(targetId)
        sim.crash(targetId)
        sim.restart(targetId)
        sim.settle()

        val target = sim.nodes.getValue(targetId)
        assertEquals(
            null, target.leader.value,
            "precondition: a restarted node holds no leader belief for the term it restored",
        )
        assertEquals(
            term, sim.storages.getValue(targetId).term(),
            "precondition: the durable term survives the restart",
        )

        sim.deliverTimeoutNow(to = targetId, from = leaderId, term = term)
        sim.settle()
        val termAfter = sim.storages.getValue(targetId).term()

        assertAll(
            {
                assertEquals(
                    RaftRole.Candidate, target.role.value,
                    "an honest §3.10 TimeoutNow must still be accepted by a target that restarted at $term",
                )
            },
            {
                assertEquals(
                    term + 1, termAfter,
                    "and the accepted transfer must campaign at the next term",
                )
            },
        )
    }

    /**
     * The other half of the restart window, and the residual #1900 is scoped to: the *forgery* that
     * shares [timeoutNowSurvivesARestartOfTheTransferTarget]'s state.
     *
     * Identical setup — the target ACKs `L` at term `T`, is isolated, crashes and restarts at `T` —
     * except the injected `TimeoutNow` comes from a **different voter**. That peer was never term
     * `T`'s leader, so §5.2 makes its frame a forgery; a restarted node that recovers the identity it
     * durably established for `T` can say so locally and refuse it.
     *
     * The two tests are deliberately a matched pair: any mechanism that refuses this frame by
     * *widening* the guard rather than by recovering the pin breaks its sibling, and any mechanism
     * that keeps the sibling green by admitting the whole no-known-leader window leaves this one red.
     */
    @Test
    fun sameTermForgeryFromAnotherVoterIsRefusedAfterARestartOfTheTransferTarget() =
        raftRunTest(timeout = 30.seconds) {
            val sim = raftSim(this, backgroundScope, n = 3)
            val leader = awaitLeader(sim)
            val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
            val targetId = sim.nodeIds.first { it != leaderId }
            val forgerId = sim.nodeIds.first { it != leaderId && it != targetId }

            sim.awaitTrue("$targetId recognises $leaderId") {
                sim.nodes.getValue(targetId).leader.value == leaderId
            }
            val term = sim.storages.getValue(targetId).term()

            sim.partitionOff(targetId)
            sim.crash(targetId)
            sim.restart(targetId)
            sim.settle()

            val target = sim.nodes.getValue(targetId)
            assertEquals(
                null, target.leader.value,
                "precondition: a restarted node holds no live-leader belief for the term it restored",
            )
            assertEquals(
                term, sim.storages.getValue(targetId).term(),
                "precondition: the durable term survives the restart",
            )

            val trace = mutableListOf<RaftTraceEvent>()
            backgroundScope.launch { target.trace.collect { trace += it } }
            sim.settle()   // let the trace collector subscribe before the frame is injected

            sim.deliverTimeoutNow(to = targetId, from = forgerId, term = term)
            sim.settle()
            val termAfter = sim.storages.getValue(targetId).term()

            assertAll(
                {
                    assertTrue(
                        target.role.value is RaftRole.Follower,
                        "$forgerId was never term $term's leader, so its TimeoutNow must not campaign a " +
                            "node that restarted at $term — role was ${target.role.value}",
                    )
                },
                {
                    assertEquals(
                        term, termAfter,
                        "a refused TimeoutNow must not bump the durable term",
                    )
                },
                {
                    assertFalse(
                        trace.any { it is RaftTraceEvent.RequestVote || it is RaftTraceEvent.Timeout },
                        "no election round may start from the refused frame: $trace",
                    )
                },
            )
        }

    /**
     * The ordinary transfer path: a follower that has heard from `L` this term must still accept
     * `L`'s own `TimeoutNow`. Here `_leader` and the per-term pin both name `L`, so this is the case
     * where re-sourcing the read must be invisible.
     *
     * The target is isolated so its campaign cannot resolve and the assertions read a stable state;
     * a partitioned node still becomes a Candidate, since `startRealElection` bumps the term before
     * any vote is counted.
     */
    @Test
    fun theRecognisedLeadersOwnTimeoutNowIsStillAccepted() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }

        sim.awaitTrue("$targetId recognises $leaderId") { sim.nodes.getValue(targetId).leader.value == leaderId }
        val term = sim.storages.getValue(targetId).term()
        sim.partitionOff(targetId)

        sim.deliverTimeoutNow(to = targetId, from = leaderId, term = term)
        sim.settle()

        val target = sim.nodes.getValue(targetId)
        val termAfter = sim.storages.getValue(targetId).term()
        assertAll(
            {
                assertEquals(
                    RaftRole.Candidate, target.role.value,
                    "the recognised leader's own TimeoutNow must start an election, was ${target.role.value}",
                )
            },
            {
                assertEquals(
                    term + 1, termAfter,
                    "and the election must campaign at the next term",
                )
            },
        )
    }
}
