@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.raft.internal.EMPTY_PAYLOAD_WIRE_BYTES
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.raft.internal.byteStringHeaderBytes
import us.tractat.kuilt.raft.internal.raftCbor
import us.tractat.kuilt.raft.internal.snapshotSliceBytes
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
 * whole envelope, and six twenty-character node ids already cost more than that (five did, before
 * #2160's short frame tags took 57 B off the envelope). Each chunk was
 * therefore minted over the transport's budget, refused at `SeamRaftTransport.sendTo` (which must
 * swallow `PayloadTooLarge`), never acked, and re-sent forever: a follower that needs a snapshot can
 * never be caught up, and `AppendEntries` cannot help it because its prefix was compacted away.
 *
 * This is the same defect [ProposeEnvelopeReserveTest] closes on the propose lane, reached through a
 * different field, and the remedy is the same shape — **published conservatively, enforced by
 * measuring** — with two differences this suite exists to hold:
 *
 * 1. **The slice is charged the byte-string header of the whole remaining window.** The propose
 *    gate measures the command's *encoded* size, header included, so it can subtract an empty
 *    payload's cost and compare wire bytes to wire bytes. `chunkBytes` picks a slice size *before*
 *    there are bytes to encode, and since #2160 a payload's header steps 1 → 2 → 3 → 5 bytes with
 *    its length — so the empty-payload probe charges a one-byte header while a real chunk carries
 *    two, three or five. The engine strips the empty payload's header from the probe and charges the
 *    header of the whole window instead. [aSliceAtThePlausibilityCeilingFitsTheBudgetWithNoSlack]
 *    holds that against the engine's own arithmetic with no slack; the offset stride pinned in
 *    [assertTransferFitsTheBudget] holds it end to end, and the distinction is measured rather than
 *    assumed — see the mutation note there.
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
 * - [aRefusalMidTransferResumesFromTheAckedOffsetWhenTheBudgetRecovers] — a refusal that lands on a
 *   transfer in flight keeps its acked offset.
 * - [aRefusalWithNoTransferInFlightNeverLoadsTheStoredSnapshot] — a refusal costs no snapshot load.
 * - [theChunkEnvelopeAlreadyOutgrowsTheFlatReserve] — the premise the fix exists for, over an
 *   **independently constructed** envelope.
 * - [theChunkEnvelopeOverheadIsAdditiveInTheChunkData] — the property the probe's cheapness rests on.
 * - [aSliceAtThePlausibilityCeilingFitsTheBudgetWithNoSlack] — the header-aware sizing, at the one
 *   place its error is visible: a frame charged at the plausibility ceiling.
 * - [theSliceUnderFillsByAtMostTwoBytesAtAHeaderStep] — what the header-aware form costs.
 * - [theEngineHeaderStepAgreesWithTheCodec] — the step table the sizing reads, against the codec.
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

    /**
     * The engine's own plausibility ceiling for a term and an index (`RaftEngine.MAX_PLAUSIBLE_TERM`
     * / `MAX_PLAUSIBLE_INDEX`, both `1L shl 60`), restated by value. Anything above it the engine
     * refuses outright, so it is the top of the range a reserve has to cover.
     */
    private val plausibleCeiling = 1L shl 60

    /**
     * The engine's own wire codec, not a restated one. The codec is the one thing on this lane the
     * suite must **not** construct independently: the sizing premises below exist to red when the
     * codec's framing changes, and a bare [Cbor] would carry on measuring the old framing through
     * that change. Measured when #2160 flipped `raftCbor` to `alwaysUseByteString = true`: while this
     * field was a bare `Cbor`, the codec-premise tests of the time stayed green; on `raftCbor` they
     * reddened, which is what told #2746's rebase that the sizing had to be re-derived.
     *
     * `encodeDefaults` is off, so a `null` config and a zero `round` are omitted — which is why the
     * probe must be built from the *real* config rather than a placeholder.
     */
    private val cbor = raftCbor

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
     * the engine measures, and reports as its reserve.
     *
     * It includes the empty payload's own one-byte header, which a real chunk does not carry — it
     * carries a wider one. The sizing strips it and charges the window's header instead; see
     * [aSliceAtThePlausibilityCeilingFitsTheBudgetWithNoSlack].
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
        wrapStorage: (NodeId, RaftStorage) -> RaftStorage = { _, storage -> storage },
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
                    wrapStorage(id, storage),
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
        return Transfer(sim, leaderId, behind, through, finalCommit, stamped, bigState, chunkOffsets)
    }

    private class Transfer(
        val sim: RaftSimulation,
        val leaderId: NodeId,
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
     * The headline statement is the one [ProposeEnvelopeReserveTest] makes: the leader must never
     * mint a frame larger than the transport it was told about. It is joined here by a deliberately
     * *implementation*-coupled one — the chunk count, pinned exactly — because on this lane the
     * headline assertion has slack that hides the errors worth catching. See
     * [assertTransferFitsTheBudget], which carries the measurement.
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
     * config fits the budget can still be wedged by a snapshot cut during a membership change. That
     * snapshot is stamped joint, keeps the stamp after the change commits, and so every chunk of every
     * transfer that installs it carries the wider payload. A newer snapshot cut past the change ends
     * that only for transfers that start after it: one already in flight keeps the joint snapshot
     * until it completes, the budget recovers, or leadership changes.
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
     * `offset` are small, so a correctly-sized chunk sits tens of bytes under the budget. An
     * over-count of two bytes — the exact size of the mistake this suite exists to prevent — is
     * therefore invisible to it. Pinning the offset stride against an independently computed slice
     * size removes that slack: nothing about the sizing can move without this reddening, including a
     * slice that forgets the byte-string header steps, a re-introduced divisor, or a probe taken at
     * small `Long`s.
     *
     * It is deliberately coupled to the *outcome of the sizing*, not merely to "it fitted", and that
     * coupling is the point: a codec change that alters the sizing **must** red here and re-derive
     * [expectedStride]. #2746's rebase is the receipt — the byte-string framing moved the stride and
     * this arm said so. A green suite across such a change would mean nothing pinned how much data a
     * chunk carries — which is the state this arm was written to leave behind.
     *
     * ### Measured, not argued
     *
     * Mutations of the engine's sizing, each run against the whole `:kuilt-raft` suite (577 tests)
     * with the header-aware form in place:
     *
     * | mutation | `overBudget`, both arms | this stride assertion, both arms |
     * |---|---|---|
     * | naive port, `wireCap − reserve` | green | **red** — 2 B wide |
     * | empty payload's header not stripped | green | **red** — 1 B short |
     * | probe measured at `offset = 0` | green | **red** — 8 B wide |
     * | divisor re-introduced | green | **red** — half |
     * | flat 256 B reserve (before #2720) | joint **red**, simple green | **red** |
     *
     * The first is the shape a rebase onto byte strings produces by default, and it is 1–4 B over
     * budget at the plausibility ceiling on every chunk; `overBudget` saw none of the first four.
     * That is not a defect in it: it is the slack described above, and it is why this row exists.
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
                    "every chunk must carry exactly $stride raw bytes — the largest slice whose byte " +
                        "string fits what the measured ${worstCaseReserve(t.config)} B reserve leaves inside " +
                        "a ${BUDGET} B budget. overBudget has tens of bytes of slack here, so a slice that " +
                        "forgets the header steps, a divisor, or a probe at small Longs is silent to it",
                )
            },
        )
    }

    /**
     * The raw bytes per chunk the engine must choose for [config] at [BUDGET], found here by
     * **searching** the codec rather than by restating the engine's closed form.
     *
     * The largest slice whose frame — the reserve with its empty payload's header stripped, plus the
     * slice as the codec actually encodes it — fits the budget. The engine computes the same number
     * in closed form (`snapshotSliceBytes`); a search over real encodings is the independent route to
     * it, so an error in that form cannot be copied into the expectation. The two agree exactly at
     * [BUDGET] for both configs this suite builds. They would part by a byte or two only if the window
     * landed on a header step — see [theSliceUnderFillsByAtMostTwoBytesAtAHeaderStep] — and a red here
     * naming a stride one or two below this one should be read as that before anything else.
     *
     * `RaftConfig.snapshotChunkCeiling` (16 KiB by default) does not bind at this budget, so it is
     * deliberately absent: including it would make the expression agree with the engine by
     * construction on the one term that actually decides the answer.
     */
    private fun expectedStride(config: ConfigPayload?): Int {
        val overhead = maxOf(headerBudget, worstCaseReserve(config)) - wireBytes(ByteArray(0))
        return (BUDGET - overhead downTo 1).first { raw -> overhead + wireBytes(ByteArray(raw)) <= BUDGET }
    }

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
     * The snapshot here is cut at the joint entry *after* `changeMembership` has returned, which is
     * after the settled `Simple` config committed — so these refusals, still naming the joint
     * envelope, are also the receipt that a joint-config refusal does not clear when the change does.
     *
     * ### What proves the rig fired
     *
     * The budget is **restored at the end and the transfer then completes**. Without that, "no chunk
     * was sent" is indistinguishable from "the leader was never trying to send one", and the arm
     * would pass against an engine that had simply forgotten this peer. Completion afterwards proves
     * the leader still owes this peer a snapshot, and that the refusal is a level that clears rather
     * than a latch.
     *
     * It proves nothing about a transfer **in flight**. The budget is starved before the follower
     * restarts, so no transfer exists when these refusals fire and the one that completes is started
     * afresh after recovery — an engine that dropped an in-flight transfer on refusal passes here.
     * [aRefusalMidTransferResumesFromTheAckedOffsetWhenTheBudgetRecovers] holds that half.
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

    /**
     * A refusal that lands on a transfer **already in flight** must keep it: when the budget recovers,
     * the next chunk starts at the offset the follower acked, not at zero.
     *
     * `SnapshotSender.nextChunk`'s contract is that a refusal "does not end" a running transfer,
     * because a budget too small for the envelope is a level — a peer attaching over a tighter link
     * lowers it and leaving raises it — rather than a verdict on the transfer. An engine that dropped
     * the transfer instead would still converge, by re-sending the whole snapshot from offset 0, so
     * nothing about completion can see the difference. The offset of the first chunk after recovery
     * can.
     *
     * ### What proves the rig fired
     *
     * The budget is starved from the leader's trace, the moment it sends a chunk past offset 0, so the
     * first ack has already landed. The arm then asserts, before recovering, that the follower acked
     * part of the snapshot and not all of it, that the leader refused this peer inside the window,
     * and that nothing was dropped for size — so every chunk sent before the starvation was delivered
     * and acked, and "the acked offset" is a value the follower actually sent.
     */
    @Test
    fun aRefusalMidTransferResumesFromTheAckedOffsetWhenTheBudgetRecovers() = raftRunTest {
        val metrics = mutableListOf<Pair<NodeId, RaftMetric>>()
        val t = snapshotStampedWithConfig(sim(this, BUDGET) { id, m -> metrics += id to m }, wantJoint = true)
        val network = t.sim.network
        var starvedAfter: Long? = null
        backgroundScope.launch {
            t.sim.nodes.getValue(t.leaderId).trace.collect { event ->
                if (starvedAfter == null && event is RaftTraceEvent.InstallSnapshot &&
                    event.to == t.behind && event.offset > 0L
                ) {
                    network.maxPayloadBytes = STARVED_BUDGET  // a peer attaches over a tighter link, mid-transfer
                    starvedAfter = event.offset
                }
            }
        }
        t.sim.settle()                                        // subscribe before the transfer starts
        network.recording = true

        t.sim.restart(t.behind)
        t.sim.awaitTrue("the budget was starved mid-transfer") { starvedAfter != null }
        val hb = fastRaftConfig().heartbeatInterval.inWholeMilliseconds
        advanceTimeBy(hb * 4); runCurrent(); t.sim.settle()  // several divert rounds at the starved budget

        val acked = network.sent
            .filter { it.from == t.behind && it.to == t.leaderId }
            .mapNotNull { (it.message as? RaftMessage.InstallSnapshotResponse)?.nextOffset }
            .lastOrNull()
        val refusals = metrics.count { (id, m) ->
            id == t.leaderId && m is RaftMetric.SnapshotChunkEnvelopeOverBudget && m.peer == t.behind
        }
        assertAll(
            {
                assertTrue(
                    acked != null && acked in 1L until BIG_STATE.toLong(),
                    "rig: the follower must have acked part of the snapshot and not all of it, or no " +
                        "transfer was in flight when the refusal fired; acked=$acked of $BIG_STATE",
                )
            },
            { assertTrue(refusals > 0, "rig: the leader must refuse ${t.behind} inside the window") },
            {
                assertTrue(
                    network.overBudget.isEmpty(),
                    "rig: nothing may be dropped for size, or the acked offset is not the transfer's " +
                        "true position: ${network.overBudget}",
                )
            },
        )

        val sentBeforeRecovery = t.chunkOffsets.size
        network.maxPayloadBytes = BUDGET                      // the tighter link leaves
        t.sim.awaitCommit(t.finalCommit, on = setOf(t.behind))
        assertEquals(
            acked,
            t.chunkOffsets.getOrNull(sentBeforeRecovery),
            "the first chunk after the budget recovers must resume at the offset the follower acked; " +
                "offsets before recovery=${t.chunkOffsets.take(sentBeforeRecovery)}, " +
                "after=${t.chunkOffsets.drop(sentBeforeRecovery)}",
        )
    }

    /**
     * A refusal with no transfer in flight must be decided **before** the stored snapshot is loaded,
     * not after loading it and throwing the copy away.
     *
     * The refusal repeats at every heartbeat divert, for every stranded peer, for as long as the
     * budget stays short. `DurableStoreRaftStorage.loadSnapshot` returns a fresh copy of the whole
     * snapshot, so a load per refusal is an `O(snapshot)` copy on the actor loop at the heartbeat
     * interval. [InMemoryRaftStorage] hands back the same reference, which is why no other test in
     * this module can see the cost — hence a counting wrapper, and a count rather than a timing.
     *
     * ### What proves the rig fired
     *
     * A zero is only evidence if the refusal fired and the counter can count. So the arm asserts
     * both: the leader refused this peer more than once inside the window, and the counter sees the
     * load the transfer makes once the budget is restored.
     */
    @Test
    fun aRefusalWithNoTransferInFlightNeverLoadsTheStoredSnapshot() = raftRunTest {
        val metrics = mutableListOf<Pair<NodeId, RaftMetric>>()
        val loads = mutableMapOf<NodeId, Int>()
        val sim = sim(
            this,
            BUDGET,
            wrapStorage = { id, storage -> LoadCountingStorage(storage) { loads[id] = (loads[id] ?: 0) + 1 } },
        ) { id, m -> metrics += id to m }
        val t = snapshotStampedWithConfig(sim, wantJoint = true)
        fun leaderLoads() = loads[t.leaderId] ?: 0
        fun leaderRefusals() = metrics.count { (id, m) ->
            id == t.leaderId && m is RaftMetric.SnapshotChunkEnvelopeOverBudget && m.peer == t.behind
        }

        t.sim.network.maxPayloadBytes = STARVED_BUDGET      // no transfer exists yet: the fresh-load path
        val loadsBefore = leaderLoads()
        t.sim.restart(t.behind)
        val hb = fastRaftConfig().heartbeatInterval.inWholeMilliseconds
        advanceTimeBy(hb * 4); runCurrent(); t.sim.settle()  // several divert rounds at the starved budget
        val refusals = leaderRefusals()
        val loadsWhileRefused = leaderLoads() - loadsBefore

        assertTrue(
            refusals > 1,
            "rig: the leader must refuse ${t.behind} repeatedly inside the window, or zero loads is a " +
                "statement about a leader that never tried; refusals=$refusals",
        )
        assertEquals(
            0, loadsWhileRefused,
            "a refused transfer must not load the stored snapshot: $refusals refusals cost " +
                "$loadsWhileRefused loads, one O(snapshot) copy per heartbeat per stranded peer",
        )

        t.sim.network.maxPayloadBytes = BUDGET               // the tighter link leaves
        t.sim.awaitCommit(t.finalCommit, on = setOf(t.behind))
        assertTrue(
            leaderLoads() - loadsBefore >= 1,
            "rig: the counter must see the load the recovered transfer makes, or the zero above proves " +
                "nothing about loads at all",
        )
    }

    /** Counts [RaftStorage.loadSnapshot] calls and otherwise delegates to [inner] untouched. */
    private class LoadCountingStorage(
        private val inner: RaftStorage,
        private val onLoad: () -> Unit,
    ) : RaftStorage by inner {
        override suspend fun loadSnapshot(): StoredSnapshot? = inner.loadSnapshot().also { onLoad() }
    }

    // ── Premises, over an independently constructed envelope ──────────────────

    /**
     * The premise the measured reserve exists for, kept under a test so it cannot quietly stop being
     * true: an ordinary cluster's `ConfigPayload` already outgrows the flat [headerBudget].
     *
     * If this ever reds the envelope shrank far enough that a flat reserve covers it again, and the
     * measurement could be reconsidered — the red is an instruction to revisit #2720, not a defect.
     *
     * **It has already moved once.** #2160's short frame tags took 57 B off every `InstallSnapshot`,
     * and five twenty-character voter ids in a simple payload fell from 308 B to 251 B — under the
     * flat reserve. The simple arm therefore uses the six-voter membership this suite's promotion
     * settles on (272 B), which is still ordinary; the joint arm is untouched by the choice. What
     * that says about the design is narrower than it looks: node ids are consumer-chosen and
     * unbounded, so *some* ordinary cluster outgrows any flat number, and the measurement stays.
     *
     * The values are measured here rather than quoted, so the arm cannot drift from what CBOR
     * actually costs. What it pins is the *shape* of the answer: a simple six-voter config already
     * exceeds the reserve, and a joint one exceeds it by substantially more.
     */
    @Test
    fun theChunkEnvelopeAlreadyOutgrowsTheFlatReserve() {
        val voters = ClusterConfig(voters = voterIds.toSet())
        val simple = worstCaseReserve(ConfigPayload(old = null, new = promoted))
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
                    "${promoted.voters.size} ${voterIds.first().value.length}-character voter ids in a simple ConfigPayload " +
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
     * The property the probe's cheapness rests on: apart from the payload's own header, the
     * envelope's cost does not depend on the chunk's size, so the engine may measure it once around an
     * **empty** payload — `O(|config|)` rather than a second `O(chunk)` encode.
     *
     * True because CBOR is definite in structure: every enclosing map/array header here is a function
     * of element *count*, so the only size-dependent bytes in the frame are the payload's own — its
     * length plus its byte-string header. Asserted rather than reasoned, across four chunk sizes and
     * all three config shapes, because if it ever stops holding the probe silently under-measures and
     * the sizing goes quietly unsound again.
     *
     * **Not a detector for a length-dependent payload header.** Both sides of the equation carry the
     * payload's header, so they cancel: this stayed green when #2160 made that header step with the
     * length (measured). Catching *that* is [aSliceAtThePlausibilityCeilingFitsTheBudgetWithNoSlack]'s
     * job, and the steps themselves are pinned by [theEngineHeaderStepAgreesWithTheCodec] and by
     * `RaftWireGoldenVectorTest.anOpaquePayloadCostsItsOwnLengthPlusAByteStringHeader`.
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
     * The sizing `chunkBytes` performs, checked against real encodings at the one place its error is
     * visible: a frame charged at the **plausibility ceiling**, where the reserve has no slack left.
     *
     * The empty-payload probe measures the envelope around a zero-length payload, whose byte-string
     * header is one byte. A real chunk's header is two, three or five (#2160), so a slice sized as
     * `budget − reserve` — the naive port of the pre-#2160 arithmetic with its divisor dropped — mints
     * a frame one to four bytes over the budget on every chunk. The engine instead strips the empty
     * payload's header, spends the budget on the window that is left, and charges the header of that
     * **whole** window, which is safe because the step function is monotone: a slice no longer than
     * the window can never need a wider header than the window does.
     *
     * Three statements per budget and config, all with **no slack**:
     *
     * 1. the engine's slice fits — the frame it produces at the ceiling is `<=` the budget;
     * 2. it is not wastefully small — one more byte would not fit, so at these budgets the form is
     *    exact (it can under-fill by a byte or two only at a header step, which none of
     *    [EDGE_BUDGETS] lands on; see [theSliceUnderFillsByAtMostTwoBytesAtAHeaderStep]);
     * 3. the naive `budget − reserve` slice does **not** fit. If this reds the header has stopped
     *    stepping inside these budgets and the distinction above is no longer load-bearing.
     *
     * The frames are encoded here, around an independently constructed envelope, so the codec is the
     * oracle; only the slice size comes from the engine. `overBudget.isEmpty()` in the behaviour arms
     * cannot see this error — a real transfer's `Long`s are tens of bytes narrower than the probe's —
     * which is why the verdict lives here and in the stride, not there.
     */
    @Test
    fun aSliceAtThePlausibilityCeilingFitsTheBudgetWithNoSlack() {
        val voters = ClusterConfig(voters = voterIds.toSet())
        val configs = listOf(ConfigPayload(old = null, new = voters), ConfigPayload(old = voters, new = promoted))

        val checks: List<() -> Unit> = configs.flatMap { config ->
            val reserve = worstCaseReserve(config)
            val shape = if (config.old != null) "joint" else "simple"
            fun frameAt(raw: Int) = frameBytes(
                config, plausibleCeiling, plausibleCeiling, plausibleCeiling, Long.MAX_VALUE, ByteArray(raw),
            )
            EDGE_BUDGETS.flatMap { budget ->
                val raw = snapshotSliceBytes(budget, reserve)
                listOf<() -> Unit>(
                    {
                        val frame = frameAt(raw)
                        assertTrue(
                            frame <= budget,
                            "$shape, budget=$budget: the engine's $raw B slice must fit at the ceiling, " +
                                "got a ${frame} B frame (${frame - budget} B over)",
                        )
                    },
                    {
                        val frame = frameAt(raw + 1)
                        assertTrue(
                            frame > budget,
                            "$shape, budget=$budget: one more byte than the engine's $raw B slice still " +
                                "fits ($frame B), so the slice under-fills the budget",
                        )
                    },
                    {
                        val naive = budget - reserve
                        val frame = frameAt(naive)
                        assertTrue(
                            frame > budget,
                            "$shape, budget=$budget: the naive `budget − reserve` slice ($naive B) must NOT " +
                                "fit at the ceiling — if this reds the header no longer steps inside this " +
                                "budget and the header-aware form is no longer load-bearing here",
                        )
                    },
                )
            }
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * What the header-aware form costs, bounded: at a window that sits on a byte-string header step
     * the slice can come out a byte or two short of the best that fits, and never over.
     *
     * `window − header(window)` charges the header of the whole window, and a slice just below a step
     * needs a narrower one. The shortfall is one byte at windows of 24, 256, 257 and 65539 bytes, and
     * two at 65536 to 65538 (the header there drops from five bytes to three). Every other window is
     * exact. Checked here over a run of windows straddling each step, against the codec, so the claim
     * about the cost is a measurement rather than a remark — and so a "fix" that recovers those bytes
     * by overshooting reds on the first statement.
     */
    @Test
    fun theSliceUnderFillsByAtMostTwoBytesAtAHeaderStep() {
        val reserve = 101                               // any reserve: only the window it leaves matters
        val overhead = reserve - wireBytes(ByteArray(0))
        val windows = (20..28) + (252..260) + (65_532..65_542)
        val checks: List<() -> Unit> = windows.map { window ->
            {
                val raw = snapshotSliceBytes(window + overhead, reserve)
                val best = (window downTo 0).first { r -> wireBytes(ByteArray(r)) <= window }
                assertAll(
                    {
                        assertTrue(
                            wireBytes(ByteArray(raw)) <= window,
                            "window=$window: a $raw B slice encodes to ${wireBytes(ByteArray(raw))} B, over the window",
                        )
                    },
                    {
                        assertTrue(
                            best - raw in 0..2,
                            "window=$window: the slice is $raw B where $best B would fit — short by ${best - raw}",
                        )
                    },
                )
            }
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * The step table `chunkBytes` reads, and the empty-payload cost it strips from the probe, agree
     * with what the codec actually writes.
     *
     * The engine sizes a slice *before* there are bytes to encode, so it cannot measure the header it
     * charges; it reads it from `byteStringHeaderBytes`, a restatement of RFC 8949's length encoding.
     * A restatement is exactly what drifts from the thing it restates, so it is checked here at every
     * step boundary through the engine's own codec, alongside `EMPTY_PAYLOAD_WIRE_BYTES`.
     */
    @Test
    fun theEngineHeaderStepAgreesWithTheCodec() {
        val lengths = listOf(0, 1, 23, 24, 255, 256, 65_535, 65_536, 100_000)
        assertAll(
            {
                assertEquals(
                    wireBytes(ByteArray(0)), EMPTY_PAYLOAD_WIRE_BYTES,
                    "the empty payload's cost the probe strips must be what the codec writes",
                )
            },
            *lengths.map { length ->
                {
                    assertEquals(
                        wireBytes(ByteArray(length)) - length,
                        byteStringHeaderBytes(length),
                        "a $length-byte payload's header, as the sizing charges it vs as the codec writes it",
                    )
                }
            }.toTypedArray(),
        )
    }

    private companion object {
        /**
         * A transport budget large enough that the reserve does not dominate the chunk, so the sizing
         * arithmetic is what decides whether a frame fits — the same 4096 B
         * `InstallSnapshotTest.BIG_BUDGET` uses, and for the same reason.
         */
        const val BUDGET = 4096

        /**
         * A budget smaller than the joint envelope itself, so no slice size can produce a frame that
         * fits — the refusal case. Deliberately still well above a config-free envelope, so heartbeats
         * and vote frames keep flowing and the arm is about the snapshot lane rather than about a
         * cluster that has stopped working.
         *
         * ⚠ **The margin is thin: 3 B.** #2160's short frame tags took the joint envelope from 444 B to
         * 387 B. The first refusal arm asserts `reserve > STARVED_BUDGET` outright and the other two
         * assert that refusals actually fired, so the next envelope change reds a rig rather than
         * quietly turning a refusal into a transfer — which is also why an 8 B narrower probe
         * (measured at `offset = 0`) reds all three refusal arms as well as the stride.
         */
        const val STARVED_BUDGET = 384

        /** Enough state to span several chunks at [BUDGET], so the sizing is exercised repeatedly. */
        const val BIG_STATE = 8000

        /**
         * Budgets [aSliceAtThePlausibilityCeilingFitsTheBudgetWithNoSlack] checks the sizing at.
         *
         * Spread across three orders of magnitude on purpose, so the slice lands under each of the
         * byte-string header widths a chunk can carry: two or three bytes at 512 (the joint slice is
         * under 256 B, the simple one over it), three at 1 KiB to 16 KiB, five at 128 KiB. That is what
         * makes the naive port's overshoot +1, +2 or +4 here rather than one number. 512 B is roughly
         * the smallest budget at which a joint config still leaves room for data at all.
         */
        val EDGE_BUDGETS = listOf(512, 1024, 4096, 16_384, 131_072)
    }
}
