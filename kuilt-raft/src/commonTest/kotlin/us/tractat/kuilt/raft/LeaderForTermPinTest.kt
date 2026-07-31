@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

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
     * The composed two-frame attack from the issue. Forge the belief with a same-term `AppendEntries`,
     * then send a same-term `TimeoutNow` from the same peer: `onTimeoutNow` authenticates its sender
     * against `_leader`, so a poisoned `_leader` turns the second frame into a pre-vote-less election
     * the attacker can force at will.
     *
     * With the belief pinned, frame one is dropped and frame two fails the authority test it was
     * relying on — so the victim neither campaigns nor bumps its term.
     *
     * (`onTimeoutNow` still *reads* `_leader` here; moving that read to `leaderForTerm` is #1900 and
     * is deliberately not in this change. This test asserts the composed attack is closed by keeping
     * `_leader` honest, which is what slice 1 buys.)
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
