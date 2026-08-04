package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.internal.MembershipState
import us.tractat.kuilt.raft.internal.ReadIndexTracker
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for #1817: a voter's echoed heartbeat round must never be credited past the round the
 * leader has actually reached.
 *
 * [ReadIndexTracker] is a plain, actor-confined decision machine, so driving one instance directly is
 * legitimate (not a hand-rolled cluster) — the same justification as `LeadershipTransferMachineTest`.
 * The forged value is a wire field, so no cluster is needed to inject it: `recordAck` *is* the parse
 * boundary.
 *
 * `recordAck` stored `echoedRound` verbatim. A voter replying with `echoedRound = Long.MAX_VALUE`
 * therefore satisfied `lastAckRound[v] > read.sinceRound` for **every** subsequent read, and since
 * `lastAckRound` is cleared only on leadership change, that one response bought a permanent seat in
 * `freshContacts` for the rest of the leadership term. `quorumOfContacts` then reported a fresh quorum
 * assembled from voters that never answered the current round, and the engine's `confirmFreshReads()`
 * completed `readIndex()` — a stale read served as linearizable, violating §3.7.
 *
 * **The shipped disposition is a discard, not the clamp this class is named for.** `recordAck` is
 * `if (echoedRound <= round) lastAckRound[from] = echoedRound` — an out-of-range echo drops its
 * freshness evidence entirely. The `minOf(echoedRound, round)` clamp that the sibling
 * `minOf(m.matchIndex, state.lastLogIndex)` (#1175) would suggest was considered and **rejected**:
 * `matchIndex` is a quantity, where the nearest valid value is a meaningful conservative answer, but
 * a round is a nonce, where clamping maps a forged value onto the *most favourable* value in range.
 * See [forgedEchoArrivingAfterBumpMustNotConfirmRead], the one test in the repo that separates the
 * two — it is what the clamp fails and the discard passes.
 *
 * Either disposition is a no-op for every honest peer: `round` only ever increases and only the
 * leader stamps it into a request, so any round a follower can be echoing was necessarily stamped
 * when `round` was at or below its present value. They differ only on a value the leader never sent.
 */
internal class ReadIndexRoundClampTest {

    private val leaderId = NodeId("leader")
    private val v2 = NodeId("v2")
    private val v3 = NodeId("v3")

    /** Simple 3-voter config: quorum = 2, so the leader's self-credit plus ONE fresh voter confirms a read. */
    private val threeVoters = MembershipState.Simple(ClusterConfig(voters = setOf(leaderId, v2, v3)))

    /** Advance the nonce to [target] the way the engine does — one bump per heartbeat broadcast. */
    private fun ReadIndexTracker.advanceRoundTo(target: Long) {
        repeat(target.toInt()) { bumpRound() }
    }

    /**
     * #1817 — the safety property. A voter that has answered nothing in the current round must not be
     * counted toward a read's freshness quorum, no matter what round it claims to have answered.
     */
    @Test
    fun forgedEchoedRoundDoesNotConfirmRead() {
        val tracker = ReadIndexTracker()
        tracker.advanceRoundTo(5L)

        // v2 claims to have answered a round the leader has never stamped and never will.
        tracker.recordAck(v2, Long.MAX_VALUE)

        val deferred = CompletableDeferred<Long>()
        val decision = tracker.request(deferred, commitIndex = 7L, membership = threeVoters, selfId = leaderId) { }

        // The heartbeat round advances; NEITHER v2 nor v3 answers round 6.
        tracker.bumpRound()

        // A returned read is one the engine will confirm — it emits ReadIndexConfirmed and completes the
        // caller's deferred at the call site. So an empty return is the whole safety property here.
        assertAll(
            { assertEquals(ReadIndexTracker.ReadDecision.Queued(7L, 5L, 1), decision, "read must be queued at the round it was requested in") },
            {
                assertEquals(
                    emptyList(),
                    tracker.resolve(threeVoters, leaderId).map { it.readIndex },
                    "a forged echoedRound must not manufacture a freshness quorum: no voter answered round 6",
                )
            },
        )
    }

    /**
     * #1817 — the ordering the sibling test misses, and the one that separates *discard* from *clamp*.
     *
     * The forged echo arrives **after** the round is bumped rather than before. A clamp
     * (`minOf(echoedRound, round)`) maps the forged value onto the round the leader has just broadcast
     * and never heard an answer to — the most favourable value in range — so it is credited exactly as
     * if v2 had honestly answered round 6. The exploit is not closed, merely re-armed per frame: echo
     * `Long.MAX_VALUE` on every reply and the forger is fresh for every read for the rest of the term.
     *
     * `echoedRound > round` is *proof* the leader never sent that round, so there is no conservative
     * in-range reading to fall back on the way there is for a quantity like `matchIndex`. Discarding is
     * the only disposition that preserves what the nonce is for: §6.4 freshness means the leader
     * exchanged heartbeats with a majority *after the read arrived*, and crediting an unanswered round
     * severs exactly that request→response link.
     */
    @Test
    fun forgedEchoArrivingAfterBumpMustNotConfirmRead() {
        val tracker = ReadIndexTracker()
        tracker.advanceRoundTo(5L)

        val deferred = CompletableDeferred<Long>()
        tracker.request(deferred, commitIndex = 7L, membership = threeVoters, selfId = leaderId) { }

        tracker.bumpRound()                      // round = 6, broadcast goes out
        tracker.recordAck(v2, Long.MAX_VALUE)    // v2 forges instead of answering round 6

        assertEquals(
            emptyList(),
            tracker.resolve(threeVoters, leaderId).map { it.readIndex },
            "a forged echoedRound must not count as an answer to round 6",
        )
    }

    /**
     * The other direction. A disposition that simply refused to credit acks, or that stored `round`
     * instead of the echoed value, would also pass the tests above — so pin that an honest ack in the
     * current round still confirms the read.
     */
    @Test
    fun honestEchoedRoundStillConfirmsRead() {
        val tracker = ReadIndexTracker()
        tracker.advanceRoundTo(5L)

        val deferred = CompletableDeferred<Long>()
        tracker.request(deferred, commitIndex = 7L, membership = threeVoters, selfId = leaderId) { }

        tracker.bumpRound()          // round = 6
        tracker.recordAck(v2, 6L)    // v2 genuinely answers round 6 → self + v2 = quorum of 3 voters

        assertEquals(
            listOf(7L),
            tracker.resolve(threeVoters, leaderId).map { it.readIndex },
            "an honest current-round ack must still confirm the read",
        )
    }

    /**
     * BLOCKER 1 (round-slip) must survive the out-of-range guard. Storing `round` at receipt — the
     * tempting one-liner — would credit a late ack for round 6 to round 7 and wrongly confirm a read
     * queued at `sinceRound = 6`. The guard fires only on `echoedRound > round`, so an in-range echo
     * is stored verbatim and the late ack stays credited to the round it answered.
     */
    @Test
    fun inRangeEchoPreservesRoundSlipCreditingForLateAck() {
        val tracker = ReadIndexTracker()
        tracker.advanceRoundTo(6L)

        val deferred = CompletableDeferred<Long>()
        tracker.request(deferred, commitIndex = 7L, membership = threeVoters, selfId = leaderId) { }

        // The round advances while v2's response to round 6 is still in flight, then it lands.
        tracker.bumpRound()          // round = 7
        tracker.recordAck(v2, 6L)    // answered 6, arriving at 7 — must stay credited to 6

        assertEquals(
            emptyList(),
            tracker.resolve(threeVoters, leaderId).map { it.readIndex },
            "a round-slipped ack must be credited to the round it answered (6), not the round at receipt (7)",
        )
    }
}
