package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The contract every [Bolt] backend must satisfy. Subclass and implement [newBolt].
 *
 * Six properties, and the second one is the reason the module exists:
 *
 * 1. **Round-trip** — appended ops replay identically, in order, at contiguous offsets.
 * 2. **The firewall** — a bolt fed a `Compact` keeps the ops that `Compact` suppresses and never
 *    replays the `Compact` itself. Mutation-checked; see
 *    [aBoltDiscardsCompactionRecordsAndKeepsWhatTheySuppress].
 * 3. **Asymmetric retention** — a windowed live replica forgets; the bolt beside it does not.
 * 4. **Scoped replay** — by offset, by arrival time, and by insert dots (inserts *only*).
 * 5. **An append with no content writes no frame.**
 * 6. **[Bolt.availability] is honest** — `Available` means an append will be accepted.
 *
 * Fixtures are built from [Rga] because it is the op-log CRDT with the harder shape: it carries a
 * `compactedBelow` floor that `Fugue` has no equivalent of, so it can forget an op with no
 * `Compact` naming it. Backend-independent behaviour over `Fugue` ops is pinned separately,
 * outside this suite.
 */
abstract class BoltConformanceSuite {

    /**
     * A fresh, empty bolt reading its arrival timestamps from [clock].
     *
     * The clock is a parameter rather than a default because property 4 scopes a replay by arrival
     * time, which a backend reaching for the wall clock could not be asked about deterministically.
     */
    protected abstract fun newBolt(clock: Clock): Bolt<RgaOp<String>>

    /**
     * A bolt of this backend that is **already out of room** — a full archive, a read-only volume,
     * a runtime with no filesystem. Whatever exhaustion means for this backend, produced
     * deterministically.
     *
     * Deliberately **not nullable.** An "I cannot be exhausted" opt-out would put the vacuity this
     * obligation exists to remove one level up, where it is harder to see: the suite would go green
     * for a backend that never exercised the exhausted path at all. Every backend can produce one —
     * a byte budget, a tiny file cap, or (for a runtime with no storage) its ordinary state.
     *
     * The obligation asserts the *precondition* too, so a backend that returns a healthy bolt here
     * fails loudly rather than passing quietly.
     */
    protected abstract fun newExhaustedBolt(clock: Clock): Bolt<RgaOp<String>>

    /**
     * A bolt of this backend whose archive holds exactly [intactFrames] readable frames and is then
     * **damaged** — a crash mid-append, a pre-allocated region never written, bit-rot. Whatever a
     * torn archive looks like for this backend, produced deterministically.
     *
     * Non-nullable for the same reason [newExhaustedBolt] is, and the vacuity it removes is larger:
     * every path a *consumer* can reach on any backend writes whole frames after a whole header, so
     * without this hook [Truncated] is never constructed by any test in the tree — the verdict, its
     * offset, and the decision to stop the entire replay at it are all unasserted.
     *
     * **The damage must be followed by a HEALTHY segment** (or equivalent readable region) wherever
     * the backend has more than one. That detail carries the property's discriminating power against
     * the *plausible* mutation rather than the clumsy one — see
     * [aTruncatedArchiveStopsAtTheDamageAndSaysSo]. A fixture whose damage is last leaves "stop the
     * replay" and "skip to the next region, keeping the verdict" emitting identical events.
     */
    protected abstract suspend fun newTruncatedBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>>

    /**
     * A bolt of this backend whose archive is missing a whole region out of the **middle** of its
     * offset space, with exactly [intactFrames] readable frames ahead of the hole — a deleted segment
     * file, a segment that never reached disk, a region pre-allocated and never written. Whatever
     * losing a middle looks like for this backend, produced deterministically.
     *
     * Non-nullable for the same reason [newExhaustedBolt] and [newTruncatedBolt] are, and the vacuity
     * it removes is the one that shipped this defect twice. [newTruncatedBolt]'s damage is always
     * *within* a frame, so nothing in this suite ever produced an archive whose **segment sequence
     * itself** is discontinuous — and the reference backend's segments are an in-process list that
     * cannot lose an element, so it satisfied that silence for free. Two disk-backed backends were
     * then written independently against this suite and **both** replayed a hole as a [CleanTail]
     * (#2240). The general form is worth stating: *a conformance property is only as strong as the
     * weakest failure the reference implementation can reach.*
     *
     * **Frames must survive BEHIND the hole**, and here that is structural rather than merely
     * discriminating. A hole is seen by comparing a segment header's absolute base offset against
     * where the previous segment stopped, so an archive that simply ends early has no header left to
     * disagree with anything and is not discontinuous at all — it is just shorter. Frames behind the
     * hole are also what keeps "stop at the hole" and "step over the hole" from emitting identical
     * events, exactly as [newTruncatedBolt]'s KDoc argues one level down.
     *
     * The obligation asserts its own precondition, so a backend that returns a healthy archive — or
     * one damaged some other way — fails loudly rather than passing quietly.
     */
    protected abstract suspend fun newDiscontinuousBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>>

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")
    private val epoch = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    // ── 1. Round-trip ─────────────────────────────────────────────────────────

