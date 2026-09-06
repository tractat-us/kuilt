@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)
package us.tractat.kuilt.raft

import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `RaftEngine`'s propose bound must hold back what the `AppendEntries` envelope actually costs, not
 * what a constant says it costs (#2156).
 *
 * The gate admits a command whose *encoded* size fits `budget − reserved`, and everything else — the
 * envelope's five `Long`s, the entry's own `index` / `term`, and its `dedupKey` — rides in `reserved`.
 * Before this, `reserved` was the flat `HEADER_BUDGET` and the bound's soundness was an unstated
 * assumption about it. It was false: a command accepted at exactly the published limit minted a
 * frame *over* the transport budget, dropped at `SeamRaftTransport.sendTo`, never acked, `nextIndex`
 * frozen — the exact wedge the gate exists to prevent, reached **through** the reserve.
 *
 * ### Why a flat number could not be repaired by bounding the ClientId
 *
 * The envelope holds eight `Long`s and CBOR widens each from one byte to nine as the log grows, so
 * the headroom a 256 B reserve leaves an id is not a constant: 81 characters on a fresh log, 48 at
 * 1e9 entries, 30 at 1e12, and **11** at `MAX_PLAUSIBLE_INDEX` / `MAX_PLAUSIBLE_TERM`. `ClientId.auto`
 * — which the library mints for itself — is 23 characters at its shortest. A maximum admitting the
 * library's own id and a maximum fitting the reserve are disjoint sets, which is why the fix measures
 * rather than bounds. [aFlatReserveIsStillInsufficientAtThePlausibilityCeiling] keeps that premise
 * under a test rather than in prose.
 *
 * ### What each test holds
 *
 * - [aDurableClientIdAtThePublishedLimitNeverMintsAnOverBudgetFrame] — the behaviour, on the
 *   canonical harness. This is the arm that reddened before the fix.
 * - [theChargedReserveCoversTheWorstCaseEnvelopeItStandsFor] — the arithmetic, against an
 *   **independently constructed** envelope. The test builds the frame from [RaftMessage] itself; it
 *   never asks the engine what it reserved and then checks that against itself.
 * - [theEnvelopeOverheadIsAdditiveInTheCommand] — the property the fix's *cheapness* rests on.
 * - [aFlatReserveIsStillInsufficientAtThePlausibilityCeiling] — the premise the fix exists for.
 */
class ProposeEnvelopeReserveTest {

    /**
     * `RaftEngine.HEADER_BUDGET`, restated by value — the engine's copy is `private` to its
     * companion. Same direction of coupling as [ProposePayloadBudgetTest]: a test that read the
     * constant would agree with the engine by construction and assert nothing about *which* number
     * it is. Since the fix this is the published **floor**, not the reserve.
     */
    private val headerBudget = 256

    /** A transport budget comfortably above [headerBudget], as in [ProposePayloadBudgetTest]. */
    private val budget = 1024

    /**
     * The engine's own plausibility ceiling for a term and an index (`RaftEngine.MAX_PLAUSIBLE_TERM`
     * / `MAX_PLAUSIBLE_INDEX`, both `1L shl 60`), restated by value for the same reason as
     * [headerBudget]. Anything above it the engine refuses outright, so it is the top of the range
     * the reserve has to cover — not a hypothetical.
     */
    private val plausibleCeiling = 1L shl 60

    /**
     * A bare [Cbor] suffices: the engine's instance differs only by `ignoreUnknownKeys`, a *decoding*
     * option. `encodeDefaults` is off in both, so a defaulted `round` / `isNoOp` is omitted in both.
     */
    private val cbor = Cbor

    private fun wireBytes(command: ByteArray): Int =
        cbor.encodeToByteArray(ByteArraySerializer(), command).size

    /** The frame one proposed entry produces, built here rather than obtained from the engine. */
    private fun frameBytes(
        clientId: String?,
        index: Long,
        term: Long,
        round: Long,
        requestId: Long,
        command: ByteArray,
    ): Int {
        val frame: RaftMessage = RaftMessage.AppendEntries(
            term = term,
            prevLogIndex = index - 1,
            prevLogTerm = term,
            entries = listOf(
                LogEntry(
                    index = index,
                    term = term,
                    command = command,
                    dedupKey = clientId?.let { DedupKey(ClientId(it), requestId) },
                ),
            ),
            leaderCommit = index - 1,
            round = round,
        )
        return cbor.encodeToByteArray(RaftMessage.serializer(), frame).size
    }

    /** What that frame costs *beyond* its command — the quantity the engine holds back. */
    private fun envelopeOverhead(clientId: String?, index: Long, term: Long, round: Long, requestId: Long): Int {
        val empty = ByteArray(0)
        return frameBytes(clientId, index, term, round, requestId, empty) - wireBytes(empty)
    }

    /** The widest envelope this node can ever produce — what the engine's probe stands for. */
    private fun worstCaseOverhead(clientId: String) =
        envelopeOverhead(clientId, plausibleCeiling, plausibleCeiling, Long.MAX_VALUE, Long.MAX_VALUE)

    /**
     * A command of exactly [wire] encoded bytes — copied from [ProposePayloadBudgetTest] so both
     * suites sit the propose on the same edge. `0x7F` costs two wire bytes, `0x00` costs one, and the
     * array header costs two (kotlinx CBOR writes an indefinite-length array, so that two is a
     * constant rather than a function of the length).
     */
    private fun commandOfWireSize(wire: Int, wide: Int = 100): ByteArray =
        ByteArray(wide + (wire - 2 - 2 * wide)) { if (it < wide) 0x7F else 0 }

    /**
     * A `ClientIdentity.Durable` id long enough to outrun a flat 256 B reserve even on a young log.
     *
     * 96 characters — a prefixed uuid, or a tenant-scoped token. Not a pathological value: a plain
     * 36-character uuid is already past the flat reserve once the log is long-lived, which is what
     * [aFlatReserveIsStillInsufficientAtThePlausibilityCeiling] guards.
     */
    private val durableId = "tenant-7f3a9c21:client-0f8e1d4b-6a52-4c9e-b1d7-3e8a5f2c0946:shard-11-writer"
        .padEnd(96, 'z')

    /**
     * The behaviour: at the limit the engine publishes, nothing it mints exceeds the transport it was
     * told about, and the leader keeps its term.
     *
     * ### What proves the rig fired
     *
     * `awaitCommit(1L)` — the leader's §5.4.2 election no-op reaching every voter *at this budget*.
     * It is the precondition, asserted rather than assumed: it proves replication genuinely flows
     * through this transport with this ceiling, so [InMemoryRaftNetwork.overBudget] being empty
     * afterwards is a statement about the propose's own frame and not about a cluster that never sent
     * anything. The list is asserted empty and then cleared, so nothing before the propose can
     * contribute to it.
     *
     * ### Both assertions are fix-agnostic
     *
     * Neither says the propose must succeed, and neither says it must fail — a repaired engine may
     * commit this command or refuse it with a typed [PayloadTooLarge], and both are verdicts a caller
     * can act on. What must not happen is the third outcome, which is what this reddened on before
     * the fix: accepted, minted into a 1046 B frame against a 1024 B budget, retried eight times, and
     * paid for with the leader's term.
     */
    @Test
    fun aDurableClientIdAtThePublishedLimitNeverMintsAnOverBudgetFrame() = raftRunTest {
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
        sim.awaitCommit(1L)
        assertTrue(
            sim.network.overBudget.isEmpty(),
            "rig: replication must already flow at this budget, or the emptiness asserted below " +
                "would hold for a cluster that never sent a frame — ${sim.network.overBudget}",
        )
        sim.network.overBudget.clear()

        val command = commandOfWireSize(budget - headerBudget)
        assertEquals(
            budget - headerBudget,
            wireBytes(command),
            "premise: the command must sit exactly ON the published limit, not under it",
        )
        val outcome = runCatchingCancellable { leader.propose(command) }
        sim.settle()

        assertAll(
            {
                assertTrue(
                    sim.network.overBudget.isEmpty(),
                    "the engine must never mint a frame larger than the transport it was told " +
                        "about; the ${durableId.length}-character ClientId ate past the reserve: " +
                        "${sim.network.overBudget}",
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
     * The arithmetic: what the engine holds back is at least what the widest envelope it can produce
     * actually costs, and never less than the published floor.
     *
     * The needed figure is an **independent construction** — built here out of [RaftMessage] and
     * [LogEntry] — rather than a re-derivation of the engine's own expression. A reference computed
     * the way the subject computes it asserts `x == x`.
     *
     * The floor half is the other direction, and it is what "published conservatively" means: the
     * enforcement may be *stricter* than [headerBudget] promised, never laxer, so no caller that
     * sized against the old published number is newly surprised by a frame being carried when it
     * expected a refusal.
     */
    @Test
    fun theChargedReserveCoversTheWorstCaseEnvelopeItStandsFor() = raftRunTest {
        val ids = (1..3).map { NodeId("v$it") }
        val cluster = ClusterConfig(voters = ids.toSet())
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
        // A command far past any plausible limit, so the refusal reports the engine's own arithmetic
        // rather than being sized against a number this test picked.
        val refusal = assertFailsWith<PayloadTooLarge> { leader.propose(ByteArray(budget)) }
        val needed = worstCaseOverhead(durableId)

        assertAll(
            {
                assertTrue(
                    refusal.reservedBytes >= needed,
                    "the reserve the engine charges (${refusal.reservedBytes} B) must cover the " +
                        "widest envelope it can mint around a ${durableId.length}-character " +
                        "ClientId ($needed B)",
                )
            },
            {
                assertTrue(
                    refusal.reservedBytes >= headerBudget,
                    "and must never undercut the published floor: ${refusal.reservedBytes} B " +
                        "< $headerBudget B",
                )
            },
            {
                assertEquals(
                    budget,
                    refusal.budgetBytes + refusal.reservedBytes,
                    "the limit and the reserve partition the transport's whole budget",
                )
            },
        )
    }

    /**
     * The property the fix's cheapness rests on: the envelope's cost does not depend on the command's
     * size, so the engine's probe may encode an **empty** command and add the command's own measured
     * cost — `O(|clientId|)` rather than a second `O(payload)` encode.
     *
     * True because CBOR is definite in structure and indefinite in array length here: every enclosing
     * map/array header is a function of element *count*, and the only payload-sized header is the
     * command array's own, which the gate measures separately. Asserted rather than reasoned, across
     * three payload sizes and both a short and a long id, because if it ever stops holding the probe
     * silently under-measures and the bound goes quietly unsound again.
     */
    @Test
    fun theEnvelopeOverheadIsAdditiveInTheCommand() {
        val checks: List<() -> Unit> = listOf("a", durableId).flatMap { id ->
            listOf(0, 1, 100, 5_000).map { size ->
                {
                    val command = ByteArray(size) { 0x7F }
                    val overhead = worstCaseOverhead(id)
                    val frame = frameBytes(
                        id, plausibleCeiling, plausibleCeiling, Long.MAX_VALUE, Long.MAX_VALUE, command,
                    )
                    assertEquals(
                        overhead + wireBytes(command),
                        frame,
                        "id=${id.length} chars, command=$size B: the envelope must cost the same " +
                            "whatever the command's size, or the empty-command probe under-measures",
                    )
                }
            }
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * The premise the engine's **probe** rests on: every incarnation of an auto id encodes to the
     * same width, so a probe captured once at construction stands for an id that may be re-minted
     * later.
     *
     * `RaftEngine.clientIdProbe` is captured from `myClientId` at construction and never refreshed —
     * deliberately, because `myClientId` is a `var` mutated on the actor loop and the propose gate
     * runs on the caller's coroutine, so re-reading it would be a data race. That is sound only
     * because *width* is invariant, and width invariance has two halves:
     *
     * - **Durable** — the id is never reassigned at all. `RaftEngine.detectCollision` throws
     *   `ClientIdCollisionException` rather than re-minting, pinned by
     *   [RaftEngineDedupIntegrationTest].
     * - **Auto** — the id *is* re-minted, as `"auto:" + selfId + "-" + 16 hex` over an immutable
     *   `selfId`. Nothing pinned that the hex tail is fixed-width, and it is fixed-width only
     *   because `ClientId.auto` calls `padStart(2, '0')` on each of eight bytes. **Dropping that
     *   `padStart` is a plausible tidy-up that would silently break this fix**: a byte below `0x10`
     *   would render as one character, so a re-mint could come out *wider* than the probe captured,
     *   the reserve would under-measure, and #2156's wedge would return with every test still green.
     *   `DedupKey.autoFamily` would misparse too, but only by luck of the seed — it is not a
     *   dependable red.
     *
     * Measured in the probe's own units (the envelope overhead) rather than in characters, since
     * that is the quantity the fix actually depends on. 256 seeds: a dropped `padStart` makes about
     * 40% of ids short, so a single short draw is essentially certain to appear.
     *
     * This is a **different** premise from
     * [aFlatReserveIsStillInsufficientAtThePlausibilityCeiling], which guards the envelope's *size*.
     * That one going green would mean the fix is unnecessary; this one going red means the fix is
     * unsound.
     */
    @Test
    fun everyAutoIncarnationEncodesToTheSameEnvelopeWidth() {
        val nodeId = NodeId("dc1-rack2-host3")
        val widths = (1..256).map { seed ->
            worstCaseOverhead(ClientId.auto(nodeId, Random(seed)).value)
        }
        assertEquals(
            1,
            widths.toSet().size,
            "an auto id must encode to one width on every incarnation, or RaftEngine's " +
                "construction-time probe under-measures a later re-mint (#2156): saw " +
                "${widths.toSet().sorted()}",
        )
    }

    /**
     * The premise the measured enforcement exists for — kept under a test so it cannot quietly stop
     * being true.
     *
     * If this ever reds, the envelope shrank (`@ByteString` framing, #2160, or a dropped field) far
     * enough that a flat [headerBudget] covers it again at the top of the admitted range, and
     * `checkProposeFitsTransport`'s measurement could be reconsidered — so the red is an instruction
     * to revisit #2156, not a defect.
     *
     * The id measured is `ClientId.auto`'s **shortest possible** form, `"auto:" + a one-character
     * NodeId + "-" + 16 hex`. That is the sharpest statement of the refutation: no consumer-supplied
     * value is involved, so no bound on a consumer's id could have helped.
     */
    @Test
    fun aFlatReserveIsStillInsufficientAtThePlausibilityCeiling() {
        val shortestAutoId = "auto:v-0123456789abcdef"
        assertTrue(
            worstCaseOverhead(shortestAutoId) > headerBudget,
            "a ${shortestAutoId.length}-character auto-minted ClientId costs " +
                "${worstCaseOverhead(shortestAutoId)} B at the plausibility ceiling, and the flat " +
                "reserve is $headerBudget B — if this is no longer true, the measured enforcement " +
                "in checkProposeFitsTransport can be reconsidered (#2156)",
        )
    }
}
