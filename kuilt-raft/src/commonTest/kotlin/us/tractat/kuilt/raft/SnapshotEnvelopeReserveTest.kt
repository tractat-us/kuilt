@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A snapshot chunk must fit the wire budget once the `InstallSnapshot` envelope's **`ConfigPayload`**
 * is paid for, not merely once a flat 256 B has been (#2720).
 *
 * `RaftMessage.InstallSnapshot` carries `config: ConfigPayload?` — a `ClusterConfig` of
 * consumer-supplied [NodeId]s — on *every* chunk, deliberately, so an installer can adopt the
 * membership whichever chunk it finalizes on. `chunkBytes()` reserved a flat `HEADER_BUDGET` for the
 * whole envelope, and five twenty-character node ids already cost more than that. Each chunk was
 * therefore minted over the transport's budget, refused at `SeamRaftTransport.sendTo` (which must
 * swallow `PayloadTooLarge`), never acked, and re-sent forever: a follower that needs a snapshot can
 * never be caught up, and `AppendEntries` cannot help it because its prefix was compacted away.
 *
 * This is the same defect [ProposeEnvelopeReserveTest] closes on the propose lane, reached through a
 * different field, and the remedy is the same shape — **published conservatively, enforced by
 * measuring** — with two differences this suite exists to hold:
 *
 * 1. **The reserve is the whole probe frame, not the frame minus its empty payload.** The propose
 *    gate measures the command's *encoded* size and subtracts it, so the payload array's own header
 *    is counted there. `chunkBytes` picks a slice size *before* there are bytes to encode, so it
 *    converts a raw count through `CBOR_BYTE_EXPANSION` instead, and the array header has nowhere
 *    else to live. [theReserveMustIncludeTheChunkArraysOwnHeader] pins that two-byte difference,
 *    which is exactly enough to put every full chunk one byte over.
 * 2. **There is no `coerceAtLeast(0)` escape.** A config large enough to exhaust the budget leaves
 *    no room for any data at all, and a one-byte chunk that can never fit is a silent wedge rather
 *    than a degraded success — so the lane refuses, observably.
 *    [aBudgetTooSmallForTheWholeEnvelopeRefusesObservablyInsteadOfMintingAChunkThatCannotFit] holds
 *    that.
 *
 * ### What each test holds
 *
 * - [aSimpleConfigOnTheChunkNeverMintsAnOverBudgetFrame] /
 *   [aJointConfigOnTheChunkNeverMintsAnOverBudgetFrame] — the behaviour, on the canonical harness,
 *   over the two `ConfigPayload` shapes a real cluster produces. These are the arms that reddened.
 * - [aBudgetTooSmallForTheWholeEnvelopeRefusesObservablyInsteadOfMintingAChunkThatCannotFit] — the
 *   refusal, and that it is not permanent.
 * - [theChunkEnvelopeAlreadyOutgrowsTheFlatReserve] — the premise the fix exists for, over an
 *   **independently constructed** envelope.
 * - [theChunkEnvelopeOverheadIsAdditiveInTheChunkData] — the property the probe's cheapness rests on.
 * - [theReserveMustIncludeTheChunkArraysOwnHeader] — the off-by-two above.
 */
class SnapshotEnvelopeReserveTest {

    // ── Independently constructed envelope arithmetic ──────────────────────────

    /**
     * `RaftEngine.HEADER_BUDGET`, restated by value — the engine's copy is `private` to its
     * companion. Same direction of coupling as [ProposeEnvelopeReserveTest]: a test that read the
     * constant would agree with the engine by construction and assert nothing about *which* number
     * it is.
     */
    private val headerBudget = 256

    /** `RaftEngine.CBOR_BYTE_EXPANSION`, restated by value for the same reason. */
    private val cborByteExpansion = 2

