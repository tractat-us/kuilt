package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What [MappedBolt] does when a [ReplayScope.FromOffset] resume lets it skip whole segment files
 * (#2236) — the correctness the pruning must not cost, and the I/O it must actually save.
 *
 * `BoltConformanceSuite` pins what every backend **answers**; this pins what only a backend that
 * prunes can get wrong, which is what it **reads**.
 *
 * ### The correctness half has moved out of this file
 *
 * When this file shipped it also carried "a resume from beyond a hole still reports it", because
 * promoting that needed a fixture hook every subclass implements and that did not belong in a perf
 * PR. #2268 added the hook, and the property now lives in the suite as
 * `resumingFromBeyondTheHoleReachesTheSameVerdictRatherThanACleanTail`, where it holds all three
 * backends to it — `PosixMappedBolt` reached the same answer by a different route and **nothing
 * anywhere reddened if its continuity carry was dropped**, which is the shape that shipped one
 * defect twice already (#2240).
 *
 * That promotion also retired the no-pre-allocated-tail duplicate of it here, and the claim is worth
 * stating exactly rather than generously: the suite drives both segment-tail shapes on both mapped
 * backends through its `TinySegment…` subclasses, so the *correctness* assertions lost nothing. The
 * pruning receipt below is no longer measured in the no-tail shape anywhere — which is deliberate,
 * because which segments get pruned is decided from **headers alone**, and a header is a fixed size
 * whatever follows it.
 *
 * ### What is left, and why it cannot follow
 *
 * Everything below is a claim about **bytes read off a filesystem**, and [Bolt] exposes no such
 * thing — a conformance property can only ask what a replay *said*, and every assertion in the suite
 * would hold on a backend that read the whole archive every time. So the suite proves this backend
 * answers correctly, and this file proves it answers correctly *cheaply*: that the pruned prefix is
 * genuinely skipped rather than read and forgiven ([aResumeOverAHoleReadsNothingBelowTheBoundary]),
 * that a caught-up resume reads nothing at all wherever the damage below it sits, and that only
 * [ReplayScope.FromOffset] prunes.
 */
class MappedBoltPruningTest {

    /**
     * A resume from **beyond** a lost segment reads nothing below the boundary segment — the pruning
     * receipt for the one archive shape where pruning is most tempting and most dangerous.
     *
     * Whether that resume reports the hole is `BoltConformanceSuite`'s question now
     * (`resumingFromBeyondTheHoleReachesTheSameVerdictRatherThanACleanTail`, over all three backends).
     * What is left here is the half a conformance property cannot ask: that the prefix below the
     * boundary was **skipped**, not read and forgiven. The scenario is a retention sweep — a consumer
     * reads up to `E`, an old segment file is swept behind it, it resumes from `E` — so the segments
     * this replay must not touch are the same ones whose disappearance it must still notice.
     *
     * Four assertions, in the order below, and the numbering the receipts use. 1 and 2 are inherited
     * preconditions rather than the claim: they are what stops 3 measuring an archive with no hole in
     * it, or one whose cursor sits where the suite's fixture already puts it.
     *
     * 1. **(precondition)** the fixture's archive really is discontinuous under [ReplayScope.All];
     * 2. **(precondition)** the resume cursor really is **past** the hole;
     * 3. **(the claim)** destroying the oldest segment's frame changes the resume's events not at all —
     *    so that segment was genuinely never read;
     * 4. **(the control for 3)** [ReplayScope.All] over that same damaged archive now stops at offset
     *    0, so 3 rests on a corruption that really landed rather than one that missed.
     *
     * **Mutation receipts**, each applied alone to `MappedBolt.firstSegmentToRead`, reverted, the
     * revert grep-verified, `build/test-results/jvmTest` deleted first, and the log checked for
     * `compileKotlinJvm`/`compileTestKotlinJvm FAILED` and `e: file` — a mutation that does not
     * compile leaves Gradle serving the *previous* run's XML, and the verdict it fabricates is a
     * plausible copy of the row before it.
     *
     * | Mutation | Reds here | Reds in `BoltConformanceSuite` |
     * |---|---|---|
     * | Return `firstUnpruned` — prune the boundary segment, dropping the continuity cursor | **none** | the promoted property, all three `MappedBolt` subclasses |
     * | Seed the cursor from the surviving header (`baseOffsetOf`) instead of parsing | **none** | the same, plus one `MappedBoltDamageTest` case |
     * | Return `0` — prune nothing, the shipped behaviour before #2236 | 3 | **none** |
     *
     * **The right-hand column has inverted since this file shipped, and that is the point of #2268.**
     * Every row used to read "none" there: a backend could lose its continuity carry outright and no
     * conformance case anywhere would say so, which is why the correctness assertions had to live in a
     * JVM-only file. They no longer do — and the left column inverted with it.
     *
     * **Rows 1 and 2 are green *here*, and that is structural rather than an oversight.** Both prune
     * *more* than the shipped code, and assertion 3 works by corrupting a segment the shipped code
     * already skips — so under either mutation the corruption stays unread in **both** replays and the
     * two stay equal. Nothing in this file can see a defect that prunes too much; the promoted property
     * is the only thing that can, which is the whole argument for having promoted it.
     *
     * So row 3 is this file's entire warrant: "prune nothing" is a *performance* regression that
     * answers every question correctly, and no conformance property can be asked about it. **Its
     * coverage here is a subset of its siblings'** — [aResumeReadsFarLessThanThePrefixItPrunes] and
     * [everyScopeWithoutAnOffsetStillReadsTheWholeArchive] red under it too. What this test alone
     * drives is that receipt over a **discontinuous** archive, where the temptation to prune the
     * boundary is greatest and the cost of doing so is a silently gapped history.
     */
    @Test
    fun aResumeOverAHoleReadsNothingBelowTheBoundary() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = FixedClock(EPOCH)
        val archive = oneFramePerSegmentArchive(
            clock,
            HOLE_FIXTURE_FRAMES,
            PIN_PAYLOAD_CHARS,
        )
        check(segmentsIn(archive.directory)[LOST_SEGMENT].delete()) { "the fixture's hole must be punched" }
        val bolt = archive.reopened(clock)

        val whole = bolt.replay(ReplayScope.All).toList()
        // What a consumer that had already read past the lost segment hands back.
        val cursor = archive.written[LOST_SEGMENT + 1].offset
        val resumed = bolt.replay(ReplayScope.FromOffset(cursor)).toList()
        // Destroy the OLDEST segment's frame — the one the cursor is far past. A backend that reads
        // it cannot then answer as if it had not.
        flipByteAt(segmentsIn(archive.directory).first(), segmentHeaderBytes() + FIRST_BODY_BYTE)
        val resumedOverDamagedPrefix = bolt.replay(ReplayScope.FromOffset(cursor)).toList()
        val wholeOverDamagedPrefix = bolt.replay(ReplayScope.All).toList()

        assertAll(
            {
                assertEquals(
                    Truncated(archive.written[LOST_SEGMENT].offset, TruncationReason.MissingRegion),
                    whole.lastOrNull(),
                    "the fixture must hand back an archive with a HOLE in it — a healthy one makes " +
                        "every assertion below vacuous, so it fails here rather than passing",
                )
            },
            {
                // `cursor > written[LOST_SEGMENT].offset` would be the obvious wording and cannot
                // fail — append offsets increase — so it is the hole's FAR EDGE that is asserted:
                // the surviving frame must start exactly where the lost one ended.
                assertEquals(
                    archive.written[LOST_SEGMENT].endOffset,
                    cursor,
                    "the cursor must sit at the far edge of the hole — inside it the boundary segment " +
                        "is unprunable, and there is no pruned prefix left to corrupt",
                )
            },
            {
                assertEquals(
                    resumed,
                    resumedOverDamagedPrefix,
                    "destroying a pruned segment's frame changes nothing — so it was genuinely SKIPPED " +
                        "rather than read and forgiven, which is the whole claim of #2236",
                )
            },
            {
                assertEquals(
                    Truncated(0L, TruncationReason.Frame),
                    wholeOverDamagedPrefix.lastOrNull(),
                    "and the control: an unpruned replay over that same archive stops at the damage, so " +
                        "the assertion above rests on a corruption that really landed",
                )
            },
        )
    }

    /**
     * A **caught-up** resume — `FromOffset(archive end)`, the commonest `FromOffset` call there is —
     * reads nothing at all, and answers the same way wherever the damage below it sits.
     *
     * Two claims, and the second is the one that cost a review round. It is easy to say "damage below
     * the cursor is invisible to a resume" and easy to *implement* something that means "…except in
     * the newest few segments": pruning walks a prefix, so a formulation that always reads the last
     * `k` segments makes the answer a function of **how many segments have rolled since**, not of the
     * archive's contents. A consumer polling an unchanged, unrepaired archive would then get
     * `Truncated` today and [CleanTail] after two more rolls — and [Bolt]'s KDoc has consumers branch
     * hard on exactly that verdict. Position-independence is the property; the byte count is how it is
     * kept honest.
     *
     * The [ReplayScope.All] arm is the control: it must see every one of these, or the fixture is
     * asserting that undamaged archives replay cleanly.
     *
     * **Not covered, deliberately:** damage in the **newest** segment. That is not damage *below* an
     * archive-end cursor at all — a torn newest segment moves the append cursor down to its last
     * intact frame, so the damage sits at or above it — and re-opening classifies it as a torn tail
     * or an unappendable archive before any replay runs.
     *
     * **Mutation receipt**, measured on *this* fixture: deleting the caught-up short-circuit in
     * [replay] reds the first two assertions. The five verdicts become four [CleanTail]s and a
     * `Truncated(atOffset=547, reason=Frame)` — only the **deepest** damage position changes its
     * answer — and every resume reads `531` bytes where it should read `0`. That single differing
     * entry is the whole shape this test exists to rule out: the same bytes read two ways according
     * to where the tail happens to be.
     */
    @Test
    fun aCaughtUpResumeAnswersTheSameWhereverTheDamageIs() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = FixedClock(EPOCH)
        // One archive per damage position: the corrupted byte has to be the only difference between
        // them, so a shared archive re-damaged in place would not do.
        val damagePositions = 0 until TAIL_FIXTURE_FRAMES - 1
        val outcomes = damagePositions.map { damaged ->
            val archive = oneFramePerSegmentArchive(
                clock,
                TAIL_FIXTURE_FRAMES,
                PIN_PAYLOAD_CHARS,
            )
            flipByteAt(segmentsIn(archive.directory)[damaged], segmentHeaderBytes() + FIRST_BODY_BYTE)
            val bolt = archive.reopened(clock)
            val caughtUp = bolt.measuredReplay(ReplayScope.FromOffset(archive.written.last().endOffset))
            DamageOutcome(damaged, caughtUp, bolt.measuredReplay(ReplayScope.All))
        }

        assertAll(
            {
                assertEquals(
                    damagePositions.map { CleanTail },
                    outcomes.map { it.caughtUp.events.lastOrNull() },
                    "a caught-up resume answers CleanTail whatever the damage below it is and WHEREVER " +
                        "it sits — an answer that changes with the damage's distance from the tail " +
                        "changes as the archive grows, over bytes that never moved",
                )
            },
            {
                assertEquals(
                    damagePositions.map { 0L },
                    outcomes.map { it.caughtUp.fileBytesRead },
                    "and reads nothing at all: a consumer that is caught up is the commonest FromOffset " +
                        "caller there is, and the archive already knows where its frames end",
                )
            },
            {
                assertEquals(
                    damagePositions.toList(),
                    outcomes.filter { it.whole.events.lastOrNull() is Truncated }.map { it.damagedSegment },
                    "the control: an unscoped replay sees every one of those, so each fixture really " +
                        "is damaged and the silence above is pruning rather than a corruption that missed",
                )
            },
        )
    }

    /**
     * A resume reads **fewer bytes than the prefix it pruned holds** — the claim #2236 is actually
     * about.
     *
     * Asserted on bytes read off the segment files rather than on wall-clock, which on a contended
     * box measures the box; and rather than on frames emitted or parsed, which the scope filter
     * already guarantees and an implementation that read every file whole would still satisfy.
     *
     * The bound is structural, not a tuned ratio: the pruned prefix's files are counted from the
     * filesystem, and a replay that read them could not have come in under their total. The
     * [ReplayScope.All] arm is the control — it establishes what "read the archive" costs on this
     * fixture, exactly, so a saving cannot be an artefact of a small denominator.
     *
     * Measured on this fixture: the resume reads **7,431 bytes** where [ReplayScope.All] reads
     * **24,607**, and the prefix it skipped holds **17,896** on its own.
     *
     * **Mutation receipt:** returning `0` from `MappedBolt.firstSegmentToRead` — the shipped
     * behaviour before #2236 — reds assertions 3 and 4: the resume goes from 7,431 bytes to
     * **25,327**, which is every byte [ReplayScope.All] reads plus the header probes. Assertions 1
     * and 2 are the fixture's own preconditions and stay green, which is what they are for.
     */
    @Test
    fun aResumeReadsFarLessThanThePrefixItPrunes() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = FixedClock(EPOCH)
        val archive = oneFramePerSegmentArchive(
            clock,
            PRUNING_FIXTURE_FRAMES,
            PRUNED_PAYLOAD_CHARS,
        )
        val bolt = archive.reopened(clock)
        val files = segmentsIn(archive.directory)
        // The newest segment is the ACTIVE one: its bytes come out of the live mapping under the
        // lock, never off disk, so it can never appear in either measurement.
        val onDisk = files.dropLast(1).sumOf { it.length() }
        val prunedPrefix = files.take(RESUME_FROM_SEGMENT - 1).sumOf { it.length() }

        val whole = bolt.measuredReplay(ReplayScope.All)
        val resumed = bolt.measuredReplay(ReplayScope.FromOffset(archive.written[RESUME_FROM_SEGMENT].offset))

        assertAll(
            {
                assertEquals(
                    PRUNING_FIXTURE_FRAMES,
                    files.size,
                    "the fixture needs one frame per segment, or there is no prefix to prune",
                )
            },
            {
                assertEquals(
                    onDisk,
                    whole.fileBytesRead,
                    "the control: an unscoped replay reads every segment file on disk, exactly once — " +
                        "which is what a FromOffset resume used to cost too",
                )
            },
            {
                assertTrue(
                    resumed.fileBytesRead < prunedPrefix,
                    "the resume read ${resumed.fileBytesRead} bytes, fewer than the $prunedPrefix the " +
                        "prefix it pruned holds — so it demonstrably did not read them",
                )
            },
            {
                assertTrue(
                    resumed.fileBytesRead < whole.fileBytesRead,
                    "and fewer than reading the archive, which is the regression this guards: " +
                        "${resumed.fileBytesRead} against ${whole.fileBytesRead}",
                )
            },
            {
                assertEquals(
                    archive.written.drop(RESUME_FROM_SEGMENT).map { it.offset },
                    resumed.frames.map { it.offset },
                    "and it still replays every frame the cursor selects — a cheaper answer that is " +
                        "short is not an answer",
                )
            },
            { assertEquals(CleanTail, resumed.events.lastOrNull(), "over an intact archive, cleanly") },
        )
    }

    /**
     * Pruning stays **scope-gated**: only [ReplayScope.FromOffset] carries an offset predicate, so
     * every other scope still reads the whole archive.
     *
     * For [ReplayScope.All] a header pass would be pure overhead. For [ReplayScope.Arrived] and
     * [ReplayScope.InsertsAbove] it would be worse than overhead — an arrival time and a frame's
     * dots live in its *body*, so no segment header can rule a segment out, and a backend that let
     * the `FromOffset` predicate leak into them would silently drop frames those scopes select.
     *
     * **Mutation receipt:** dropping the `scope !is ReplayScope.FromOffset` guard does not compile —
     * there is no `offset` to read — so the reachable mutation is letting every other scope prune
     * against `Long.MAX_VALUE`. Measured, it reds all four arms at once: the three unscoped replays
     * drop from 24,607 bytes to 2,957, and [ReplayScope.Arrived] hands back 2 of 12 frames. That last
     * number is the point — an over-eager prune is not a cheaper answer, it is a wrong one.
     */
    @Test
    fun everyScopeWithoutAnOffsetStillReadsTheWholeArchive() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = FixedClock(EPOCH)
        val archive = oneFramePerSegmentArchive(
            clock,
            PRUNING_FIXTURE_FRAMES,
            PRUNED_PAYLOAD_CHARS,
        )
        val bolt = archive.reopened(clock)
        val onDisk = segmentsIn(archive.directory).dropLast(1).sumOf { it.length() }

        val all = bolt.measuredReplay(ReplayScope.All)
        val arrived = bolt.measuredReplay(ReplayScope.Arrived(EPOCH, EPOCH + ONE_STEP))
        val dots = bolt.measuredReplay(ReplayScope.InsertsAbove(VersionVector.EMPTY))
        val byOffset = bolt.measuredReplay(ReplayScope.FromOffset(archive.written[RESUME_FROM_SEGMENT].offset))

        assertAll(
            { assertEquals(onDisk, all.fileBytesRead, "All reads every segment file") },
            { assertEquals(onDisk, arrived.fileBytesRead, "so does Arrived — an arrival time is in a BODY") },
            { assertEquals(onDisk, dots.fileBytesRead, "and InsertsAbove — a frame's dots are too") },
            {
                assertTrue(
                    byOffset.fileBytesRead < onDisk,
                    "and only FromOffset prunes, or this test is measuring nothing",
                )
            },
            {
                assertEquals(
                    PRUNING_FIXTURE_FRAMES,
                    arrived.frames.size,
                    "the Arrived window must select every frame, or its read cost is trivially explained",
                )
            },
        )
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /**
     * An archive of [frameCount] frames, **one per segment file**, each carrying a [payloadChars]-long
     * element, with [PRE_ALLOCATED_TAIL_BYTES] of pre-allocated tail behind every frame.
     *
     * The budget is measured off a real encoded frame rather than guessed: it has to be big enough
     * for one frame and too small for two, and a fixture that quietly packed two frames into a file
     * would have no segment to prune and no boundary to lose.
     *
     * **The tail is a constant here, not a knob, and that is a deliberate narrowing.** It used to be a
     * parameter so a sibling test could drive segments ending exactly on a frame boundary; #2268 moved
     * that test's correctness assertions into `BoltConformanceSuite`, whose `TinySegment…` subclasses
     * drive both tail shapes on both mapped backends. What is left in this file measures *which
     * segments were read*, which is decided from headers alone and so cannot depend on the tail. A
     * parameter with one value left is a configuration nobody sets.
     */
    private suspend fun oneFramePerSegmentArchive(
        clock: Clock,
        frameCount: Int,
        payloadChars: Int,
    ): PrunableArchive {
        val directory = tempArchiveDirectory()
        val format = rgaStringFormat()
        val payload = "x".repeat(payloadChars)
        val ops = buildList {
            var live = Rga.empty<String>()
            repeat(frameCount) { index ->
                val (next, op) = live.insertAt(FIXTURE_REPLICA, live.size, "$index-$payload")
                live = next
                add(op)
            }
        }
        val frameBytes = encodeFrame(
            RawFrame(clock.now(), setOf(ops[0].id.dot), null, listOf(format.encode(ops[0]))),
        ).size.toLong()
        check(PRE_ALLOCATED_TAIL_BYTES < frameBytes) { "that pad would leave room for a second frame" }
        val budget = frameBytes + PRE_ALLOCATED_TAIL_BYTES
        val bolt = mappedBolt(clock, directory, segmentFrameBytes = budget)
        val written = ops.map { assertIs<AppendResult.Written>(bolt.append(listOf(it)), "every frame is written") }
        check(segmentsIn(directory).size == frameCount) {
            "the fixture needs one frame per segment, or there is nothing to prune"
        }
        // VERIFIED, not merely configured: which shape a segment ends in decides which exit its
        // parse takes, and that is where this backend computes the boundary offset from.
        val tail = segmentsIn(directory).first().length() - segmentHeaderBytes() - frameBytes
        check(tail > 0L) {
            "every segment must carry the pre-allocated tail this fixture asked for, and the oldest " +
                "one measured $tail bytes"
        }
        return PrunableArchive(directory, budget, written)
    }

    /**
     * An archive on disk, and the appends that built it.
     *
     * [reopened] is how every replay here reads: a second instance over the same directory shares no
     * in-memory state, so it must read the segments back off the files — which is both what a restart
     * does and what makes the byte counter mean anything.
     */
    private class PrunableArchive(
        val directory: File,
        private val budget: Long,
        val written: List<AppendResult.Written>,
    ) {
        fun reopened(clock: Clock): MappedBolt<RgaId, String, RgaOp<String>> =
            mappedBolt(clock, directory, segmentFrameBytes = budget)
    }

    /** One damage position, and what the two scopes made of it. */
    private class DamageOutcome(val damagedSegment: Int, val caughtUp: MeasuredReplay, val whole: MeasuredReplay)

    /** One replay's events, and the segment-file bytes it cost. */
    private class MeasuredReplay(val events: List<ReplayEvent<RgaOp<String>>>, val fileBytesRead: Long) {
        val frames: List<Archived<RgaOp<String>>> get() = events.filterIsInstance<Archived<RgaOp<String>>>()
    }

    /**
     * Collect a replay and report what it read off disk.
     *
     * A **difference** across this one replay rather than the counter's absolute value, because the
     * counter is monotonic for the life of the bolt and these tests replay the same archive several
     * times over.
     */
    private suspend fun MappedBolt<RgaId, String, RgaOp<String>>.measuredReplay(
        scope: ReplayScope,
    ): MeasuredReplay {
        val before = segmentFileBytesRead()
        val events = replay(scope).toList()
        return MeasuredReplay(events, segmentFileBytesRead() - before)
    }

    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }

    private companion object {
        val FIXTURE_REPLICA = ReplicaId("alice")
        val EPOCH: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val ONE_STEP = 1.seconds

        /**
         * Five frames: one pruned segment, then the boundary segment, then the hole, then **two**
         * frames behind it.
         *
         * **An argument, not a measurement, and the difference is the point.** The obvious rationale
         * — "at four, 'stepped over the hole' and 'off by one' produce the same count" — is
         * `BoltConformanceSuite.newDiscontinuousBolt`'s, where the surviving frames are compared
         * against `INTACT_FRAMES` and an off-by-one really is invisible. It does **not** transfer
         * here, and since #2268 it transfers even less: nothing in this file counts frames any more.
         * What the remaining claim needs is simply a pruned prefix to corrupt, which is what
         * [LOST_SEGMENT] is chosen against.
         *
         * What five buys over four is the same shape the shared suite's fixture has, so the two can be
         * read against each other. Lowering it to four would not make this test vacuous; lowering it
         * far enough to leave no pruned prefix would, and [LOST_SEGMENT] documents that boundary as a
         * measured red rather than an argument.
         */
        const val HOLE_FIXTURE_FRAMES = 5

        /**
         * The segment whose file is deleted, and the knob that decides whether anything is pruned
         * at all.
         *
         * Pruning needs a segment whose **successor** starts at or below the cursor, and the cursor is
         * the base of the segment after the hole. At `LOST_SEGMENT = 1` the only candidate is the
         * archive's first segment, whose successor *is* the boundary segment — so nothing is pruned,
         * segment 0 is read, and assertion 3's corruption lands in a segment this replay looks at.
         *
         * **Measured, because the interesting question is which way it fails:** at `1` the test is
         * **red on unmutated production code** —
         * `expected:<[Truncated(atOffset=139, reason=MissingRegion)]> but was:<[Truncated(atOffset=0, reason=Frame)]>`
         * — not silently vacuous. This constant is load-bearing and **self-guarding**, which is the
         * opposite of this epic's recurring fixture hazard and worth writing down as such: lowering it
         * cannot quietly turn assertion 3 into a row that claims a receipt it no longer has.
         *
         * Re-measured after #2268 reshaped the assertions around it, and the red is byte-for-byte the
         * one quoted above — which is the point of re-measuring rather than renumbering: a receipt
         * that survives a rewrite unverified is a receipt for code that no longer exists.
         */
        const val LOST_SEGMENT = 2

        /**
         * Twelve frames and a resume at the tenth: enough pruned prefix that a fixed-size header
         * probe per segment is unmistakably cheaper than the files it replaces. At three or four the
         * probes are a large fraction of the archive and the byte assertion would be measuring the
         * header size rather than the pruning.
         */
        const val PRUNING_FIXTURE_FRAMES = 12
        const val RESUME_FROM_SEGMENT = 9

        /**
         * Big enough that a segment file dwarfs its own header, so the saving being measured is the
         * frames rather than the probes. A few bytes here and the two costs are the same order and
         * the assertion says nothing.
         */
        const val PRUNED_PAYLOAD_CHARS = 2000

        /** The pin does not care how big a frame is — only where the segment boundaries fall. */
        const val PIN_PAYLOAD_CHARS = 8

        /**
         * Six segments, so the damage sweep reaches depths a "read the last `k`" formulation would
         * treat differently.
         *
         * At two or three every segment is within the last two and the sweep cannot tell
         * position-independence from "the newest few are always read" — the very confusion this test
         * exists to rule out. Six over the swept positions (`0 until 5`) leaves four depths a `k` of
         * 2 never reaches and exactly **one** — position 4 — that it does; position 3 would need a
         * `k` of 3. That single reachable depth is what the receipt above measures, and it is why the
         * red is one changed entry rather than a wholesale flip.
         */
        const val TAIL_FIXTURE_FRAMES = 6

        /** A real pre-allocated tail on every segment. Smaller than a frame, so one frame lands per file. */
        const val PRE_ALLOCATED_TAIL_BYTES = 32L

        /**
         * The first byte of a frame's body — past the length prefix, so flipping it breaks the frame's
         * checksum without moving anything behind it.
         */
        const val FIRST_BODY_BYTE = 4L
    }
}
