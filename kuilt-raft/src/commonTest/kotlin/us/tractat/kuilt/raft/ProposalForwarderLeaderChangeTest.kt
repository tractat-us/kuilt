package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.raft.internal.ProposalForwarder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ProposalForwarder.onLeaderChanged]'s `(node, term)` reap key (Raft §8, #1238).
 *
 * The residual gap keyed on node alone: a leader can crash, restart (term/vote persisted), and re-win at a
 * **higher** term while a forward it never answered is still outstanding — a node-only key treats that
 * re-elected same-node leader as "unchanged" and never reaps, so the caller parks forever again. These
 * tests pin the widened key at the machine level: same-node-higher-term reaps, same-node-same-term (a
 * heartbeat) does not.
 *
 * Why machine-level and not a sim scenario: a crash → restart → re-elect-*the same node* interleaving can't
 * be forced deterministically — the seeded election RNG decides which survivor wins, not the test — so an
 * end-to-end "L1 specifically re-wins" scenario would be flaky. The different-leader and crash-then-new-
 * leader end-to-end paths are covered by [RaftProposeForwardingTest]; the term dimension is pinned here.
 */
class ProposalForwarderLeaderChangeTest {
    private val self = NodeId("self")
    private val l1 = NodeId("L1")
    private val l2 = NodeId("L2")

    /** Register + SEND a forward to [leader] under leadership [term]; return its caller deferred. */
    private fun ProposalForwarder.sendTo(leader: NodeId, term: Long): CompletableDeferred<LogEntry> {
        val d = CompletableDeferred<LogEntry>()
        val decision = forward(d, byteArrayOf(1), dedupKey = null, leaderId = leader, selfId = self, currentTerm = term)
        assertTrue(decision is ProposalForwarder.ForwardDecision.SendToLeader, "precondition: forward must be SENT")
        return d
    }

    @Test
    fun sameNodeHigherTerm_reapsSentForward() {
        val f = ProposalForwarder()
        val d = f.sendTo(l1, term = 1)                    // forward sent to L1 under term 1
        val reaped = f.onLeaderChanged(l1, newTerm = 2)   // L1 crashed, restarted, re-won at term 2
        assertTrue(reaped.singleOrNull()?.deferred === d, "same node at a higher term must reap the stranded forward")
        assertFalse(d.isCompleted, "onLeaderChanged returns the forward; the engine (not the machine) completes it")
    }

    @Test
    fun sameNodeSameTerm_doesNotReap() {
        val f = ProposalForwarder()
        val d = f.sendTo(l1, term = 1)
        val reaped = f.onLeaderChanged(l1, newTerm = 1)   // a repeated heartbeat from the same leader, same term
        assertTrue(reaped.isEmpty(), "same node at the same term (a heartbeat) must NOT reap a healthy in-flight forward")
        assertFalse(d.isCompleted, "the healthy forward stays pending")
        assertNotNull(f.onResponse(0L), "the untouched forward must still be resolvable by its ForwardResponse")
    }

    @Test
    fun differentNode_reapsSentForward() {
        val f = ProposalForwarder()
        val d = f.sendTo(l1, term = 1)
        val reaped = f.onLeaderChanged(l2, newTerm = 1)   // a different leader appeared at the same term
        assertTrue(reaped.singleOrNull()?.deferred === d, "a different leader must reap the stranded forward")
    }
}