    /**
     * The engine's own plausibility ceiling for a term and an index (`RaftEngine.MAX_PLAUSIBLE_TERM`
     * / `MAX_PLAUSIBLE_INDEX`, both `1L shl 60`), restated by value. Anything above it the engine
     * refuses outright, so it is the top of the range a reserve has to cover.
     */
    private val plausibleCeiling = 1L shl 60

    /**
     * A bare [Cbor] suffices: the engine's instance differs only by `ignoreUnknownKeys`, a *decoding*
     * option. `encodeDefaults` is off in both, so a `null` config and a zero `round` are omitted in
     * both — which is why the probe must be built from the *real* config rather than a placeholder.
     */
    private val cbor = Cbor

    private fun wireBytes(data: ByteArray): Int = cbor.encodeToByteArray(ByteArraySerializer(), data).size

    /** The frame one snapshot chunk produces, built here rather than obtained from the engine. */
    private fun frameBytes(
        config: ConfigPayload?,
        index: Long,
        term: Long,
        offset: Long,
        round: Long,
        data: ByteArray,
    ): Int {
        val frame: RaftMessage = RaftMessage.InstallSnapshot(
            term = term,
            lastIncludedIndex = index,
            lastIncludedTerm = term,
            offset = offset,
            data = data,
            done = false,
            config = config,
            round = round,
        )
        return cbor.encodeToByteArray(RaftMessage.serializer(), frame).size
    }

    /**
     * The widest chunk frame this cluster can ever mint around an **empty** payload — the quantity
     * the engine must hold back.
     *
     * Deliberately *not* `frame − wireBytes(empty)`, which is what the propose lane's
     * `proposeEnvelopeBytes` computes. See [theReserveMustIncludeTheChunkArraysOwnHeader].
     */
    private fun worstCaseReserve(config: ConfigPayload?): Int =
        frameBytes(config, plausibleCeiling, plausibleCeiling, plausibleCeiling, Long.MAX_VALUE, ByteArray(0))

    // ── Fixture ───────────────────────────────────────────────────────────────

    /**
     * Five voters whose ids are twenty characters long, and a sixth node promoted into the voter set
     * during the test.
     *
     * Twenty characters is not a pathological value: `NodeId`'s own KDoc offers `"192.168.1.10:7000"`
     * (seventeen) as a natural one, and a region-and-ordinal scheme like this is what a real
     * deployment produces. The point of the suite is that nothing here is exotic.
     */
    private val voterIds = (1..5).map { NodeId("node-us-east-1a-000$it") }
    private val joinerId = NodeId("node-eu-west-2c-0009")

    /** The membership the promotion settles on — a *simple* `ConfigPayload`'s `new`. */
    private val promoted = ClusterConfig(voters = (voterIds + joinerId).toSet())

    private fun sim(
        scope: TestScope,
        budget: Int,
        onMetric: ((NodeId, RaftMetric) -> Unit)? = null,
    ): RaftSimulation {
        val voterConfig = ClusterConfig(voters = voterIds.toSet())
        val joinerConfig = ClusterConfig(voters = voterIds.toSet(), learners = setOf(joinerId))
        // ONE config shared by every node: its seeded `Random` is a single stream, so each node draws
        // a different election timeout and a leader can actually win. See `raftSim`, which shares one
        // the same way, and the note in ProposeEnvelopeReserveTest.
        val raftCfg = fastRaftConfig()
        return RaftSimulation(
            nodeIds = voterIds + joinerId,
            scope = scope,
            nodeScope = scope.backgroundScope,
            maxPayloadBytes = budget,
            nodeFactory = { id, transport, storage, nodeScope ->
                nodeScope.raftNode(
                    if (id == joinerId) joinerConfig else voterConfig,
                    transport,
                    storage,
                    raftCfg,
                    onMetric = onMetric?.let { sink -> { metric -> sink(id, metric) } },
                )
            },
        )
    }