    @Test
    fun appendedOpsReplayIdenticallyAndInOrder() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (afterFirst, first) = Rga.empty<String>().mintInserts("first")
        val (_, second) = afterFirst.mintInserts("second")

        val one = bolt.append(first)
        val two = bolt.append(second)
        val frames = bolt.replay(ReplayScope.All).frames().toList()

        assertAll(
            { assertIs<AppendResult.Written>(one, "a content append must be written") },
            { assertIs<AppendResult.Written>(two, "a content append must be written") },
            { assertEquals(2, frames.size, "one frame per append") },
            { assertEquals(first, frames[0].ops, "frame 1 replays its ops, in order") },
            { assertEquals(second, frames[1].ops, "frame 2 replays its ops, in order") },
            { assertEquals(0L, frames[0].offset, "the first frame starts the offset space at 0") },
            {
                assertEquals(
                    frames[0].endOffset,
                    frames[1].offset,
                    "offsets are contiguous — one frame's end is the next frame's start",
                )
            },
            { assertTrue(frames[1].endOffset > frames[1].offset, "a frame occupies a non-empty range") },
            {
                assertEquals(
                    first.map { it.id.dot }.toSet(),
                    frames[0].insertDots,
                    "the frame carries the dots its inserts minted",
                )
            },
            { assertEquals(null, frames[0].key, "the key slot is reserved, and unwritten in v1") },
        )
    }

    // ── 2. The firewall ───────────────────────────────────────────────────────

    /**
     * **The safety property of the whole module.** A bolt is fed content ops and then the
     * `RgaOp.Compact` that suppresses one of them. It must keep the suppressed content and drop the
     * compaction record — that asymmetry is the only thing letting an archive outlive its source's
     * forgetting.
     *
     * **Mutation receipt:** deleting the `Compact` filter in `BoltArchiveFormat.contentOnly` reddens
     * the first assertion below, because the replayed op list gains the `Compact`.
     *
     * **Fixture hazard this test is written around.** Both `Rga` and `Fugue` silently return `null`
     * from `compact()` for an id a live insert still anchors — `after` for `Rga`. A fixture that
     * compacted the *leading* element of a two-element sequence would get no compaction at all, and
     * because `compact` returns `null` rather than throwing, this test would then run against an
     * **uncompacted** log and pass even with the discard removed — the one outcome its mutation
     * check exists to prevent. [rgaWithACompaction] therefore compacts the **trailing** element and
     * asserts the result is non-null.
     */
    @Test
    fun aBoltDiscardsCompactionRecordsAndKeepsWhatTheySuppress() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val fixture = rgaWithACompaction()

        bolt.append(fixture.contentOps)
        val compactAppend = bolt.append(listOf(fixture.compactOp))
        val archived = bolt.replay(ReplayScope.All).frames().toList().flatMap { it.ops }
        val stillLive = fixture.compacted.operations().toList()

        assertAll(
            {
                assertEquals(
                    fixture.contentOps,
                    archived,
                    "the archive keeps every content op, in order, and no compaction record",
                )
            },
            { assertTrue(archived.none { it is RgaOp.Compact }, "a Compact must never reach the archive") },
            {
                assertIs<AppendResult.Skipped>(
                    compactAppend,
                    "an append of nothing but compaction records writes no frame",
                )
            },
            // The other half of the asymmetry: the source really did forget.
            {
                assertTrue(
                    stillLive.none { it == fixture.suppressedInsert || it == fixture.suppressedRemove },
                    "the live replica dropped the ops its own compaction suppressed",
                )
            },
            {
                assertTrue(
                    archived.containsAll(listOf(fixture.suppressedInsert, fixture.suppressedRemove)),
                    "and the archive still holds them — the capability the module exists for",
                )
            },
        )
    }

    // ── 3. Asymmetric retention, end to end ───────────────────────────────────

    /**
     * The headline capability, pinned directly rather than inferred: a live replica windowed down
     * to its newest records, and a bolt beside it that still replays every one it was fed.
     *
     * Windowing via `Rga.dropWindow` is the harder half of the `Rga`/`Fugue` asymmetry — it raises
     * the `compactedBelow` floor and records **no** `Compact` op naming what it dropped. So a bolt
     * that tried to reconstruct history from a live replica afterwards would find nothing to
     * reconstruct, which is exactly why a bolt is fed at append time instead.
     */
    @Test
    fun aWindowedReplicaForgetsWhileTheBoltBesideItDoesNot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (live, inserts) = Rga.empty<String>().mintInserts("record", count = RECORDS)
        bolt.append(inserts)

        val dropped = inserts.take(WINDOW_DROP).map { it.id }.toSet()
        val (windowed, _) = assertNotNull(live.dropWindow(alice, dropped), "dropWindow takes a non-empty set")
        val survivingIds = windowed.operations().filterIsInstance<RgaOp.Insert<String>>().map { it.id }.toSet()
        val archivedIds = bolt.replay(ReplayScope.All).frames().toList()
            .flatMap { it.ops }
            .filterIsInstance<RgaOp.Insert<String>>()
            .map { it.id }
            .toSet()

        assertAll(
            { assertEquals(RECORDS - WINDOW_DROP, windowed.size, "the live replica kept only its window") },
            { assertTrue(survivingIds.intersect(dropped).isEmpty(), "the live replica really forgot them") },
            { assertTrue(archivedIds.containsAll(dropped), "the bolt still replays every windowed-away record") },
            { assertEquals(inserts.map { it.id }.toSet(), archivedIds, "and everything else it was fed") },
        )
    }

    // ── 4. Scoped replay ──────────────────────────────────────────────────────

    @Test
    fun replayFromAnOffsetResumesExactlyWhereTheCursorLeftOff() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (r1, one) = Rga.empty<String>().mintInserts("one")
        val (r2, two) = r1.mintInserts("two")
        val (_, three) = r2.mintInserts("three")
        val written = listOf(one, two, three).map { assertIs<AppendResult.Written>(bolt.append(it)) }

        val fromSecond = bolt.replay(ReplayScope.FromOffset(written[0].endOffset)).frames().toList()
        val fromThird = bolt.replay(ReplayScope.FromOffset(written[1].endOffset)).frames().toList()
        val fromTail = bolt.replay(ReplayScope.FromOffset(written[2].endOffset)).frames().toList()
        val midFrame = bolt.replay(ReplayScope.FromOffset(written[1].offset + 1)).frames().toList()

        assertAll(
            { assertEquals(listOf(two, three), fromSecond.map { it.ops }, "resume skips consumed frames") },
            { assertEquals(listOf(three), fromThird.map { it.ops }, "and again") },
            { assertTrue(fromTail.isEmpty(), "a cursor at the tail yields nothing until the next append") },
            {
                assertEquals(
                    listOf(two, three),
                    midFrame.map { it.ops },
                    "an offset inside a frame yields it whole — a cursor never points at half a record",
                )
            },
        )
    }

    @Test
    fun replayScopedByArrivalTimeReturnsExactlyTheFramesInRange() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = MutableClock(epoch)
        val bolt = newBolt(clock)
        val (r1, early) = Rga.empty<String>().mintInserts("early")
        val (r2, middle) = r1.mintInserts("middle")
        val (_, late) = r2.mintInserts("late")

        bolt.append(early)
        clock.advanceBy(STEP)
        bolt.append(middle)
        clock.advanceBy(STEP)
        bolt.append(late)
        val inWindow = bolt.replay(ReplayScope.Arrived(epoch + STEP, epoch + STEP * 2)).frames().toList()
        val atStart = bolt.replay(ReplayScope.Arrived(epoch, epoch + STEP)).frames().toList()
        val all = bolt.replay(ReplayScope.All).frames().toList()

        assertAll(
            { assertEquals(listOf(middle), inWindow.map { it.ops }, "half-open: [from, untilExclusive)") },
            { assertEquals(listOf(early), atStart.map { it.ops }, "the lower bound is inclusive") },
            {
                assertEquals(
                    listOf(epoch, epoch + STEP, epoch + STEP * 2),
                    all.map { it.arrivedAt },
                    "every frame is stamped from the injected clock, never a wall clock",
                )
            },
        )
    }

    /**
     * Dot-scoped replay is **insert-only**, and this pins both halves of that: a frame is selected
     * by the inserts it carries, and a frame of pure removes is selected by **no** floor, not even
     * the empty one.
     *
     * That is the format's promise, not a gap. A `Remove` mints no dot — it reuses its target
     * `Insert`'s id, and it arrives arbitrarily later than that insert. A frame of removes could
     * only claim its targets' *old* dots, in which case a resume-from-dot cursor would skip the
     * frame and replay a removed record as live. So dots are informational, the append offset is
     * the resume cursor, and a test that scoped removes by dot would be testing something this
     * format deliberately does not offer.
     */
    @Test
    fun dotScopedReplaySelectsInsertsOnlyAndNeverAFrameOfRemoves() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (afterEarly, early) = Rga.empty<String>().mintInserts("early", count = 2)
        val (afterLate, late) = afterEarly.insertAt(alice, afterEarly.size, "late")
        val (_, removal) = assertNotNull(afterLate.removeAt(0), "removeAt(0) must find a visible element")

        bolt.append(early)
        val lateFrame = assertIs<AppendResult.Written>(bolt.append(listOf(late)))
        val removeFrame = assertIs<AppendResult.Written>(bolt.append(listOf(removal)))
        val aboveTwo = bolt.replay(ReplayScope.InsertsAbove(VersionVector.of(mapOf(alice to 2L)))).frames().toList()
        val aboveNothing = bolt.replay(ReplayScope.InsertsAbove(VersionVector.EMPTY)).frames().toList()
        val byOffset = bolt.replay(ReplayScope.FromOffset(removeFrame.offset)).frames().toList()

        assertAll(
            { assertEquals(listOf(listOf(late)), aboveTwo.map { it.ops }, "only the frame above the floor") },
            {
                assertEquals(
                    listOf(early, listOf(late)),
                    aboveNothing.map { it.ops },
                    "an empty floor selects every frame carrying an insert — and only those",
                )
            },
            { assertEquals(emptySet<Dot>(), removeFrame.insertDots, "a frame of removes mints no dots") },
            { assertEquals(listOf(listOf(removal)), byOffset.map { it.ops }, "removes are reachable — by OFFSET") },
            { assertTrue(lateFrame.insertDots.isNotEmpty(), "the insert frame does carry dots, or the above is empty") },
        )
    }

    // ── 5. An append with no content writes no frame ──────────────────────────

    @Test
    fun anAppendWithNoContentWritesNoFrame() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val fixture = rgaWithACompaction()

        val empty = bolt.append(emptyList())
        val onlyCompaction = bolt.append(listOf(fixture.compactOp))
        val afterBoth = bolt.replay(ReplayScope.All).frames().toList()
        val real = bolt.append(fixture.contentOps)
        val afterReal = bolt.replay(ReplayScope.All).frames().toList()

        assertAll(
            { assertIs<AppendResult.Skipped>(empty, "an empty append is a no-op") },
            { assertIs<AppendResult.Skipped>(onlyCompaction, "so is an append of nothing but compaction records") },
            { assertTrue(afterBoth.isEmpty(), "neither wrote a frame") },
            { assertEquals(0L, assertIs<AppendResult.Written>(real).offset, "and neither consumed offset space") },
            { assertEquals(1, afterReal.size, "the first real append is the archive's first frame") },
        )
    }

    // ── 6. availability() is honest ───────────────────────────────────────────

    /**
     * The **healthy-state** half of the availability contract, and on its own it is weak: a bolt
     * built by [newBolt] is writable on every backend in the tree, so only the `Available` branch is
     * ever taken and the assertion it makes duplicates property 1.
     *
     * Said plainly because the vacuity is the point: the half that discriminates is
     * [anExhaustedBoltReportsUnavailableAndRefusesTheAppend], which drives the *other* state. Keep
     * them together — this one alone would go green against a bolt that reported `Available` while
     * every append failed, which is exactly the bug the pair exists to catch.
     */
    @Test
    fun availabilityAgreesWithWhetherAnAppendIsAccepted() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val availability = bolt.availability()
        val (_, ops) = Rga.empty<String>().mintInserts("probe")
        val result = bolt.append(ops)

        assertAll(
            {
                when (availability) {
                    BoltAvailability.Available ->
                        assertIs<AppendResult.Written>(result, "Available must mean an append is accepted")
                    is BoltAvailability.Unavailable ->
                        assertIs<AppendResult.Failed>(result, "Unavailable must not then claim a write")
                    is BoltAvailability.Unknown ->
                        assertTrue(result !is AppendResult.Skipped, "content was offered, so this is not a no-op")
                }
            },
        )
    }

    /**
     * The half of the availability contract that can fail: a bolt with no room must **say so** and
     * must **refuse**, reporting the identities it could not keep.
     *
     * This is the obligation that catches "reports `Available` while every append fails". It is a
     * conformance property rather than a backend test because exhaustion is where the backends
     * differ most — a byte budget in memory, a full disk under mmap, no filesystem at all on
     * wasm — and it is the one state where a wrong answer costs records permanently: the live
     * replica windows them away next, so a lost append is lost from both sides.
     */
    @Test
    fun anExhaustedBoltReportsUnavailableAndRefusesTheAppend() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newExhaustedBolt(FixedClock(epoch))
        val (_, ops) = Rga.empty<String>().mintInserts("probe")

        val availability = bolt.availability()
        val result = bolt.append(ops)
        val archived = bolt.replay(ReplayScope.All).frames().toList()

        assertAll(
            {
                assertIs<BoltAvailability.Unavailable>(
                    availability,
                    "newExhaustedBolt must hand back a bolt that reports itself unusable — returning a " +
                        "healthy one makes this obligation vacuous, so it fails here rather than passing",
                )
            },
            { assertIs<AppendResult.Failed>(result, "and it must refuse the append rather than claim a write") },
            {
                assertEquals(
                    ops.map { it.id.dot }.toSet(),
                    assertIs<AppendResult.Failed>(result).insertDots,
                    "reporting WHICH records it lost — a tally makes every recovery unimplementable",
                )
            },
            { assertTrue(archived.isEmpty(), "and leaving no partial frame behind") },
        )
    }

    /**
     * Every replay ends with exactly one verdict, and on an intact archive it is [CleanTail].
     *
     * Without this, a consumer cannot tell a complete history from one that stopped at damage — and
     * "I still hold what the live replica forgot" is the only thing a bolt sells.
     */
    @Test
    fun everyReplayEndsWithExactlyOneTerminalVerdict() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (afterFirst, first) = Rga.empty<String>().mintInserts("first")
        val (_, second) = afterFirst.mintInserts("second")

        bolt.append(first)
        bolt.append(second)
        val events = bolt.replay(ReplayScope.All).toList()
        val empty = newBolt(FixedClock(epoch)).replay(ReplayScope.All).toList()

        assertAll(
            { assertEquals(CleanTail, events.lastOrNull(), "an intact archive ends clean") },
            { assertEquals(1, events.count { it !is Archived<*> }, "exactly one terminal event, never two") },
            { assertEquals(2, events.filterIsInstance<Archived<RgaOp<String>>>().size, "and the frames precede it") },
            { assertEquals(listOf(CleanTail), empty, "an empty archive is a clean tail, not a silent nothing") },
        )
    }

    /**
     * The **damaged-archive** half of the verdict contract, and the one that can fail.
     *
     * [everyReplayEndsWithExactlyOneTerminalVerdict] drives only the [CleanTail] arm, which every
     * backend reaches for free — so on its own it is the same vacuity property 6 had before the
     * exhaustion hook: the branch that discriminates is never taken. This drives the other arm.
     *
     * Four assertions, and each pins a decision nothing else in the tree does:
     *
     * 1. the verdict is [Truncated], not a clean tail — replay does not paper over damage;
     * 2. its `atOffset` is the last intact frame's `endOffset` — it is a *resume cursor*, so a
     *    consumer can re-read from exactly there once the writer catches up;
     * 3. **no frame follows it**, and none from a later, healthy region either — a torn segment
     *    stops the WHOLE replay rather than skipping ahead and handing back a history with a silent
     *    hole in it and offsets that jump;
     * 4. every frame before the damage survives — the intact prefix is not discarded over a bad tail.
     *
     * **Mutation receipts.** Turning the stop into a bare `continue` reddens (1) and (4) — the
     * verdict becomes [CleanTail] and a frame from beyond the damage replays. Zeroing
     * `Truncated.atOffset` reddens (2). And the one the fixture's shape exists for: hoisting the
     * verdict out of the segment loop and `continue`-ing past a damaged segment emits exactly one
     * plausible-looking verdict, so it is **green** against a fixture whose damage is last and reds
     * only because the backend puts a healthy region behind it.
     */
    @Test
    fun aTruncatedArchiveStopsAtTheDamageAndSaysSo() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newTruncatedBolt(FixedClock(epoch), INTACT_FRAMES)

        val events = bolt.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()
        val verdict = events.lastOrNull()

        assertAll(
            {
                assertIs<Truncated>(
                    verdict,
                    "newTruncatedBolt must hand back a DAMAGED archive — a healthy one makes this " +
                        "obligation vacuous, so it fails here rather than passing",
                )
            },
            { assertEquals(1, events.count { it !is Archived<*> }, "exactly one terminal event, never two") },
            {
                assertEquals(
                    INTACT_FRAMES,
                    frames.size,
                    "every frame before the damage survives — and nothing from beyond it is replayed, " +
                        "because a torn region stops the whole replay rather than leaving a silent hole",
                )
            },
            {
                assertEquals(
                    frames.last().endOffset,
                    assertIs<Truncated>(verdict).atOffset,
                    "the verdict stops at the last intact frame's end — it is a resume cursor",
                )
            },
            {
                assertEquals(
                    frames.size,
                    events.indexOfFirst { it !is Archived<*> },
                    "the verdict is LAST — every event before it is a frame, and none follows it",
                )
            },
        )
    }

    /**
     * The **discontinuous-archive** arm of the verdict contract, and the one no backend reached for
     * free.
     *
     * [aTruncatedArchiveStopsAtTheDamageAndSaysSo] drives damage *inside* a frame, which every layer
     * of the format is built to catch: a bad frame fails its own checksum. A hole between segments
     * presents **no bad bytes at all** — the surviving segments are perfectly intact, they simply do
     * not join up — so nothing checks it unless a backend compares each segment's absolute base
     * offset against where the previous one stopped. Two backends shipped without that check (#2240).
     *
     * Five assertions, and each pins a decision the truncation property does not:
     *
     * 1. the verdict is [Truncated], not a clean tail — a hole is damage, not something to step over;
     * 2. its `atOffset` is the last intact frame's `endOffset`, i.e. where the hole **starts** and not
     *    where the next segment picks up. Those differ by exactly the missing region, and the wrong
     *    one names an offset no frame this replay emitted ever ended at;
     * 3. its `reason` is [TruncationReason.MissingRegion] — the constant a consumer branches on to
     *    learn that `atOffset` is **not** somewhere to resume from, because the records between here
     *    and the next segment exist nowhere;
     * 4. **nothing from beyond the hole** is replayed, however intact those frames are — a history
     *    with a silent gap in it is the one thing [Bolt] cannot hand back;
     * 5. every frame before the hole survives, and the verdict is last.
     *
     * **Mutation receipts** (measured, see the PR for #2240):
     *
     * - Deleting the cross-segment continuity check in a backend's replay reddens (1), (4) and (5):
     *   the verdict becomes [CleanTail] and the frames from beyond the hole are emitted. This is the
     *   pre-#2240 state of both mmap backends, and this test is what reds against it.
     * - Reporting the *next* segment's `baseOffset` instead of the previous segment's end reddens
     *   (2) alone — the shape a check that fires at the right moment and answers with the wrong
     *   coordinate produces.
     * - Reporting [TruncationReason.SegmentHeader] (what both backends did before #2240, knowingly)
     *   reddens (3) alone. Nothing else notices, which is exactly why that lie survived two reviews.
     *
     * **What this property cannot reach**, said plainly because a table of reds invites the opposite
     * conclusion. It drives one shape of hole — a whole segment lost out of the middle — and only on
     * [ReplayScope.All]. It says nothing about a discontinuity discovered under a scope that prunes
     * segments (the first segment a pruning scope actually reads has nothing behind it to be checked
     * against, by construction), nor about a *backwards* jump, nor about a backend that reaches the
     * hole through its own within-segment extent bookkeeping instead and answers
     * [TruncationReason.Frame] — that last one would red here, correctly, but no fixture in the tree
     * produces it.
     */
    @Test
    fun anArchiveMissingAMiddleRegionStopsAtTheHoleAndSaysSo() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newDiscontinuousBolt(FixedClock(epoch), INTACT_FRAMES)

        val events = bolt.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()
        val verdict = events.lastOrNull()

        assertAll(
            {
                assertIs<Truncated>(
                    verdict,
                    "newDiscontinuousBolt must hand back an archive with a HOLE in it — a healthy one " +
                        "makes this obligation vacuous, so it fails here rather than passing",
                )
            },
            { assertEquals(1, events.count { it !is Archived<*> }, "exactly one terminal event, never two") },
            {
                assertEquals(
                    INTACT_FRAMES,
                    frames.size,
                    "every frame before the hole survives — and nothing from beyond it is replayed, " +
                        "however intact those frames are, because a history with a silent gap in it and " +
                        "offsets that jump is worse than a short answer that says it is short",
                )
            },
            {
                assertEquals<Long?>(
                    frames.lastOrNull()?.endOffset,
                    assertIs<Truncated>(verdict).atOffset,
                    "the verdict stops where the hole STARTS — the last intact frame's end, not where " +
                        "the next segment picks up",
                )
            },
            {
                assertEquals(
                    TruncationReason.MissingRegion,
                    assertIs<Truncated>(verdict).reason,
                    "a region that is GONE is not a torn tail: the two have opposite remedies, and this " +
                        "is the one a consumer must not resume from",
                )
            },
            {
                assertEquals(
                    frames.size,
                    events.indexOfFirst { it !is Archived<*> },
                    "the verdict is LAST — every event before it is a frame, and none follows it",
                )
            },
        )
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /**
     * [count] inserts appended to this replica by [alice], returned with the replica that holds
     * them. Threading one replica through successive calls is what keeps the minted ids distinct —
     * minting each batch from a fresh `Rga.empty()` would hand every batch the same `(lamport, seq)`
     * pairs and quietly collapse the dot assertions.
     */
    private fun Rga<String>.mintInserts(label: String, count: Int = 3): Pair<Rga<String>, List<RgaOp.Insert<String>>> {
        var live = this
        val ops = (0 until count).map { index ->
            val (next, op) = live.insertAt(alice, live.size, "$label-$index")
            live = next
            op
        }
        return live to ops
    }

    /**
     * An `Rga` that has compacted its **trailing** element, plus the ops that produced it.
     *
     * Trailing, not leading: `Rga.compact` refuses to GC an id a live insert still anchors on via
     * `after`, and signals that refusal by returning `null` rather than throwing. Compacting the
     * first of two elements would therefore yield no compaction at all — silently.
     */
    private fun rgaWithACompaction(): CompactionFixture {
        val (r1, first) = Rga.empty<String>().insertAt(alice, 0, "kept")
        val (r2, second) = r1.insertAt(bob, 1, "suppressed")
        val (r3, removal) = assertNotNull(r2.removeAt(1), "removeAt(1) must find the trailing element")

        val cut = VersionVector.of(mapOf(alice to first.id.seq, bob to second.id.seq))
        val (compacted, compactOp) = assertNotNull(
            r3.compact(stableCut = cut, frontierMax = cut, delivered = cut),
            "the tombstoned, causally-stable, unanchored trailing insert must be GC-eligible",
        )
        return CompactionFixture(
            contentOps = listOf(first, second, removal),
            compactOp = compactOp,
            compacted = compacted,
            suppressedInsert = second,
            suppressedRemove = removal,
        )
    }

    private class CompactionFixture(
        val contentOps: List<RgaOp<String>>,
        val compactOp: RgaOp.Compact,
        val compacted: Rga<String>,
        val suppressedInsert: RgaOp.Insert<String>,
        val suppressedRemove: RgaOp.Remove<String>,
    )

    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }

    private class MutableClock(private var current: Instant) : Clock {
        override fun now(): Instant = current

        fun advanceBy(step: Duration) {
            current += step
        }
    }

    private companion object {
        const val RECORDS = 4
        const val WINDOW_DROP = 2

        /** More than one, so "stopped at the damage" is distinguishable from "stopped at the start". */
        const val INTACT_FRAMES = 3
        val STEP = 10.seconds
    }
}
