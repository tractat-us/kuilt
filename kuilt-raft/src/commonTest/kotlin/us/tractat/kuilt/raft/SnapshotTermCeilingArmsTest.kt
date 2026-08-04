@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package us.tractat.kuilt.raft

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `RaftEngine.snapshotChunkRefusal`'s term bound is a **conjunction of two independent ceilings**,
 * and this class pins them one at a time (#2053).
 *
 * ```kotlin
 * val termCeiling = minOf(m.term, MAX_PLAUSIBLE_TERM)
 * if (m.lastIncludedTerm < 0L || m.lastIncludedTerm > termCeiling) { … }
 * ```
 *
 * `minOf` is two separately-deletable clauses, and a suite can pin the bound *as a whole* while
 * pinning **neither arm** — which is what #1980's mutation survey measured. Every forged-term test in
 * `InstallSnapshotMetaValidationTest` uses `lastIncludedTerm = Long.MAX_VALUE`, a value above *both*
 * ceilings, so either arm alone still refuses the frame and deleting either one leaves the class
 * green: textbook mutual shadowing.
 *
 * ### Why attribution cannot do this job, and the fixture has to
 *
 * Both arms live behind one `return RefusalGate.InstallSnapshotTermOutOfRange`, so the
 * [RaftTraceEvent.FrameRefused] mechanism #2055 added is **structurally unable** to say which of them
 * fired — the event is byte-identical either way. Do not reach for it here; that is the one thing it
 * cannot resolve.
 *
 * The isolation is therefore a property of the **fixture value**, and each test asserts its own
 * isolating premise before injecting, so an edit that retunes a literal off its arm reddens the test
 * instead of silently converting it back into a disjunction pin:
 *
 * | Test | `lastIncludedTerm` | Arm that can fire | Arm proven inert |
 * |---|---|---|---|
 * | [aTermAboveTheFrameTermButInsideTheAbsoluteCeilingIsRefused] | `MAX_PLAUSIBLE_TERM`, with `m.term` small | `m.term` | `MAX_PLAUSIBLE_TERM` — the value is *on* the ceiling, and the test is `>` |
 * | [aTermAboveTheAbsoluteCeilingButAtTheFrameTermIsRefused] | `MAX_PLAUSIBLE_TERM + 1 == m.term` | `MAX_PLAUSIBLE_TERM` | `m.term` — the value equals it |
 *
 * Given that, the refusal *is* observable through the gate: only one arm can fire, so a
 * `FrameRefused(InstallSnapshotTermOutOfRange)` names it unambiguously *for this frame*. Each test
 * asserts the gate **and** the four state surfaces `InstallSnapshotMetaValidationTest` uses (durable
 * log, compaction floor, commit index, stored snapshot), **and** carries an in-test positive control
 * — a well-formed snapshot at a higher index over the identical injection path, which must install.
 * A refusal's whole effect is an *absence*, and an absence is unfalsifiable without a control.
 *
 * ### What each arm is worth
 *
 * The `m.term` arm is the sharp one. `MAX_PLAUSIBLE_TERM` is `2^60`, so a chunk carrying
 * `term = <small>, lastIncludedTerm = 2^60` sits *exactly on* the absolute ceiling: drop the `m.term`
 * arm and it installs, `state.snapshotTerm` becomes `2^60`, [RaftState.lastLogTerm] falls back to it
 * over the emptied log, and the victim's [RaftState.lastLogPosition] dominates every honest node's —
 * **#1868's §5.4.1 attack restored at `2^60` instead of `Long.MAX_VALUE`**, and persisted, so it
 * survives restart. The realistic trigger is not an attacker but a refactor: collapsing
 * `minOf(m.term, MAX_PLAUSIBLE_TERM)` to the named constant reads as a tidy-up and reopens a closed
 * issue with a green suite.
 *
 * The `MAX_PLAUSIBLE_TERM` arm bites only on a frame whose *own* term is above `2^60`, which needs a
 * recipient already near the ceiling — since #1897 the dispatch boundary bounds the term *jump*, not
 * the absolute term. That is reachable rather than hypothetical, and
 * [aTermAboveTheAbsoluteCeilingButAtTheFrameTermIsRefused] constructs it: a node restored at exactly
 * `MAX_PLAUSIBLE_TERM` (which `TermRestoreBoundTest.durableTermExactlyAtTheCeiling_stillStarts` pins
 * as admissible) is one `maxTermJump` step away from admitting `term = 2^60 + 1`.
 *
 * ### Measured coverage at the time of writing
 *
 * Before these tests: deleting the `MAX_PLAUSIBLE_TERM` arm (`termCeiling = m.term`) left the whole
 * `:kuilt-raft` module green — 486/486, `Math.min` absent from the compiled
 * `snapshotChunkRefusal-HfnLOkc` by `javap` — so that arm was pinned by nothing. Deleting the `m.term`
 * arm (`termCeiling = MAX_PLAUSIBLE_TERM`) reddened two `FrameRefusedTest` cases, whose fixtures
 * happen to sit in the isolating range. That coverage is **incidental**: those tests measure
 * *attribution*, their KDoc does not mention the isolation, and moving `term + 1` to `Long.MAX_VALUE`
 * — which is what every other forged-term fixture uses — would drop it silently.
 *
 * With them, each arm is red under its own deletion and **green under the sibling's**, which is what
 * distinguishes two arm-specific pins from one more disjunction pin:
 *
 * | | `termCeiling = MAX_PLAUSIBLE_TERM` | `termCeiling = m.term` |
 * |---|---|---|
 * | [aTermAboveTheFrameTermButInsideTheAbsoluteCeilingIsRefused] | 🔴 | 🟢 |
 * | [aTermAboveTheAbsoluteCeilingButAtTheFrameTermIsRefused] | 🟢 | 🔴 |
 *
 * Both reds are the frame **installing**, not merely going unrefused: `snapshotTerm` is persisted at
 * `2^60` and `2^60 + 1` respectively, over a wiped log, with `commitIndex` fabricated from the frame.
 * The second carries a consequence past §5.4.1 — adopting `m.term` writes a durable term of
 * `2^60 + 1`, which is **above the storage ceiling `checkedRestoredTerm` enforces**, so the node
 * refuses to start on its next boot.
 *
 * Every test holds the target still — partitioned off, and only [RaftSimulation.settle]ing after each
 * injection, never advancing virtual time — so no election timer fires and the injected frames are
 * the only thing the node reacts to.
 */
internal class SnapshotTermCeilingArmsTest {

    /**
     * Mirrors the engine's private `MAX_PLAUSIBLE_TERM` (`2^60`). The bound is **inclusive** — the
     * engine tests `> MAX_PLAUSIBLE_TERM` — which is what makes this exact value the sharpest probe
     * for the `m.term` arm: it is the largest `lastIncludedTerm` the absolute ceiling admits, so the
     * ceiling arm is inert on it by construction rather than by luck.
     */
    private val maxPlausibleTerm = 1L shl 60

    /** Mirrors the engine's private `MAX_PLAUSIBLE_INDEX` (`2^60`), so a fixture can prove bound 1 inert. */
    private val maxPlausibleIndex = 1L shl 60

    /**
     * The `m.term` arm, alone.
     *
     * `lastIncludedTerm = MAX_PLAUSIBLE_TERM` is above the frame's own term and **at** the absolute
     * ceiling, so `> MAX_PLAUSIBLE_TERM` is false and that arm provably cannot be what refuses this
     * frame. Replace the bound with the named constant — the tidy-up refactor — and this frame
     * installs a `snapshotTerm` of `2^60` over a wiped log.
     */
    @Test
    fun aTermAboveTheFrameTermButInsideTheAbsoluteCeilingIsRefused() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderId = sim.idOf(awaitLeader(sim))
        val victimId = sim.nodeIds.first { it != leaderId }
        val victim = sim.nodes.getValue(victimId)
        sim.awaitCommit(1L)   // the §5.4.2 no-op is replicated everywhere — the cluster is converged

        sim.partitionOff(victimId)
        val victimTerm = sim.storages.getValue(victimId).term()
        val commitBefore = victim.commitIndex.value
        val floorBefore = victim.compactionFloor.value
        val logBefore = sim.storages.getValue(victimId).entries(1L).map { it.index }
        val forgedIndex = commitBefore + JUMP_AHEAD
        val controlIndex = forgedIndex + JUMP_AHEAD
        val forgedTerm = maxPlausibleTerm

        // The isolating premise. If a later edit retunes `forgedTerm` off this arm — `Long.MAX_VALUE`
        // reads just as natural, and is what every other forged-term fixture in the suite uses — this
        // test stops measuring the `m.term` arm, and must say so rather than quietly becoming a
        // disjunction pin again.
        assertAll(
            {
                assertTrue(
                    forgedTerm > victimTerm,
                    "premise: the m.term arm must be able to fire — lastIncludedTerm=$forgedTerm " +
                        "must exceed the frame's own term $victimTerm",
                )
            },
            {
                assertTrue(
                    forgedTerm <= maxPlausibleTerm,
                    "premise: the MAX_PLAUSIBLE_TERM arm must be inert — the bound is `> MAX_PLAUSIBLE_TERM`, " +
                        "so lastIncludedTerm=$forgedTerm must be at or below $maxPlausibleTerm",
                )
            },
            {
                assertTrue(
                    controlIndex in 0L..maxPlausibleIndex,
                    "premise: both indices must be inside bound 1, which is evaluated first and returns " +
                        "early — controlIndex=$controlIndex",
                )
            },
        )

        val refusals = collectRefusals(sim)
        sim.settle()
        refusals.clear()

        // `term` is the victim's OWN current term, so the stale-term reply, the §5.2 leader-authority
        // gate and the dispatch boundary's two term arms all pass it: nothing but this bound stands
        // between the frame and the install.
        sim.deliverInstallSnapshot(
            to = victimId, from = leaderId, term = victimTerm,
            lastIncludedIndex = forgedIndex, lastIncludedTerm = forgedTerm,
        )
        sim.settle()

        // Read every surface before the control frame moves any of them.
        val floorAfterForgery = victim.compactionFloor.value
        val commitAfterForgery = victim.commitIndex.value
        val logAfterForgery = sim.storages.getValue(victimId).entries(1L).map { it.index }
        val snapshotAfterForgery = sim.storages.getValue(victimId).loadSnapshot()?.meta

        // The positive control, over the identical injection path at the same node: a refusal's whole
        // effect is an absence, and an absence proves nothing unless something admissible gets through.
        sim.deliverInstallSnapshot(
            to = victimId, from = leaderId, term = victimTerm,
            lastIncludedIndex = controlIndex, lastIncludedTerm = victimTerm,
        )
        sim.settle()
        val controlFloor = victim.compactionFloor.value

        val hits = refusals.at(victimId)
        assertAll(
            { assertEquals(1, hits.size, "exactly one refusal at $victimId; all refusals were $refusals") },
            {
                assertEquals(
                    RefusalGate.InstallSnapshotTermOutOfRange, hits.singleOrNull()?.gate,
                    "only the m.term arm can refuse this frame, so the gate names it unambiguously here",
                )
            },
            { assertEquals(leaderId, hits.singleOrNull()?.from) },
            { assertEquals(RaftMessageType.InstallSnapshot, hits.singleOrNull()?.messageType) },
            { assertEquals(logBefore, logAfterForgery, "a term above the frame's own must not wipe the durable log") },
            { assertEquals(floorBefore, floorAfterForgery, "the compaction floor must not jump to the forged boundary") },
            { assertEquals(commitBefore, commitAfterForgery, "commitIndex must not be fabricated from the forged frame") },
            {
                assertNull(
                    snapshotAfterForgery,
                    "a snapshotTerm of 2^60 is PERSISTED once installed, so the §5.4.1 domination would " +
                        "survive restart; stored=$snapshotAfterForgery",
                )
            },
            {
                assertEquals(
                    controlIndex, controlFloor,
                    "positive control: a well-formed snapshot over the same path at the same node must " +
                        "still install, or the assertions above are vacuous",
                )
            },
        )
    }

    /**
     * The `MAX_PLAUSIBLE_TERM` arm, alone — and the arm that no test reached before this one.
     *
     * Isolating it needs `lastIncludedTerm > MAX_PLAUSIBLE_TERM` while `lastIncludedTerm <= m.term`,
     * hence a frame whose own term is above `2^60`. That is not reachable at an ordinary recipient:
     * since #1897 the dispatch boundary refuses a term more than `RaftConfig.maxTermJump` above
     * *ours*, so `m.term` is bounded relative to the victim, not absolutely. **It is reachable at a
     * recipient already at the ceiling**, and a node restored at exactly `MAX_PLAUSIBLE_TERM` is
     * admissible by design — `TermRestoreBoundTest.durableTermExactlyAtTheCeiling_stillStarts` pins
     * that the restore bound is inclusive so the wire bound and the storage bound agree. One
     * `maxTermJump` step above it is `2^60 + 1`, and this arm is the only thing bounding the snapshot
     * metadata of a frame at that term.
     *
     * So the arm is **constructible, not merely theoretical** — it just needs a recipient the rest of
     * the suite never builds. The node is reached by crash/seed/restart rather than by climbing terms;
     * an honest climb would take `2^60 / maxTermJump` frames, which is the whole reason the survey read
     * this arm as unreachable.
     *
     * The frame's term equals its `lastIncludedTerm`, which is the equality
     * `wellFormedSnapshotOverTheSameInjectionPathIsStillInstalled` asserts must be *accepted* at an
     * ordinary term — so the `m.term` arm is inert here by equality, not by a margin.
     */
    @Test
    fun aTermAboveTheAbsoluteCeilingButAtTheFrameTermIsRefused() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderId = sim.idOf(awaitLeader(sim))
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        // Isolate the victim before touching its storage, so nothing repairs or masks the seeded term.
        sim.partitionOff(victimId)
        sim.crash(victimId)
        sim.storages.getValue(victimId).saveTermAndVotedFor(maxPlausibleTerm, null)
        sim.restart(victimId)
        sim.settle()

        val victim = sim.nodes.getValue(victimId)
        val restoredTerm = sim.storages.getValue(victimId).term()
        val commitBefore = victim.commitIndex.value
        val floorBefore = victim.compactionFloor.value
        val logBefore = sim.storages.getValue(victimId).entries(1L).map { it.index }
        val frameTerm = maxPlausibleTerm + 1L
        val forgedTerm = frameTerm
        val forgedIndex = commitBefore + JUMP_AHEAD
        val controlIndex = forgedIndex + JUMP_AHEAD

        assertAll(
            {
                assertEquals(
                    maxPlausibleTerm, restoredTerm,
                    "premise: the victim must actually have restored at the ceiling — the whole fixture is " +
                        "that a frame above 2^60 can clear the relative term-jump bound at this node",
                )
            },
            {
                assertTrue(
                    forgedTerm > maxPlausibleTerm,
                    "premise: the MAX_PLAUSIBLE_TERM arm must be able to fire — lastIncludedTerm=$forgedTerm " +
                        "must exceed $maxPlausibleTerm",
                )
            },
            {
                assertTrue(
                    forgedTerm <= frameTerm,
                    "premise: the m.term arm must be inert — lastIncludedTerm=$forgedTerm must be at or " +
                        "below the frame's own term $frameTerm, the equality the well-formed control " +
                        "asserts must be accepted",
                )
            },
            {
                assertTrue(
                    controlIndex in 0L..maxPlausibleIndex,
                    "premise: both indices must be inside bound 1, evaluated first — controlIndex=$controlIndex",
                )
            },
        )

        val refusals = collectRefusals(sim)
        sim.settle()
        refusals.clear()

        sim.deliverInstallSnapshot(
            to = victimId, from = leaderId, term = frameTerm,
            lastIncludedIndex = forgedIndex, lastIncludedTerm = forgedTerm,
        )
        sim.settle()

        val floorAfterForgery = victim.compactionFloor.value
        val commitAfterForgery = victim.commitIndex.value
        val logAfterForgery = sim.storages.getValue(victimId).entries(1L).map { it.index }
        val snapshotAfterForgery = sim.storages.getValue(victimId).loadSnapshot()?.meta
        val termAfterForgery = sim.storages.getValue(victimId).term()

        // The control sits exactly ON the absolute ceiling, at the victim's own restored term: the
        // largest metadata this bound admits. It proves the node is live and the bound is inclusive.
        sim.deliverInstallSnapshot(
            to = victimId, from = leaderId, term = maxPlausibleTerm,
            lastIncludedIndex = controlIndex, lastIncludedTerm = maxPlausibleTerm,
        )
        sim.settle()
        val controlFloor = victim.compactionFloor.value

        val hits = refusals.at(victimId)
        assertAll(
            { assertEquals(1, hits.size, "exactly one refusal at $victimId; all refusals were $refusals") },
            {
                assertEquals(
                    RefusalGate.InstallSnapshotTermOutOfRange, hits.singleOrNull()?.gate,
                    "only the MAX_PLAUSIBLE_TERM arm can refuse this frame — lastIncludedTerm == m.term",
                )
            },
            { assertEquals(leaderId, hits.singleOrNull()?.from) },
            { assertEquals(RaftMessageType.InstallSnapshot, hits.singleOrNull()?.messageType) },
            { assertEquals(logBefore, logAfterForgery, "a term above the absolute ceiling must not wipe the log") },
            { assertEquals(floorBefore, floorAfterForgery, "the compaction floor must not jump") },
            { assertEquals(commitBefore, commitAfterForgery, "commitIndex must not be fabricated") },
            { assertNull(snapshotAfterForgery, "nothing may be persisted; stored=$snapshotAfterForgery") },
            {
                assertEquals(
                    maxPlausibleTerm, termAfterForgery,
                    "refused BEFORE §5.1 term adoption — a frame this bound rejects must not drag the " +
                        "durable term past the ceiling on its way to the floor",
                )
            },
            {
                assertEquals(
                    controlIndex, controlFloor,
                    "positive control: lastIncludedTerm exactly AT MAX_PLAUSIBLE_TERM is inside the " +
                        "inclusive bound and must still install",
                )
            },
        )
    }

    // ── Local copies of FrameRefusedTest's trace helpers ─────────────────────
    // Duplicated rather than shared, as RestoredLeaderPinBoundTest already does: the helpers are
    // three lines each and hoisting them into RaftSimulation would put a trace-collection policy in
    // the harness that only these suites want.

    /** Every node's [RaftTraceEvent.FrameRefused] events, in arrival order, into one list. */
    private fun TestScope.collectRefusals(sim: RaftSimulation): MutableList<RaftTraceEvent.FrameRefused> {
        val out = mutableListOf<RaftTraceEvent.FrameRefused>()
        sim.nodes.values.forEach { node ->
            backgroundScope.launch {
                node.trace.collect { if (it is RaftTraceEvent.FrameRefused) out += it }
            }
        }
        return out
    }

    /**
     * The refusals recorded at [node].
     *
     * Deliberately **not** `FrameRefusedTest.only`, which asserts the count and returns the single
     * hit. That assertion has to run before the caller's `assertAll`, so on the mutation this class
     * exists to catch — where the frame is *admitted* and there is no refusal at all — it throws first
     * and the state assertions never execute. The failure then says "expected 1, was 0" and nothing
     * about whether the forged snapshot installed, which is the evidence a reader wants. Returning the
     * list lets the count assertion sit *inside* `assertAll` alongside the four state surfaces, so a
     * red reports the whole picture at once.
     */
    private fun List<RaftTraceEvent.FrameRefused>.at(node: NodeId): List<RaftTraceEvent.FrameRefused> =
        filter { it.node == node }

    private fun RaftSimulation.idOf(node: RaftNode): NodeId = nodeIds.first { nodes[it] === node }

    private companion object {
        /**
         * A snapshot boundary comfortably above the recipient's commit frontier, so
         * `committedTermFloorRefusal`'s behind-commit exemption cannot be what admits a frame, and its
         * floor cannot be what refuses one — every `lastIncludedTerm` here is far above any term the
         * recipient has committed.
         */
        const val JUMP_AHEAD = 50L
    }
}
