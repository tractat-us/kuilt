package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression for #1831: the stale-term AppendEntries rejection must not echo the *old* request's
 * heartbeat round into the *current* term.
 *
 * `onAppendEntries`' first branch replied `AppendEntriesResponse(state.currentTerm, false,
 * echoedRound = m.round)` — pairing the current term with a round the current leadership never
 * stamped. `ReadIndexTracker.round` resets to 0 on every `becomeLeader`, so a round carried by a
 * delayed AppendEntries from an *earlier* leadership can be arbitrarily larger than the current
 * one. The response then passes the leader's `m.term == state.currentTerm` guard and reaches
 * `recordAck(from, 1000)`, seating a voter in `freshContacts` for every read for the rest of the
 * term — a stale read served as linearizable (§3.7).
 *
 * **No forgery is required.** Two honest, correct nodes produce this on their own in the plain
 * crash-fault async model: leader L reaches round 1000 at term 2, one AppendEntries is delayed, L
 * loses and regains leadership at term 4 with its round counter reset, and the delayed frame finally
 * lands on a follower now at term 4.
 *
 * The fix echoes `0L`, matching the sibling InstallSnapshot stale-term rejection, which already
 * replies with the `echoedRound = 0L` default. `resolve` counts a voter fresh only when
 * `lastAckRound[v] > read.sinceRound` and `sinceRound >= 0`, so a round-0 echo contributes no
 * freshness — correct, because a stale-term rejection genuinely answers nothing in the current
 * round. Reachability for CheckQuorum is unaffected: that runs off `recentVoterContacts`, a
 * separate mechanism the response still feeds.
 *
 * This is the **nonce** disposition, not the quantity one. #1829's `conflictIndex` clamp two files
 * away maps an out-of-range value onto the nearest valid one; here the responder instead refuses to
 * attest to a round it is not answering. Clamping `m.round` into the current round would be exactly
 * the #1817 mistake — it would launder a foreign round into the most favourable valid one.
 *
 * Independent of #1817's `recordAck` discard, which only helps when the stale round happens to
 * exceed the current one. A new leadership's counter climbs with every heartbeat, so an old round
 * that is coincidentally `<= round` is accepted at face value; the send site needs its own fix.
 */
internal class StaleTermRoundEchoTest {

    /** The delayed frame's round — far above anything a freshly-reset leadership has reached. */
    private val staleRound = 1000L

    @Test
    fun staleTermRejectionEchoesRoundZeroNotTheRequestRound() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val followerId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)   // the §5.4.2 no-op replicated everywhere — the cluster is converged

        sim.network.sent.clear()
        sim.network.recording = true

        // A delayed AppendEntries from an earlier leadership: a term the follower has long left
        // behind, carrying that leadership's much-higher round. `from` is the current leader so the
        // §5.2 leader-authority gate passes and the frame reaches the handler.
        sim.deliverAppendEntries(to = followerId, from = leaderId, term = 0L, round = staleRound)

        sim.awaitTrue("follower emitted its stale-term rejection") {
            sim.network.sent.any { it.from == followerId && (it.message as? RaftMessage.AppendEntriesResponse)?.success == false }
        }
        sim.network.recording = false

        val rejection = sim.network.sent
            .map { it.from to it.message }
            .firstOrNull { (from, msg) -> from == followerId && (msg as? RaftMessage.AppendEntriesResponse)?.success == false }
            ?.second as RaftMessage.AppendEntriesResponse?
        assertNotNull(rejection, "the follower must reject the stale-term AppendEntries")
        assertEquals(
            0L,
            rejection.echoedRound,
            "a stale-term rejection answers nothing in the current round, so it must echo 0 — " +
                "echoing the old request's round ($staleRound) manufactures a freshness quorum",
        )
    }

    /**
     * The other direction, and the reason this cannot be "always echo 0": a rejection that answers
     * nothing must contribute no freshness, but an *accepted* AppendEntries genuinely answers the
     * round it carries and must keep echoing it, or every linearizable read stops resolving.
     *
     * Also pins the sibling that was already correct — `InstallSnapshotResponse`'s stale-term reply
     * defaults to `echoedRound = 0L` — so the two stale-term paths stay consistent.
     */
    @Test
    fun acceptedAppendEntriesStillEchoesTheRoundItAnswered() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        sim.awaitCommit(1L)

        sim.network.sent.clear()
        sim.network.recording = true
        sim.awaitTrue("a heartbeat round-trip completed") {
            sim.network.sent.any { (it.message as? RaftMessage.AppendEntriesResponse)?.success == true }
        }
        sim.network.recording = false

        val roundsSentToPeers = sim.network.sent
            .filter { it.from == leaderId }
            .mapNotNull { (it.message as? RaftMessage.AppendEntries)?.round }
            .toSet()
        val acceptedEchoes = sim.network.sent
            .mapNotNull { it.message as? RaftMessage.AppendEntriesResponse }
            .filter { it.success }
            .map { it.echoedRound }

        assertAll(
            { assertTrue(acceptedEchoes.isNotEmpty(), "expected at least one accepted AppendEntriesResponse") },
            {
                assertTrue(
                    acceptedEchoes.any { it > 0L },
                    "an accepted AppendEntries must echo the round it answered, not 0; echoes=$acceptedEchoes",
                )
            },
            {
                assertTrue(
                    acceptedEchoes.all { it in roundsSentToPeers },
                    "every echoed round must be one the leader actually stamped; echoes=$acceptedEchoes sent=$roundsSentToPeers",
                )
            },
        )
    }
}
