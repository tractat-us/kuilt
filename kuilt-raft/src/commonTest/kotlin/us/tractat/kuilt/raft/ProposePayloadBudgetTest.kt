@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)
package us.tractat.kuilt.raft

import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.raft.internal.raftCbor
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
 * The gate originally compared `command.size` against the budget. That was wrong by up to a factor of
 * two while CBOR rendered a `ByteArray` as an array of integers, so a command at exactly the old limit
 * produced a frame the transport refused — the wedge the gate exists to prevent, reached *through*
 * the gate. #2160's byte-string framing narrowed the gap to the 1–5 byte length header but did not
 * close it, and a frame one byte over budget is dropped exactly as one twice over is. Every size here
 * is the encoded one ([wireBytes]), and [aCommandInsideTheRawLimitButOverItOnTheWireIsRefused] is
 * still the case that separates measuring the encoding from counting the bytes.
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
     * The largest command [headerBudget] alone would allow, **as encoded**. Denominated in wire
     * bytes, not raw ones — see [wireBytes].
     *
     * Since #2156 this is a **ceiling on the enforced limit, not the enforced limit itself.**
     * `HEADER_BUDGET` is the number a caller may rely on; the gate reserves whichever is larger of it
     * and the measured envelope, so the real limit is at or below this and moves with the node's
     * `ClientId`. Every test that sits *on* the edge therefore discovers it via [derivedLimit]
     * instead of assuming this — hard-coding it is what would rot the moment the envelope changes.
     */
    private val floorLimit = budget - headerBudget

    /**
     * The limit this node actually enforces, **discovered from a refusal** rather than recomputed
     * here.
     *
     * A test that recomputed `budget − reserve` would agree with the engine by construction and
     * assert nothing; asking the engine and then pinning the *relationships* around the answer —
     * that it partitions [budget], that it never exceeds [floorLimit], that a command one byte over
     * it is refused and one exactly on it commits — is what still has content. The probe command is
     * far past any plausible limit, so this cannot itself be sized against a number the test picked.
     */
    private suspend fun derivedLimit(node: RaftNode): Int =
        assertFailsWith<PayloadTooLarge> { node.propose(ByteArray(budget)) }.budgetBytes

    /**
     * What [command] costs on the wire, which is what the budget is denominated in and what the gate
     * compares against (#2150).
     *
     * Raw length and wire length are not the same number. Until #2160 they differed by up to a factor
     * of two, because CBOR rendered a `ByteArray` as an array of integers and a byte outside the short
     * range (`0..23` / `-1..-24`) cost two; since it they differ by the CBOR byte-string header, 1 to 5
     * bytes stepping with the length. The gate used to compare the *raw* one against the budget, which
     * is how [aCommandInsideTheRawLimitButOverItOnTheWireIsRefused] slipped straight through it — and
     * still would, a header's worth rather than a factor's.
     *
     * Measured here rather than computed from that rule, so the test and the engine agree by
     * *measurement* rather than by both hard-coding a model of the codec. It goes through the
     * engine's own `raftCbor`: a bare `Cbor` differs from it in `alwaysUseByteString`, which is an
     * **encoding** option, so this would measure a different wire from the one the gate enforces.
     */
    private fun wireBytes(command: ByteArray): Int =
        raftCbor.encodeToByteArray(ByteArraySerializer(), command).size

    /**
     * A command of exactly [wire] encoded bytes.
     *
     * Found by walking down from `wire - 1` rather than computed, for the same reason [wireBytes] is
     * measured: the relation between raw and wire length is the codec's business, and a test that
     * modelled it would agree with a *model* of the engine rather than with the engine. The walk is
     * at most a handful of steps, since the byte-string header is never wider than five bytes.
     *
     * The alternating `0x7F`/`0x00` content is deliberate but no longer load-bearing: under
     * array-of-integers framing those two bytes cost different amounts and the mix was how a command
     * hit a wire size the raw count could not predict. Under byte strings the size is
     * content-independent, and the mix survives only so a command is not a run of one value.
     */
    private fun commandOfWireSize(wire: Int): ByteArray {
        for (raw in (wire - 1) downTo maxOf(0, wire - 8)) {
            val candidate = ByteArray(raw) { if (it % 2 == 0) 0x7F else 0 }
            if (wireBytes(candidate) == wire) return candidate
        }
        error("no command encodes to exactly $wire wire bytes")
    }

    @Test
    fun anOversizeProposeIsRefusedNamingTheDerivedLimit() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = budget)
        val leader = awaitLeader(sim)
        val limit = derivedLimit(leader)
        val command = commandOfWireSize(limit + 1)
        assertEquals(limit + 1, wireBytes(command), "premise: the command must be exactly one wire byte over")
        val refusal = assertFailsWith<PayloadTooLarge> { leader.propose(command) }
        assertAll(
            { assertEquals(limit + 1, refusal.payloadBytes, "payloadBytes names what the command costs on the wire") },
            { assertEquals(limit, refusal.budgetBytes, "budgetBytes is the transport budget less the envelope reserve") },
            {
                assertEquals(
                    budget, refusal.budgetBytes + refusal.reservedBytes,
                    "the limit and the reserve partition the transport's whole budget",
                )
            },
            {
                assertTrue(
                    refusal.reservedBytes >= headerBudget,
                    "the reserve never undercuts the published floor (#2156): it may be stricter " +
                        "than HEADER_BUDGET promised, never laxer — ${refusal.reservedBytes} B",
                )
            },
            {
                assertTrue(
                    limit <= floorLimit,
                    "and so the enforced limit never exceeds the one the published floor implies: " +
                        "$limit > $floorLimit",
                )
            },
        )
    }

    /**
     * The regression the enforcement of #2150 exposed: a command **within** the old raw-byte limit whose
     * *encoded* form is not, is refused.
     *
     * `ByteArray(limit) { 0x7F }` is `limit` raw bytes — exactly what the old gate admitted as "at the
     * limit" — and more than that on the wire. Admitting it produced precisely the wedge the gate
     * exists to prevent, reached *through* the gate: appended, minted into an `AppendEntries` no
     * chunking covers, refused by the transport, retried forever.
     *
     * **#2160 shrank the margin from a factor to a header, and the assertion moved with it.** Under
     * array-of-integers framing this command cost `2 * limit + 2`, twice the whole transport budget,
     * and the premise said so. Under byte strings it costs `limit + 3`: past the limit, inside the
     * budget. The *discrimination* is unchanged — a gate counting raw bytes admits this command and
     * this gate refuses it — but the premise now has to name the limit rather than the budget, and a
     * reader should not mistake the smaller number for a weaker test. A frame three bytes over what
     * the transport published is dropped exactly as a frame twice over is.
     *
     * This is the case that distinguishes measuring the encoding from counting the bytes. Both
     * [anOversizeProposeIsRefusedNamingTheDerivedLimit] and [aProposeAtExactlyTheLimitCommits] stay
     * green against a gate that counted raw bytes; only this one does not.
     */
    @Test
    fun aCommandInsideTheRawLimitButOverItOnTheWireIsRefused() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = budget)
        val leader = awaitLeader(sim)
        val leaderId = sim.idOf(leader)
        sim.awaitCommit(1L)
        val limit = derivedLimit(leader)
        val before = sim.storages.getValue(leaderId).entries(1L).size
        val command = ByteArray(limit) { 0x7F }
        assertAll(
            { assertTrue(command.size <= limit, "premise: raw size is INSIDE the limit (${command.size} <= $limit)") },
            {
                assertTrue(
                    wireBytes(command) > limit,
                    "premise: wire size is OUTSIDE the limit (${wireBytes(command)} > $limit)",
                )
            },
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
        // Over the enforced limit however the reserve is derived: this costs floorLimit + 1 raw
        // bytes plus a CBOR byte-string header (#2160), and the reserve is never below headerBudget
        // — so the enforced limit is never above floorLimit. This test is about the log not growing,
        // not about where the edge falls; the edge itself is held above.
        assertFailsWith<PayloadTooLarge> { leader.propose(ByteArray(floorLimit + 1)) }
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
        val limit = derivedLimit(leader)
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
        val refusal = assertFailsWith<PayloadTooLarge> { follower.propose(ByteArray(floorLimit + 1)) }
        assertAll(
            {
                assertEquals(
                    budget, refusal.budgetBytes + refusal.reservedBytes,
                    "a non-leader is bounded by its own transport's budget",
                )
            },
            {
                assertTrue(
                    refusal.budgetBytes <= floorLimit,
                    "and by its own node's reserve, which is never laxer than the published floor",
                )
            },
        )
    }
}

/** The [NodeId] under which [node] is registered in this simulation. */
private fun RaftSimulation.idOf(node: RaftNode): NodeId =
    nodes.entries.first { it.value === node }.key
