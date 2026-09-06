@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)
package us.tractat.kuilt.raft

import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `RaftEngine.HEADER_BUDGET` must actually cover the `AppendEntries` envelope it reserves for
 * (#2156) — the arithmetic `checkProposeFitsTransport`'s bound rests on, asserted rather than
 * assumed.
 *
 * The propose gate admits a command whose *encoded* size fits `budget − HEADER_BUDGET` and charges
 * everything else — the envelope's five `Long`s, the entry's own `index` / `term`, and the entry's
 * `dedupKey` — to a flat 256 B reserve. Soundness is therefore one inequality:
 *
 * > `encoded(AppendEntries with this entry) − encoded(command) <= HEADER_BUDGET`
 *
 * Nothing states it and nothing checks it. When it is false, a command accepted at exactly the
 * published limit produces a frame *over* the transport budget: dropped at
 * `SeamRaftTransport.sendTo` (which must swallow `PayloadTooLarge`), never acked, `nextIndex`
 * frozen — the exact wedge the gate exists to prevent, reached **through** the reserve.
 *
 * ### Why two tests and not one
 *
 * [aDurableClientIdCanPushAnAtLimitProposeOverTheTransportBudget] is the observed wedge: a real
 * cluster on the canonical harness, a real propose the gate *accepted*, and a frame the network
 * refused. It proves the defect is reachable, not just arithmetically possible.
 *
 * [theEnvelopeCostsNoMoreThanTheReserveAcrossThePlausibleRange] is what the sim cannot show. A
 * simulation runs at `index`/`term` in the single digits, where CBOR spends one byte per `Long`;
 * the engine admits both up to `MAX_PLAUSIBLE_INDEX`/`MAX_PLAUSIBLE_TERM` (`2^60`), where each
 * costs **nine**. The reserve's headroom for a `ClientId` is therefore not a constant — it shrinks
 * as the log grows, and the sim sits at the most forgiving end of that range.
 */
class ProposeEnvelopeReserveTest {

    /**
     * `RaftEngine.HEADER_BUDGET`, restated by value — the engine's copy is `private` to its
     * companion. Same direction of coupling as [ProposePayloadBudgetTest]: a test that read the
     * constant would agree with the engine by construction and assert nothing about *which* number
     * it is.
     */
    private val headerBudget = 256

    /** A transport budget comfortably above [headerBudget], as in [ProposePayloadBudgetTest]. */
    private val budget = 1024

    /** The largest command the propose gate admits, as encoded. */
    private val limit = budget - headerBudget

    /**
     * The engine's own plausibility ceiling for a term and an index (`RaftEngine.MAX_PLAUSIBLE_TERM`
     * / `MAX_PLAUSIBLE_INDEX`, both `1L shl 60`), restated by value for the same reason as
     * [headerBudget]. Anything above it the engine refuses outright, so it is the top of the range
     * the reserve has to cover — not a hypothetical.
     */
    private val plausibleCeiling = 1L shl 60

    /**
     * A bare [Cbor] suffices: the engine's instance differs only by `ignoreUnknownKeys`, a *decoding*
     * option. `encodeDefaults` is off in both, so a defaulted `round`/`isNoOp` is omitted in both.
     */
    private val cbor = Cbor

    private fun wireBytes(command: ByteArray): Int =
        cbor.encodeToByteArray(ByteArraySerializer(), command).size

    /**
     * What one entry's `AppendEntries` costs *beyond* its command — the quantity [headerBudget]
     * reserves for.
     *
     * Measured with an **empty** command and no subtraction of a payload, which is exact rather than
     * approximate: CBOR is definite-length, and the only size-dependent header in the frame is the
     * command array's own, which `wireBytes` already accounts for. (Verified additive:
     * `frame(cmd) == overhead + wireBytes(cmd)` for every row below.)
     */
    private fun envelopeOverhead(clientId: String?, index: Long, term: Long, round: Long, requestId: Long): Int {
        val empty = ByteArray(0)
        val entry = LogEntry(
            index = index,
            term = term,
            command = empty,
            dedupKey = clientId?.let { DedupKey(ClientId(it), requestId) },
        )
        val frame = RaftMessage.AppendEntries(
            term = term,
            prevLogIndex = index - 1,
            prevLogTerm = term,
            entries = listOf(entry),
            leaderCommit = index - 1,
            round = round,
        )
        return cbor.encodeToByteArray(RaftMessage.serializer(), frame).size - wireBytes(empty)
    }

    /**
     * A command of exactly [wire] encoded bytes — copied from [ProposePayloadBudgetTest] so both
     * suites sit the propose on the same edge. `0x7F` costs two wire bytes, `0x00` costs one, and the
     * array header costs two.
     */
    private fun commandOfWireSize(wire: Int, wide: Int = 100): ByteArray =
        ByteArray(wide + (wire - 2 - 2 * wide)) { if (it < wide) 0x7F else 0 }

    /**
     * A `ClientIdentity.Durable` id long enough to push the envelope past the reserve at the
     * magnitudes a simulation actually reaches (`index`/`term` in the single digits).
     *
     * 96 characters — a prefixed uuid, or a tenant-scoped token. Not a pathological value: the
     * arithmetic row below shows a plain 36-character uuid is already past the reserve once the log
     * is long-lived.
     */
    private val durableId = "tenant-7f3a9c21:client-0f8e1d4b-6a52-4c9e-b1d7-3e8a5f2c0946:shard-11-writer"
        .padEnd(96, 'z')

    /**
     * The observed wedge: a propose the gate **accepted** mints a frame the transport **refuses**,
     * and the leader then loses its term because every replication round carrying that entry is
     * dropped.
     *
     * ### What proves the rig fired
     *
     * `awaitCommit(1L)` — the leader's §5.4.2 election no-op reaching every voter *at this budget*.
     * It is the precondition, asserted rather than assumed: it proves replication genuinely flows
     * through this transport with this ceiling, so [InMemoryRaftNetwork.overBudget] being empty
     * afterwards is a statement about the propose's own frame and not about a cluster that never
     * sent anything. The list is cleared immediately after, so nothing before the propose can
     * contribute to it.
     *
     * ### Both assertions are fix-agnostic
     *
     * Neither says the propose must succeed, and neither says it must fail. A repaired engine may
     * commit this command or refuse it with a typed [us.tractat.kuilt.core.PayloadTooLarge] — both
     * are verdicts a caller can act on. What must not happen is the third outcome: accepted, minted
     * into a frame the transport refuses, and paid for with the leader's term.
     */
    @Test
    fun aDurableClientIdCanPushAnAtLimitProposeOverTheTransportBudget() = raftRunTest {
        val ids = (1..3).map { NodeId("v$it") }
        val cluster = ClusterConfig(voters = ids.toSet())
        // ONE config, shared by all three nodes: its seeded `Random` is a single stream, so each
        // node draws a different election timeout and a leader can actually win. A per-node
        // `fastRaftConfig()` gives every node the same seed, hence identical timeouts and a
        // perpetual split vote (`AlreadyVoted` forever) — see `raftSim`, which shares one this way.
        val config = fastRaftConfig()
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = this,
            nodeScope = backgroundScope,
            maxPayloadBytes = budget,
            nodeFactory = { _, transport, storage, childScope ->
                childScope.raftNode(
                    cluster,
                    transport,
                    storage,
                    config,
                    ClientIdentity.Durable(ClientId(durableId)),
                )
            },
        )
        val leader = awaitLeader(sim)
        // Rig: replication demonstrably flows at this budget before the propose. Without this the
        // emptiness asserted below would also hold for a cluster that never sent a frame at all.
        sim.awaitCommit(1L)
        assertTrue(
            sim.network.overBudget.isEmpty(),
            "rig: nothing may be over budget before the propose — ${sim.network.overBudget}",
        )
        sim.network.overBudget.clear()

        val command = commandOfWireSize(limit)
        assertTrue(
            wireBytes(command) == limit,
            "premise: the command must sit exactly ON the published limit, not under it",
        )
        val outcome = runCatchingCancellable { leader.propose(command) }
        sim.settle()

        assertAll(
            {
                assertTrue(
                    sim.network.overBudget.isEmpty(),
                    "the engine must never mint a frame larger than the transport it was told about; " +
                        "the ${durableId.length}-character ClientId ate past the $headerBudget B " +
                        "reserve: ${sim.network.overBudget}",
                )
            },
            {
                assertTrue(
                    outcome.exceptionOrNull() !is LeadershipLostException,
                    "and a propose must end in a verdict the caller can act on, not in the leader " +
                        "losing its term to its own undeliverable frames: ${outcome.exceptionOrNull()}",
                )
            },
        )
    }

    /**
     * The reserve must cover the envelope across the whole range the engine declares plausible — not
     * merely at the magnitudes a simulation reaches.
     *
     * Rows are ordered fresh → ceiling, and the *shape* of the failure is the point: the headroom for
     * a `ClientId` shrinks monotonically as `index`/`term` grow, so a single documented maximum
     * cannot be sound at both ends. The last row carries `ClientId.auto`'s own shortest form, which
     * the library mints itself — no consumer-supplied value is involved in it at all.
     */
    @Test
    fun theEnvelopeCostsNoMoreThanTheReserveAcrossThePlausibleRange() {
        // "auto:" + nodeId + "-" + 16 hex — the shortest form `ClientId.auto` can produce for a
        // one-character NodeId. A realistic `host:port` NodeId makes it ~39.
        val autoId = "auto:v-0123456789abcdef"
        val rows = listOf(
            Row("fresh log, auto id", autoId, index = 1L, term = 1L, round = 0L, requestId = 1L),
            Row("mature log (1e9 entries), auto id", autoId, 1_000_000_000L, 10_000L, 1_000_000L, 1_000_000L),
            Row("long-lived log (1e12), uuid id", "6a52-4c9e-b1d7-3e8a5f2c0946-0f8e1d4b", 1_000_000_000_000L, 1_000_000L, 1_000_000_000L, 1_000_000_000L),
            Row("plausibility ceiling (2^60), auto id", autoId, plausibleCeiling, plausibleCeiling, plausibleCeiling, plausibleCeiling),
        )
        val checks: List<() -> Unit> = rows.map { row ->
            {
                val overhead = envelopeOverhead(row.clientId, row.index, row.term, row.round, row.requestId)
                assertTrue(
                    overhead <= headerBudget,
                    "${row.label}: the envelope around a ${row.clientId.length}-character ClientId " +
                        "costs $overhead B, past the $headerBudget B reserved for it — a command " +
                        "accepted at the published limit mints a frame ${overhead - headerBudget} B " +
                        "over the transport budget",
                )
            }
        }
        assertAll(*checks.toTypedArray())
    }

    private data class Row(
        val label: String,
        val clientId: String,
        val index: Long,
        val term: Long,
        val round: Long,
        val requestId: Long,
    )
}
