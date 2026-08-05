@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A propose whose command cannot fit the transport's published budget is refused **before** the
 * command enters the log (#2069).
 *
 * The wedge this closes has no other exit. An over-budget command is appended by
 * `RaftEngine.onLocalPropose`, rides out in an `AppendEntries` that nothing chunks, and is dropped
 * at the transport — `SeamRaftTransport.sendTo` must swallow [PayloadTooLarge], since
 * `RaftEngine.send` invokes it unguarded and a throw would fail the engine coroutine rather than one
 * message. The follower therefore never acks, `nextIndex` never advances, and the leader retries
 * that same frame forever. There is no un-propose, and AppendEntries is prefix-ordered, so no
 * *later* entry can commit either: one oversize command wedges the log permanently.
 *
 * ### Why the log assertion is the load-bearing one
 *
 * [aRefusedProposeNeverEntersTheLog] is what distinguishes this fix from a cosmetic one. A gate that
 * threw *after* `state.log += entry` would satisfy [anOversizeProposeIsRefusedNamingTheDerivedLimit]
 * exactly as well while leaving the wedge fully intact — the caller would see a failure and the log
 * would still be poisoned. "It threw" is not the property; "nothing was appended" is.
 *
 * ### Why the exception's numbers are asserted, not just its type
 *
 * A bare `assertFailsWith<PayloadTooLarge>` pins no threshold, so it stays green against a gate set
 * anywhere at all — including one that refuses every propose. Asserting `budgetBytes` and
 * `reservedBytes` pins where the line actually falls, and [aProposeAtExactlyTheLimitCommits] holds
 * the other edge so it cannot drift a byte tight.
 *
 * ### Two of these are green before the fix, deliberately
 *
 * [aProposeAtExactlyTheLimitCommits] and [anUnboundedTransportRefusesNothing] are regression guards
 * on the paths the gate must *not* touch: the at-limit command, and the `null`-budget default that
 * every in-tree transport but a `SeamRaftTransport` over a bounded fabric still reports. They pass
 * before and after; that is their job.
 *
 * [InMemoryRaftNetwork] publishes `maxPayloadBytes` without enforcing it, which is what makes the
 * red phase legible: pre-fix, an oversize propose *commits* rather than throwing, so the failure
 * names the missing gate instead of tripping over a fake transport's own refusal.
 *
 * The ceiling inherited from [raftRunTest] (`TEST_WEDGE_BACKSTOP`) is a **generous wedge backstop,
 * not an assertion** — it is wall-clock over a virtual-time trajectory, so it measures the host
 * rather than the code (#1891). Every wait here goes through a bounded `await*` / [RaftSimulation.settle].
 */
class ProposePayloadBudgetTest {

    /**
     * `RaftEngine.HEADER_BUDGET`, restated by value.
     *
     * The engine's copy is `private` to its companion, so a test can only name the number. That is
     * already the in-tree convention — `InstallSnapshotTest` sizes its chunking cases against the
     * same literal — and it is the right direction of coupling: a test that read the constant would
     * agree with the engine by construction and assert nothing about *which* number it is.
     */
    private val headerBudget = 256

    /** A transport budget comfortably above [headerBudget], so the derived limit is a real number. */
    private val budget = 1024

    /** The largest command this transport can carry: [budget] less the AppendEntries envelope reserve. */
    private val limit = budget - headerBudget

    @Test
    fun anOversizeProposeIsRefusedNamingTheDerivedLimit() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = budget)
        val leader = awaitLeader(sim)
        val refusal = assertFailsWith<PayloadTooLarge> { leader.propose(ByteArray(limit + 1)) }
        assertAll(
            { assertEquals(limit + 1, refusal.payloadBytes, "payloadBytes names the command offered") },
            { assertEquals(limit, refusal.budgetBytes, "budgetBytes is the transport budget less the envelope reserve") },
            { assertEquals(headerBudget, refusal.reservedBytes, "reservedBytes is the envelope reserve") },
        )
    }

    @Test
    fun aRefusedProposeNeverEntersTheLog() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = budget)
        val leader = awaitLeader(sim)
        val leaderId = sim.idOf(leader)
        // Let the leader's election no-op commit first, so the log is quiescent and the only thing
        // that could move the entry count across the refused propose is the propose itself.
        sim.awaitCommit(1L)
        val before = sim.storages.getValue(leaderId).entries(1L).size
        assertFailsWith<PayloadTooLarge> { leader.propose(ByteArray(limit + 1)) }
        sim.settle()
        val after = sim.storages.getValue(leaderId).entries(1L).size
        assertEquals(
            before,
            after,
            "an over-budget command must be refused before it is appended — an entry that reached " +
                "the log can never be un-proposed, and wedges every later index behind it",
        )
    }

    @Test
    fun aProposeAtExactlyTheLimitCommits() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = budget)
        val leader = awaitLeader(sim)
        val command = ByteArray(limit) { (it % 251).toByte() }
        val entry = leader.propose(command)
        sim.awaitCommit(entry.index)
        assertAll(
            { assertContentEquals(command, entry.command, "the at-limit command commits unaltered") },
            { assertTrue(sim.nodes.values.all { it.commitIndex.value >= entry.index }, "replicated to every voter") },
        )
    }

    @Test
    fun anUnboundedTransportRefusesNothing() = raftRunTest {
        // maxPayloadBytes = null — "unknown, not unbounded", and the default every in-tree
        // RaftTransport but a SeamRaftTransport over a bounded fabric still reports.
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val entry = leader.propose(ByteArray(64 * 1024))
        sim.awaitCommit(entry.index)
        assertTrue(
            sim.nodes.values.all { it.commitIndex.value >= entry.index },
            "a transport that names no budget must not have one invented for it",
        )
    }

    @Test
    fun aFollowersProposeIsRefusedBeforeItForwards() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = budget)
        val leader = awaitLeader(sim)
        val followerId = sim.nodeIds.first { it != sim.idOf(leader) }
        sim.awaitRole(followerId, RaftRole.Follower)
        val follower = sim.nodes.getValue(followerId)
        // The forward hop crosses this same local transport, so the gate has to bite on the
        // caller's coroutine — before `RaftMessage.Forward` is minted, not at the leader.
        val refusal = assertFailsWith<PayloadTooLarge> { follower.propose(ByteArray(limit + 1)) }
        assertEquals(limit, refusal.budgetBytes, "a non-leader is bounded by its own transport's budget")
    }
}

/** The [NodeId] under which [node] is registered in this simulation. */
private fun RaftSimulation.idOf(node: RaftNode): NodeId =
    nodes.entries.first { it.value === node }.key
