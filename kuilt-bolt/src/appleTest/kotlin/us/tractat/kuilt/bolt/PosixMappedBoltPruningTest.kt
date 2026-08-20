package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What [PosixMappedBolt] does when a [ReplayScope.FromOffset] resume lets it skip whole segment
 * files — the I/O the pruning must actually save.
 *
 * `BoltConformanceSuite` pins what every backend **answers**, on all three of them. This pins the
 * half a conformance property cannot ask, which is what a replay **read**. [Bolt] exposes no such
 * thing, and #2331 settled that at the TCK level rather than leaving it as an omission: every
 * assertion in the suite holds on a backend that reads the whole archive every time and then
 * filters. Bytes touched on a filesystem need a backend-specific instrument.
 *
 * ### Why a second copy of `MappedBoltPruningTest` rather than a shared one
 *
 * Because the instrument cannot be shared. `MappedBolt` counts a `RandomAccessFile` probe and a
 * `readBytes`; this backend counts `read(2)`. Until #2337 only the JVM had one — so the identical
 * pruning decision in this class was pinned by nothing at all, which is precisely the state two
 * independently-written backends were in when they shipped the same defect twice (#2240).
 *
 * ### Three differences from the JVM twin, each of which moves the numbers
 *
 * 1. **Every segment is read, the newest included.** This backend never replays out of its live
 *    mapping (see [PosixMappedBolt]'s "Replay reads, it does not map"), so it has no free segment and
 *    the control arm's total is *every* file in the directory. `MappedBoltPruningTest` drops the last
 *    one, because there the active segment's bytes are copied out of the mapping under the lock.
 * 2. **Pruning itself costs nothing to decide.** `skippable` reads the `baseOffset` and extent that
 *    adoption already holds in memory, where `MappedBolt.firstSegmentToRead` pays a bounded header
 *    probe per decision. So the twin's "at three or four frames the probes are a large fraction of
 *    the archive" argument does **not** transfer, and the counts here are exact multiples of one
 *    segment file.
 * 3. **Adoption is lazy.** `MappedBolt` recovers eagerly in `init`; this one reads the directory on
 *    the first `append`, `availability` or `replay`. So the archive is opened *before* either arm is
 *    measured — left inside the first arm, adoption's cost would land on the control and make the
 *    saving below read larger than it is.
 */
class PosixMappedBoltPruningTest {

    /**
     * A resume reads **fewer bytes than the prefix it pruned holds** — the claim #2236 is actually
     * about, on the backend that had no way to make it.
     *
     * Asserted on bytes read off the segment files rather than on wall-clock, which on a contended
     * box measures the box; and rather than on frames emitted or parsed, which the scope filter
     * already guarantees and an implementation that read every file whole would still satisfy.
     *
     * The bound is structural, not a tuned ratio: the pruned prefix's files are `stat`ed off the
     * filesystem, and a replay that read them could not have come in under their total. The
     * [ReplayScope.All] arm is the control — it establishes what "read the archive" costs on this
     * fixture, exactly, so a saving cannot be an artefact of a small denominator.
     *
     * Six assertions, in the order below, and the numbering the receipt uses. 1 and 2 are the
     * fixture's own preconditions rather than the claim.
     *
     * 1. **(precondition)** the fixture really did put one frame in each segment file;
     * 2. **(the control)** [ReplayScope.All] reads every segment file on disk, exactly once;
     * 3. **(the claim)** the resume reads fewer bytes than the prefix it pruned holds;
     * 4. **(the regression)** and fewer than reading the archive;
     * 5. it still replays every frame the cursor selects;
     * 6. and ends [CleanTail], over an intact archive.
     *
     * Measured on this fixture: the resume reads **8,948 bytes** where [ReplayScope.All] reads
     * **26,844**, and the prefix it skipped holds **17,896** on its own. Twelve identical segment
     * files of 2,237 bytes, so those are exactly 4, 12 and 8 whole files — the round multiples
     * difference 2 above buys, where the JVM twin's 7,431 carries eight header probes on top of its
     * three files.
     *
     * **Mutation receipt:** forcing `firstUnpruned` to `0` in [PosixMappedBolt.replay] — pruning
     * nothing, the shipped behaviour before #2236 — reds assertions 3 and 4, and only those. The
     * resume goes from 8,948 bytes to **26,844**, which is every byte [ReplayScope.All] reads:
     *
     * ```
     * 3: the resume read 26844 bytes, fewer than the 17896 the prefix it pruned holds …
     * 4: and fewer than reading the archive, which is the regression this guards: 26844 against 26844
     * ```
     *
     * Assertions 1, 2, 5 and 6 stay green, which is what they are for — 5 in particular is the point
     * that an over-eager prune would be a *wrong* answer while this mutation is only an expensive
     * one. The red is two of six, so a reader checking the shape rather than the presence of the red
     * should see exactly those two byte-count lines and no verdict or frame-list failure.
     */
    @Test
    fun aResumeReadsFarLessThanThePrefixItPrunes() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = FixedClock(EPOCH)
        val archive = oneFramePerSegmentArchive(clock, PRUNING_FIXTURE_FRAMES, PRUNED_PAYLOAD_CHARS)
        val bolt = archive.reopened(clock)
        // Adoption happens on the first open and is NOT part of either arm — see difference 3 above.
        // It is done here, and checked, because a prime that quietly read nothing is green by
        // absence: adoption's cost would silently join the control arm and inflate the denominator
        // every assertion below is measured against.
        val opened = bolt.availability()
        check(opened == BoltAvailability.Available) { "the fixture archive must open cleanly, and said $opened" }
        check(bolt.segmentFileBytesRead() > 0L) {
            "opening must have READ the archive — an open that read nothing leaves adoption's cost " +
                "inside whichever arm runs first, which is the control"
        }

        val files = segmentFiles(archive.directory)
        // Every file, unlike the JVM twin: this backend reads the newest segment off disk too.
        val onDisk = files.sumOf { fileSize(it) }
        val prunedPrefix = files.take(RESUME_FROM_SEGMENT - 1).sumOf { fileSize(it) }

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

    @AfterTest
    fun removeArchives(): Unit = removeBoltTestDirectories()

    // ── fixtures ──────────────────────────────────────────────────────────────

    /**
     * An archive of [frameCount] frames, **one per segment file**, each carrying a [payloadChars]-long
     * element, with [PRE_ALLOCATED_TAIL_BYTES] of pre-allocated tail behind every frame.
     *
     * The budget is measured off a real encoded frame rather than guessed: it has to be big enough
     * for one frame and too small for two, and a fixture that quietly packed two frames into a file
     * would have a shorter prefix to prune than it claims.
     *
     * **The tail is a constant here, not a knob, and that is deliberate.** Which shape a segment ends
     * in decides which exit a segment's parse takes, and both shapes are driven on this backend by
     * `PosixMappedBoltConformanceTest` and `TinySegmentPosixMappedBoltConformanceTest`. What is
     * measured here is *which segments were read*, which this backend decides from `baseOffset` and
     * extent alone and so cannot depend on the tail. A parameter with one value left is a
     * configuration nobody sets.
     */
    private suspend fun oneFramePerSegmentArchive(
        clock: Clock,
        frameCount: Int,
        payloadChars: Int,
    ): PrunableArchive {
        val directory = boltTestDirectory()
        val format = rgaArchiveFormat()
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
        val writer = PosixMappedBolt(format, clock, directory, synchronous = true, segmentFrameBytes = budget)
        val written = ops.map { assertIs<AppendResult.Written>(writer.append(listOf(it)), "every frame is written") }
        // Closed before anything reads it: the writer holds the newest segment MAPPED, and a reader
        // over the same directory must see what is on disk — which is also what a restart sees.
        writer.close()
        check(segmentFiles(directory).size == frameCount) {
            "the fixture needs one frame per segment, or there is nothing to prune"
        }
        // VERIFIED, not merely configured: a pad that left room for a second frame would shorten the
        // pruned prefix without changing a single constant this test reads.
        val tail = fileSize(segmentFiles(directory).first()) - headerBytesOf(format) - frameBytes
        check(tail > 0L) {
            "every segment must carry the pre-allocated tail this fixture asked for, and the oldest " +
                "one measured $tail bytes"
        }
        return PrunableArchive(directory, format, budget, written)
    }

    /**
     * An archive on disk, and the appends that built it.
     *
     * [reopened] is how every replay here reads: a second instance over the same directory shares no
     * in-memory state, so it adopts the segments off the files — which is both what a restart does
     * and what makes the byte counter mean anything.
     */
    private class PrunableArchive(
        val directory: String,
        private val format: BoltArchiveFormat<RgaId, String, RgaOp<String>>,
        private val budget: Long,
        val written: List<AppendResult.Written>,
    ) {
        fun reopened(clock: Clock): PosixMappedBolt<RgaId, String, RgaOp<String>> =
            PosixMappedBolt(format, clock, directory, synchronous = true, segmentFrameBytes = budget)
    }

    /** One replay's events, and the segment-file bytes it cost. */
    private class MeasuredReplay(val events: List<ReplayEvent<RgaOp<String>>>, val fileBytesRead: Long) {
        val frames: List<Archived<RgaOp<String>>> get() = events.filterIsInstance<Archived<RgaOp<String>>>()
    }

    /**
     * Collect a replay and report what it read off disk.
     *
     * A **difference** across this one replay rather than the counter's absolute value, because the
     * counter is monotonic for the life of the bolt and this test replays the same archive twice
     * after having opened it once.
     */
    private suspend fun PosixMappedBolt<RgaId, String, RgaOp<String>>.measuredReplay(
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

        /**
         * Twelve segment files, resuming at the tenth — and this pair is the only thing deciding
         * whether assertion 3 can fail at all, so it is stated as arithmetic rather than as taste.
         *
         * The resume reads from `RESUME_FROM_SEGMENT - 1` (the boundary segment is read with every
         * frame filtered out) to the end, so it touches `FRAMES - RESUME_FROM_SEGMENT + 1` files
         * against a pruned prefix of `RESUME_FROM_SEGMENT - 1`. Assertion 3 is therefore a claim that
         * `2 * RESUME_FROM_SEGMENT > FRAMES + 2` — at twelve frames, that it is **8 or more**.
         *
         * **Measured, because the interesting question is which way it fails:** at
         * `RESUME_FROM_SEGMENT = 7` the test is red on unmutated production code, with assertion 3
         * and nothing else saying
         * `the resume read 13422 bytes, fewer than the 13422 the prefix it pruned holds` — six files
         * against six, the boundary exactly. Not silently vacuous. So this constant is load-bearing
         * and **self-guarding**, the opposite of this epic's recurring fixture hazard: lowering it
         * cannot quietly turn assertion 3 into a row claiming a receipt it no longer has. Nine
         * rather than eight leaves a whole file of margin on the safe side.
         */
        const val PRUNING_FIXTURE_FRAMES = 12
        const val RESUME_FROM_SEGMENT = 9

        /**
         * Big enough that a segment file dwarfs its own header.
         *
         * **This knob switches less off here than it does in the JVM twin, and saying so is the point
         * of writing it down.** There, pruning costs a header probe per pruned segment, so a small
         * payload makes the assertion measure the header size rather than the pruning. Here the
         * pruning decision reads nothing at all, so both arms are whole segment files and assertion 3
         * is the ratio `4 files < 8 files` at any payload whatever.
         *
         * It is kept for two honest reasons rather than an inherited one: the counts stay dominated
         * by frames rather than headers, and the receipt above can be read against
         * `MappedBoltPruningTest`'s, which is measured at the same payload.
         */
        const val PRUNED_PAYLOAD_CHARS = 2000

        /**
         * A real pre-allocated tail on every segment. Smaller than a frame, so one frame lands per
         * file — a pad that left room for a second would halve the prefix, and is refused by the
         * fixture's own `check` rather than silently taken.
         */
        const val PRE_ALLOCATED_TAIL_BYTES = 32L
    }
}
