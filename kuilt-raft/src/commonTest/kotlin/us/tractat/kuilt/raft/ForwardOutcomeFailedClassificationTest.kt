@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.internal.ForwardOutcome
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The originator's retry classification of a forwarded proposal's rejection (#2922).
 *
 * `ForwardOutcome.NotLeader` means "retry on the next leader" and surfaces as
 * [LeadershipLostException]. `ForwardOutcome.Failed` means "failed for a non-retryable reason" and
 * must surface as [ForwardedProposalFailedException] — a type a `catch (LeadershipLostException)`
 * retry loop does not catch. No leader produces `Failed` today, so the response is injected from the
 * peer the forward was registered to, exactly as a real leader's reply would arrive.
 *
 * Each test severs only victim → leader, so the `Forward` is emitted and registered to the leader but
 * never delivered: the real leader appends nothing and sends no reply of its own, and the injected
 * response is the only one the victim ever sees. The victim's first forward carries correlation id
 * `0` (the forwarder's nonce starts there on every node).
 *
 * Runs on the canonical [raftRunTest] + [raftSim] harness (virtual time, seeded election RNG).
 */
class ForwardOutcomeFailedClassificationTest {

    @Test
    fun failedFromTheForwardTarget_surfacesAsNonRetryable() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId = sim.nodeIds.first { it != leaderId }

        sim.dropLink(victimId, leaderId)
        val call = async { runCatchingCancellable { sim.nodes.getValue(victimId).propose(COMMAND) } }
        sim.settle()

        sim.deliverForwardResponse(to = victimId, from = leaderId, clientRequestId = 0L, outcome = ForwardOutcome.Failed)
        sim.settle()

        assertTrue(call.isCompleted, "precondition: the correlated Failed response must resolve the forward")
        val error = call.await().exceptionOrNull()
        assertAll(
            { assertIs<ForwardedProposalFailedException>(error, "Failed must surface as the non-retryable type") },
            {
                assertFalse(
                    error is LeadershipLostException,
                    "Failed must not reach a catch (LeadershipLostException) retry loop, got $error",
                )
            },
        )
    }

    @Test
    fun notLeaderFromTheForwardTarget_surfacesAsRetryable() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId = sim.nodeIds.first { it != leaderId }

        sim.dropLink(victimId, leaderId)
        val call = async { runCatchingCancellable { sim.nodes.getValue(victimId).propose(COMMAND) } }
        sim.settle()

        sim.deliverForwardResponse(to = victimId, from = leaderId, clientRequestId = 0L, outcome = ForwardOutcome.NotLeader)
        sim.settle()

        assertTrue(call.isCompleted, "precondition: the correlated NotLeader response must resolve the forward")
        val error = call.await().exceptionOrNull()
        assertIs<LeadershipLostException>(error, "NotLeader must surface as the retryable type")
    }

    /**
     * The provenance guard (#1911) applies to `Failed` as to every outcome: a `Failed` from a peer the
     * forward was not sent to is dropped, and the forward stays outstanding.
     */
    @Test
    fun failedFromAPeerThatReceivedNoForward_isDropped() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId = sim.nodeIds.first { it != leaderId }
        val attackerId = sim.nodeIds.first { it != leaderId && it != victimId }

        sim.dropLink(victimId, leaderId)
        val call = async { runCatchingCancellable { sim.nodes.getValue(victimId).propose(COMMAND) } }
        sim.settle()

        sim.deliverForwardResponse(to = victimId, from = attackerId, clientRequestId = 0L, outcome = ForwardOutcome.Failed)
        sim.settle()

        val observed = if (call.isCompleted) call.getCompleted().toString() else "<still outstanding>"
        assertFalse(
            call.isCompleted,
            "propose() must NOT complete on a Failed from $attackerId — the Forward went to $leaderId. Got: $observed",
        )
        call.cancelAndJoin()
    }

    private companion object {
        val COMMAND = byteArrayOf(0x43)
    }
}