    /**
     * Everything a chunk-sizing arm needs: a cluster that has promoted [joinerId] into the voter set,
     * a follower crashed across the resulting compaction boundary, and a snapshot cut **exactly at
     * the config entry [wantJoint] selects** so its `SnapshotMeta.config` is the shape under test.
     *
     * Cutting at a chosen index rather than at `compactionFloorCandidate` is the whole trick: a
     * promotion appends `Joint(old, new)` and then `Simple(new)`, and `onCompact` stamps the snapshot
     * with the highest-index config entry at or below the cut. Cutting at the joint entry therefore
     * yields a joint payload — two `ClusterConfig`s, roughly twice the overrun — while cutting at the
     * simple entry yields the settled one. Both are ordinary states of a real cluster.
     *
     * ### What proves the rig fired
     *
     * Three preconditions, each asserted rather than assumed, because every one of them silently
     * turns the arm vacuous if it stops holding: replication genuinely flows at this budget before
     * the snapshot phase (or an empty `overBudget` afterwards would be a statement about a cluster
     * that never sent anything); the promotion really did leave a config entry of the requested
     * shape in the log; and the stored snapshot really is stamped with it (a `null` here is the
     * pre-#2720 world, where no in-tree test constructed a `ConfigPayload` at all and nothing
     * reddened).
     */
    private suspend fun TestScope.snapshotStampedWithConfig(sim: RaftSimulation, wantJoint: Boolean): Transfer {
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val behind = voterIds.first { it != leaderId }

        // Voters only: `joinerId` bootstraps listing itself as a learner of the five, but the *voters*
        // boot with a learner-free config, so nothing replicates to it until the promotion below.
        sim.awaitCommit(1L, on = voterIds)
        assertTrue(
            sim.network.overBudget.isEmpty(),
            "rig: replication must already flow at ${sim.network.maxPayloadBytes} B, or the emptiness " +
                "asserted later would hold for a cluster that never sent a frame — ${sim.network.overBudget}",
        )
        sim.network.overBudget.clear()

        sim.crash(behind)                                    // falls behind the coming compaction boundary
        repeat(4) { leader.propose(ByteArray(64) { i -> (0x80 or (i and 0x3F)).toByte() }) }
        leader.changeMembership(promoted)                    // Joint(old, new) then Simple(new)

        val configEntries = sim.storages.getValue(leaderId).entries(0L)
            .mapNotNull { entry -> entry.config?.let { entry.index to it } }
        val shape = if (wantJoint) "joint" else "simple"
        val (through, stamped) = configEntries.firstOrNull { (_, payload) -> (payload.old != null) == wantJoint }
            ?: fail("rig: promoting $joinerId must leave a $shape config entry in the leader's log; saw $configEntries")

        val finalCommit = leader.commitIndex.value
        val bigState = ByteArray(BIG_STATE) { (0x80 or (it and 0x3F)).toByte() }
        leader.snapshots.value = Snapshot(through, bigState)
        leader.compactionFloor.first { it == through }
        assertEquals(
            stamped,
            sim.storages.getValue(leaderId).loadSnapshot()?.meta?.config,
            "rig: the stored snapshot must be stamped with the $shape config the cut sits on — a null " +
                "here is the pre-fix world in which nothing this suite asserts can fail",
        )

        val chunkOffsets = mutableListOf<Long>()
        backgroundScope.launch {
            leader.trace.collect { event ->
                if (event is RaftTraceEvent.InstallSnapshot && event.to == behind) chunkOffsets += event.offset
            }
        }
        sim.settle()                                         // subscribe before the transfer starts
        return Transfer(sim, leader, behind, through, finalCommit, stamped, bigState, chunkOffsets)
    }

    private class Transfer(
        val sim: RaftSimulation,
        val leader: RaftNode,
        val behind: NodeId,
        val through: Long,
        val finalCommit: Long,
        val config: ConfigPayload,
        val state: ByteArray,
        val chunkOffsets: MutableList<Long>,
    )

    // ── Behaviour ─────────────────────────────────────────────────────────────

