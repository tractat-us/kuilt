@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.bolt

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import platform.posix.O_RDWR
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.lseek
import platform.posix.open
import platform.posix.read
import platform.posix.write
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
 * What a **caught-up** [ReplayScope.FromOffset] resume answers on [PosixMappedBolt] — the commonest
 * `FromOffset` call there is, and the one whose answer must not depend on how far the archive has
 * grown since the damage below the cursor happened.
 *
 * `BoltConformanceSuite` pins what every backend answers about a resume that **selects** frames.
 * This is the resume that selects none: the cursor sits at the archive's end, so every segment is
 * prunable and [PosixMappedBolt.replay] short-circuits to [CleanTail] having read nothing. That
 * short-circuit is what keeps the pruning contract from depending on the archive's **length** —
 * without it a cursor at the end still walks the newest segments, and damage below the cursor gets
 * reported or not according to how far from the tail it happens to sit. A consumer polling an
 * unchanged, unrepaired archive would then see [Truncated] today and [CleanTail] after two more
 * rolls, over bytes that never moved.
 *
 * ### Why this is here rather than in the suite or in `PosixMappedBoltPruningTest`
 *
 * Not in the suite, for the reason #2331 settled: half the claim is about **bytes touched on a
 * filesystem**, and [Bolt] exposes no such thing — every assertion below about a verdict would hold
 * on a backend that read the whole archive every time and then filtered. The instrument
 * ([PosixMappedBolt.segmentFileBytesRead], #2337) is necessarily backend-specific.
 *
 * Not in `PosixMappedBoltPruningTest`, because that file's claim is a **saving** — a resume reads
 * fewer bytes than the prefix it skipped. This one is **position-independence**: the same verdict
 * wherever the damage is. The two share a backend and nothing else, and folding this into that
 * file's fixture would have made one archive shape serve two claims, which is how a fixture ends up
 * configured for the case where neither can fail.
 *
 * The gap is #2504, and it was measured rather than assumed: forcing `firstUnpruned` to `0` on
 * `appleMain` disables this short-circuit as a side effect, and under that mutation the whole of
 * `macosArm64Test` — then 156 tests across 14 classes — reddened exactly one test, and only on byte
 * counts that say nothing about a verdict. Nothing anywhere exercised a caught-up resume over an
 * archive carrying damage below the cursor.
 *
 * With this file the tree is 157 tests across 15 classes, and under **either** mutation recorded
 * below exactly one of them reds — this one. All three of this backend's `BoltConformanceSuite`
 * subclasses stay green, and so do `PosixMappedBoltTest` and `PosixMappedBoltPruningTest`. That is
 * the warrant for a separate file rather than a stronger conformance property: no fixture in the
 * suite hands a backend an archive-end cursor *and* damage below it, and #2331 settled that the suite
 * cannot ask the other half of the question at all.
 *
 * The JVM twin is `MappedBoltPruningTest.aCaughtUpResumeAnswersTheSameWhereverTheDamageIs`. The byte
 * half is **stronger here**: that backend copies its newest segment out of the live mapping, so one
 * file is free of charge whatever a replay does, while this one reads every segment with `read(2)`
 * (see [PosixMappedBolt]'s "Replay reads, it does not map"). A zero here means no file was opened at
 * all.
 */
class PosixMappedBoltCaughtUpResumeTest {

    /**
     * A caught-up resume answers [CleanTail] and reads **nothing**, wherever the damage below the
     * cursor sits.
     *
     * The sweep is the claim. It is easy to say "damage below the cursor is invisible to a resume"
     * and easy to implement something that means "…except in the newest few segments": pruning walks
     * a *prefix*, so any formulation that always reads the last `k` segments makes the answer a
     * function of how many segments have rolled since, not of the archive's contents. One damage
     * position cannot tell those apart; a sweep across depths can.
     *
     * Five assertions, in the order below, and the numbering the receipts use. 1 and 2 are
     * preconditions rather than the claim — they are what stops 3 and 4 being green by absence.
     *
     * 1. **(precondition)** the sweep reaches deeper than the newest
     *    [SEGMENTS_A_PREFIX_WALK_ALWAYS_READS] segments, so "wherever" is a claim about several
     *    depths rather than one;
     * 2. **(precondition)** opening each fixture really **read** the archive, so adoption's cost is
     *    outside both arms and the zero in 4 is a measurement rather than a counter nobody touched;
     * 3. **(the claim)** every caught-up resume ends [CleanTail];
     * 4. **(the claim)** and reads `0` bytes;
     * 5. **(the control)** an unscoped replay over each of those same archives stops at exactly the
     *    damaged segment's base offset, so 3 and 4 rest on corruptions that really landed, in the
     *    segments they were aimed at.
     *
     * Assertion 2 is Apple-specific and load-bearing. This backend adopts an archive **lazily** — on
     * the first `append`, `availability` or `replay` — where `MappedBolt` recovers eagerly in `init`.
     * Left inside the first arm, adoption's `read(2)`s would land on the caught-up measurement and 4
     * would be red for a reason that has nothing to do with pruning.
     *
     * **Not covered, deliberately: damage in the newest segment.** That is not damage *below* an
     * archive-end cursor at all — re-opening scans the last segment for its append cursor, finds the
     * torn frame and **repairs** the tail by zeroing it, so by the time any replay runs the archive
     * is healthy and shorter. [DAMAGE_POSITIONS] measures that rather than asserting it.
     *
     * ### Mutation receipts
     *
     * Both applied alone to `PosixMappedBolt.replay`, reverted, the revert grep-verified, the results
     * XML deleted before each run, and the build log checked for `compileKotlinMacosArm64 FAILED` and
     * `e: file` — a mutation that does not compile leaves Gradle serving the *previous* run's XML, and
     * the verdict it fabricates is a plausible copy of the row before it.
     *
     * Each segment file here is 243 bytes, so every count below is a whole number of them.
     *
     * | Mutation | Reds | Verdicts across the five positions | Bytes |
     * |---|---|---|---|
     * | Delete `if (firstUnpruned < 0) return@flow emit(CleanTail)` | 3 and 4 | `Truncated(0)`, `(139)`, `(275)`, `(411)`, `(547)`, all `Frame` | `243, 486, 729, 972, 1215` |
     * | …and start the walk at the newest two segments instead — `.let { if (it < 0) views.size - 1 else it }` | 3 and 4 | `CleanTail ×4`, then `Truncated(547, Frame)` | `486, 486, 486, 486, 243` |
     *
     * **The second row is the shape this test exists to rule out, and it is why the sweep is six
     * segments deep.** A "read the newest `k`" formulation answers *correctly* at four of the five
     * damage positions — one entry of five differs — so a fixture damaging a single segment anywhere
     * above depth 4 would have called it green. Assertion 4 is what keeps that honest: the byte count
     * reds at **every** position under it, including the four whose verdict is right.
     *
     * The first row reads the archive from segment 0 and stops at the damage, so its byte counts climb
     * with depth. That is the *easy* mutation; it is here only to show the two are distinguishable.
     *
     * **Assertions 1, 2 and 5 stay green under both, and that is right.** 1 and 2 are the fixture's
     * own preconditions — nothing about which segments a `FromOffset` replay walks changes how many
     * files the fixture wrote or what adoption cost, and both mutations are downstream of the open. 5
     * is the [ReplayScope.All] control: `skippable` is false for every scope that is not
     * [ReplayScope.FromOffset], so `firstUnpruned` is `0` on that arm and neither mutation is
     * reachable from it. A control that moved with the mutation would not be one — it would be a
     * second copy of the claim.
     *
     * ### Fixture boundaries, measured on unmutated production code
     *
     * Both knobs that decide whether this test can fail are **self-guarding**, and which way they fail
     * was measured rather than argued:
     *
     * | Change | Reds | How |
     * |---|---|---|
     * | [DAMAGE_POSITIONS] widened to `0 until TAIL_FIXTURE_FRAMES` | 5, and nothing else | `CleanTail` where `Truncated(683, Frame)` was expected — adoption repaired the torn newest segment before any replay ran |
     * | [TAIL_FIXTURE_FRAMES] lowered to `3` | 1, and nothing else | `it swept [0, 1]` — no depth left that a two-segment walk does not reach anyway |
     *
     * Neither goes silently vacuous, which is the failure mode this epic keeps shipping.
     */
    @Test
    fun aCaughtUpResumeAnswersTheSameWhereverTheDamageIs() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = FixedClock(EPOCH)
        // One archive per damage position: the corrupted byte has to be the only difference between
        // them, so a shared archive re-damaged in place would not do.
        val outcomes = DAMAGE_POSITIONS.map { damaged ->
            val archive = oneFramePerSegmentArchive(clock, TAIL_FIXTURE_FRAMES, PIN_PAYLOAD_CHARS)
            flipByteAt(segmentFiles(archive.directory)[damaged], archive.headerBytes + FIRST_BODY_BYTE)
            val bolt = archive.reopened(clock)
            // Adoption happens on the first open and is NOT part of either arm — see assertion 2.
            val opened = bolt.availability()
            check(opened == BoltAvailability.Available) {
                "a frame corrupted below the cursor must leave the archive openable, and segment " +
                    "$damaged said $opened"
            }
            DamageOutcome(
                damagedSegment = damaged,
                adoptionBytes = bolt.segmentFileBytesRead(),
                caughtUp = bolt.measuredReplay(ReplayScope.FromOffset(archive.written.last().endOffset)),
                whole = bolt.measuredReplay(ReplayScope.All),
                expected = Truncated(archive.written[damaged].offset, TruncationReason.Frame),
            )
        }

        assertAll(
            {
                assertTrue(
                    DAMAGE_POSITIONS.count() > SEGMENTS_A_PREFIX_WALK_ALWAYS_READS,
                    "the sweep must reach deeper than the newest $SEGMENTS_A_PREFIX_WALK_ALWAYS_READS " +
                        "segments any prefix walk reads anyway, or 'wherever the damage is' is a claim " +
                        "about one position — it swept ${DAMAGE_POSITIONS.toList()}",
                )
            },
            {
                assertEquals(
                    emptyList<Int>(),
                    outcomes.filter { it.adoptionBytes <= 0L }.map { it.damagedSegment },
                    "opening must have READ each archive: adoption is lazy on this backend, and an " +
                        "open that read nothing would leave its cost inside the caught-up arm, where " +
                        "the zero below is measured",
                )
            },
            {
                assertEquals(
                    DAMAGE_POSITIONS.map { CleanTail },
                    outcomes.map { it.caughtUp.events.lastOrNull() },
                    "a caught-up resume answers CleanTail whatever the damage below it is and WHEREVER " +
                        "it sits — an answer that changes with the damage's distance from the tail " +
                        "changes as the archive grows, over bytes that never moved",
                )
            },
            {
                assertEquals(
                    DAMAGE_POSITIONS.map { 0L },
                    outcomes.map { it.caughtUp.fileBytesRead },
                    "and reads nothing at all: a consumer that is caught up is the commonest FromOffset " +
                        "caller there is, and this backend has no segment it reads for free",
                )
            },
            {
                assertEquals(
                    outcomes.map { it.expected },
                    outcomes.map { it.whole.events.lastOrNull() },
                    "the control: an unscoped replay stops at exactly the segment each fixture aimed " +
                        "at, so the silence above is pruning rather than a corruption that missed",
                )
            },
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
     * for one frame and too small for two. A fixture that quietly packed two frames into a file would
     * write fewer segments than [frameCount], so the sweep would index the wrong ones and the deepest
     * positions the fixture exists to reach would not exist — which the `check` below refuses rather
     * than takes.
     *
     * The writer is **closed** before anything reads: it holds the newest segment mapped, and a reader
     * over the same directory must see what is on disk, which is also what a restart sees.
     */
    private suspend fun oneFramePerSegmentArchive(
        clock: Clock,
        frameCount: Int,
        payloadChars: Int,
    ): DamageableArchive {
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
        writer.close()
        check(segmentFiles(directory).size == frameCount) {
            "the fixture needs one frame per segment, or the sweep damages the wrong ones"
        }
        // VERIFIED, not merely configured: a pad that left room for a second frame would put the
        // damage in a segment that still holds an intact frame, and the control below would then be
        // asserting a stop offset the damage did not cause.
        val tail = fileSize(segmentFiles(directory).first()) - headerBytesOf(format) - frameBytes
        check(tail > 0L) {
            "every segment must carry the pre-allocated tail this fixture asked for, and the oldest " +
                "one measured $tail bytes"
        }
        return DamageableArchive(directory, format, budget, written)
    }

    /** An archive on disk, the appends that built it, and how to re-open it as a restart would. */
    private class DamageableArchive(
        val directory: String,
        private val format: BoltArchiveFormat<RgaId, String, RgaOp<String>>,
        private val budget: Long,
        val written: List<AppendResult.Written>,
    ) {
        /** Where a segment file's first frame starts, so damage can be aimed past the header. */
        val headerBytes: Long get() = headerBytesOf(format)

        fun reopened(clock: Clock): PosixMappedBolt<RgaId, String, RgaOp<String>> =
            PosixMappedBolt(format, clock, directory, synchronous = true, segmentFrameBytes = budget)
    }

    /** One damage position, what opening cost, and what the two scopes made of it. */
    private class DamageOutcome(
        val damagedSegment: Int,
        val adoptionBytes: Long,
        val caughtUp: MeasuredReplay,
        val whole: MeasuredReplay,
        val expected: Truncated,
    )

    /** One replay's events, and the segment-file bytes it cost. */
    private class MeasuredReplay(val events: List<ReplayEvent<RgaOp<String>>>, val fileBytesRead: Long)

    /**
     * Collect a replay and report what it read off disk.
     *
     * A **difference** across this one replay rather than the counter's absolute value, because the
     * counter is monotonic for the life of the bolt and each fixture is opened before it is replayed
     * twice.
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
         * Six segments, so the damage sweep reaches depths a "read the newest `k`" formulation would
         * treat differently.
         *
         * At two or three every swept position is inside the window such a formulation always walks,
         * and the sweep cannot tell position-independence from "the newest few are always read" — the
         * very confusion this test exists to rule out. Six leaves four depths that the second mutation
         * receipt above never reaches and exactly one that it does, which is why that receipt is one
         * changed entry rather than a wholesale flip. Assertion 1 is what refuses a smaller value
         * rather than letting it quietly narrow the claim: at `3` it is **red on unmutated production
         * code**, alone, saying `it swept [0, 1]`.
         */
        const val TAIL_FIXTURE_FRAMES = 6

        /**
         * The segments swept — **every one but the newest**, and the exclusion is measured rather
         * than argued.
         *
         * A torn frame in the newest segment is not damage *below* an archive-end cursor: adoption
         * scans that segment for the append cursor, classifies the corrupt frame as a torn tail and
         * **zeroes it**, so every replay afterwards sees a healthy, shorter archive. Sweeping
         * `0 until TAIL_FIXTURE_FRAMES` instead is **red on unmutated production code**, at assertion
         * 5 and nothing else, with `CleanTail` where `Truncated(atOffset=683, reason=Frame)` was
         * expected — because by then there is nothing left to find. Not silently vacuous, and in the
         * informative direction: the repair is a different mechanism with its own coverage
         * (`PosixMappedBolt.repairedTailAt`), not a hole in this one.
         */
        val DAMAGE_POSITIONS = 0 until TAIL_FIXTURE_FRAMES - 1

        /**
         * How many segments the tail of a prefix walk touches anyway: the boundary segment
         * (`firstUnpruned - 1`, read with every frame filtered out) and the first survivor.
         *
         * Assertion 1 holds the sweep deeper than this, because damage inside this window is read by
         * a correct implementation as well as by a broken one — a sweep confined to it would agree
         * with the very formulation the test rules out.
         */
        const val SEGMENTS_A_PREFIX_WALK_ALWAYS_READS = 2

        /**
         * A real pre-allocated tail on every segment. Smaller than a frame, so one frame lands per
         * file — a pad that left room for a second is refused by the fixture's own `check` rather
         * than silently taken.
         */
        const val PRE_ALLOCATED_TAIL_BYTES = 32L

        /**
         * The pin does not care how big a frame is — only where the segment boundaries fall, and how
         * many of them there are. Kept small so the sweep builds six archives quickly.
         */
        const val PIN_PAYLOAD_CHARS = 8

        /**
         * The first byte of a frame's **body** — past the four-byte length prefix, so flipping it
         * fails the frame's checksum without moving anything behind it.
         *
         * Aiming at the prefix instead would still corrupt the frame, but by changing how many bytes
         * the codec believes follow it, which is a different failure with a different stop offset —
         * and assertion 5 pins the stop offset.
         */
        const val FIRST_BODY_BYTE = 4L
    }
}

/**
 * Invert the byte at [index] in [path] — how a test manufactures a frame a consumer never could.
 *
 * Read-modify-write rather than a fixed byte: writing a constant would be a no-op exactly when the
 * byte already held it, and a fixture whose damage silently missed is the shape this file's control
 * arm exists to catch. `check`ed on the way through, so a miss is a loud failure rather than a
 * quietly healthy archive.
 */
private fun flipByteAt(path: String, index: Long) {
    val byte = ByteArray(1)
    val fd = open(path, O_RDWR)
    check(fd >= 0) { "could not open $path for damage" }
    try {
        byte.usePinned { pinned ->
            check(lseek(fd, index, SEEK_SET) == index) { "could not seek to $index in $path" }
            check(read(fd, pinned.addressOf(0), 1.convert()) == 1L) { "could not read the byte at $index in $path" }
            byte[0] = (byte[0].toInt() xor BYTE_MASK).toByte()
            check(lseek(fd, index, SEEK_SET) == index) { "could not seek back to $index in $path" }
            check(write(fd, pinned.addressOf(0), 1.convert()) == 1L) { "could not flip the byte at $index in $path" }
        }
    } finally {
        close(fd)
    }
}

/** Every bit of a byte, so the flip cannot land on the value that was already there. */
private const val BYTE_MASK = 0xFF
