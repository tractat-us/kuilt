@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
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
 * `from`, and the engine then read them as *authority*: `_leader.value = m.leaderId`,
 * `persistVote(m.candidateId)`, `transfer.onLeaderElected(m.leaderId, …)`. So each was redundant on
 * every honest frame and forgeable on every hostile one.
 *
 * These tests inject the frames by hand rather than through [RaftSimulation]'s typed
 * `deliver*` helpers, because those helpers pass `from` into the identity field — they cannot
 * express the mismatch that is the whole point.
 *
 * Runs on the canonical [raftRunTest] + [raftSim] harness (virtual time, seeded election RNG); see
 * [RaftTestFixtures] for the determinism contract.
 */
class SenderIdentityProvenanceTest {

    /**
     * A voter sends an `AppendEntries` naming a third party as leader. The victim must recognise the
     * peer that actually addressed it, not the name in the payload.
     */
    @Test
    fun appendEntries_namingAThirdPartyAsLeader_doesNotSetLeaderToThatName() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId = sim.nodeIds.first { it != leaderId }
        val attackerId = sim.nodeIds.first { it != leaderId && it != victimId }
        val victimTerm = sim.storages.getValue(victimId).term()

        sim.network.deliver(
            from = attackerId,
            to = victimId,
            bytes = Cbor.encodeToByteArray<RaftMessage>(
                RaftMessage.AppendEntries(
                    term = victimTerm,
                    leaderId = GHOST,
                    prevLogIndex = 0L,
                    prevLogTerm = 0L,
                    entries = emptyList(),
                    leaderCommit = 0L,
                )
            ),
        )
        sim.settle()

        assertEquals(
            attackerId,
            sim.nodes.getValue(victimId).leader.value,
            "the victim must name the peer that addressed it ($attackerId), not the payload's claim (${GHOST.value})",
        )
    }

    /**
     * A voter sends a `RequestVote` naming a third party as candidate. Pre-fix the victim persists the
     * phantom as `votedFor`, which then denies the *genuine* candidate at that term with `AlreadyVoted`
     * — a vote burned on a node that never campaigned, at two frames per term, indefinitely (§5.2).
     */
    @Test
    fun requestVote_namingAThirdPartyAsCandidate_doesNotBurnTheVoteOnThatName() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId = sim.nodeIds.first { it != leaderId }
        val attackerId = sim.nodeIds.first { it != leaderId && it != victimId }
        val forgedTerm = sim.storages.getValue(victimId).term() + 1L

        // `leadershipTransfer = true` bypasses the §4.2.3 leader-stickiness deny, so the vote is
        // actually processed against a victim that still believes the real leader is alive.
        sim.network.deliver(
            from = attackerId,
            to = victimId,
            bytes = Cbor.encodeToByteArray<RaftMessage>(
                RaftMessage.RequestVote(
                    term = forgedTerm,
                    candidateId = GHOST,
                    lastLogIndex = 99L,
                    lastLogTerm = 99L,
                    leadershipTransfer = true,
                )
            ),
        )
        sim.settle()
        val votedFor = sim.storages.getValue(victimId).votedFor()

        // The genuine candidate now asks for the same term. It must not be denied on behalf of a
        // phantom that never ran.
        sim.network.recording = true
        sim.deliverRequestVote(
            to = victimId,
            from = attackerId,
            term = forgedTerm,
            lastLogIndex = 99L,
            lastLogTerm = 99L,
            leadershipTransfer = true,
        )
        sim.settle()
        val granted = sim.network.sent.any { s ->
            s.from == victimId && s.to == attackerId &&
                (s.message as? RaftMessage.RequestVoteResponse)?.voteGranted == true
        }
        sim.network.recording = false

        assertAll(
            {
                assertEquals(
                    attackerId,
                    votedFor,
                    "the vote must be recorded for the peer that asked ($attackerId), not the payload's claim (${GHOST.value})",
                )
            },
            {
                assertTrue(
                    granted,
                    "the genuine candidate $attackerId must not be denied at term $forgedTerm on behalf of ${GHOST.value}",
                )
            },
        )
    }

    /**
     * §3.10: a transfer completes only on a leader-authored message **from the target**. Pre-fix any
     * voter could name the target in an `AppendEntries` and falsely confirm a transfer for a node that
     * never won an election — [transferLeadership] returns success while the target is unreachable.
     */
    @Test
    fun appendEntries_namingTheTransferTarget_doesNotConfirmTheTransfer() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val targetId = sim.nodeIds.first { it != leaderId }
        val attackerId = sim.nodeIds.first { it != leaderId && it != targetId }
        val leaderTerm = sim.storages.getValue(leaderId).term()

        // The target can neither hear the TimeoutNow nor win an election.
        sim.partitionOff(targetId)
        val transfer = async { leader.transferLeadership(targetId) }
        sim.settle()
        assertFalse(transfer.isCompleted, "precondition: the transfer must still be in flight")

        sim.network.deliver(
            from = attackerId,
            to = leaderId,
            bytes = Cbor.encodeToByteArray<RaftMessage>(
                RaftMessage.AppendEntries(
                    term = leaderTerm + 1L,
                    leaderId = targetId,
                    prevLogIndex = 0L,
                    prevLogTerm = 0L,
                    entries = emptyList(),
                    leaderCommit = 0L,
                )
            ),
        )
        sim.settle()

        assertFalse(
            transfer.isCompleted,
            "a transfer to $targetId must not be confirmed by an AppendEntries from $attackerId — " +
                "only a message the target itself authored proves it won",
        )
        transfer.cancelAndJoin()
    }

    private companion object {
        /** A NodeId no peer in the simulation holds — a name nobody can have authored a frame from. */
        val GHOST = NodeId("ghost")
    }
}