    /**
     * The settled, five-voters-plus-one membership every cluster ends a promotion in: a *simple*
     * `ConfigPayload`, on every chunk, already past the flat reserve.
     *
     * Both assertions are fix-agnostic in the sense [ProposeEnvelopeReserveTest] uses: neither says
     * the transfer must take a particular number of chunks. What must not happen is that the leader
     * mints a frame larger than the transport it was told about.
     */
    @Test
    fun aSimpleConfigOnTheChunkNeverMintsAnOverBudgetFrame() = raftRunTest {
        assertTransferFitsTheBudget(snapshotStampedWithConfig(sim(this, BUDGET), wantJoint = false))
    }

    /**
     * The transitional membership every voter-set change transits: a *joint* `ConfigPayload` carrying
     * **two** `ClusterConfig`s, so the envelope is roughly twice the simple one's and the overrun
     * roughly twice as large.
     *
     * Worth its own arm rather than folding into the one above because a cluster whose steady-state
     * config fits the budget can still be wedged for the duration of a membership change — and a
     * snapshot taken during one is stamped joint, so the transfer that installs it carries the wider
     * payload on every chunk.
     */
    @Test
    fun aJointConfigOnTheChunkNeverMintsAnOverBudgetFrame() = raftRunTest {
        assertTransferFitsTheBudget(snapshotStampedWithConfig(sim(this, BUDGET), wantJoint = true))
    }

    /**
     * The shared verdict for the two arms above: nothing over budget, the slice is the size the
     * measured reserve implies, and the snapshot still arrives.
     *
     * The convergence half runs **after** the budget assertion so a pre-fix run reds on the crisp
     * arithmetic statement rather than on an `awaitCommit` dump — the wedge is real, and a follower
     * that never catches up would otherwise be the first thing to fail.
     *
     * ### Why the offsets are pinned exactly
     *
     * `overBudget.isEmpty()` has **slack**, and the slack is not small: the reserve charges the
     * widest `Long`s the engine admits, while a real transfer's `term` / `lastIncludedIndex` /
     * `offset` are small, so a correctly-sized chunk sits roughly fifty bytes under the budget. An
     * under-count of two bytes — the exact size of the mistake this suite exists to prevent — is
     * therefore invisible to it. Pinning the offset stride against an independently computed slice
     * size removes that slack: nothing about the sizing can move without this reddening, including a
     * re-introduced divisor or a reserve that drops the payload array's header.
     *
     * It is deliberately coupled to the *formula*, not just to the outcome, and that coupling is the
     * point: a codec change that alters the sizing (#2160 / #2746 replace the
     * `CBOR_BYTE_EXPANSION` divisor outright) **must** red here and re-derive [expectedStride]. A
     * green suite across such a change would mean nothing pinned how much data a chunk carries —
     * which is the state this arm was written to leave behind.
     */
    private suspend fun assertTransferFitsTheBudget(t: Transfer) {
        val installs = t.sim.collectInstalls(t.behind)
        t.sim.restart(t.behind)
        t.sim.awaitTrue("the leader attempted snapshot chunks for the rejoining node") {
            t.chunkOffsets.isNotEmpty()
        }

        assertTrue(
            t.sim.network.overBudget.isEmpty(),
            "no chunk may exceed the ${t.sim.network.maxPayloadBytes} B budget the transport published; " +
                "the ${if (t.config.old != null) "joint" else "simple"} ConfigPayload rides on every " +
                "chunk and ate past the reserve: ${t.sim.network.overBudget}",
        )

        t.sim.awaitCommit(t.finalCommit, on = setOf(t.behind))
        val stride = expectedStride(t.config)
        assertAll(
            { assertTrue(installs.isNotEmpty(), "the rejoining node must receive a Committed.Install") },
            { assertEquals(t.through, installs.last().snapshot.throughIndex) },
            {
                assertContentEquals(
                    t.state, installs.last().snapshot.state,
                    "and the snapshot must reassemble byte-for-byte at the smaller slice size",
                )
            },
            {
                assertEquals(
                    (0 until BIG_STATE step stride).map { it.toLong() },
                    t.chunkOffsets.distinct().sorted(),
                    "every chunk must carry exactly $stride raw bytes — the slice the measured " +
                        "${worstCaseReserve(t.config)} B reserve leaves inside a ${BUDGET} B budget. " +
                        "Nothing else in this module pins how much data a chunk carries, so a changed " +
                        "divisor or a reserve missing the payload header would otherwise be silent",
                )
            },
        )
    }

