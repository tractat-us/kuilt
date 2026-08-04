@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.launch
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The **forward-only commit clamp** in `RaftEngine.onAppendEntries` — the `maxOf(_, currentCommitIndex)`
 * in `advanceCommit(minOf(m.leaderCommit, maxOf(lastNewIndex, state.currentCommitIndex)))`.
 *
 * `advanceCommit` ends with `state.currentCommitIndex = newCommit`, **unconditionally**. Without the
 * clamp, an AppendEntries whose batch ends *below* the follower's committed prefix while carrying a
 * `leaderCommit` *above* it drives that assignment backwards: a committed prefix un-commits.
 *
 * ## Why the whole module stayed green without it
 *
 * The regression is **silent**. `advanceCommit`'s emit loop is `for (idx in (currentCommitIndex + 1)..newCommit)`,
 * which is *empty* when `newCommit` is lower — nothing is re-emitted, `_commitIndex` (the public
 * [RaftNode.commitIndex]) is left stale at the old value, and only the engine's private
 * `state.currentCommitIndex` moves back. So the guard only ever *withholds*, and deleting it strictly
 * enlarges the set of green trajectories: `:kuilt-raft` was 469/469 green under the deletion (#1980's
 * discrimination audit, verdict C5). One more "…and eventually index N commits" test reproduces that
 * blind spot rather than closing it, and [RaftNode.commitIndex] alone cannot see the regression at all.
 *
 * The pin therefore (a) asserts a **negative at a moment**, and (b) reads the engine's own
 * [RaftTraceEvent.AdvanceCommitIndex] — the one observable that carries `state.currentCommitIndex`
 * itself — with the consumer-visible consequence (a re-emitted committed prefix) as a second,
 * independent kill.
 *
 * ## Reachability: injection-only today, and that is the point
 *
 * `sendAppendEntries` ships `logSliceFrom(state.log, state.snapshotIndex, nextIndex[peer])` — the
 * **entire** suffix, with no entry-count or payload-byte cap (`RaftConfig.snapshotChunkCeiling` /
 * `RaftTransport.maxPayloadBytes` gate only InstallSnapshot chunking). So every honestly minted frame
 * has `prevLogIndex + entries.size == the leader's lastLogIndex`, and its `leaderCommit` is that same
 * leader's `currentCommitIndex`, which never exceeds its own `lastLogIndex`. Hence
 * **`leaderCommit <= prevLogIndex + entries.size` in every honest frame** — and that is a relation
 * between two fields *of the frame*, fixed at mint time, so delay, duplication and reordering cannot
 * break it. The clamp needs the opposite (`leaderCommit > lastNewIndex`), which no honest sender emits.
 *
 * That makes this a defence with no honest trigger *under full-suffix batching*, not a live bug — and
 * it is kept and pinned regardless. An unreachability argument is a smell here (#1965, #1886), the
 * reachability is one `sendAppendEntries` change away (any batch-size or payload cap on AppendEntries
 * produces `leaderCommit > lastNewIndex` immediately), and a malformed or foreign frame reaches it now.
 *
 * ## The trajectory, and why C4 two lines above is inert on it
 *
 * `val lastNewIndex = m.prevLogIndex + m.entries.size` (C4, the #1248/#1249 exact-attestation guard)
 * sits two lines above the clamp, is well pinned by [AppendEntriesApplyPathTest], and that test is
 * blind to the clamp — on its trajectory `maxOf(3, 0) == 3 == lastNewIndex`, so the clamp evaluates to
 * its own input. Per the #2001/#2004 cross-certification discipline this pin runs where C4 is inert:
 *
 * - The **control** frame ends exactly at the follower's `lastLogIndex`, so C4's expression and the
 *   pre-#1248 `state.lastLogIndex` it replaced compute the **same** value. Asserted as a premise.
 * - The **pin** frame asserts *non-regression* (`>=`), not a fixed value. Any reading of `lastNewIndex`
 *   is bounded above by `state.lastLogIndex`, which sits *above* the committed prefix here (asserted as
 *   a premise), so a C4 mutation can only push the clamp's result **forward** — never backwards. The
 *   pin's direction is the clamp's alone.
 *
 * The two probe frames differ in exactly one input — `prevLogIndex`, hence where the batch ends
 * relative to the committed prefix. Same term, same sender, same `leaderCommit`, same virtual-time
 * budget, and both carry a single entry the follower already holds at the same term, so neither
 * mutates the log.
 *
 * Mutation-verified (`--no-build-cache`, `compileKotlinJvm` EXECUTED):
 *
 * | | delete `maxOf(_, currentCommitIndex)` | `lastNewIndex := state.lastLogIndex` |
 * |---|---|---|
 * | this test | **RED** | GREEN |
 * | [AppendEntriesApplyPathTest] | GREEN | **RED** |
 */
class ForwardOnlyCommitClampTest {

    /**
     * Election timeouts far beyond anything this test's virtual clock reaches, so both nodes stay
     * passive followers and the only thing that ever touches the follower's commit index is a frame
     * this test delivered. Seeded off [RAFT_TEST_SEED], minted per test method.
     */
    private val holdConfig = RaftConfig(
        electionTimeoutMin = 30.seconds,
        electionTimeoutMax = 60.seconds,
        heartbeatInterval = 1.seconds,
        expectVirtualTime = true,
        random = Random(RAFT_TEST_SEED),
    )

    @Test
    fun batchEndingBelowTheCommittedPrefix_cannotRegressCommitIndex() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 2, config = holdConfig)
        val (followerId, leaderId) = sim.nodeIds
        val follower = sim.nodes.getValue(followerId)

        // Two observables. `advances` carries `state.currentCommitIndex` itself — the only thing that
        // moves when the clamp is gone. `emitted` catches the consumer-visible consequence: a prefix
        // that un-commits is re-delivered the next time commit advances past it.
        val advances = mutableListOf<RaftTraceEvent.AdvanceCommitIndex>()
        val emitted = mutableListOf<Long>()
        backgroundScope.launch {
            follower.trace.collect { if (it is RaftTraceEvent.AdvanceCommitIndex) advances += it }
        }
        backgroundScope.launch {
            follower.committed.collect { c ->
                when (c) {
                    is Committed.Entry -> emitted += c.entry.index
                    is Committed.Internal -> emitted += c.index
                    is Committed.Install -> Unit
                }
            }
        }
        sim.settle()   // both collectors subscribed before the first frame is delivered

        // Setup — an ordinary full-suffix send: the batch ends at LAST_LOG_INDEX and commits through
        // COMMITTED_PREFIX. `leaderCommit <= lastNewIndex`, i.e. the shape every honest frame has.
        val log = (1L..LAST_LOG_INDEX).map { LogEntry(index = it, term = TERM, command = byteArrayOf(it.toByte())) }
        sim.deliverAppendEntries(
            to = followerId, from = leaderId, term = TERM,
            prevLogIndex = 0L, prevLogTerm = 0L, entries = log, leaderCommit = COMMITTED_PREFIX,
        )
        sim.awaitCommit(COMMITTED_PREFIX, on = listOf(followerId))

        val lastLogIndex = sim.storages.getValue(followerId).entries(1L).last().index
        val commitBefore = follower.commitIndex.value

        // Premises. Each is a property the two probes silently depend on; asserting them is what stops
        // a future change to the setup from turning the pin vacuously green.
        assertAll(
            { assertEquals(COMMITTED_PREFIX, commitBefore, "setup must leave a committed prefix to regress from") },
            { assertEquals(LAST_LOG_INDEX, lastLogIndex, "follower must hold the whole batch") },
            {
                assertTrue(
                    lastLogIndex > commitBefore,
                    "the log must extend ABOVE the committed prefix, so any reading of lastNewIndex " +
                        "(bounded by lastLogIndex $lastLogIndex) can only clamp FORWARD of $commitBefore — " +
                        "this is what makes the pin below insensitive to the C4 expression two lines up",
                )
            },
            {
                assertTrue(
                    PROBE_LEADER_COMMIT > commitBefore,
                    "both probes must enter `if (m.leaderCommit > state.currentCommitIndex)`, or the clamp is never reached",
                )
            },
            {
                assertTrue(
                    PIN_PREV_LOG_INDEX + 1L < commitBefore,
                    "the pin frame's batch must END BELOW the committed prefix (lastNewIndex " +
                        "${PIN_PREV_LOG_INDEX + 1L} < $commitBefore), or the clamp is inert and pins nothing",
                )
            },
            {
                assertEquals(
                    lastLogIndex, CONTROL_PREV_LOG_INDEX + 1L,
                    "C4 inertness premise: the control frame's `prevLogIndex + entries.size` must equal the " +
                        "follower's lastLogIndex, so the pre-#1248 `state.lastLogIndex` reading computes the " +
                        "same value and the control's advance is not attributable to C4",
                )
            },
        )

        // The pin. A batch ending at index PIN_PREV_LOG_INDEX + 1, below the committed prefix, with a
        // leaderCommit above it. The entry is one the follower already holds at the same term, so the
        // log is untouched and commit is the only thing in play.
        val advancesBeforePin = advances.size
        sim.deliverAppendEntries(
            to = followerId, from = leaderId, term = TERM,
            prevLogIndex = PIN_PREV_LOG_INDEX, prevLogTerm = TERM,
            entries = listOf(log[PIN_PREV_LOG_INDEX.toInt()]), leaderCommit = PROBE_LEADER_COMMIT,
        )
        sim.settle()
        val pinAdvance = advances.lastOrNull()

        // The control. Identical path, identical budget, identical leaderCommit — the batch just ends
        // ABOVE the committed prefix instead of below it. Without this, the pin would pass equally well
        // against an engine that ignored the frames altogether.
        sim.deliverAppendEntries(
            to = followerId, from = leaderId, term = TERM,
            prevLogIndex = CONTROL_PREV_LOG_INDEX, prevLogTerm = TERM,
            entries = listOf(log[CONTROL_PREV_LOG_INDEX.toInt()]), leaderCommit = PROBE_LEADER_COMMIT,
        )
        sim.awaitCommit(PROBE_LEADER_COMMIT, on = listOf(followerId))

        assertAll(
            {
                assertTrue(
                    advances.size > advancesBeforePin,
                    "non-vacuity: the pin frame must have reached the clamp and re-assigned " +
                        "state.currentCommitIndex (no AdvanceCommitIndex trace followed it)",
                )
            },
            {
                assertTrue(
                    pinAdvance != null && pinAdvance.newCommitIndex >= commitBefore,
                    "a batch ending at ${PIN_PREV_LOG_INDEX + 1L} must never drive state.currentCommitIndex " +
                        "below the committed prefix $commitBefore — was $pinAdvance",
                )
            },
            {
                assertTrue(
                    advances.all { it.newCommitIndex >= it.oldCommitIndex },
                    "state.currentCommitIndex must be monotonic across every advance: $advances",
                )
            },
            {
                assertEquals(
                    emitted.distinct(), emitted,
                    "a committed index must never be emitted twice — a re-emission is the consumer-visible " +
                        "proof that the prefix un-committed and was re-committed: $emitted",
                )
            },
            {
                assertTrue(
                    follower.commitIndex.value >= PROBE_LEADER_COMMIT,
                    "control: a batch ending at $lastLogIndex with leaderCommit $PROBE_LEADER_COMMIT must " +
                        "commit that far, or the pin's silence is vacuous — was ${follower.commitIndex.value}",
                )
            },
        )
        sim.checkInvariants()
    }

    private companion object {
        /** Term of every entry and every frame; the follower adopts it from the setup send. */
        const val TERM = 1L

        /** Length of the follower's log after the setup send. */
        const val LAST_LOG_INDEX = 7L

        /** How far the setup send commits — strictly below [LAST_LOG_INDEX], so the log extends above it. */
        const val COMMITTED_PREFIX = 5L

        /** `leaderCommit` on BOTH probes: above [COMMITTED_PREFIX], so both enter the clamp's `if`. */
        const val PROBE_LEADER_COMMIT = LAST_LOG_INDEX

        /** Pin: batch ends at 2 — below [COMMITTED_PREFIX]. The only input that differs from the control. */
        const val PIN_PREV_LOG_INDEX = 1L

        /** Control: batch ends at [LAST_LOG_INDEX] — above [COMMITTED_PREFIX], and where C4 is inert. */
        const val CONTROL_PREV_LOG_INDEX = LAST_LOG_INDEX - 1L
    }
}
