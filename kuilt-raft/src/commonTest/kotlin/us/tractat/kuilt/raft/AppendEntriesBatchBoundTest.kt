@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The **per-batch payload bound** on AppendEntries (#2150) — the cap in `RaftEngine.sendAppendEntries`
 * on the slice `logSliceFrom` returns.
 *
 * `sendAppendEntries` used to ship the *entire* un-replicated tail: `log.subList(offset, log.size)`,
 * with nothing capping the count or the cumulative bytes. On a transport that publishes and enforces a
 * payload budget that wedges a lagging follower **permanently**, and the wedge is self-sustaining
 * rather than transient:
 *
 *  - the over-budget frame is refused at the *sender*, so the follower never sees it;
 *  - never seeing it, the follower never rejects it, so `nextIndex` never backs up;
 *  - never receiving it, the follower never acks, so `nextIndex` never advances;
 *  - the next heartbeat mints the same over-budget frame again.
 *
 * Nothing self-heals it. Enough wedged followers and the leader loses commit quorum outright.
 *
 * ## Distinct from the propose-time bound (#2069)
 *
 * `RaftEngine.checkProposeFitsTransport` refuses a single command too large for the transport. It
 * cannot reach this: every entry below is comfortably *inside* that per-entry bound, and they overflow
 * only **in aggregate**. Per-entry and per-batch are separate bounds, and this test is sized to hold
 * exactly that distinction — [singleEntryFrameBytes] is asserted to fit while [tailFrameBytes] is
 * asserted not to.
 *
 * ## Why no simulation saw it
 *
 * [InMemoryRaftNetwork] *published* `maxPayloadBytes` to the engine and then carried a frame of any
 * size — a budget nothing enforced. The harness now drops an over-budget frame the way a budgeted
 * `Seam` does (`PayloadTooLarge`, swallowed by `SeamRaftTransport.sendTo`) and records it on
 * [InMemoryRaftNetwork.overBudget], which is what turns "the leader minted a frame larger than the
 * transport it was told about" from invisible into an assertion.
 *
 * ## The two kills
 *
 * The catch-up assertion is the consumer-visible one; `overBudget` is the direct one, and it fires
 * even while the follower is offline, because the leader mints the oversize frame regardless of
 * whether anyone is there to receive it. They are independent: a fix that chunked correctly but
 * dropped entries would pass the second and fail the first.
 *
 * ## What the bound makes load-bearing
 *
 * The forward-only commit clamp in `onAppendEntries` — `advanceCommit(minOf(m.leaderCommit,
 * maxOf(lastNewIndex, state.currentCommitIndex)))` — was until now a defence with no honest trigger,
 * because a full-suffix batch always satisfies `leaderCommit <= lastNewIndex`. A capped batch breaks
 * that relation on the *first* frame of every catch-up: the leader's `leaderCommit` is its own commit
 * index, far above where the truncated batch ends. Committing to `leaderCommit` on a batch that
 * attests only a prefix of it would commit entries the follower does not hold. See
 * [ForwardOnlyCommitClampTest], whose "reachability: injection-only today" section this test retires.
 */
class AppendEntriesBatchBoundTest {

    /**
     * A follower whose un-replicated tail exceeds the transport budget catches up rather than wedging,
     * and no frame the leader minted along the way exceeded that budget.
     *
     * Offline is modelled as [RaftSimulation.crash] + [RaftSimulation.restart] rather than a partition,
     * for the reason [InstallSnapshotTest] gives: a partitioned-but-running node inflates its term and
     * drags in the orthogonal disruptive-rejoin problem. The crashed node keeps its persisted log, so
     * the leader's `nextIndex` for it stays where it was and the rejoin is a pure AppendEntries
     * catch-up — no compaction, no InstallSnapshot divert.
     */
    @Test
    fun laggingFollower_catchesUp_whenItsUnreplicatedTailExceedsTheTransportBudget() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = BUDGET)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val offline = sim.nodeIds.first { it != leaderId }

        sim.crash(offline)
        repeat(ENTRIES) { n -> leader.propose(ByteArray(COMMAND_BYTES) { n.toByte() }) }
        val finalCommit = leader.commitIndex.value

        // Premises, measured against the real CBOR encoding rather than assumed. Together they are the
        // "individually legal, collectively not" condition this bug needs: drop either and the test
        // still passes for a reason that is not the bound.
        val log = sim.storages.getValue(leaderId).entries(1L)
        val term = sim.storages.getValue(leaderId).term()
        val tail = log.filter { it.index >= FIRST_UNREPLICATED }
        fun frameBytes(entries: List<LogEntry>): Int = Cbor.encodeToByteArray<RaftMessage>(
            RaftMessage.AppendEntries(
                term = term,
                prevLogIndex = FIRST_UNREPLICATED - 1L,
                prevLogTerm = term,
                entries = entries,
                leaderCommit = finalCommit,
                round = 1L,
            )
        ).size
        val tailFrameBytes = frameBytes(tail)
        val singleEntryFrameBytes = frameBytes(tail.take(1))

        assertAll(
            {
                assertTrue(
                    tail.size >= 2,
                    "premise: the follower must be behind by more than one entry, or a per-batch bound " +
                        "and a per-entry bound are indistinguishable here — tail was ${tail.size}",
                )
            },
            {
                assertTrue(
                    tailFrameBytes > BUDGET,
                    "premise: the WHOLE tail must not fit one frame ($tailFrameBytes B vs $BUDGET B budget), " +
                        "or there is no over-budget batch to bound and this test is vacuous",
                )
            },
            {
                assertTrue(
                    singleEntryFrameBytes <= BUDGET,
                    "premise: a SINGLE entry must fit ($singleEntryFrameBytes B vs $BUDGET B budget), or the " +
                        "follower could never catch up at any batch size and the failure below would be " +
                        "attributable to the per-entry bound of #2069 rather than to the batch",
                )
            },
            {
                assertTrue(
                    COMMAND_BYTES <= BUDGET - HEADER_BUDGET,
                    "premise: every command must be inside the propose-time bound of #2069 " +
                        "($COMMAND_BYTES B vs ${BUDGET - HEADER_BUDGET} B), or the proposals above would " +
                        "have been refused before reaching the log",
                )
            },
        )

        sim.restart(offline)
        sim.awaitCommit(finalCommit, on = setOf(offline))

        assertAll(
            {
                assertContentEquals(
                    sim.appliedState(leaderId), sim.appliedState(offline),
                    "the caught-up follower's state machine must converge with the leader's — a bound that " +
                        "dropped entries instead of deferring them would diverge here while still " +
                        "satisfying the byte assertion below",
                )
            },
            {
                assertTrue(
                    sim.network.overBudget.isEmpty(),
                    "the leader must never mint a frame larger than the budget the transport published " +
                        "(${BUDGET} B): ${sim.network.overBudget}",
                )
            },
        )
        sim.checkInvariants()
    }

    private companion object {
        /**
         * The transport's published payload budget. Comfortably above `RaftEngine.HEADER_BUDGET` so a
         * command has real room — the fixture bug of #2069 was a budget *below* the envelope reserve,
         * which silently floors the usable payload at 1 and tests nothing it claims to.
         */
        const val BUDGET = 1024

        /** Mirrors `RaftEngine.HEADER_BUDGET`, which is private; asserted against, never assumed. */
        const val HEADER_BUDGET = 256

        /** Per-command bytes: far inside the per-entry bound, so only the aggregate can overflow. */
        const val COMMAND_BYTES = 64

        /** Enough entries that the tail overflows [BUDGET] several times over. */
        const val ENTRIES = 20

        /**
         * The first index the crashed follower is missing. Index 1 is the leader's §5.4.2 election
         * no-op, which replicated before the crash, so the tail starts at 2.
         */
        const val FIRST_UNREPLICATED = 2L
    }
}