    /**
     * The raw bytes per chunk the engine must choose for [config] at [BUDGET], computed here from an
     * independently constructed envelope rather than read back from the engine.
     *
     * `RaftConfig.snapshotChunkCeiling` (16 KiB by default) does not bind at this budget, so it is
     * deliberately absent: including it would make the expression agree with the engine by
     * construction on the one term that actually decides the answer.
     */
    private fun expectedStride(config: ConfigPayload?): Int =
        (BUDGET - maxOf(headerBudget, worstCaseReserve(config))) / cborByteExpansion

    /**
     * The constraint that makes this more than a port of the propose lane's fix: a budget the
     * envelope alone exhausts must produce an **observable refusal**, not a one-byte chunk.
     *
     * `chunkBytes` floored at 1 because a zero-byte chunk would never terminate a transfer. That
     * floor is right for its original reason and wrong here: when the envelope has already spent the
     * whole budget, the one-byte chunk is minted over budget, refused at the transport, never acked,
     * and re-sent forever — the same permanent wedge, now produced *by* the guard. There is no
     * `coerceAtLeast(0)` escape available either, because unlike a propose there is no caller on the
     * stack to hand a typed failure to: the transfer runs on the actor loop, driven by heartbeats.
     *
     * So the refusal is a metric plus a log, the shape [RaftMetric.SnapshotRejectedSizeCeiling]
     * already uses for the receiver-side analogue of this. The metric is the assertable half —
     * `kuilt-raft`'s tests are `commonTest` with no log-capture backend on Kotlin/Native or wasmJs.
     *
     * ### What proves the rig fired
     *
     * The budget is **restored at the end and the transfer then completes**. Without that, "no chunk
     * was sent" is indistinguishable from "the leader was never trying to send one", and the arm
     * would pass against an engine that had simply forgotten this peer. Completion afterwards proves
     * the leader held the transfer live across the refusal, and that the refusal is a level that
     * clears rather than a latch.
     */
    @Test
    fun aBudgetTooSmallForTheWholeEnvelopeRefusesObservablyInsteadOfMintingAChunkThatCannotFit() = raftRunTest {
        val metrics = mutableListOf<Pair<NodeId, RaftMetric>>()
        val t = snapshotStampedWithConfig(sim(this, BUDGET) { id, m -> metrics += id to m }, wantJoint = true)
        val reserve = worstCaseReserve(t.config)
        assertTrue(
            reserve > STARVED_BUDGET,
            "rig: the whole point of $STARVED_BUDGET B is that the ${t.config.new.voters.size}-voter joint " +
                "envelope ($reserve B) cannot fit inside it, leaving no room for state bytes",
        )
        val installs = t.sim.collectInstalls(t.behind)

        t.sim.network.maxPayloadBytes = STARVED_BUDGET      // a peer attaches over a tighter link
        t.sim.restart(t.behind)
        val hb = fastRaftConfig().heartbeatInterval.inWholeMilliseconds
        advanceTimeBy(hb * 4); runCurrent(); t.sim.settle()  // several divert rounds at the starved budget

        val refusals = metrics.mapNotNull { (_, m) -> m as? RaftMetric.SnapshotChunkEnvelopeOverBudget }
        assertAll(
            {
                assertTrue(
                    t.sim.network.overBudget.isEmpty(),
                    "a budget the envelope alone exhausts must yield no frame at all, not a chunk sized " +
                        "to a floor it can never satisfy: ${t.sim.network.overBudget}",
                )
            },
            {
                assertTrue(
                    refusals.any { it.peer == t.behind },
                    "and the refusal must be observable, or a follower simply never converges with " +
                        "nothing naming why; metrics=${metrics.map { it.second }.filterNot { it is RaftMetric.ProposeApplied }}",
                )
            },
            {
                assertTrue(
                    refusals.all { it.budgetBytes == STARVED_BUDGET && it.reservedBytes >= reserve },
                    "the refusal must name both numbers an operator can move — the published budget and " +
                        "the measured envelope (>= $reserve B): $refusals",
                )
            },
            {
                assertTrue(
                    refusals.size > 1,
                    "the metric is a level to sample, not an edge to count: a standing misconfiguration " +
                        "and a transient budget dip are told apart by whether it keeps firing; got $refusals",
                )
            },
        )

        t.sim.network.maxPayloadBytes = BUDGET               // the tighter link leaves
        t.sim.awaitCommit(t.finalCommit, on = setOf(t.behind))
        assertContentEquals(
            t.state, installs.last().snapshot.state,
            "rig: the transfer must complete once the budget is restored, or 'no chunk was sent' is " +
                "indistinguishable from the leader having forgotten this peer",
        )
    }

