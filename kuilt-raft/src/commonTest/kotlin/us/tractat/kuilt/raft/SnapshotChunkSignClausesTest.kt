@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * `RaftEngine.snapshotChunkRefusal`'s two bounds are each a **disjunction**, and this class pins the
 * two **lower (sign)** disjuncts — the ones nothing measured (#2054).
 *
 * ```kotlin
 * if (m.lastIncludedIndex < 0L || m.lastIncludedIndex > MAX_PLAUSIBLE_INDEX) { … }
 * val termCeiling = minOf(m.term, MAX_PLAUSIBLE_TERM)
 * if (m.lastIncludedTerm  < 0L || m.lastIncludedTerm  > termCeiling)        { … }
 * ```
 *
 * Both upper disjuncts are pinned — `InstallSnapshotMetaValidationTest` for the bounds as a whole,
 * [SnapshotTermCeilingArmsTest] for the term ceiling's two arms. When #1980's mutation survey ran,
 * deleting *either* `< 0L` left the whole module green: no test on the wire path had ever put a
 * negative `lastIncludedIndex` or `lastIncludedTerm` on it, so both clauses evaluated `false` on
 * every trajectory that reached them and were inert. (The negatives in `RestoredLogValidationTest`
 * are on the *restore* path — `checkedRestoredSnapshotMeta`, a different guard.)
 *
 * **One of those two receipts has since expired, and saying so is the point of this paragraph.**
 * #2055's attribution suite added `FrameRefusedTest.aNegativeSnapshotIndex_isRefusedAs_InstallSnapshotIndexOutOfRange`,
 * which injects `lastIncludedIndex = -1`, so the *index* clause has been reachable-and-measured since
 * — deleting it now reddens that test (and `everyRefusalGateIsReachable`, which loses the gate's only
 * emit site) before this class is consulted. What that test does **not** measure is the ordering
 * obligation below: it asserts the gate and nothing else, so it is green on any refactor that keeps
 * the drop but moves it after a side-effect. The *term* clause was un-pinned exactly as #2054
 * measured — deleting it leaves 504 of 505 green, and [aNegativeLastIncludedTermIsDroppedBeforeEverySideEffect]
 * is the one red.
 *
 * ### Severity: injection-only, and downstream-shadowed. This is ordering, not a safety hole
 *
 * Said plainly so nobody triages it as a live vulnerability. Both values are **injection-only**: a
 * Byzantine voter can put any `Long` on the wire, but no honest leader mints one — `sendSnapshotChunk`
 * copies a [SnapshotMeta] off a snapshot it stored. And the safety-relevant consequence of dropping
 * either clause is **already closed off downstream**: a negative `lastIncludedIndex` is exempted by
 * `committedTermFloorRefusal` and then acked-and-ignored by `finalizeInstalledSnapshot`'s
 * `<= currentCommitIndex` guard (#1219/#1220); a negative `lastIncludedTerm` is refused by the
 * committed floor (#1910) on any frame that advances the frontier. Nothing installs either way —
 * which is precisely why the mutants were green.
 *
 * So a pin asserting only *"the log wasn't wiped"* **reproduces the defect**: that end state holds
 * with the clause deleted. The residual property is **ordering**. With the clause, the frame is
 * dropped *before every side-effect*. Without it the frame is **processed** — term adoption, leader
 * adoption, election-timeout reset, reassembly, and a reply to its own author — on the way to a
 * downstream guard that happens to render it inert. That "drop before every side-effect" contract is
 * what `snapshotChunkRefusal`'s KDoc claims ("Called before the term check, so a malformed frame
 * never adopts its term or resets the recipient's election timeout either") and what nothing
 * enforced. Each test here asserts the **four** effects the guard's position in `onInstallSnapshot`
 * is what prevents:
 *
 * | Effect | Engine site | Observed as |
 * |---|---|---|
 * | reply to the forger | the stale-term `send`, then `finalizeInstalledSnapshot`'s behind-commit ack | no `InstallSnapshotResponse` in `InMemoryRaftNetwork.sent` |
 * | §5.1 term adoption | `stepDown(m.term, HigherTermObserved)` | the victim's **durable** term is unchanged |
 * | leader adoption | `adoptLeaderForTerm` / `_leader.value = from` | `leader` still names the real leader |
 * | election-timeout reset | `resetElectionTimeout()` | the victim's timer still fires on its **original** deadline |
 *
 * ### Isolating the clause, and then isolating the downstream guards
 *
 * Both sign disjuncts share a `return` with their upper sibling, so [RaftTraceEvent.FrameRefused]
 * cannot report which disjunct fired — the limitation [SnapshotTermCeilingArmsTest] documents.
 * Isolation is a property of the injected value, and each test asserts its premises before injecting:
 * a negative is below *every* ceiling, so the upper disjunct is inert by construction.
 *
 * The harder half is isolating the **downstream guards**, because those are what made the mutants
 * green — a frame *they* refuse produces no side-effect either, and all four assertions above would
 * then pass with the clause deleted. Hence the two frame shapes:
 *
 * - [aNegativeLastIncludedIndexIsDroppedBeforeEverySideEffect] — `lastIncludedIndex = -1`. The
 *   committed-term floor exempts `lastIncludedIndex <= currentCommitIndex`, and `-1` is.
 * - [aNegativeLastIncludedTermIsDroppedBeforeEverySideEffect] — `lastIncludedTerm = -1` at a
 *   **behind-commit** index, so the same exemption applies and the floor cannot refuse it.
 *
 * **The behind-commit index is a deliberate departure from #2054's prescription**, which paired the
 * negative term with `lastIncludedIndex > commitIndex`. That shape hands the frame straight to
 * `committedTermFloorRefusal` the moment the clause is deleted (`floor >= 0 > -1`), which also drops
 * it before any side-effect — so all four assertions would hold either way and the only surviving
 * discriminator would be the *name* of the gate in the trace. Below the commit frontier the floor is
 * exempted by its own guard, and the sign clause is the **sole** thing between the frame and the
 * whole of `onInstallSnapshot`.
 *
 * Each test carries an in-test **positive control** — a well-formed snapshot over the identical
 * injection path, which must install. A refusal's entire effect is an absence, and an absence is
 * unfalsifiable without one.
 *
 * ### Measured coverage
 *
 * Each clause is red under its own deletion and **green under its sibling's**, which is what makes
 * these two clause pins rather than one coarser bound pin (`:kuilt-raft:jvmTest --rerun-tasks
 * --no-build-cache`, 505 tests, `compileKotlinJvm` EXECUTED on both runs):
 *
 * | | `lastIncludedIndex < 0L` deleted | `lastIncludedTerm < 0L` deleted |
 * |---|---|---|
 * | [aNegativeLastIncludedIndexIsDroppedBeforeEverySideEffect] | 🔴 | 🟢 |
 * | [aNegativeLastIncludedTermIsDroppedBeforeEverySideEffect] | 🟢 | 🔴 |
 * | rest of the module | 2 🔴 (`FrameRefusedTest`, above) | 0 🔴 |
 *
 * Both reds are the **whole** side-effect set, not one surface — 9 of the 15 assertions, reporting
 * that the victim answered the forger with an `InstallSnapshotResponse`, raised its durable term
 * from 1 to 2, adopted `v3` (a follower) as leader in place of `v1`, and restarted its election
 * timer. The positive control fails alongside them, for a reason worth naming: having adopted the
 * forged frame's term, the victim then reads the *control* frame as stale. That is a consequence of
 * the mutation, not an independent signal — the control's job is to keep the green case honest.
 */
internal class SnapshotChunkSignClausesTest {

    /** Mirrors the engine's private `MAX_PLAUSIBLE_INDEX` (`2^60`), so a fixture can prove the upper disjunct inert. */
    private val maxPlausibleIndex = 1L shl 60

    /** Mirrors the engine's private `MAX_PLAUSIBLE_TERM` (`2^60`) — the other half of `minOf(m.term, …)`. */
    private val maxPlausibleTerm = 1L shl 60

    /**
     * A negative `lastIncludedIndex`, alone.
     *
     * `-1` is below `0` and so below `MAX_PLAUSIBLE_INDEX` too: the upper disjunct is provably inert
     * and only the sign disjunct can refuse this frame. It is also below the victim's commit
     * frontier, which keeps `committedTermFloorRefusal` — the guard that absorbs this value once the
     * clause is gone — exempt rather than refusing on the clause's behalf.
     *
     * Delete the clause and the frame is *processed*: the victim adopts `victimTerm + 1`, installs a
     * peer that is not its leader as this term's leader, restarts its election timer, and answers the
     * forger with the `InstallSnapshotResponse` of `finalizeInstalledSnapshot`'s behind-commit ack.
     */
    @Test
    fun aNegativeLastIncludedIndexIsDroppedBeforeEverySideEffect() = raftRunTest {
        val config = slowElectionConfig()
        val sim = raftSim(this, backgroundScope, n = 3, config = config)
        val leaderId = sim.idOf(awaitLeader(sim))
        val victimId = sim.nodeIds.first { it != leaderId }
        val injectorId = sim.nodeIds.first { it != leaderId && it != victimId }
        sim.awaitCommit(1L)

        val armed = isolateAndArm(sim, leaderId, victimId)
        val frameTerm = armed.before.term + 1L
        val forgedIndex = -1L
        val forgedTerm = armed.before.term

        assertAll(
            {
                assertTrue(
                    forgedIndex < 0L,
                    "premise: the sign disjunct must be able to fire — lastIncludedIndex=$forgedIndex",
                )
            },
            {
                assertTrue(
                    forgedIndex <= maxPlausibleIndex,
                    "premise: the MAX_PLAUSIBLE_INDEX disjunct must be inert — the bound is " +
                        "`> $maxPlausibleIndex` and lastIncludedIndex=$forgedIndex is not",
                )
            },
            {
                assertTrue(
                    forgedTerm in 0L..minOf(frameTerm, maxPlausibleTerm),
                    "premise: bound 2 must be inert on the mutation — lastIncludedTerm=$forgedTerm must be " +
                        "inside 0..min(term=$frameTerm, $maxPlausibleTerm), or it would refuse the frame in " +
                        "bound 1's place and hide the deletion",
                )
            },
            {
                assertTrue(
                    forgedIndex <= armed.before.commit,
                    "premise: the committed-term floor must be inert on the mutation — it exempts " +
                        "lastIncludedIndex=$forgedIndex <= commitIndex=${armed.before.commit}, so it cannot " +
                        "be what drops the frame once the sign clause is gone",
                )
            },
            *sharedPremises(armed, frameTerm, config.maxTermJump, leaderId, injectorId),
        )

        val probe = sim.injectAndProbe(armed, leaderId, victimId, injectorId, frameTerm, forgedIndex, forgedTerm)
        probe.assertRefusedWithoutSideEffect(RefusalGate.InstallSnapshotIndexOutOfRange, injectorId)
    }

    /**
     * A negative `lastIncludedTerm`, alone — at a **behind-commit** index, which is what makes the
     * four side-effect assertions load-bearing rather than vacuous (see the class KDoc).
     *
     * `-1` is below `0` and so below `min(m.term, MAX_PLAUSIBLE_TERM)` for every admissible `m.term`:
     * the ceiling disjunct is provably inert. `lastIncludedIndex` sits inside bound 1 (evaluated
     * first, and it returns early) and at or below the commit frontier, so neither bound 1 nor
     * `committedTermFloorRefusal` can refuse this frame in the sign clause's place.
     */
    @Test
    fun aNegativeLastIncludedTermIsDroppedBeforeEverySideEffect() = raftRunTest {
        val config = slowElectionConfig()
        val sim = raftSim(this, backgroundScope, n = 3, config = config)
        val leaderId = sim.idOf(awaitLeader(sim))
        val victimId = sim.nodeIds.first { it != leaderId }
        val injectorId = sim.nodeIds.first { it != leaderId && it != victimId }
        sim.awaitCommit(1L)

        val armed = isolateAndArm(sim, leaderId, victimId)
        val frameTerm = armed.before.term + 1L
        val forgedIndex = armed.before.commit
        val forgedTerm = -1L

        assertAll(
            {
                assertTrue(
                    forgedTerm < 0L,
                    "premise: the sign disjunct must be able to fire — lastIncludedTerm=$forgedTerm",
                )
            },
            {
                assertTrue(
                    forgedTerm <= minOf(frameTerm, maxPlausibleTerm),
                    "premise: the ceiling disjunct must be inert — the bound is " +
                        "`> min(term=$frameTerm, $maxPlausibleTerm)` and lastIncludedTerm=$forgedTerm is not",
                )
            },
            {
                assertTrue(
                    forgedIndex in 0L..maxPlausibleIndex,
                    "premise: bound 1 must be inert — it is evaluated first and returns early, so " +
                        "lastIncludedIndex=$forgedIndex must be inside 0..$maxPlausibleIndex",
                )
            },
            {
                assertTrue(
                    forgedIndex <= armed.before.commit,
                    "premise: the committed-term floor must be inert on the mutation — its own guard exempts " +
                        "lastIncludedIndex=$forgedIndex <= commitIndex=${armed.before.commit}. Above the " +
                        "frontier the floor refuses a negative term itself (floor >= 0 > -1) and every " +
                        "assertion below would pass with the clause deleted",
                )
            },
            *sharedPremises(armed, frameTerm, config.maxTermJump, leaderId, injectorId),
        )

        val probe = sim.injectAndProbe(armed, leaderId, victimId, injectorId, frameTerm, forgedIndex, forgedTerm)
        probe.assertRefusedWithoutSideEffect(RefusalGate.InstallSnapshotTermOutOfRange, injectorId)
    }

    /**
     * The premises both frames share: the injected term must be adoptable-but-new, and its sender
     * must not already be the leader — otherwise §5.1 term adoption and `_leader.value = from` are
     * no-ops and two of the four assertions measure nothing.
     */
    private fun sharedPremises(
        armed: Armed,
        frameTerm: Long,
        maxTermJump: Long,
        leaderId: NodeId,
        injectorId: NodeId,
    ): Array<() -> Unit> = arrayOf(
        {
            assertTrue(
                frameTerm > armed.before.term,
                "premise: §5.1 term adoption must be observable — the frame's term $frameTerm must be " +
                    "above the victim's ${armed.before.term}",
            )
        },
        {
            assertTrue(
                frameTerm - armed.before.term <= maxTermJump,
                "premise: the dispatch boundary's term-jump bound (#1897) must be inert",
            )
        },
        {
            assertEquals(
                leaderId, armed.before.leader,
                "premise: the victim must already recognise the real leader, so adopting the injector " +
                    "would be a visible change",
            )
        },
        {
            assertTrue(
                injectorId != leaderId,
                "premise: the injector must not be the leader the victim already recognises, or " +
                    "`_leader.value = from` is a no-op and the leader-adoption assertion is vacuous",
            )
        },
    )

    // ── The injection, in two phases ─────────────────────────────────────────

    /** Every state surface a *processed* `InstallSnapshot` would move, read in one pass. */
    private data class Surfaces(
        val term: Long,
        val leader: NodeId?,
        val log: List<Long>,
        val floor: Long,
        val commit: Long,
        val storedSnapshot: SnapshotMeta?,
    )

    /** A victim isolated with its election timer re-armed at a known instant, plus its trace tap. */
    private class Armed(val events: MutableList<RaftTraceEvent>, val before: Surfaces)

    /**
     * Isolate [victimId], re-arm its election timer at *this* virtual instant, and read the
     * before-state.
     *
     * The re-arm is what makes "did the malformed frame reset the timer?" answerable at all: without
     * it the deadline sits somewhere unknown in `[now, now + electionTimeoutMax)`. A leader-authored
     * heartbeat resets it — `resetElectionTimeout()` runs *before* `onAppendEntries`' log-consistency
     * check — so the empty origin heartbeat re-arms the timer and touches nothing else.
     */
    private suspend fun TestScope.isolateAndArm(sim: RaftSimulation, leaderId: NodeId, victimId: NodeId): Armed {
        sim.partitionOff(victimId)
        val events = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(victimId).trace.collect { events += it } }
        sim.settle()

        sim.deliverAppendEntries(to = victimId, from = leaderId, term = sim.storages.getValue(victimId).term())
        sim.settle()
        events.clear()
        sim.network.sent.clear()
        sim.network.recording = true
        return Armed(events, sim.surfacesOf(victimId))
    }

    /**
     * Inject the malformed chunk [INJECT_AFTER] into the victim's election window, read every
     * surface, carry the clock past the original deadline, then run the positive control.
     *
     * The frame's `term` is one above the victim's and its sender is not the leader, so a processed
     * frame changes both the durable term and the recognised leader. Nothing else is delivered to
     * the victim in between: it is partitioned off, and `deliverInstallSnapshot` bypasses only the
     * drop filter, not the engine.
     */
    @Suppress("LongParameterList") // one injected frame's worth of fixture, named at every call site
    private suspend fun RaftSimulation.injectAndProbe(
        armed: Armed,
        leaderId: NodeId,
        victimId: NodeId,
        injectorId: NodeId,
        frameTerm: Long,
        forgedIndex: Long,
        forgedTerm: Long,
    ): Probe {
        delay(INJECT_AFTER)
        deliverInstallSnapshot(
            to = victimId, from = injectorId, term = frameTerm,
            lastIncludedIndex = forgedIndex, lastIncludedTerm = forgedTerm,
        )
        settle()

        val refusals = armed.events.filterIsInstance<RaftTraceEvent.FrameRefused>().filter { it.node == victimId }
        val snapshotResponses = network.sent.filter {
            it.from == victimId && it.message is RaftMessage.InstallSnapshotResponse
        }
        network.recording = false
        val after = surfacesOf(victimId)
        val electionsBeforeInjection = armed.events.count { it is RaftTraceEvent.PreVoteStarted }

        // Past the deadline the injected frame must NOT have moved: the timer was re-armed
        // INJECT_AFTER + PAST_THE_DEADLINE ago and every draw is below ELECTION_MAX, so a timer left
        // alone has fired — while one the frame reset is still most of a window away.
        delay(PAST_THE_DEADLINE)
        val electionsPastTheDeadline = armed.events.count { it is RaftTraceEvent.PreVoteStarted }

        // The positive control, over the identical injection path at the same node: well-formed, from
        // the leader the victim recognises, at a boundary above its commit frontier.
        val controlIndex = armed.before.commit + JUMP_AHEAD
        deliverInstallSnapshot(
            to = victimId, from = leaderId, term = armed.before.term,
            lastIncludedIndex = controlIndex, lastIncludedTerm = armed.before.term,
        )
        settle()

        return Probe(
            refusals = refusals,
            snapshotResponses = snapshotResponses,
            before = armed.before,
            after = after,
            electionsBeforeInjection = electionsBeforeInjection,
            electionsPastTheDeadline = electionsPastTheDeadline,
            controlIndex = controlIndex,
            controlFloor = nodes.getValue(victimId).compactionFloor.value,
        )
    }

    private suspend fun RaftSimulation.surfacesOf(id: NodeId): Surfaces {
        val node = nodes.getValue(id)
        val storage = storages.getValue(id)
        return Surfaces(
            term = storage.term(),
            leader = node.leader.value,
            log = storage.entries(1L).map { it.index },
            floor = node.compactionFloor.value,
            commit = node.commitIndex.value,
            storedSnapshot = storage.loadSnapshot()?.meta,
        )
    }

    /** Everything the injection produced (or did not), so one `assertAll` reports the whole picture. */
    private class Probe(
        val refusals: List<RaftTraceEvent.FrameRefused>,
        val snapshotResponses: List<InMemoryRaftNetwork.Sent>,
        val before: Surfaces,
        val after: Surfaces,
        val electionsBeforeInjection: Int,
        val electionsPastTheDeadline: Int,
        val controlIndex: Long,
        val controlFloor: Long,
    ) {

        /**
         * The whole obligation: the frame was refused at [gate] and produced **no** observable effect
         * — no reply, no term, no leader, no restarted election timer, and no state.
         *
         * Every surface is asserted inside one [assertAll], deliberately including the refusal count.
         * On the mutation this class exists to catch the frame is *admitted* and there is no refusal
         * at all; a count assertion that threw first would report "expected 1, was 0" and nothing
         * about what the admitted frame then did — which is the evidence a reader wants.
         */
        fun assertRefusedWithoutSideEffect(gate: RefusalGate, injectorId: NodeId) = assertAll(
            { assertEquals(1, refusals.size, "exactly one refusal at the victim; all refusals were $refusals") },
            {
                assertEquals(
                    gate, refusals.singleOrNull()?.gate,
                    "the frame-shape bound must be what refuses this frame",
                )
            },
            { assertEquals(injectorId, refusals.singleOrNull()?.from) },
            { assertEquals(RaftMessageType.InstallSnapshot, refusals.singleOrNull()?.messageType) },
            {
                assertEquals(
                    emptyList(), snapshotResponses,
                    "a malformed chunk is DROPPED, not acked — a reply hands its author a lever on the " +
                        "sender's SnapshotSender transfer state",
                )
            },
            {
                assertEquals(
                    before.term, after.term,
                    "refused BEFORE §5.1 term adoption — the guard runs ahead of stepDown, so a malformed " +
                        "frame must not raise the victim's durable term",
                )
            },
            {
                assertEquals(
                    before.leader, after.leader,
                    "refused BEFORE leader adoption — a malformed frame must not install its author as " +
                        "this term's leader",
                )
            },
            {
                assertEquals(
                    0, electionsBeforeInjection,
                    "premise: the victim's election timer must still be pending when the frame lands, or " +
                        "the deadline assertion below measures nothing",
                )
            },
            {
                assertTrue(
                    electionsPastTheDeadline > 0,
                    "refused BEFORE resetElectionTimeout() — the victim's timer was re-armed " +
                        "${INJECT_AFTER + PAST_THE_DEADLINE} ago and every draw is below $ELECTION_MAX, so " +
                        "it must have fired on its ORIGINAL deadline; a frame that reset it holds this " +
                        "node's election open for another whole window",
                )
            },
            { assertEquals(before.log, after.log, "the durable log must be untouched") },
            { assertEquals(before.floor, after.floor, "the compaction floor must not move") },
            { assertEquals(before.commit, after.commit, "commitIndex must not be fabricated from the frame") },
            { assertNull(after.storedSnapshot, "nothing may be persisted; stored=${after.storedSnapshot}") },
            {
                assertEquals(
                    controlIndex, controlFloor,
                    "positive control: a well-formed snapshot over the same path at the same node must " +
                        "still install, or every assertion above is vacuous",
                )
            },
        )
    }

    // ── Local helpers ────────────────────────────────────────────────────────

    private fun RaftSimulation.idOf(node: RaftNode): NodeId = nodeIds.first { nodes[it] === node }

    /**
     * Slow, wide election timings, so an election deadline can be *placed*: the frame lands
     * [INJECT_AFTER] into a window at least [ELECTION_MIN] long, and the assertion runs
     * [PAST_THE_DEADLINE] later — past [ELECTION_MAX]. [fastRaftConfig]'s 5..10 ms window leaves no
     * room to sit inside one window and still resolve the difference.
     *
     * A function, not a `val`, and the [Random] is minted per call: the #1952 rule — every simulation
     * must start from the same position in the stream, whatever ran before it. The window stays a
     * *range* (not a pinned pair) so the nodes still symmetry-break into a leader.
     */
    private fun slowElectionConfig(): RaftConfig = RaftConfig(
        electionTimeoutMin = ELECTION_MIN,
        electionTimeoutMax = ELECTION_MAX,
        heartbeatInterval = HEARTBEAT,
        expectVirtualTime = true,
        random = Random(RAFT_TEST_SEED),
    )

    private companion object {
        val ELECTION_MIN = 100.milliseconds
        val ELECTION_MAX = 110.milliseconds
        val HEARTBEAT = 20.milliseconds

        /** Where in the window the malformed frame lands — below [ELECTION_MIN], so no timer has fired yet. */
        val INJECT_AFTER = 50.milliseconds

        /** Carries the clock past [ELECTION_MAX] from the re-arm, so an untouched timer has certainly fired. */
        val PAST_THE_DEADLINE = 65.milliseconds

        /** A snapshot boundary comfortably above the recipient's commit frontier, for the positive control. */
        const val JUMP_AHEAD = 50L
    }
}
