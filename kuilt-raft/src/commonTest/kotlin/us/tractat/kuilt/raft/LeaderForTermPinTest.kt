@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * §5.2 Election Safety permits **at most one leader per term**, so two same-term leader→peer frames
 * naming different senders are proof that one of them is forged. Issue #1906: `_leader` was written
 * straight from whichever frame arrived last, with no per-term uniqueness check — so an ordinary
 * same-term `AppendEntries` from any voter redirected a victim's belief to its sender, which then
 * satisfied `onTimeoutNow`'s `from == _leader` authority test and bought a pre-vote-less election on
 * demand. Two frames, from a peer that was never leader.
 *
 * The fix pins the term's leader identity once (`leaderForTerm`) and requires every later same-term
 * adoption to match it. The two halves this suite has to hold apart:
 *
 * - **It rejects the forgery** — [sameTermAppendEntriesFromAnotherVoterDoesNotSeizeLeaderBelief] and
 *   the composed [forgedLeaderBeliefThenSameTermTimeoutNowStartsNoElection].
 * - **It rejects nothing honest** — the load-bearing half. Write-once is only sound if every
 *   legitimate `null → L` adoption still happens: on first contact in a term, again in the *next*
 *   term, from a Candidate that lost, and after a restart. Those four are pinned positively below,
 *   because the Election-Safety argument that they are safe was established by reading guards, not
 *   by driving them (#1906's decision comment).
 *
 * The `timeout` on each test is a **generous wedge backstop, not an assertion**: it is wall-clock
 * over a virtual-time trajectory, so it measures the host rather than the code (#1891). Fast failure
 * comes from the bounded `await*`/[RaftSimulation.settle] helpers, which are bounded in *virtual*
 * time and so are load-independent.
 */
class LeaderForTermPinTest {

    /**
     * The headline forgery. A follower that already recognises the real leader `L` at term `T`
     * receives an ordinary same-term `AppendEntries` from a *different* voter. Election Safety says
     * that frame cannot be honest, so the victim must keep believing `L`.
     *
     * The scenario only [RaftSimulation.settle]s (yields at the current virtual instant, never
     * advancing the clock) after the injection, so no heartbeat from the real leader can fire and
     * *repair* `_leader` behind the assertion — the injected frame is the only thing that runs.
     */
    @Test
    fun sameTermAppendEntriesFromAnotherVoterDoesNotSeizeLeaderBelief() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val (victimId, forgerId) = sim.nodeIds.filter { it != leaderId }

        sim.awaitTrue("$victimId recognises $leaderId") { sim.nodes.getValue(victimId).leader.value == leaderId }
        val term = sim.storages.getValue(victimId).term()

        // Isolate the victim so ONLY the injected frame (deliver bypasses partition rules) can move
        // its belief — an honest heartbeat must not be able to launder the result either way.
        sim.partitionOff(victimId)
        sim.deliverAppendEntries(to = victimId, from = forgerId, term = term)
        sim.settle()

        val victim = sim.nodes.getValue(victimId)
        assertAll(
            {
                assertEquals(
                    leaderId,
                    victim.leader.value,
                    "a same-term AppendEntries from $forgerId must NOT install it as the term-$term leader: " +
                        "§5.2 permits one leader per term and $leaderId already holds it",
                )
            },
            { assertTrue(victim.role.value is RaftRole.Follower, "victim must stay a Follower, was ${victim.role.value}") },
        )
    }

    /**
     * The sibling adoption site, and the one where the forgery is worse than a mis-set belief. An
     * `InstallSnapshot` that clears the recipient's commit frontier **wipes its log** (`truncateFrom(0)`
     * when the boundary term does not match) and resets its state machine to attacker-supplied bytes.
     * Same-term, from a peer that Election Safety says cannot be this term's leader, that must not
     * happen — so the frame is refused before it reaches `SnapshotReceiver` at all.
     */
    @Test
    fun sameTermInstallSnapshotFromAnotherVoterNeitherSeizesBeliefNorInstalls() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val (victimId, forgerId) = sim.nodeIds.filter { it != leaderId }

        sim.awaitTrue("$victimId recognises $leaderId") { sim.nodes.getValue(victimId).leader.value == leaderId }
        val term = sim.storages.getValue(victimId).term()
        sim.partitionOff(victimId)

        val victim = sim.nodes.getValue(victimId)
        val installs = sim.collectInstalls(victimId)
        val commitBefore = victim.commitIndex.value
        sim.settle()   // let the install collector subscribe before the frame is injected

        // Above the victim's commit frontier, so pre-fix this genuinely installs rather than being
        // waved off as a stale duplicate.
        sim.deliverInstallSnapshot(
            to = victimId,
            from = forgerId,
            term = term,
            lastIncludedIndex = commitBefore + 5L,
            lastIncludedTerm = term,
            data = byteArrayOf(6, 6, 6),
        )
        sim.settle()

        assertAll(
            { assertEquals(leaderId, victim.leader.value, "a same-term InstallSnapshot from $forgerId must not install it as leader") },
            { assertTrue(installs.isEmpty(), "and must not reset the state machine — installs were $installs") },
            { assertEquals(commitBefore, victim.commitIndex.value, "nor advance the commit frontier") },
        )
    }

    /**
     * The composed two-frame attack from the issue. Forge the belief with a same-term `AppendEntries`,
     * then send a same-term `TimeoutNow` from the same peer: `onTimeoutNow` authenticated its sender
     * against `_leader`, so a poisoned `_leader` turned the second frame into a pre-vote-less election
     * the attacker could force at will.
     *
     * With the belief pinned, frame one is dropped and frame two fails the authority test it was
     * relying on — so the victim neither campaigns nor bumps its term. That is what slice 1 buys, and
     * it is what this test asserts: the attack is closed by keeping `_leader` honest.
     *
     * `onTimeoutNow` has since moved its read to `leaderForTerm` (#1900, slice 2), so frame two is now
     * refused twice over — see [TimeoutNowAuthorityPinTest] for the window that only the pin closes.
     */
    @Test
    fun forgedLeaderBeliefThenSameTermTimeoutNowStartsNoElection() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val (victimId, forgerId) = sim.nodeIds.filter { it != leaderId }

        sim.awaitTrue("$victimId recognises $leaderId") { sim.nodes.getValue(victimId).leader.value == leaderId }
        val term = sim.storages.getValue(victimId).term()
        sim.partitionOff(victimId)

        sim.deliverAppendEntries(to = victimId, from = forgerId, term = term)   // frame 1: seize the belief
        sim.settle()
        sim.deliverTimeoutNow(to = victimId, from = forgerId, term = term)      // frame 2: cash it in
        sim.settle()

        val victim = sim.nodes.getValue(victimId)
        val termAfter = sim.storages.getValue(victimId).term()
        assertAll(
            {
                assertTrue(
                    victim.role.value is RaftRole.Follower,
                    "a TimeoutNow from a peer that was never leader must start no election — role was ${victim.role.value}",
                )
            },
            { assertEquals(term, termAfter, "no election means no term bump: the durable term must still be $term") },
            { assertEquals(leaderId, victim.leader.value, "belief must still name the real leader") },
        )
    }

    /**
     * Decision (b) on #1906, and the reason `becomeLeader` pins **itself**: winning term `T` is the
     * establishing event for `T`, so a same-term leader-contact reaching a node that still believes it
     * is Leader can never be honest — §5.2 already gave the term to this node.
     *
     * Both halves are kept, deliberately. #1250's defence-in-depth still fires: a node that somehow
     * holds leadership when such a frame arrives tears its leadership down through the full relinquish
     * path (timers, pending proposals, dedup) rather than flipping a role field. But the sender does
     * **not** thereby install itself — the belief goes to `null` (the step-down's own clearing), not
     * to the frame's author. Option (a) — dropping the frame outright — was rejected because it buys
     * the invariant by leaving a deposed leader still running its timers.
     */
    @Test
    fun sameTermAppendEntriesWhileLeaderDemotesButDoesNotInstallTheSender() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val forgerId = sim.nodeIds.first { it != leaderId }

        val trace = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { leader.trace.collect { trace += it } }
        sim.partitionOff(leaderId)
        val term = sim.storages.getValue(leaderId).term()
        sim.settle()   // let the trace collector subscribe

        sim.deliverAppendEntries(to = leaderId, from = forgerId, term = term)
        sim.settle()

        assertAll(
            {
                assertTrue(
                    trace.filterIsInstance<RaftTraceEvent.BecomeFollower>()
                        .any { it.reason == StepDownReason.AppendEntriesFromLeader },
                    "#1250's teardown must still run — BecomeFollower(AppendEntriesFromLeader) absent. trace=$trace",
                )
            },
            {
                assertEquals(
                    null,
                    leader.leader.value,
                    "$forgerId must NOT be installed as the term-$term leader: this node won that term, " +
                        "so §5.2 says no other node can hold it",
                )
            },
        )
    }

    /**
     * The third consumer of `_leader`'s provenance from #1906, and why the refusal is placed *before*
     * the rest of `onAppendEntries`' side-effects rather than merely guarding the `_leader` write.
     *
     * §3.10's transfer completes on a leader-authored message from the target at a term above the one
     * the transfer started in — `transfer.onLeaderElected`. That is a **resolution** path: it cancels
     * the auto-abandon timer, so a frame that reaches it falsely does not merely mis-report, it
     * disarms the mechanism that would later have reported the truth. `transferLeadership()` returns
     * normally while the target sits partitioned, having won nothing.
     *
     * A frame refused by the per-term pin must therefore not reach it. Here the transfer starts at
     * term `T`, term `T + 1` is legitimately won by a third node, and the target then claims `T + 1`:
     * from the target, above the transfer's start term, so it satisfies `onLeaderElected` on both
     * counts — and is refused before it gets there.
     */
    @Test
    fun aRefusedFrameCannotFalselyConfirmALeadershipTransfer() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val (targetId, winnerId) = sim.nodeIds.filter { it != leaderId }
        val term = sim.storages.getValue(leaderId).term()

        // The target can neither hear the TimeoutNow nor win an election, so only a frame reaching
        // onLeaderElected could resolve the transfer inside this window.
        sim.partitionOff(targetId)
        val transfer = async { leader.transferLeadership(targetId) }
        sim.settle()
        assertTrue(!transfer.isCompleted, "precondition: the transfer must still be in flight")

        // `winnerId` legitimately takes term+1 — the old leader steps down and pins it for that term.
        sim.deliverAppendEntries(to = leaderId, from = winnerId, term = term + 1L)
        sim.settle()
        assertEquals(winnerId, leader.leader.value, "precondition: term ${term + 1} belongs to $winnerId")

        // Now the target claims that same term.
        sim.deliverAppendEntries(to = leaderId, from = targetId, term = term + 1L)
        sim.settle()

        assertTrue(
            !transfer.isCompleted,
            "a transfer to $targetId must not be confirmed by a frame the term's established leader " +
                "($winnerId) contradicts — onLeaderElected cancels the auto-abandon timer, so a false " +
                "confirmation is terminal, not merely wrong",
        )
        transfer.cancelAndJoin()
    }

    // ── Write-once rejects nothing honest ────────────────────────────────────
    // Four legitimate `null → L` adoptions. If any of these goes red the DESIGN is wrong, not the
    // test: a write-once rule that blocks an honest adoption costs liveness on every path that
    // depends on recognising a leader.

    /** First contact in a term: nothing is pinned yet, so the leader must be adopted. */
    @Test
    fun freshNodeAdoptsTheLeaderOnFirstContactInATerm() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }

        sim.nodeIds.filter { it != leaderId }.forEach { id ->
            sim.awaitTrue("$id adopts $leaderId on first contact") {
                sim.nodes.getValue(id).leader.value == leaderId
            }
        }
    }

    /**
     * A new term re-opens adoption. The previous term's pin must not survive into term `T + 1` —
     * otherwise the first leader a node ever hears from would be its leader forever and no
     * subsequent election could ever be recognised.
     */
    @Test
    fun aHigherTermReopensAdoptionForADifferentLeader() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val (victimId, successorId) = sim.nodeIds.filter { it != leaderId }

        sim.awaitTrue("$victimId recognises $leaderId") { sim.nodes.getValue(victimId).leader.value == leaderId }
        val term = sim.storages.getValue(victimId).term()
        sim.partitionOff(victimId)

        // `successorId` won term+1 elsewhere and heartbeats us. The pin is for `term`, so it must not
        // block this.
        sim.deliverAppendEntries(to = victimId, from = successorId, term = term + 1)
        sim.settle()

        val victim = sim.nodes.getValue(victimId)
        val termAfter = sim.storages.getValue(victimId).term()
        assertAll(
            { assertEquals(successorId, victim.leader.value, "a higher-term leader must be adopted") },
            { assertEquals(term + 1, termAfter, "the higher term must be adopted too") },
        )
    }

    /**
     * A Candidate that loses must still adopt the winner **at the same term** it campaigned in — the
     * ordinary end of every split vote. A Candidate at term `T` reached `T` by bumping its own term,
     * which invalidates any pin it held, so nothing may be pinned for `T` when the winner's first
     * `AppendEntries` lands.
     *
     * The Candidate is produced through the §3.10 `TimeoutNow` path, which skips the pre-vote round —
     * a *partitioned* node can never become a Candidate at all (its pre-vote never reaches quorum,
     * which is exactly what PreVote is for), so partition-and-wait cannot construct this state. It is
     * then isolated so its own election cannot resolve, and the peer that won the same term contacts
     * it.
     */
    @Test
    fun aCandidateThatLosesAdoptsTheWinnerAtTheSameTerm() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val (loserId, winnerId) = sim.nodeIds.filter { it != leaderId }

        sim.awaitTrue("$loserId recognises $leaderId") { sim.nodes.getValue(loserId).leader.value == leaderId }
        val term = sim.storages.getValue(loserId).term()
        sim.partitionOff(loserId)   // its RequestVotes go nowhere, so it stays a Candidate

        sim.deliverTimeoutNow(to = loserId, from = leaderId, term = term)
        sim.settle()
        val loser = sim.nodes.getValue(loserId)
        val campaignTerm = sim.storages.getValue(loserId).term()
        assertEquals(RaftRole.Candidate, loser.role.value, "precondition: the node must be campaigning")
        assertEquals(term + 1, campaignTerm, "precondition: campaigning at the next term")

        // `winnerId` won that same term while we were campaigning for it. Same term, not higher:
        // exactly the case a write-once rule must NOT refuse.
        sim.deliverAppendEntries(to = loserId, from = winnerId, term = campaignTerm)
        sim.settle()

        assertAll(
            { assertEquals(winnerId, loser.leader.value, "a losing Candidate must adopt the winner of its own term") },
            { assertTrue(loser.role.value is RaftRole.Follower, "and step down to Follower, was ${loser.role.value}") },
        )
    }

    /**
     * After a restart the node restores a durable term without ever having pinned a leader for it —
     * the init-restore path writes `state.currentTerm` directly and touches neither `_leader` nor the
     * pin. A restarted node must therefore still adopt the sitting leader, at the very term it
     * restored.
     */
    @Test
    fun aRestartedNodeAdoptsTheSittingLeaderAtTheTermItRestored() = raftRunTest(timeout = 30.seconds) {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val restartedId = sim.nodeIds.first { it != leaderId }

        sim.awaitTrue("$restartedId recognises $leaderId") { sim.nodes.getValue(restartedId).leader.value == leaderId }
        sim.crash(restartedId)
        sim.restart(restartedId)

        sim.awaitTrue("$restartedId re-adopts a leader after restart") {
            sim.nodes.getValue(restartedId).leader.value != null
        }
    }
}
