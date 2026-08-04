@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `RaftEngine.isWellFormedBatch` — the §5.3 frame-internal validation of an `AppendEntries` batch
 * (#1832). It holds **three independent `return false` bounds**, and this file exists to measure each
 * of them separately:
 *
 * | | bound | the frame it must refuse |
 * |---|---|---|
 * | **C1** | `prevLogIndex < 0 \|\| prevLogIndex > Long.MAX_VALUE - entries.size - 1` | a probe point below the log origin, or one so high the expected-index arithmetic overflows |
 * | **C2** | `entry.index != prevLogIndex + 1 + i` | a batch that is not contiguous from the probe point — a log gap, and `logEntryAt`'s offset arithmetic is only valid without gaps |
 * | **C3** | `entry.term < 0 \|\| entry.term > m.term` | an entry carrying a term no honest leader could have stamped — at `Long.MAX_VALUE` it makes the victim's `lastLogPosition` unbeatable under §5.4.1 |
 *
 * ## Why all three went unmeasured at once (#2022)
 *
 * Deleting **any one** of them left `:kuilt-raft` green (#1980's discrimination audit, verdicts
 * C1/C2/C3); deleting two together was caught. The suite pinned their **disjunction** and no
 * conjunct. The cause is one fixture: both forgery tests reached for an *obviously* invalid frame,
 * `(index = Long.MAX_VALUE - 1, term = Long.MAX_VALUE)` delivered at the victim's tail, and that
 * frame trips C1, C2 **and** C3 simultaneously. Remove any one and a sibling still refuses it, with
 * every state effect identical.
 *
 * > A fixture built to be "obviously invalid" tends to be invalid in several ways at once, and every
 * > extra way is a guard that stops being measured.
 *
 * So each probe below violates **exactly one** bound and is **legal on the other two**, and each test
 * asserts that legality as a premise — the executable form of "evaluate both sides of the sibling
 * guard's expression on this frame's own values; equal ⇒ the guard is inert here ⇒ it is not what
 * refused the frame". Two shapes of inertness are used, and the stronger one is preferred:
 *
 * - **Structural.** The C1 probe carries `entries = emptyList()`, so `forEachIndexed`'s body never
 *   runs and C2/C3 are *unreachable*, not merely satisfied.
 * - **By equality.** Every other probe leaves the sibling guards' two sides equal.
 *
 * ## The observable, and why the obvious one does not discriminate
 *
 * `isWellFormedBatch` returns **before** the term check, the demotion, the log path and the reply —
 * so a refused frame leaves *no* state effect, and "the forged entry is not in the log" is satisfied
 * just as well by a frame the log-consistency check rejected one screen later. The C1 overflow probe
 * makes that concrete: delete C1 and the frame is *processed*, yet it still never reaches the log
 * (`entryAt(Long.MAX_VALUE - 1)` is null, so §5.3 rejects it) — a log-only assertion is blind to it.
 *
 * The bound's actual contract is **drop the frame**, so that is what is asserted: after each probe
 * the victim emitted no `AppendEntriesAccepted` and no `AppendEntriesRejected`, put no
 * `AppendEntriesResponse` on the wire, and holds the same log. The `RefusalGate` attribution of #1998
 * does not reach this guard — it refuses silently — which is why the pin is "nothing was processed"
 * rather than "gate X fired".
 *
 * A negative is only evidence if the path was live, so [armedVictim]'s two setup frames are a
 * **control**: well-formed, same sender, same term, same injection path, single entry at the tail —
 * the exact shape of every probe, differing along the one axis that probe tests. Their acceptance is
 * asserted before any probe runs.
 *
 * ## Mutation matrix
 *
 * Each column is one deletion, rebuilt `--no-build-cache` with `:kuilt-raft:compileKotlinJvm`
 * EXECUTED and the branch proved absent from the compiled `RaftEngine` class, then the **whole
 * module** run. Every column failed exactly **one** of the module's 476 JVM tests — the one on the
 * diagonal:
 *
 * | | delete C1 | delete C2 | delete C3 |
 * |---|---|---|---|
 * | [implausiblePrevLogIndexIsDropped] | **RED** | GREEN | GREEN |
 * | [nonContiguousBatchIsDropped] | GREEN | **RED** | GREEN |
 * | [entryTermOutsideTheFrameTermIsDropped] | GREEN | GREEN | **RED** |
 * | [wellFormedBatchOverTheSameInjectionPathIsStillAppended] | GREEN | GREEN | GREEN |
 * | *every other test in `:kuilt-raft`* | GREEN | GREEN | GREEN |
 *
 * C1 and C3 are each a **disjunction of two** clauses, and deleting one clause is a finer hole than
 * deleting the bound. Each of those two tests therefore carries one probe per clause and is red under
 * either clause's deletion in isolation — again module-wide alone:
 *
 * | | drop `prevLogIndex < 0` | drop the overflow clause | drop `entry.term < 0` | drop `entry.term > m.term` |
 * |---|---|---|---|---|
 * | [implausiblePrevLogIndexIsDropped] | **RED** | **RED** | GREEN | GREEN |
 * | [entryTermOutsideTheFrameTermIsDropped] | GREEN | GREEN | **RED** | **RED** |
 *
 * Two of those receipts are worth keeping, because they are the argument for the observable this file
 * chose. Dropping `prevLogIndex < 0` makes the victim emit
 * `AppendEntriesAccepted(matchIndex = -1)` — it attests to a **negative** index on the wire. Dropping
 * the overflow clause makes it emit `AppendEntriesRejected(conflictIndex = 8)` instead: the frame was
 * processed, answered, and *never reached the log*. A log-only assertion sees nothing at all there.
 */
internal class AppendEntriesBatchValidationTest {

    /**
     * Election timeouts far beyond anything this file's virtual clock reaches, so all three voters
     * stay passive followers: nobody campaigns, nobody heartbeats, and every `AppendEntries` the
     * victim ever sees is one a test delivered. That quiet is what lets "the victim processed no
     * AppendEntries" be an assertion rather than a race. Seeded off [RAFT_TEST_SEED], minted per test
     * method (the framework builds the class per method).
     */
    private val holdConfig = RaftConfig(
        electionTimeoutMin = 30.seconds,
        electionTimeoutMax = 60.seconds,
        heartbeatInterval = 1.seconds,
        expectVirtualTime = true,
        random = Random(RAFT_TEST_SEED),
    )

    /**
     * **C1 — the implausible-`prevLogIndex` bound.** No test exercised it at all before #2022.
     *
     * Two probes, one per clause of the disjunction, and C2/C3 are inert on both:
     *
     * - **Below the origin.** `prevLogIndex = -1` with an **empty** batch. C2 and C3 live inside
     *   `entries.forEachIndexed`, so with no entries they are *structurally* unreachable: whatever
     *   refuses this frame, it cannot be one of them. Delete C1 and the frame is accepted outright —
     *   `prevLogIndex > snapshotIndex` is false so even the §5.3 consistency check is skipped — and
     *   the victim attests `matchIndex = -1` on the wire.
     * - **Overflow-adjacent.** `prevLogIndex = Long.MAX_VALUE - 1` with one entry at
     *   `Long.MAX_VALUE`. Here C2 and C3 *are* evaluated and both hold, by equality: the expected
     *   index is `(Long.MAX_VALUE - 1) + 1 + 0 = Long.MAX_VALUE`, which is exactly the entry's index,
     *   and the entry's term is the frame's own. Both premises are asserted below.
     */
    @Test
    fun implausiblePrevLogIndexIsDropped() = raftRunTest {
        val victim = armedVictim()
        val overflowEntry = LogEntry(index = Long.MAX_VALUE, term = TERM, command = byteArrayOf())

        assertAll(
            {
                assertTrue(
                    NEGATIVE_PREV_LOG_INDEX < 0L,
                    "non-vacuity: the first probe must actually violate C1's lower clause",
                )
            },
            {
                assertTrue(
                    OVERFLOW_PREV_LOG_INDEX > Long.MAX_VALUE - 1L - 1L,
                    "non-vacuity: the second probe must actually violate C1's overflow clause for a " +
                        "one-entry batch (prevLogIndex > Long.MAX_VALUE - entries.size - 1)",
                )
            },
            {
                assertEquals(
                    OVERFLOW_PREV_LOG_INDEX + 1L, overflowEntry.index,
                    "C2-inertness premise: the entry sits at exactly `prevLogIndex + 1 + 0`, so the " +
                        "contiguity bound's two sides are equal and it cannot be what refuses this frame",
                )
            },
            {
                assertTrue(
                    overflowEntry.term in 0L..TERM,
                    "C3-inertness premise: the entry's term is inside `0..m.term`, so the entry-term " +
                        "bound cannot be what refuses this frame",
                )
            },
        )

        val belowOrigin = victim.deliverAndObserve("C1a: prevLogIndex = $NEGATIVE_PREV_LOG_INDEX, below the log origin") {
            victim.sim.deliverAppendEntries(
                to = victim.id, from = victim.sender, term = TERM,
                prevLogIndex = NEGATIVE_PREV_LOG_INDEX, prevLogTerm = 0L, entries = emptyList(),
            )
        }
        val overflowAdjacent = victim.deliverAndObserve("C1b: prevLogIndex = Long.MAX_VALUE - 1, one entry short of overflowing") {
            victim.sim.deliverAppendEntries(
                to = victim.id, from = victim.sender, term = TERM,
                prevLogIndex = OVERFLOW_PREV_LOG_INDEX, prevLogTerm = TERM, entries = listOf(overflowEntry),
            )
        }
        assertAll({ belowOrigin.assertDropped() }, { overflowAdjacent.assertDropped() })
        victim.sim.checkInvariants()
    }

    /**
     * **C2 — the contiguity bound.** Narrowed from the old `forgedNonContiguousBatchIsNotAppended`,
     * whose `(index = Long.MAX_VALUE - 1, term = Long.MAX_VALUE)` entry violated all three bounds.
     *
     * The probe now skips exactly one index — an entry at `prevLogIndex + 2` — and is legal
     * everywhere else: `prevLogIndex` is the victim's own tail (well inside C1's range) and the
     * entry carries the frame's own term (inside C3's range). Both are asserted as premises.
     *
     * Delete C2 and the batch lands: `entryAt(prevLogIndex)` matches, `entryAt(prevLogIndex + 2)` is
     * null, so the append scan writes it onto the tail and the live log holds a **gap**.
     * `logEntryAt` computes its offset as `index - (snapshotIndex + 1)`, valid only because indices
     * are monotonically increasing with no gaps, so from then on lookups resolve the wrong slot or
     * fall out of range.
     */
    @Test
    fun nonContiguousBatchIsDropped() = raftRunTest {
        val victim = armedVictim()
        val gapEntry = LogEntry(index = PROBE_INDEX + 1L, term = TERM, command = byteArrayOf())

        assertAll(
            {
                assertTrue(
                    gapEntry.index != CONTROL_INDEX + 1L,
                    "non-vacuity: the probe must actually violate contiguity — expected index " +
                        "${CONTROL_INDEX + 1L}, carried ${gapEntry.index}",
                )
            },
            {
                assertTrue(
                    CONTROL_INDEX >= 0L && CONTROL_INDEX <= Long.MAX_VALUE - 1L - 1L,
                    "C1-inertness premise: prevLogIndex $CONTROL_INDEX is inside " +
                        "`0..(Long.MAX_VALUE - entries.size - 1)`, so the implausible-prevLogIndex " +
                        "bound cannot be what refuses this frame",
                )
            },
            {
                assertTrue(
                    gapEntry.term in 0L..TERM,
                    "C3-inertness premise: the entry's term is inside `0..m.term`, so the entry-term " +
                        "bound cannot be what refuses this frame",
                )
            },
        )

        val gap = victim.deliverAndObserve("C2: entries[0].index = ${gapEntry.index}, expected ${CONTROL_INDEX + 1L}") {
            victim.sim.deliverAppendEntries(
                to = victim.id, from = victim.sender, term = TERM,
                prevLogIndex = CONTROL_INDEX, prevLogTerm = TERM, entries = listOf(gapEntry),
            )
        }

        assertAll(
            { gap.assertDropped() },
            {
                assertTrue(
                    gap.logAfter.zipWithNext().all { (a, b) -> b.first == a.first + 1L },
                    "the live log must stay contiguous — logEntryAt's offset arithmetic depends on it; " +
                        "indices=${gap.logAfter.map { it.first }}",
                )
            },
        )
        victim.sim.checkInvariants()
    }

    /**
     * **C3 — the entry-term bound**, and the §5.4.1 safety property it protects. Narrowed from the
     * old `forgedMaxTermEntryDoesNotMakeTheVictimUnbeatableInElections`, whose entry sat at
     * `Long.MAX_VALUE - 1` — far past the victim's tail — and so violated C1 and C2 as well.
     *
     * Both probes now sit at exactly `prevLogIndex + 1`, the index an honest batch would occupy, so
     * contiguity is inert by equality and `prevLogIndex` is the victim's own tail. One probe per
     * clause of the disjunction: a term **below 0**, then a term **above the frame's own**.
     *
     * The order is load-bearing, and so is deferring every assertion until both probes and the vote
     * have run. Delete only the `term < 0` clause and the negative-term entry lands at `(term = -1)`,
     * which *loses* every §5.4.1 comparison — the vote is still granted, and the first probe's
     * `assertDropped` is the only thing that names the regression. Delete only the `term > m.term`
     * clause and the second probe's `assertDropped` **and** the vote probe both fire, independently.
     *
     * That vote probe is the §5.4.1 half: after swallowing an entry at `term = Long.MAX_VALUE` the
     * victim's `lastLogPosition` dominates every honest node's, so it denies every vote it is asked
     * for and wins every election it enters with a log missing committed entries — Leader
     * Completeness (§5.4 / Figure 3.2) violated, not merely a corrupt log. It is answered on the wire
     * by a real `RequestVote` from an honest voter exactly as up-to-date as the victim legitimately
     * is, not by re-deriving `isLogUpToDate` from test-side state. `leadershipTransfer = true`
     * bypasses the recipient's §4.2.3 leader-stickiness deny, which would otherwise short-circuit
     * before the log comparison and hide the result.
     *
     * **Covers the AppendEntries lane only.** `InstallSnapshot`'s `lastIncludedTerm` /
     * `lastIncludedIndex` reach the same §5.4.1 domination through a sibling frame guarded by
     * `isWellFormedSnapshotChunk` and pinned by `InstallSnapshotMetaValidationTest` (#1868). Neither
     * check makes a frame trustworthy — in-range metadata stays unauthenticated (#1876).
     */
    @Test
    fun entryTermOutsideTheFrameTermIsDropped() = raftRunTest {
        val victim = armedVictim()
        val negativeTermEntry = LogEntry(index = PROBE_INDEX, term = -1L, command = byteArrayOf())
        val aboveFrameTermEntry = LogEntry(index = PROBE_INDEX, term = Long.MAX_VALUE, command = byteArrayOf())

        assertAll(
            {
                assertTrue(
                    negativeTermEntry.term < 0L && aboveFrameTermEntry.term > TERM,
                    "non-vacuity: the two probes must violate C3's lower and upper clause respectively",
                )
            },
            {
                assertTrue(
                    CONTROL_INDEX >= 0L && CONTROL_INDEX <= Long.MAX_VALUE - 1L - 1L,
                    "C1-inertness premise: prevLogIndex $CONTROL_INDEX is inside " +
                        "`0..(Long.MAX_VALUE - entries.size - 1)`, so the implausible-prevLogIndex " +
                        "bound cannot be what refuses either frame",
                )
            },
            {
                assertEquals(
                    listOf(CONTROL_INDEX + 1L, CONTROL_INDEX + 1L),
                    listOf(negativeTermEntry.index, aboveFrameTermEntry.index),
                    "C2-inertness premise: both entries sit at exactly `prevLogIndex + 1 + 0`, so the " +
                        "contiguity bound's two sides are equal and it cannot be what refuses either frame",
                )
            },
        )

        val negativeTerm = victim.deliverAndObserve("C3a: entries[0].term = ${negativeTermEntry.term}, below 0") {
            victim.sim.deliverAppendEntries(
                to = victim.id, from = victim.sender, term = TERM,
                prevLogIndex = CONTROL_INDEX, prevLogTerm = TERM, entries = listOf(negativeTermEntry),
            )
        }
        val aboveFrameTerm = victim.deliverAndObserve("C3b: entries[0].term = Long.MAX_VALUE, above the frame's term $TERM") {
            victim.sim.deliverAppendEntries(
                to = victim.id, from = victim.sender, term = TERM,
                prevLogIndex = CONTROL_INDEX, prevLogTerm = TERM, entries = listOf(aboveFrameTermEntry),
            )
        }

        // Run the §5.4.1 probe BEFORE asserting, so it is an independent kill rather than one shadowed
        // by whichever `assertDropped` above would have thrown first (the un-pinning shape of #1980).
        victim.sim.deliverRequestVote(
            to = victim.id, from = victim.candidate, term = TERM + CANDIDATE_TERM_JUMP,
            lastLogIndex = CONTROL_INDEX, lastLogTerm = TERM, leadershipTransfer = true,
        )
        victim.sim.awaitTrue("victim answered the RequestVote") { victim.voteResponses().isNotEmpty() }
        val vote = victim.voteResponses().last()
        val logAtVote = victim.log()

        assertAll(
            { negativeTerm.assertDropped() },
            { aboveFrameTerm.assertDropped() },
            {
                assertTrue(
                    vote.voteGranted,
                    "§5.4.1: a forged high-term entry must not make the victim's log unbeatable — it " +
                        "denied a vote to an honest, equally up-to-date candidate at (term=$TERM, " +
                        "index=$CONTROL_INDEX). Victim log=$logAtVote",
                )
            },
        )
        victim.sim.checkInvariants()
    }

    /**
     * The guard against an **over-broad predicate**: `isWellFormedBatch` must not refuse honest
     * traffic. [armedVictim] already appends a multi-entry batch and a single-entry batch at the tail
     * and asserts both; this adds the third honest shape, a contiguous multi-entry batch appended
     * above the control, over the same injection path the probes use.
     */
    @Test
    fun wellFormedBatchOverTheSameInjectionPathIsStillAppended() = raftRunTest {
        val victim = armedVictim()
        victim.sim.deliverAppendEntries(
            to = victim.id, from = victim.sender, term = TERM,
            prevLogIndex = CONTROL_INDEX, prevLogTerm = TERM,
            entries = listOf(
                LogEntry(index = PROBE_INDEX, term = TERM, command = byteArrayOf(9)),
                LogEntry(index = PROBE_INDEX + 1L, term = TERM, command = byteArrayOf(8)),
            ),
        )
        victim.sim.settle()

        assertEquals(
            (1L..PROBE_INDEX + 1L).map { it to TERM }, victim.log(),
            "a contiguous two-entry batch starting at prevLogIndex + 1 must be appended",
        )
        victim.sim.checkInvariants()
    }

    /**
     * Stand up the shared trajectory and return the [Victim] every test drives.
     *
     * Two setup frames, both well-formed, both from [Victim.sender] at [TERM]:
     *
     * - the **seed**, entries `1..`[SEED_LOG_LAST], which gives the victim a log to probe against;
     * - the **control**, one entry at [CONTROL_INDEX] appended at the tail — the exact shape of every
     *   probe below, differing from each only along the axis that probe tests.
     *
     * Their acceptance is asserted here, before any probe runs, and that is what makes each probe's
     * silence evidence rather than vacuity: without it a probe would pass equally well against an
     * engine that ignored injected frames, dropped them at the §5.2 leader-authority gate, or never
     * received them at all.
     *
     * `leaderCommit` stays 0 throughout, so nothing in this file ever enters the commit lane.
     */
    private suspend fun TestScope.armedVictim(): Victim {
        val sim = raftSim(this, backgroundScope, n = 3, config = holdConfig)
        val (senderId, victimId, candidateId) = sim.nodeIds
        val traced = mutableListOf<RaftTraceEvent>()
        backgroundScope.launch { sim.nodes.getValue(victimId).trace.collect { traced += it } }
        sim.network.recording = true
        sim.settle()   // collector subscribed and tap armed before the first frame is delivered

        sim.deliverAppendEntries(
            to = victimId, from = senderId, term = TERM,
            prevLogIndex = 0L, prevLogTerm = 0L,
            entries = (1L..SEED_LOG_LAST).map { LogEntry(index = it, term = TERM, command = byteArrayOf(it.toByte())) },
        )
        sim.settle()
        sim.deliverAppendEntries(
            to = victimId, from = senderId, term = TERM,
            prevLogIndex = SEED_LOG_LAST, prevLogTerm = TERM,
            entries = listOf(LogEntry(index = CONTROL_INDEX, term = TERM, command = byteArrayOf(CONTROL_INDEX.toByte()))),
        )
        sim.settle()

        val victim = Victim(sim, senderId, victimId, candidateId, traced)
        val log = victim.log()
        val accepted = victim.processed().filterIsInstance<RaftTraceEvent.AppendEntriesAccepted>().map { it.matchIndex }
        val replies = victim.replies()
        assertAll(
            {
                assertEquals(
                    (1L..CONTROL_INDEX).map { it to TERM }, log,
                    "control: a well-formed batch over this injection path must be appended, or every " +
                        "probe below asserts silence against a path that was never live",
                )
            },
            {
                assertEquals(
                    listOf(SEED_LOG_LAST, CONTROL_INDEX), accepted,
                    "control: both setup frames must be ACCEPTED, and nothing else may have reached " +
                        "the victim's AppendEntries path",
                )
            },
            {
                assertTrue(
                    replies.size == 2 && replies.all { it.success },
                    "control: the victim must answer a well-formed frame with a success reply — its " +
                        "silence is what every probe below measures; replies=$replies",
                )
            },
        )
        return victim
    }

    /**
     * The node under probe, plus the two taps that say whether a frame was *processed at all*.
     *
     * The distinction matters because `isWellFormedBatch` returns before every side-effect
     * `onAppendEntries` has: a refused frame changes no state, so state alone cannot tell a frame
     * this guard dropped from one the §5.3 consistency check rejected a screen later.
     */
    private class Victim(
        val sim: RaftSimulation,
        val sender: NodeId,
        val id: NodeId,
        val candidate: NodeId,
        private val traced: List<RaftTraceEvent>,
    ) {
        /** Every trace this node emitted while *processing* an AppendEntries — accepted or rejected. */
        fun processed(): List<RaftTraceEvent> = traced.filter {
            it is RaftTraceEvent.AppendEntriesAccepted || it is RaftTraceEvent.AppendEntriesRejected
        }

        /** Every `AppendEntriesResponse` this node put on the wire. A dropped frame produces none. */
        fun replies(): List<RaftMessage.AppendEntriesResponse> = sim.network.sent
            .filter { it.from == id }
            .mapNotNull { it.message as? RaftMessage.AppendEntriesResponse }

        /** Every `RequestVoteResponse` this node put on the wire. */
        fun voteResponses(): List<RaftMessage.RequestVoteResponse> = sim.network.sent
            .filter { it.from == id }
            .mapNotNull { it.message as? RaftMessage.RequestVoteResponse }

        /** `(index, term)` of this node's live log — the harm every bound exists to prevent. */
        suspend fun log(): List<Pair<Long, Long>> =
            sim.storages.getValue(id).entries().map { it.index to it.term }

        /**
         * Deliver one frame via [deliver] and **record** what it changed, without asserting.
         *
         * Recording rather than asserting is deliberate: it lets a test deliver every probe and run
         * its safety follow-up *before* any assertion throws, so a later observable is an independent
         * kill instead of one shadowed by whichever earlier assertion happened to fire first — the
         * un-pinning shape #1980 catalogued.
         */
        suspend fun deliverAndObserve(what: String, deliver: suspend () -> Unit): Effect {
            val tracesBefore = processed()
            val repliesBefore = replies()
            val logBefore = log()
            deliver()
            sim.settle()
            return Effect(
                what = what,
                traces = processed().drop(tracesBefore.size),
                replies = replies().drop(repliesBefore.size),
                logBefore = logBefore,
                logAfter = log(),
            )
        }
    }

    /** Everything one probe frame changed. All three axes are empty iff the frame was dropped. */
    private class Effect(
        val what: String,
        val traces: List<RaftTraceEvent>,
        val replies: List<RaftMessage.AppendEntriesResponse>,
        val logBefore: List<Pair<Long, Long>>,
        val logAfter: List<Pair<Long, Long>>,
    ) {
        /** The bound's actual contract: the frame is dropped before `onAppendEntries` takes any action. */
        fun assertDropped() {
            assertEquals(
                emptyList(), traces,
                "$what — the frame must be DROPPED before onAppendEntries takes any action",
            )
            assertEquals(
                emptyList(), replies,
                "$what — a dropped frame is answered with silence: an honest leader cannot emit such a " +
                    "batch, so there is no honest sender to answer, and a rejection would hand a forger " +
                    "a free lever on the leader's §5.3 backup",
            )
            assertEquals(logBefore, logAfter, "$what — the log must be untouched")
        }
    }

    private companion object {
        /** Term of every setup frame, every probe frame and every honest entry. */
        const val TERM = 1L

        /** Last index of the seed batch — the probe point of the control frame. */
        const val SEED_LOG_LAST = 6L

        /** The control frame's single entry, and therefore every probe's `prevLogIndex`. */
        const val CONTROL_INDEX = SEED_LOG_LAST + 1L

        /** The index an honest batch would occupy next: `CONTROL_INDEX + 1`. */
        const val PROBE_INDEX = CONTROL_INDEX + 1L

        /** C1, lower clause: a probe point below the log origin. */
        const val NEGATIVE_PREV_LOG_INDEX = -1L

        /**
         * C1, overflow clause: one short of `Long.MAX_VALUE`, so a single-entry batch's expected
         * index `prevLogIndex + 1 + 0` lands exactly on `Long.MAX_VALUE` and a second entry would
         * wrap. Contiguity and the entry-term bound both stay satisfied on it — see the test.
         */
        const val OVERFLOW_PREV_LOG_INDEX = Long.MAX_VALUE - 1L

        /** How far above [TERM] the §5.4.1 probe campaigns; well inside `RaftConfig.maxTermJump`. */
        const val CANDIDATE_TERM_JUMP = 5L
    }
}
