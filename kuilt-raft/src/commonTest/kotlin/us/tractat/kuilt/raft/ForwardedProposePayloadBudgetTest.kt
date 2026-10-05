@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ByteArraySerializer
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.raft.internal.raftCbor
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

    /**
     * The leader measures a forward's envelope around the **originator's** id, because that is the
     * id the appended entry carries — a leader appends a forward under the proposer's `DedupKey`
     * unchanged. Measured around its own short id instead, the leader admits a command at its own
     * limit, wraps it in the forwarder's long id, and mints a frame its transport drops.
     *
     * Fix-agnostic in the same way as `ProposeEnvelopeReserveTest`'s durable-id arm: the leader may
     * refuse this command or carry it, but it must never mint a frame over its budget, and the
     * originator must never be told to retry.
     */
    @Test
    fun aForwardIsMeasuredAroundTheOriginatorsIdNotTheLeaders() = raftRunTest {
        val ids = (1..3).map { NodeId("v$it") }
        val longIdNode = ids.first()
        val cluster = ClusterConfig(voters = ids.toSet())
        // One shared config so the seeded election timeouts differ across nodes (see `raftSim`).
        val config = fastRaftConfig()
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = this,
            nodeScope = backgroundScope,
            maxPayloadBytes = leaderBudget,
            nodeFactory = { id, transport, storage, childScope ->
                val identity = if (id == longIdNode) ClientIdentity.Durable(ClientId(longDurableId)) else ClientIdentity.Auto
                childScope.raftNode(cluster, transport, storage, config, identity)
            },
        )
        // The long id has to be the forwarder's, so it must not be the leader's.
        if (sim.idOf(sim.awaitLeader()) == longIdNode) sim.nodes.getValue(longIdNode).transferLeadership(ids[1])
        val leader = sim.awaitLeader(among = ids.toSet() - longIdNode)
        sim.awaitRole(longIdNode, RaftRole.Follower)
        sim.network.setNodeMaxPayloadBytes(longIdNode, forwarderBudget)
        assertTrue(
            sim.network.overBudget.isEmpty(),
            "rig: nothing may be over budget before the forward, or the assertion below measures nothing: " +
                "${sim.network.overBudget}",
        )
        sim.network.recording = true

        // The limit the leader enforces around its OWN id, discovered from a refusal.
        val leadersOwnLimit = assertFailsWith<PayloadTooLarge> { leader.propose(ByteArray(leaderBudget)) }.budgetBytes
        val command = commandOfWireSize(leadersOwnLimit)
        val outcome = runCatchingCancellable {
            withTimeout(30.seconds) { sim.nodes.getValue(longIdNode).propose(command) }
        }
        sim.settle()

        val leaderId = sim.idOf(leader)
        assertAll(
            {
                assertTrue(
                    sim.network.sent.any { it.from == longIdNode && it.to == leaderId && it.message is RaftMessage.Forward },
                    "rig: the forwarder's own gate must have admitted the command, or the leader was never asked",
                )
            },
            {
                assertTrue(
                    sim.network.overBudget.isEmpty(),
                    "a ${longDurableId.length}-character originator id must not carry a command past the " +
                        "leader's budget: ${sim.network.overBudget}",
                )
            },
            {
                assertTrue(
                    outcome.exceptionOrNull() !is LeadershipLostException,
                    "and the originator must get a verdict it can act on, not a retry: ${outcome.exceptionOrNull()}",
                )
            },
        )
    }

    /**
     * A command of exactly [wire] encoded bytes, found by walking down from `wire − 1` so the codec,
     * not this test, decides the header width.
     */
    private fun commandOfWireSize(wire: Int): ByteArray {
        for (raw in (wire - 1) downTo maxOf(0, wire - 8)) {
            val candidate = ByteArray(raw) { if (it % 2 == 0) 0x7F else 0 }
            if (raftCbor.encodeToByteArray(ByteArraySerializer(), candidate).size == wire) return candidate
        }
        error("no command encodes to exactly $wire wire bytes")
    }

    /**
     * Long enough that even the narrowest envelope around it outgrows the 256 B floor every short
     * auto id sits under — the same 160-character id `ProposeEnvelopeReserveTest` uses, for the same
     * reason.
     */
    private val longDurableId = (
        "tenant-7f3a9c21:client-0f8e1d4b-6a52-4c9e-b1d7-3e8a5f2c0946:shard-11-writer:" +
            "route/eu-west-2/az-c/rack-17/host-0042/process-3/lane-writer"
        ).padEnd(160, 'z')
}

/** The [NodeId] under which [node] is registered in this simulation. */
private fun RaftSimulation.idOf(node: RaftNode): NodeId =
    nodes.entries.first { it.value === node }.key
