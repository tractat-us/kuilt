@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sender identity is the transport's `from`, never a wire field (issue #1912).
 *
 * Five `RaftMessage` fields used to restate the sender's own id — `AppendEntries.leaderId`,
 * `InstallSnapshot.leaderId`, `RequestVote.candidateId`, `PreVote.candidateId`,
 * `TimeoutNow.leaderId`. Every honest sender set them to its own `selfId`, nobody compared them to
 * `from`, and the engine read them as *authority*: `_leader.value = m.leaderId`,
 * `persistVote(m.candidateId)`, `transfer.onLeaderElected(m.leaderId, …)`. So each was redundant on
 * every honest frame and forgeable on every hostile one — a voter could name a third party as
 * leader, burn a victim's vote on a phantom that never campaigned, or falsely confirm a §3.10
 * transfer for a node that never won.
 *
 * **Those forgeries are no longer representable.** The fields are gone rather than checked, so there
 * is no frame to construct: the identity a handler reads *is* `from`, structurally, at every read
 * site including ones not yet written. This class is therefore the positive form — each test drives
 * the honest path and pins that the derived state (`leader`, `votedFor`, transfer confirmation)
 * names the peer that actually authored the frame. The earlier, negative form of these three tests
 * (a `leaderId`/`candidateId` naming someone other than the sender) is preserved in this branch's
 * first commit; it no longer compiles, which is the point.
 *
 * Runs on the canonical [raftRunTest] + [raftSim] harness (virtual time, seeded election RNG); see
 * [RaftTestFixtures] for the determinism contract.
 */
class SenderIdentityProvenanceTest {

    /**
     * An `AppendEntries` from a voter that is not the recognised leader still moves `_leader` — the
     * #1906 lane, deliberately unchanged here. What is pinned is *who* it moves it to: the sender,
     * with no payload field left that could name anyone else.
     */
    @Test
    fun appendEntries_makesTheRecipientNameItsSender() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId = sim.nodeIds.first { it != leaderId }
        val senderId = sim.nodeIds.first { it != leaderId && it != victimId }
        val victimTerm = sim.storages.getValue(victimId).term()

        sim.deliverAppendEntries(to = victimId, from = senderId, term = victimTerm)
        sim.settle()

        assertEquals(
            senderId,
            sim.nodes.getValue(victimId).leader.value,
            "the recipient must name the peer that addressed it",
        )
    }

    /**
     * §5.2: the vote is recorded for the peer that asked. Pre-fix the payload's `candidateId` was
     * persisted instead, so a voter could burn a victim's vote on a phantom — after which the victim
     * denied the *genuine* candidate at that term with `AlreadyVoted`, at two frames per term,
     * indefinitely. The second half of this test is that denial's absence.
     */
    @Test
    fun requestVote_recordsTheVoteForItsSender_andTheSameSenderIsNotThenDenied() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId = sim.nodeIds.first { it != leaderId }
        val candidateId = sim.nodeIds.first { it != leaderId && it != victimId }
        val electionTerm = sim.storages.getValue(victimId).term() + 1L

        // `leadershipTransfer = true` bypasses the §4.2.3 leader-stickiness deny, so the vote is
        // actually processed against a victim that still believes the real leader is alive.
        sim.deliverRequestVote(
            to = victimId,
            from = candidateId,
            term = electionTerm,
            lastLogIndex = 99L,
            lastLogTerm = 99L,
            leadershipTransfer = true,
        )
        sim.settle()
        val votedFor = sim.storages.getValue(victimId).votedFor()

        // The same candidate asks again at the same term — a retransmit. §5.2 grants it, because the
        // recorded vote is already this peer's. It is denied only if the first frame recorded someone else.
        sim.network.recording = true
        sim.deliverRequestVote(
            to = victimId,
            from = candidateId,
            term = electionTerm,
            lastLogIndex = 99L,
            lastLogTerm = 99L,
            leadershipTransfer = true,
        )
        sim.settle()
        val granted = sim.network.sent.any { s ->
            s.from == victimId && s.to == candidateId &&
                (s.message as? RaftMessage.RequestVoteResponse)?.voteGranted == true
        }
        sim.network.recording = false

        assertAll(
            { assertEquals(candidateId, votedFor, "the vote must be recorded for the peer that asked") },
            { assertTrue(granted, "$candidateId must not be denied at term $electionTerm on behalf of another name") },
        )
    }

    /**
     * §3.10: a transfer completes only on a leader-authored message **from the target**. Pre-fix any
     * voter could name the target in an `AppendEntries` and falsely confirm a transfer for a node
     * that never won an election. With no name in the frame, a non-target sender cannot confirm it —
     * pinned here on the sender that could previously have lied.
     */
    @Test
    fun transferIsNotConfirmedByAnAppendEntriesFromAnyPeerButTheTarget() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }
        val otherId = sim.nodeIds.first { it != leaderId && it != targetId }
        val leaderTerm = sim.storages.getValue(leaderId).term()

        // The target can neither hear the TimeoutNow nor win an election, so nothing but a forgery
        // could resolve the transfer inside the test's window.
        sim.partitionOff(targetId)
        val transfer = async { leader.transferLeadership(targetId) }
        sim.settle()
        assertFalse(transfer.isCompleted, "precondition: the transfer must still be in flight")

        sim.deliverAppendEntries(to = leaderId, from = otherId, term = leaderTerm + 1L)
        sim.settle()

        assertFalse(
            transfer.isCompleted,
            "a transfer to $targetId must not be confirmed by an AppendEntries from $otherId — " +
                "only a message the target itself authored proves it won",
        )
        transfer.cancelAndJoin()
    }
}
