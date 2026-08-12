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
 * `BoltConformanceSuite` pins what every backend answers; this pins the two things that are *only*
 * true of a backend that prunes, and that no property in that suite can see.
 *
 * ### Why this file exists rather than another conformance obligation
 *
 * `resumingFromTheHoleReachesTheSameVerdictRatherThanACleanTail` is the suite's pruning property,
 * and its own mutation table records it as **green either way** on this backend — at the time,
 * because `MappedBolt` pruned nothing at all. Adding pruning does not on its own make that row
 * discriminating, and the reason is worth stating because it contradicts the obvious expectation:
 * the suite's fixture puts the cursor at the **start** of the hole, and a segment can be pruned here
 * only when its *successor's* `baseOffset` is at or below the cursor — so the segment before the
 * hole is never prunable at that cursor, and the boundary it would have checked is read anyway.
 * See [MappedBolt.firstSegmentToRead] for the full argument.
 *
 * What *is* reachable — and what [aResumeFromBeyondAHoleStillReportsIt] drives — is a consumer that
 * resumes from the **far side** of the hole, the shape a retention sweep produces: it consumed those
 * records, then the segment holding them went away. `InMemoryBolt` and `PosixMappedBolt` both report
 * that hole today; without the boundary read this backend would not, and the three backends would
 * disagree about one archive. That belongs in the shared suite eventually — it needs a fixture hook
 * every backend implements, so it is filed rather than smuggled into a perf PR (#2268).
 */
class MappedBoltPruningTest {

    /**
     * A resume from **beyond** a lost segment still reports the hole — and the segments below the
     * cursor are never read at all.
     *
     * The scenario is a retention sweep, not an exotic corruption: a consumer reads up to `E`, an
     * old segment file is removed behind it, and it resumes from `E`. The records between the end of
     * the surviving prefix and `E` now exist nowhere, so an archive whose whole product is "I still
     * hold what the live replica forgot" must not answer [CleanTail] to that resume.
     *
     * Six assertions, in the order below, and the numbering the receipts use:
     *
     * 1. **(precondition)** the fixture's archive really is discontinuous under [ReplayScope.All];
     * 2. **(precondition)** the resume cursor really is **past** the hole, so this is not the shape
     *    the conformance suite already drives;
     * 3. the resume replays nothing;
     * 4. and reaches the *same* verdict [ReplayScope.All] does;
     * 5. **(the pruning receipt)** destroying the oldest segment's frame changes neither — so that
     *    segment was genuinely skipped, not read and tolerated;
     * 6. **(the control for 5)** [ReplayScope.All] over that same damaged archive now stops at
     *    offset 0, so the damage in 5 is real rather than a corruption that missed.
     *
     * **Mutation receipts**, each applied alone to [MappedBolt.firstSegmentToRead], reverted, and the
     * verdict read out of the results XML:
     *
     * | Mutation | Reds |
     * |---|---|
     * | Return `firstUnpruned` — prune the boundary segment, dropping the continuity cursor | 3, 4 |
     * | Seed the cursor from the surviving header (`baseOffsetOf(reads[firstUnpruned])`) instead of parsing | 3, 4 |
     * | Return `0` — prune nothing, the shipped behaviour before #2236 | 5 |
     *
     * The first two are the landmine #2244 named: both hand back `CleanTail` plus two frames from
     * beyond a hole. The third is the honest complement — it says assertion 5 is the only thing here
     * that can tell pruning from not pruning, and that assertions 3 and 4 would go green on a backend
     * that reads everything.
     */
    @Test
    fun aResumeFromBeyondAHoleStillReportsIt() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        resumeFromBeyondAHoleStillReportsIt(PRE_ALLOCATED_TAIL_BYTES)
    }

    /**
     * [aResumeFromBeyondAHoleStillReportsIt] again, with segments that end **exactly on a frame
     * boundary**.
     *
     * The complement is not decoration. A segment with a pre-allocated tail ends its parse by failing
     * to read a frame out of the zero region; one sized to the single frame that forced it runs out
     * of bytes and leaves the loop normally. Those are two different exits, each computing the
     * boundary offset this property rests on, and the suite's own fixture notes record a real backend
     * defect that lived in exactly the gap between them.
     */
    @Test
    fun aResumeFromBeyondAHoleStillReportsItWithNoPreAllocatedTail() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        resumeFromBeyondAHoleStillReportsIt(NO_PRE_ALLOCATED_TAIL)
    }

    private suspend fun resumeFromBeyondAHoleStillReportsIt(zeroTailBytes: Long) {
        val clock = FixedClock(EPOCH)
        val archive = oneFramePerSegmentArchive(clock, HOLE_FIXTURE_FRAMES, PIN_PAYLOAD_CHARS, zeroTailBytes)
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
                assertTrue(
                    cursor > archive.written[LOST_SEGMENT].offset,
                    "the cursor must sit BEYOND the hole — at its start this is the shape " +
                        "BoltConformanceSuite already drives, and the boundary segment is unprunable",
                )
            },
            {
                assertEquals(
                    0,
                    resumed.filterIsInstance<Archived<RgaOp<String>>>().size,
                    "a resume from past the hole replays NOTHING — the frames beyond it are real, but " +
                        "the records the cursor claims to have consumed are gone, so their history is not",
                )
            },
            {
                assertEquals(
                    whole.lastOrNull(),
                    resumed.lastOrNull(),
                    "and reaches the SAME verdict: pruning a prefix must not prune the one boundary " +
                        "that proves the surviving archive joins up",
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
     * **Mutation receipt:** returning `0` from [MappedBolt.firstSegmentToRead] — the shipped
     * behaviour before #2236 — reds assertions 3 and 4 (the resume reads every byte [ReplayScope.All]
     * does). Assertions 1 and 2 are the fixture's own preconditions and stay green, which is what
     * they are for.
     */
    @Test
    fun aResumeReadsFarLessThanThePrefixItPrunes() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = FixedClock(EPOCH)
        val archive = oneFramePerSegmentArchive(
            clock,
            PRUNING_FIXTURE_FRAMES,
            PRUNED_PAYLOAD_CHARS,
            PRE_ALLOCATED_TAIL_BYTES,
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
     * **Mutation receipt:** dropping the `scope !is ReplayScope.FromOffset` guard does not compile
     * (there is no `offset` to read), so the reachable mutation is comparing against a constant
     * cursor — which reds every arm here at once.
     */
    @Test
    fun everyScopeWithoutAnOffsetStillReadsTheWholeArchive() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = FixedClock(EPOCH)
        val archive = oneFramePerSegmentArchive(
            clock,
            PRUNING_FIXTURE_FRAMES,
            PRUNED_PAYLOAD_CHARS,
            PRE_ALLOCATED_TAIL_BYTES,
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
     * element, with [zeroTailBytes] of pre-allocated tail behind every frame.
     *
     * The budget is measured off a real encoded frame rather than guessed: it has to be big enough
     * for one frame and too small for two, and a fixture that quietly packed two frames into a file
     * would have no segment to prune and no boundary to lose.
     */
    private suspend fun oneFramePerSegmentArchive(
        clock: Clock,
        frameCount: Int,
        payloadChars: Int,
        zeroTailBytes: Long,
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
        check(zeroTailBytes < frameBytes) { "a $zeroTailBytes-byte pad would leave room for a second frame" }
        // A ONE-byte budget is how "no tail at all" is expressed: a segment is allocated at
        // `maxOf(budget, frame.size)`, so a one-byte budget sizes each file to exactly the frame that
        // forced it. Padding a measured frame size would not do it — these frames grow by a few bytes
        // as the `Rga` ids do, so every segment but the first would still get an accidental tail.
        val budget = if (zeroTailBytes == NO_PRE_ALLOCATED_TAIL) 1L else frameBytes + zeroTailBytes
        val bolt = mappedBolt(clock, directory, segmentFrameBytes = budget)
        val written = ops.map { assertIs<AppendResult.Written>(bolt.append(listOf(it)), "every frame is written") }
        check(segmentsIn(directory).size == frameCount) {
            "the fixture needs one frame per segment, or there is nothing to prune"
        }
        // VERIFIED, not merely configured: which shape a segment ends in decides which exit its
        // parse takes, and that is where this backend computes the boundary offset from.
        val tail = segmentsIn(directory).first().length() - segmentHeaderBytes() - frameBytes
        check(if (zeroTailBytes == NO_PRE_ALLOCATED_TAIL) tail == 0L else tail > 0L) {
            "a segment must end the way this fixture says it does, and its tail measured $tail bytes " +
                "against a request for $zeroTailBytes"
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
         * The two behind the hole are why it is five rather than four. At four there is exactly one
         * frame past the hole, so "stepped over the hole" and "off by one" produce the same event
         * count and assertions 3 and 4 stop discriminating between them — which is the reason
         * `BoltConformanceSuite.newDiscontinuousBolt` mandates the same thing.
         */
        const val HOLE_FIXTURE_FRAMES = 5

        /**
         * The segment whose file is deleted, and the knob that decides whether anything is pruned
         * at all.
         *
         * Pruning here needs a segment whose **successor** starts at or below the cursor, and the
         * cursor is the base of the segment after the hole. At `LOST_SEGMENT = 1` the only candidate
         * is the archive's first segment, whose successor *is* the boundary segment, so nothing is
         * pruned: the test would drive the unpruned path while assertion 5 still claimed the row.
         * At 2 the oldest segment is genuinely skipped, which is what assertion 5 measures.
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

        /** A real pre-allocated tail on every segment. Smaller than a frame, so one frame lands per file. */
        const val PRE_ALLOCATED_TAIL_BYTES = 32L

        /** No tail: every segment is sized to the one frame that forced it. */
        const val NO_PRE_ALLOCATED_TAIL = 0L

        /**
         * The first byte of a frame's body — past the length prefix, so flipping it breaks the frame's
         * checksum without moving anything behind it.
         */
        const val FIRST_BODY_BYTE = 4L
    }
}
