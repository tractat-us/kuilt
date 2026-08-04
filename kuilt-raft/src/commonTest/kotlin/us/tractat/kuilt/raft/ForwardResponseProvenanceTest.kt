@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import us.tractat.kuilt.raft.internal.ForwardOutcome
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ForwardResponse` provenance (issue #1911, Raft §8 client interaction).
 *
 * A `ForwardResponse` carries no term and no leader identity — it is correlated to its `Forward`
 * solely by a follower-local nonce that starts at `0` on every node. Without a check that the
 * *sender* is the peer the `Forward` actually went to, any admitted peer can fabricate a commit
 * receipt: `propose()` returns a `LogEntry` for a command no node ever appended, and the consumer's
 * exactly-once bookkeeping marks that write done — so the retry that exactly-once exists to provide
 * never happens and the write is lost permanently.
 *
 * The forgery must be **dropped**, not clamped and not thrown: a correlation id is a nonce with no
 * conservative in-range reading (#1817), and a throw inside the engine's actor loop is permanent node
 * death (#1818). Dropping leaves `propose()` outstanding, exactly as if the response had been lost on
 * the wire — the caller's own timeout/retry then does the right thing.
 *
 * Runs on the canonical [raftRunTest] + [raftSim] harness (virtual time, seeded election RNG); see
 * [RaftTestFixtures] for the determinism contract.
 */
class ForwardResponseProvenanceTest {

    /**
     * The headline forgery: a third peer that received no `Forward` answers for the leader. Pre-fix,
     * `propose()` returns `LogEntry(index = 4242, term = 99)` — a commit that happened nowhere.
     */
    @Test
    fun forwardResponse_fromAPeerThatReceivedNoForward_isDropped() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId = sim.nodeIds.first { it != leaderId }
        val attackerId = sim.nodeIds.first { it != leaderId && it != victimId }

        // Sever ONLY victim → leader, so the Forward is emitted but never delivered and the real
        // leader appends nothing. Heartbeats still flow leader → victim, so the victim's leader
        // belief stays honest — this isolates the response half from any `leaderId` forgery.
        sim.dropLink(victimId, leaderId)
        val call = async { sim.nodes.getValue(victimId).propose(FORGED_COMMAND) }
        sim.settle()

        sim.deliverForwardResponse(
            to = victimId,
            from = attackerId,
            clientRequestId = 0L,
            outcome = ForwardOutcome.Committed(index = 4242L, term = 99L),
        )
        sim.settle()

        val fabricated = if (call.isCompleted) call.getCompleted().toString() else "<still outstanding>"
        val leaderNeverAppended = sim.storages.getValue(leaderId).entries(1L)
            .none { it.command.contentEquals(FORGED_COMMAND) }
        assertAll(
            {
                assertFalse(
                    call.isCompleted,
                    "propose() must NOT complete on a ForwardResponse from $attackerId — the Forward " +
                        "went to $leaderId. Returned: $fabricated",
                )
            },
            {
                assertTrue(
                    leaderNeverAppended,
                    "precondition: the real leader must never have appended the command",
                )
            },
        )
        call.cancelAndJoin()
    }

    /**
     * The id half of the same guard: a response from the *correct* peer but for a correlation id no
     * forward ever used must also be dropped, and must not consume the genuinely outstanding forward.
     */
    @Test
    fun forwardResponse_withUnmatchedCorrelationId_isDropped() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId = sim.nodeIds.first { it != leaderId }

        sim.dropLink(victimId, leaderId)
        val call = async { sim.nodes.getValue(victimId).propose(FORGED_COMMAND) }
        sim.settle()

        sim.deliverForwardResponse(
            to = victimId,
            from = leaderId,
            clientRequestId = 7777L,
            outcome = ForwardOutcome.Committed(index = 4242L, term = 99L),
        )
        sim.settle()

        assertFalse(
            call.isCompleted,
            "propose() must NOT complete on a ForwardResponse for an id no Forward used",
        )
        call.cancelAndJoin()
    }

    /**
     * A response that arrives before any forward is outstanding at all — nothing is pending, so every
     * response is a forgery. Pins the `null` branch of the guard.
     */
    @Test
    fun forwardResponse_withNoForwardOutstanding_isDroppedAndDoesNotPoisonALaterForward() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId = sim.nodeIds.first { it != leaderId }
        val attackerId = sim.nodeIds.first { it != leaderId && it != victimId }

        // No propose() has run on the victim yet, so nothing is pending.
        sim.deliverForwardResponse(
            to = victimId,
            from = attackerId,
            clientRequestId = 0L,
            outcome = ForwardOutcome.Committed(index = 4242L, term = 99L),
        )
        sim.settle()

        // The victim's next honest forward must still work end-to-end.
        val entry = sim.nodes.getValue(victimId).propose(HONEST_COMMAND)
        sim.awaitCommit(entry.index)
        assertAll(
            { assertTrue(entry.index != 4242L, "the honest entry must not carry the forged index") },
            { assertTrue(entry.command.contentEquals(HONEST_COMMAND)) },
        )
        sim.checkInvariants()
    }

    /**
     * The honest path, pinned: an ordinary follower forward-and-respond still completes. This is the
     * positive control for the guard — the sender check must accept the peer the forward went to.
     */
    @Test
    fun honestForwardAndResponse_stillCommits() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        awaitLeader(sim)
        val follower = sim.followers().first()

        val entry = follower.propose(HONEST_COMMAND)

        sim.awaitCommit(entry.index)
        assertTrue(entry.command.contentEquals(HONEST_COMMAND))
        sim.checkInvariants()
    }

    /**
     * The cross-leader path, pinned: a forward parked while no leader is known is sent by `flush` to
     * whichever leader appears *later*. The recorded target must therefore be stamped at the flush
     * send site, not only where the forward is first registered — otherwise every parked forward has
     * no recorded target and its legitimate response is dropped, hanging `propose()` forever. This is
     * the regression the `:kuilt-cluster` relay/failover suites would otherwise be the first to see.
     */
    @Test
    fun parkedForward_flushedToTheLeaderThatAppearsLater_stillCommits() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val v1 = sim.nodeIds[0]
        val v2 = sim.nodeIds[1]
        val v3 = sim.nodeIds[2]
        // Isolate v1 so it never sees a leader; v2/v3 hold quorum and elect one without it.
        sim.partition(setOf(v1), setOf(v2, v3))
        delay(50)
        sim.awaitLeader(setOf(v2, v3))

        // v1 proposes with no leader known — the forward parks in waitingForLeader, unsent.
        val call = async { sim.nodes.getValue(v1).propose(HONEST_COMMAND) }
        sim.settle()

        // Heal: v1 learns the leader, and flush() sends the parked forward to it for the first time.
        sim.heal()
        sim.awaitTrue("parked forward resolves after flush to the leader that appeared later") { call.isCompleted }

        val entry = call.await()
        sim.awaitCommit(entry.index)
        assertTrue(entry.command.contentEquals(HONEST_COMMAND))
        sim.checkInvariants()
    }

    private companion object {
        val FORGED_COMMAND = byteArrayOf(0x41)
        val HONEST_COMMAND = byteArrayOf(0x42)
    }
}
