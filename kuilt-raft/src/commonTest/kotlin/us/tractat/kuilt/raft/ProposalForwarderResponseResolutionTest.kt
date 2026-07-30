package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.internal.PendingForward
import us.tractat.kuilt.raft.internal.ProposalForwarder
import us.tractat.kuilt.raft.internal.ProposalForwarder.ResponseResolution
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * [ProposalForwarder.onResponse]'s verdict vocabulary (#1911).
 *
 * The three refusals have opposite causes and must not collapse back into one undifferentiated
 * "dropped" — [ResponseResolution.WrongSender] is a forgery, [ResponseResolution.NoSuchForward] is an
 * unknown/duplicate id, and [ResponseResolution.NotYetSent] is a *local* defect (a send site that
 * failed to stamp [PendingForward.sentTo]), whose symptom is a `propose()` that hangs on a genuine
 * reply. The engine can only log what it is told, so the discrimination is pinned here rather than in
 * the log text: [ForwardResponseProvenanceTest] proves the frames are dropped; this proves the reason
 * survives to the caller.
 *
 * Pure state-machine test — no cluster, no timers, so no harness ceremony is required.
 */
class ProposalForwarderResponseResolutionTest {

    @Test
    fun receiptFromThePeerTheForwardWasSentTo_resolves() {
        val f = ProposalForwarder()
        val deferred = CompletableDeferred<LogEntry>()
        val d = assertIs<ProposalForwarder.ForwardDecision.SendToLeader>(
            f.forward(deferred, COMMAND, dedupKey = null, leaderId = LEADER, selfId = SELF)
        )

        val r = assertIs<ResponseResolution.Resolved>(f.onResponse(d.id, from = LEADER))
        assertAll(
            { assertSame(deferred, r.pf.deferred) },
            { assertEquals(LEADER, r.pf.sentTo) },
            // Removed on the accept path, so a duplicate receipt cannot complete the deferred twice.
            { assertIs<ResponseResolution.NoSuchForward>(f.onResponse(d.id, from = LEADER)) },
        )
    }

    @Test
    fun receiptFromAnyOtherPeer_isWrongSender_andNamesTheRecordedTarget() {
        val f = ProposalForwarder()
        val d = assertIs<ProposalForwarder.ForwardDecision.SendToLeader>(
            f.forward(CompletableDeferred(), COMMAND, dedupKey = null, leaderId = LEADER, selfId = SELF)
        )

        val r = assertIs<ResponseResolution.WrongSender>(f.onResponse(d.id, from = ATTACKER))
        assertAll(
            { assertEquals(LEADER, r.sentTo, "the refusal must name the peer the forward actually went to") },
            // The pending entry survives, so the genuine reply still resolves it.
            { assertIs<ResponseResolution.Resolved>(f.onResponse(d.id, from = LEADER)) },
        )
    }

    @Test
    fun receiptForAParkedForward_isNotYetSent_notWrongSender() {
        val f = ProposalForwarder()
        // No leader known → the forward parks in waitingForLeader with sentTo = null.
        val d = f.forward(CompletableDeferred(), COMMAND, dedupKey = null, leaderId = null, selfId = SELF)
        assertEquals(ProposalForwarder.ForwardDecision.Queued, d)

        assertAll(
            { assertIs<ResponseResolution.NotYetSent>(f.onResponse(0L, from = LEADER)) },
            { assertIs<ResponseResolution.NotYetSent>(f.onResponse(0L, from = ATTACKER)) },
        )
    }

    @Test
    fun receiptForAnUnknownId_isNoSuchForward() {
        val f = ProposalForwarder()
        f.forward(CompletableDeferred(), COMMAND, dedupKey = null, leaderId = LEADER, selfId = SELF)

        assertIs<ResponseResolution.NoSuchForward>(f.onResponse(7777L, from = LEADER))
    }

    /**
     * A parked forward flushed to the leader that appeared later must resolve for that leader — the
     * flush-site `sentTo` stamp. Without it this returns [ResponseResolution.NotYetSent], i.e. the
     * engine drops the real leader's genuine reply and `propose()` hangs forever.
     */
    @Test
    fun parkedForwardFlushedToALaterLeader_resolvesForThatLeader() {
        val f = ProposalForwarder()
        f.forward(CompletableDeferred(), COMMAND, dedupKey = null, leaderId = null, selfId = SELF)

        val send = assertIs<ProposalForwarder.FlushAction.SendToLeader>(
            f.flush(leaderId = LEADER, selfId = SELF, amLeader = false).single()
        )

        assertAll(
            { assertEquals(LEADER, send.leaderId) },
            { assertIs<ResponseResolution.Resolved>(f.onResponse(send.id, from = LEADER)) },
        )
    }

    private companion object {
        val SELF = NodeId("self")
        val LEADER = NodeId("leader")
        val ATTACKER = NodeId("attacker")
        val COMMAND = byteArrayOf(0x41)
    }
}
