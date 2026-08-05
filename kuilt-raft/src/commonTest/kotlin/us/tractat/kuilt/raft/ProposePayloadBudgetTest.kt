@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)
package us.tractat.kuilt.raft

import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.cbor.Cbor
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
 * ### The budget is denominated in WIRE bytes, not raw ones (#2150)
 *
 * The gate originally compared `command.size` against the budget. That is wrong by up to a factor of
 * two: CBOR renders a `ByteArray` as an array of integers, so a command at exactly the old limit
 * produced a frame the transport refused — the wedge the gate exists to prevent, reached *through*
 * the gate. Every size here is now the encoded one ([wireBytes]), and
 * [aCommandInsideTheRawLimitButOverItOnTheWireIsRefused] is the case that separates measuring the
 * encoding from counting the bytes.
 *
 * That defect was invisible until [InMemoryRaftNetwork] began **enforcing** the `maxPayloadBytes` it
 * publishes. Before, it reported a budget to the engine and then carried a frame of any size, so a
 * test could assert an at-limit propose committed without that frame ever having to fit. The
 * `overBudget` assertions below are what close it: "the command was accepted" and "the frame it
 * produced was deliverable" are separate claims, and only the first was ever being made.
 *
 * ### Two of these are green before the fix, deliberately
 *
 * [aProposeAtExactlyTheLimitCommits] and [anUnboundedTransportRefusesNothing] are regression guards
 * on the paths the gate must *not* touch: the at-limit command, and the `null`-budget default that
 * every in-tree transport but a `SeamRaftTransport` over a bounded fabric still reports. They pass
 * before and after; that is their job.
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

    /**
     * The largest command this transport can carry, **as encoded**: [budget] less the AppendEntries
     * envelope reserve. Denominated in wire bytes, not raw ones — see [wireBytes].
     */
    private val limit = budget - headerBudget

    /**
     * What [command] costs on the wire, which is what the budget is denominated in and what the gate
     * compares against (#2150).
     *
     * `kotlinx-serialization`'s CBOR renders a `ByteArray` as an array of integers rather than a byte
     * string, so a byte outside CBOR's short range (`0..23` / `-1..-24`) costs **two** bytes. Raw length
     * and wire length therefore differ by up to a factor of two, and the gate used to compare the raw
     * one against the budget — which is how [aCommandInsideTheRawLimitButOverItOnTheWireIsRefused]
     * used to slip straight through it.
     *
     * Measured here rather than computed from that rule, so the test and the engine agree by
     * *measurement* rather than by both hard-coding a model of the codec. A bare `Cbor` suffices:
     * the engine's instance differs only by `ignoreUnknownKeys`, which is a decoding option.
     */
    private fun wireBytes(command: ByteArray): Int =
        Cbor.encodeToByteArray(ByteArraySerializer(), command).size

    /**
     * A command of exactly [wire] encoded bytes, built from a mix of two-byte and one-byte values so it
     * exercises the expansion rather than dodging it. `0x7F` costs two bytes, `0x00` costs one, and the
     * array header costs two.
     */
    private fun commandOfWireSize(wire: Int, wide: Int = 100): ByteArray =
        ByteArray(wide + (wire - 2 - 2 * wide)) { if (it < wide) 0x7F else 0 }

    @Test
    fun anOversizeProposeIsRefusedNamingTheDerivedLimit() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = budget)
        val leader = awaitLeader(sim)
        val command = commandOfWireSize(limit + 1)
        assertEquals(limit + 1, wireBytes(command), "premise: the command must be exactly one wire byte over")
        val refusal = assertFailsWith<PayloadTooLarge> { leader.propose(command) }
        assertAll(
            { assertEquals(limit + 1, refusal.payloadBytes, "payloadBytes names what the command costs on the wire") },
            { assertEquals(limit, refusal.budgetBytes, "budgetBytes is the transport budget less the envelope reserve") },
            { assertEquals(headerBudget, refusal.reservedBytes, "reservedBytes is the envelope reserve") },
        )
    }

    /**
     * The regression the enforcement of #2150 exposed: a command **within** the old raw-byte limit whose
     * *encoded* form is not, is refused.
     *
     * `ByteArray(limit) { 0x7F }` is `limit` raw bytes — exactly what the old gate admitted as "at the
     * limit" — and `2 * limit + 2` on the wire, twice the whole transport budget. Admitting it produced
     * precisely the wedge the gate exists to prevent, reached *through* the gate: appended, minted into
     * an `AppendEntries` no chunking covers, refused by the transport, retried forever.
     *
     * This is the case that distinguishes measuring the encoding from counting the bytes. Both
     * [anOversizeProposeIsRefusedNamingTheDerivedLimit] and [aProposeAtExactlyTheLimitCommits] stay
     * green against a gate that still counted raw bytes but halved its limit; only this one does not.
     */
    @Test
    fun aCommandInsideTheRawLimitButOverItOnTheWireIsRefused() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = budget)
        val leader = awaitLeader(sim)
        val leaderId = sim.idOf(leader)
        sim.awaitCommit(1L)
        val before = sim.storages.getValue(leaderId).entries(1L).size
        val command = ByteArray(limit) { 0x7F }
        assertAll(
            { assertTrue(command.size <= limit, "premise: raw size is INSIDE the limit (${command.size} <= $limit)") },
            { assertTrue(wireBytes(command) > budget, "premise: wire size exceeds the whole budget (${wireBytes(command)} > $budget)") },
        )
        assertFailsWith<PayloadTooLarge> { leader.propose(command) }
        sim.settle()
        val after = sim.storages.getValue(leaderId).entries(1L).size
        assertAll(
            {
                assertEquals(
                    before, after,
                    "a command that cannot be encoded within the budget must never reach the log",
                )
            },
            {
                assertTrue(
                    sim.network.overBudget.isEmpty(),
                    "and no frame carrying it may reach the transport: ${sim.network.overBudget}",
                )
            },
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
        val command = commandOfWireSize(limit)
        assertEquals(limit, wireBytes(command), "premise: the command must sit exactly ON the limit, not under it")
        val entry = leader.propose(command)
        sim.awaitCommit(entry.index)
        assertAll(
            { assertContentEquals(command, entry.command, "the at-limit command commits unaltered") },
            { assertTrue(sim.nodes.values.all { it.commitIndex.value >= entry.index }, "replicated to every voter") },
            {
                assertTrue(
                    sim.network.overBudget.isEmpty(),
                    "and the frame carrying it fits the budget the transport published — the edge this " +
                        "test holds is only meaningful if the at-limit command is actually deliverable: " +
                        "${sim.network.overBudget}",
                )
            },
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