    // ── Premises, over an independently constructed envelope ──────────────────

    /**
     * The premise the measured reserve exists for, kept under a test so it cannot quietly stop being
     * true: an ordinary cluster's `ConfigPayload` already outgrows the flat [headerBudget].
     *
     * If this ever reds the envelope shrank far enough that a flat reserve covers it again, and the
     * measurement could be reconsidered — the red is an instruction to revisit #2720, not a defect.
     *
     * The values are measured here rather than quoted, so the arm cannot drift from what CBOR
     * actually costs. What it pins is the *shape* of the answer: a simple five-voter config already
     * exceeds the reserve, and a joint one exceeds it by substantially more.
     */
    @Test
    fun theChunkEnvelopeAlreadyOutgrowsTheFlatReserve() {
        val voters = ClusterConfig(voters = voterIds.toSet())
        val simple = worstCaseReserve(ConfigPayload(old = null, new = voters))
        val joint = worstCaseReserve(ConfigPayload(old = voters, new = promoted))
        val none = worstCaseReserve(null)
        assertAll(
            {
                assertTrue(
                    none <= headerBudget,
                    "a config-free chunk is the only case the flat reserve ever covered ($none B); if " +
                        "this reds, HEADER_BUDGET is no longer a sound floor for anything (#2720)",
                )
            },
            {
                assertTrue(
                    simple > headerBudget,
                    "five ${voterIds.first().value.length}-character voter ids in a simple ConfigPayload " +
                        "cost $simple B against a $headerBudget B flat reserve — this is the ordinary " +
                        "configuration the wedge is reachable from",
                )
            },
            {
                assertTrue(
                    joint > simple,
                    "and a joint payload carries two ClusterConfigs, so a membership change widens the " +
                        "overrun rather than narrowing it: joint=$joint B vs simple=$simple B",
                )
            },
        )
    }

