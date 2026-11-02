@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.raft.internal.ProposalForwarder
import us.tractat.kuilt.raft.internal.ProposalForwarder.ResponseResolution
import us.tractat.kuilt.raft.internal.RaftEngine
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A `propose` whose caller walked away must not leave its command bytes behind (#3054).
 *
 * `RaftNode.propose` documents "wrap it in your own timeout and retry", and each retry registers a new
 * forward under a new correlation id. So whatever the forwarder keeps for an abandoned attempt, a
 * retry loop keeps once per retry. The engine tests read that retention directly through
 * [RaftEngine.forwardsHeld], which is only safe because [raftRunTest] is single-threaded. Each one
 * counts the `Forward` frames the victim attempted, so a rig that never reached the state under test
 * reds instead of passing on an empty forwarder.
 */
class ForwardReapOnCancelTest {

    /**
     * The issue's own shape: the leader is known and is never heard from, so every attempt is sent
     * and every attempt times out. Severing only victim to leader keeps heartbeats flowing, so the
     * victim's belief in that leader stays honest and no attempt falls back to parking.
     */
    @Test
    fun retriesAgainstALeaderThatNeverReplies_holdNoForwardOnceTimedOut() = raftRunTest {
        val sim: RaftSimulation = raftSim(this, backgroundScope)
        val leader: RaftNode = awaitLeader(sim)
        val leaderId: NodeId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId: NodeId = sim.nodeIds.first { it != leaderId }
        sim.dropLink(victimId, leaderId)
        sim.network.recording = true
        val victim: RaftEngine = sim.engine(victimId)

        val heldInFlight: List<Int> = List(ATTEMPTS) {
            val attempt: Deferred<LogEntry?> = async {
                withTimeoutOrNull(RETRY_AFTER) { victim.propose(COMMAND, REQUEST_ID) }
            }
            sim.settle()
            val held: Int = victim.forwardsHeld
            assertNull(attempt.await(), "a leader that hears nothing cannot have answered")
            held
        }
        sim.settle()

        val leaderLog: List<LogEntry> = sim.storages.getValue(leaderId).entries(1L)
        assertAll(
            // The rig: every attempt really was sent, to the leader, rather than parked or answered.
            { assertEquals(ATTEMPTS, sim.forwardsSent(from = victimId, to = leaderId)) },
            { assertTrue(leaderLog.none { it.command.contentEquals(COMMAND) }, "the leader must never have seen it") },
            { assertEquals(List(ATTEMPTS) { 1 }, heldInFlight, "only the live attempt may be held") },
            { assertEquals(0, victim.forwardsHeld, "every attempt timed out, so none may be held") },
        )
    }

    /**
     * The other half of `flush`'s early returns: no leader has ever been known, so nothing drains the
     * parked queue. A later leader must find nothing of these attempts to send.
     */
    @Test
    fun retriesWithNoLeaderKnown_holdNoForwardOnceTimedOut_andNeverSendLater() = raftRunTest {
        val sim: RaftSimulation = raftSim(this, backgroundScope)
        val victimId: NodeId = sim.nodeIds.first()
        sim.partition(setOf(victimId), sim.nodeIds.toSet() - victimId)
        sim.network.recording = true
        val victim: RaftEngine = sim.engine(victimId)

        val heldInFlight: List<Int> = List(ATTEMPTS) {
            val attempt: Deferred<LogEntry?> = async {
                withTimeoutOrNull(RETRY_AFTER) { victim.propose(COMMAND, REQUEST_ID) }
            }
            sim.settle()
            val held: Int = victim.forwardsHeld
            assertNull(attempt.await(), "a node that hears nobody cannot have committed")
            held
        }
        sim.settle()
        val heldWhileLeaderless: Int = victim.forwardsHeld
        val leaderWhileParked: NodeId? = victim.leader.value

        sim.heal()
        delay(HEAL_WINDOW)

        assertAll(
            { assertNull(leaderWhileParked, "the rig: the victim must have known no leader throughout") },
            { assertEquals(List(ATTEMPTS) { 1 }, heldInFlight, "only the live attempt may be held") },
            { assertEquals(0, heldWhileLeaderless, "timed out while leaderless, so none may be held") },
            { assertNotNull(victim.leader.value, "the rig: the heal must have given the victim a leader") },
            { assertEquals(0, sim.forwardsSentBy(victimId), "an abandoned attempt must never be sent") },
        )
    }

    /**
     * What reaping must not disturb. A forward the leader commits after its caller gave up is answered
     * for an id this node has reaped, and the exactly-once promise rests on the proposer's `DedupKey`
     * in the log rather than on the forwarder's entry. So the retry under the same `requestId` must
     * still coalesce onto that one entry.
     */
    @Test
    fun aForwardCommittedAfterItsCallerGaveUp_coalescesWithTheRetry() = raftRunTest {
        val sim: RaftSimulation = raftSim(this, backgroundScope)
        val leader: RaftNode = awaitLeader(sim)
        val leaderId: NodeId = sim.nodeIds.first { sim.nodes[it] === leader }
        val victimId: NodeId = sim.nodeIds.first { it != leaderId }
        val victim: RaftEngine = sim.engine(victimId)
        sim.network.setLinkLatency(victimId, leaderId, FORWARD_LATENCY)

        val abandoned: LogEntry? = withTimeoutOrNull(FORWARD_LATENCY / 2) { victim.propose(COMMAND, REQUEST_ID) }
        sim.settle()
        val heldOnceAbandoned: Int = victim.forwardsHeld

        delay(FORWARD_LATENCY * 2)
        val committedLate: List<LogEntry> =
            sim.storages.getValue(leaderId).entries(1L).filter { it.command.contentEquals(COMMAND) }

        val retried: LogEntry = withTimeout(1.seconds) { victim.propose(COMMAND, REQUEST_ID) }
        val committedAfterRetry: List<LogEntry> =
            sim.storages.getValue(leaderId).entries(1L).filter { it.command.contentEquals(COMMAND) }

        assertAll(
            { assertNull(abandoned, "the rig: the first attempt must have timed out in flight") },
            { assertEquals(0, heldOnceAbandoned) },
            { assertEquals(1, committedLate.size, "the rig: the abandoned forward must still have landed") },
            { assertEquals(committedLate.single().index, retried.index, "the retry must coalesce") },
            { assertEquals(1, committedAfterRetry.size, "the retry must not append a second copy") },
            { assertEquals(0, victim.forwardsHeld) },
        )
    }

    /** A sweep that took live forwards with it would hang their callers, so it is pinned at each site. */
    @Test
    fun reap_dropsOnlyCompletedForwards_fromBothPlacesTheyPark() {
        val f = ProposalForwarder()
        val abandonedSent = CompletableDeferred<LogEntry>()
        val liveSent = CompletableDeferred<LogEntry>()
        val abandonedParked = CompletableDeferred<LogEntry>()
        val liveParked = CompletableDeferred<LogEntry>()
        f.forward(abandonedSent, COMMAND, dedupKey = null, leaderId = LEADER, selfId = SELF)
        val sent = assertIs<ProposalForwarder.ForwardDecision.SendToLeader>(
            f.forward(liveSent, COMMAND, dedupKey = null, leaderId = LEADER, selfId = SELF),
        )
        f.forward(abandonedParked, COMMAND, dedupKey = null, leaderId = null, selfId = SELF)
        f.forward(liveParked, COMMAND, dedupKey = null, leaderId = null, selfId = SELF)
        val heldBefore: Int = f.heldCount
        val parkedBefore: Int = f.parkedCount
        abandonedSent.cancel()
        abandonedParked.cancel()

        f.reapCancelled()

        val heldAfter: Int = f.heldCount
        val parkedAfter: Int = f.parkedCount
        val flushed = assertIs<ProposalForwarder.FlushAction.SendToLeader>(
            f.flush(leaderId = LEADER, selfId = SELF, amLeader = false).single(),
        )
        assertAll(
            { assertEquals(4, heldBefore, "the rig: all four forwards must have been held") },
            { assertEquals(2, parkedBefore, "the rig: two of them must have been parked") },
            { assertEquals(2, heldAfter) },
            { assertEquals(1, parkedAfter, "a reaped id must not stay queued behind the map") },
            { assertNotEquals(sent.id, flushed.id) },
            { assertIs<ResponseResolution.Resolved>(f.onResponse(flushed.id, from = LEADER)) },
            { assertSame(liveSent, assertIs<ResponseResolution.Resolved>(f.onResponse(sent.id, from = LEADER)).pf.deferred) },
        )
    }

    /** Ids are never reused, so the reply to a reaped attempt cannot complete the attempt that replaced it. */
    @Test
    fun aLateReplyToAReapedForward_isNoSuchForward_andLeavesTheRetryOutstanding() {
        val f = ProposalForwarder()
        val abandoned = CompletableDeferred<LogEntry>()
        val retry = CompletableDeferred<LogEntry>()
        val first = assertIs<ProposalForwarder.ForwardDecision.SendToLeader>(
            f.forward(abandoned, COMMAND, dedupKey = null, leaderId = LEADER, selfId = SELF),
        )
        val second = assertIs<ProposalForwarder.ForwardDecision.SendToLeader>(
            f.forward(retry, COMMAND, dedupKey = null, leaderId = LEADER, selfId = SELF),
        )
        abandoned.cancel()

        f.reapCancelled()

        assertAll(
            { assertIs<ResponseResolution.NoSuchForward>(f.onResponse(first.id, from = LEADER)) },
            { assertEquals(1, f.heldCount, "the late reply must leave the retry held") },
            { assertSame(retry, assertIs<ResponseResolution.Resolved>(f.onResponse(second.id, from = LEADER)).pf.deferred) },
        )
    }

    private fun RaftSimulation.engine(id: NodeId): RaftEngine = nodes.getValue(id) as RaftEngine

    private fun RaftSimulation.forwardsSent(from: NodeId, to: NodeId): Int =
        network.sent.count { it.from == from && it.to == to && it.message is RaftMessage.Forward }

    private fun RaftSimulation.forwardsSentBy(from: NodeId): Int =
        network.sent.count { it.from == from && it.message is RaftMessage.Forward }

    private companion object {
        const val ATTEMPTS = 8
        const val REQUEST_ID = 7L
        val RETRY_AFTER: Duration = 50.milliseconds
        val HEAL_WINDOW: Duration = 100.milliseconds
        val FORWARD_LATENCY: Duration = 100.milliseconds
        val SELF = NodeId("self")
        val LEADER = NodeId("leader")
        val COMMAND: ByteArray = byteArrayOf(0x41)
    }
}
