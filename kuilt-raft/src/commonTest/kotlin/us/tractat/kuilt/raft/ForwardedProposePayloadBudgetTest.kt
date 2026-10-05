@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A **forwarded** propose is held to the *leader's* payload budget, and a refusal reaches the
 * originator as the same non-retryable [PayloadTooLarge] a local propose throws (#2155).
 *
 * `RaftEngine.checkProposeFitsTransport` guards the local propose on the caller's coroutine, against
 * the caller's own transport. A forward then arrives at the leader through `onForward`, which used to
 * hand it straight to `onPropose`. Where the forwarder's budget is the **larger** of the two — a mesh
 * reports the minimum across its own live links, so nodes routinely differ — the forwarder's check
 * passes, the leader appends an entry it cannot replicate, and the log wedges exactly as #2069 did for
 * local proposes. The leader then loses its quorum, the forward is answered `NotLeader`, and the
 * originator is told to *retry* a command that will fail identically against every leader.
 *
 * ### What proves the rig fired
 *
 * Everything here is vacuous unless the forwarder's own gate let the command through: a forwarder
 * that refused it locally would also throw [PayloadTooLarge], with no `Forward` ever sent and the
 * leader never consulted. So the test asserts the `Forward` frame left the forwarder for the leader,
 * and that the refusal names the **leader's** budget rather than the forwarder's.
 */
class ForwardedProposePayloadBudgetTest {

    /** The budget every node but the forwarder publishes — including the leader. */
    private val leaderBudget = 1024

    /** The forwarder's own budget: large enough that its local gate admits [overLeaderBudget]. */
    private val forwarderBudget = 4096

    /**
     * Over the leader's enforced limit however its reserve is derived (that limit is at most
     * `leaderBudget − HEADER_BUDGET`), and comfortably under the forwarder's (at least
     * `forwarderBudget − ` a measured envelope of a few hundred bytes).
     */
    private val overLeaderBudget = ByteArray(leaderBudget)

    @Test
    fun aForwardOverTheLeadersBudgetIsRefusedByTheLeaderWithoutAppending() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = leaderBudget)
        val leader = awaitLeader(sim)
        val leaderId = sim.idOf(leader)
        // The election no-op commits first, so the only thing that could grow the leader's log
        // across the forwarded propose is the propose itself.
        sim.awaitCommit(1L)
        val forwarderId = sim.nodeIds.first { it != leaderId }
        sim.awaitRole(forwarderId, RaftRole.Follower)
        sim.network.setNodeMaxPayloadBytes(forwarderId, forwarderBudget)
        val forwarder = sim.nodes.getValue(forwarderId)
        val before = sim.storages.getValue(leaderId).entries(1L).size
        sim.network.recording = true

        // Bounded in virtual time: before the fix the forward is either answered `NotLeader` (the
        // leader loses its quorum behind the unreplicable entry) or never answered at all — both of
        // which must red here rather than hang.
        val refusal = withTimeout(30.seconds) {
            assertFailsWith<PayloadTooLarge>(
                "a forward the leader cannot replicate must surface as the non-retryable PayloadTooLarge",
            ) { forwarder.propose(overLeaderBudget) }
        }
        sim.settle()
        val after = sim.storages.getValue(leaderId).entries(1L).size
        val leaderAfter = sim.idOf(sim.awaitLeader())

        assertAll(
            {
                assertTrue(
                    sim.network.sent.any { it.from == forwarderId && it.to == leaderId && it.message is RaftMessage.Forward },
                    "rig: the forwarder's own gate must have admitted the command and sent it on — " +
                        "otherwise the refusal is the local one and the leader was never consulted",
                )
            },
            {
                assertEquals(
                    leaderBudget, refusal.budgetBytes + refusal.reservedBytes,
                    "the refusal is the leader's measurement, against the leader's budget",
                )
            },
            { assertEquals(before, after, "the leader must refuse before appending — there is no un-propose") },
            {
                assertTrue(
                    sim.network.overBudget.isEmpty(),
                    "no frame carrying the command may reach a transport that cannot carry it: ${sim.network.overBudget}",
                )
            },
            { assertEquals(leaderId, leaderAfter, "the leader keeps its quorum — nothing wedged it") },
        )
    }

    @Test
    fun aForwardWithinTheLeadersBudgetStillCommits() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = leaderBudget)
        val leader = awaitLeader(sim)
        val leaderId = sim.idOf(leader)
        val forwarderId = sim.nodeIds.first { it != leaderId }
        sim.awaitRole(forwarderId, RaftRole.Follower)
        sim.network.setNodeMaxPayloadBytes(forwarderId, forwarderBudget)
        val command = ByteArray(leaderBudget / 4) { it.toByte() }

        val entry = sim.nodes.getValue(forwarderId).propose(command)
        sim.awaitCommit(entry.index)

        assertAll(
            { assertContentEquals(command, entry.command, "the forwarded command commits unaltered") },
            { assertTrue(sim.nodes.values.all { it.commitIndex.value >= entry.index }, "replicated to every voter") },
        )
    }
}

/** The [NodeId] under which [node] is registered in this simulation. */
private fun RaftSimulation.idOf(node: RaftNode): NodeId =
    nodes.entries.first { it.value === node }.key