    /**
     * The property the probe's cheapness rests on: the envelope's cost does not depend on the chunk's
     * size, so the engine may measure it once around an **empty** payload — `O(|config|)` rather than
     * a second `O(chunk)` encode.
     *
     * True because CBOR is definite in structure: every enclosing map/array header here is a function
     * of element *count*, and the only size-dependent header is the data array's own, which
     * kotlinx-serialization writes indefinite-length and therefore at a constant two bytes. Asserted
     * rather than reasoned, across three chunk sizes and all three config shapes, because if it ever
     * stops holding the probe silently under-measures and the sizing goes quietly unsound again.
     */
    @Test
    fun theChunkEnvelopeOverheadIsAdditiveInTheChunkData() {
        val voters = ClusterConfig(voters = voterIds.toSet())
        val configs = listOf(null, ConfigPayload(null, voters), ConfigPayload(voters, promoted))
        val checks: List<() -> Unit> = configs.flatMap { config ->
            listOf(0, 1, 100, 5_000).map { size ->
                {
                    val data = ByteArray(size) { 0x7F }
                    val frame = frameBytes(
                        config, plausibleCeiling, plausibleCeiling, plausibleCeiling, Long.MAX_VALUE, data,
                    )
                    assertEquals(
                        worstCaseReserve(config) - wireBytes(ByteArray(0)) + wireBytes(data),
                        frame,
                        "config=${config?.let { if (it.old != null) "joint" else "simple" } ?: "none"}, " +
                            "chunk=$size B: the envelope must cost the same whatever the chunk's size, or " +
                            "the empty-payload probe under-measures",
                    )
                }
            }
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * Why the chunk lane's reserve is the **whole** probe frame while the propose lane's is the frame
     * *minus* its empty payload — a two-byte difference that is precisely enough to put every
     * full-size chunk over the budget.
     *
     * The propose gate measures `wireBytes(command)` and compares it against `budget − reserved`, so
     * the payload array's own header is inside the measured half. `chunkBytes` has no bytes to
     * measure yet: it converts a *raw* count with `CBOR_BYTE_EXPANSION`, which accounts for the
     * per-element cost and nothing else. The header therefore has to sit in the reserve, or the
     * arithmetic is short by exactly its width — and a reserve that is short by two produces a frame
     * two bytes over, on every chunk, forever.
     *
     * Stated as the inequality `chunkBytes` must satisfy: with `raw = (budget − reserve) / expansion`,
     * the frame it produces must fit the budget. Checked at **every** budget in [EDGE_BUDGETS] and
     * with no slack in the assertion, because a two-byte overshoot is invisible to any assertion that
     * has any — including this suite's own `overBudget.isEmpty()` behaviour arms, whose reserve is
     * charged at the plausibility ceiling while a real transfer's `Long`s are small.
     *
     * ### The codec property this rests on, and what would invalidate it
     *
     * An empty-payload probe can stand in for a full chunk **only** because kotlinx-serialization
     * writes a `ByteArray` as an *indefinite-length* CBOR array — a `0x9F` … `0xFF` wrapper of
     * exactly two bytes whatever the payload's length. [theWireWrapperAroundAChunkIsLengthIndependent]
     * pins that separately, and it is the load-bearing half of the whole approach: a codec change
     * that gives the payload a **length-dependent** header (a real byte string steps 1 / 2 / 3 / 5
     * bytes as the payload crosses 24 / 256 / 65536 — which is exactly what `alwaysUseByteString`
     * does under #2160, implemented in #2746) makes the probe charge the *empty* header while the
     * chunk carries a wider one, and every full chunk goes over budget again, deterministically.
     *
     * Whoever lands that codec change owns re-deriving this: strip the empty payload's header from
     * the measurement, spend the budget on the wire quantity, and take the header of the *whole*
     * remaining window (safe because the step function is monotone). Do not simply keep the
     * `maxOf(HEADER_BUDGET, measured)` floor and assume it absorbs the difference — at the budgets
     * this module's existing tests use it does, which is precisely how the regression would land
     * green.
     */
    @Test
    fun theReserveMustIncludeTheChunkArraysOwnHeader() {
        val voters = ClusterConfig(voters = voterIds.toSet())
        val config = ConfigPayload(old = null, new = voters)
        val reserve = worstCaseReserve(config)
        val overheadOnly = reserve - wireBytes(ByteArray(0))     // what the propose lane's shape charges

        // Every byte high-valued, so each costs two on the wire — the worst case the sizing must survive.
        fun frameAt(raw: Int) = frameBytes(
            config, plausibleCeiling, plausibleCeiling, plausibleCeiling, Long.MAX_VALUE,
            ByteArray(raw) { (0x80 or (it and 0x3F)).toByte() },
        )

        val checks: List<() -> Unit> = EDGE_BUDGETS.flatMap { budget ->
            listOf<() -> Unit>(
                {
                    val frame = frameAt((budget - reserve) / cborByteExpansion)
                    assertTrue(
                        frame <= budget,
                        "budget=$budget: reserving the whole probe frame ($reserve B) must leave a slice " +
                            "whose worst-case frame fits, got $frame B",
                    )
                },
                {
                    val frame = frameAt((budget - overheadOnly) / cborByteExpansion)
                    assertTrue(
                        frame > budget,
                        "budget=$budget: reserving only the overhead ($overheadOnly B), as the propose " +
                            "lane does, must NOT — if this reds the two shapes have converged and the " +
                            "distinction above has stopped being load-bearing",
                    )
                },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * The codec property the empty-payload probe stands on: the wire wrapper around a chunk's
     * `ByteArray` costs the **same two bytes at every length**, so measuring the envelope with no
     * data measures it for a full chunk too.
     *
     * kotlinx-serialization renders a `ByteArray` as an indefinite-length CBOR array (`0x9F` …
     * `0xFF`), which is why the cost is a constant rather than a function of the length — and why
     * `CBOR_BYTE_EXPANSION` alone describes the whole difference between a raw count and a wire one.
     *
     * **A red here is not a defect in `chunkBytes`; it is notice that `chunkBytes`' arithmetic no
     * longer holds.** The `@ByteString` framing of #2160 (implemented in #2746) replaces the wrapper
     * with a real byte-string header that steps 1 → 2 → 3 → 5 bytes across 24 / 256 / 65536, at which
     * point an empty-payload measurement under-charges every full chunk by two to four bytes and the
     * wedge of #2720 returns with nothing else in the module reddening. See
     * [theReserveMustIncludeTheChunkArraysOwnHeader] for the shape the replacement has to take.
     */
    @Test
    fun theWireWrapperAroundAChunkIsLengthIndependent() {
        val empty = wireBytes(ByteArray(0))
        val checks: List<() -> Unit> = listOf(0, 1, 23, 24, 255, 256, 65_535, 65_536).map { size ->
            {
                // All-zero data, so every element costs exactly one wire byte and the residue is the wrapper.
                assertEquals(
                    empty,
                    wireBytes(ByteArray(size)) - size,
                    "a $size-byte chunk's wire wrapper must cost the same $empty B as an empty one's, or " +
                        "the empty-payload envelope probe under-measures a full chunk (#2160/#2746)",
                )
            }
        }
        assertAll(*checks.toTypedArray())
    }

    private companion object {
        /**
         * A transport budget large enough that the reserve does not dominate the chunk, so the sizing
         * arithmetic is what decides whether a frame fits — the same 4096 B
         * `InstallSnapshotTest.BIG_BUDGET` uses, and for the same reason. Below roughly 768 B the
         * reserve absorbs the expansion and neither defect is visible.
         */
        const val BUDGET = 4096

        /**
         * A budget smaller than the joint envelope itself, so no slice size can produce a frame that
         * fits — the refusal case. Deliberately still well above a config-free envelope (~175 B at
         * the plausibility ceiling), so heartbeats and vote frames keep flowing and the arm is about
         * the snapshot lane rather than about a cluster that has stopped working.
         */
        const val STARVED_BUDGET = 384

        /** Enough state to span several chunks at [BUDGET], so the sizing is exercised repeatedly. */
        const val BIG_STATE = 8000

        /**
         * Budgets [theReserveMustIncludeTheChunkArraysOwnHeader] checks the sizing inequality at.
         *
         * Spread across three orders of magnitude on purpose: the two-byte error the arm is about is
         * constant, so a single budget large enough would let a *proportional* mistake hide inside
         * it, and one small enough would be dominated by the reserve. 512 B is roughly the smallest
         * budget at which a five-voter config still leaves room for data at all.
         */
        val EDGE_BUDGETS = listOf(512, 1024, 4096, 16_384, 131_072)
    }
}
