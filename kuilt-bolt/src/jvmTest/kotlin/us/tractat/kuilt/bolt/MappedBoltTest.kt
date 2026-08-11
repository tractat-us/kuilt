package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What [MappedBolt] does that no other backend has to: pre-allocated zero regions, reopening a
 * directory a crashed process left behind, and the durability flag.
 *
 * The shared contract is pinned by [MappedBoltConformanceTest]; nothing here repeats it.
 *
 * "Crash" is simulated the way `FileChannelDurableStoreTest` simulates it — a second instance over
 * the same directory, which shares no in-memory state and so must read everything back off disk.
 */
class MappedBoltTest {

    /**
     * **The hazard the eager pre-allocation creates, and the one this backend must not lose to.**
     *
     * Pre-filling a segment with real writes leaves a zero-filled tail, and `crc32(ByteArray(0))` is
     * `0` — so a naive framing reads that tail as an endless run of valid *empty* frames, and a
     * two-frame archive replays as hundreds. Two independent barriers stop it: the frame CRC covers
     * the length prefix **as well as** the body, and `MINIMUM_BODY_BYTES` rejects a body shorter than
     * its own fixed fields.
     *
     * **Mutation receipt — the single mutations are both GREEN, and that is the point.**
     * Narrowing the frame CRC to the body alone (`crc32(…, fromIndex = INT_BYTES)` in both
     * `encodeFrame` and `readFrameV1`) leaves this green, because `MINIMUM_BODY_BYTES` still rejects
     * a zero-length body. Deleting the `MINIMUM_BODY_BYTES` guard alone leaves it green too, because
     * a zero run still checksums to `0x2144DF1C` against a stored `0`. Applying **both** reddens it —
     * `java.io.EOFException: Buffer doesn't contain required number of bytes (size: 0, required: 8)`,
     * thrown out of the replay as the padding's first phantom frame is decoded and found to have no
     * timestamp in it. Neither barrier is redundant, and a single-mutation reading of either one
     * would wrongly conclude it could go.
     */
    @Test
    fun aPreAllocatedTailReplaysAsACleanTailRatherThanPhantomFrames() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = tempArchiveDirectory()
        val bolt = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = SEGMENT_BUDGET_BYTES)
        val (afterSmall, small) = Rga.empty<String>().insertAt(ALICE, 0, "small")
        // Larger than the whole budget, so it cannot share a segment and forces a roll — which is
        // what leaves the FIRST segment with an unwritten, pre-allocated remainder.
        val (_, oversize) = afterSmall.insertAt(ALICE, 1, "x".repeat(OVERSIZE_ELEMENT_CHARS))

        val first = assertIs<AppendResult.Written>(bolt.append(listOf(small)))
        bolt.append(listOf(oversize))
        val events = bolt.replay(ReplayScope.All).toList()
        val segments = segmentsIn(directory)
        val padded = segments.first().readBytes()

        assertAll(
            { assertEquals(2, segments.size, "the oversize frame rolled a second segment") },
            {
                assertEquals(
                    segmentHeaderBytes() + SEGMENT_BUDGET_BYTES,
                    padded.size.toLong(),
                    "a segment is pre-allocated to its whole budget up front, not grown per append",
                )
            },
            {
                assertTrue(
                    padded.drop(segmentHeaderBytes() + first.endOffset.toInt()).all { it == ZERO },
                    "everything past the written frame is the untouched pre-allocated region",
                )
            },
            { assertEquals(2, events.filterIsInstance<Archived<RgaOp<String>>>().size, "two frames, not the padding") },
            { assertEquals(CleanTail, events.last(), "a pre-allocated tail is a CLEAN end, not damage") },
        )
    }

    /**
     * Reopening a directory replays everything the previous instance wrote and continues its offset
     * space — the archive's state lives in the bytes, not in the object that wrote them.
     */
    @Test
    fun aReopenedArchiveReplaysEverythingAndKeepsCountingFromWhereItStopped() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val directory = tempArchiveDirectory()
            val writer = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = SEGMENT_BUDGET_BYTES)
            var live = Rga.empty<String>()
            val written = (0 until BEFORE_RESTART).map { index ->
                val (next, op) = live.insertAt(ALICE, live.size, "before-$index")
                live = next
                assertIs<AppendResult.Written>(writer.append(listOf(op)))
            }

            val reopened = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = SEGMENT_BUDGET_BYTES)
            val replayed = reopened.replay(ReplayScope.All).toList()
            val (_, after) = live.insertAt(ALICE, live.size, "after-the-restart")
            val appended = assertIs<AppendResult.Written>(reopened.append(listOf(after)))
            val everything = reopened.replay(ReplayScope.All).frames().toList()

            assertAll(
                { assertEquals(BEFORE_RESTART, replayed.filterIsInstance<Archived<RgaOp<String>>>().size) },
                { assertEquals(CleanTail, replayed.last(), "an intact archive reopens clean") },
                {
                    assertEquals(
                        written.last().endOffset,
                        appended.offset,
                        "the next append lands exactly where the previous instance stopped",
                    )
                },
                { assertEquals(BEFORE_RESTART + 1, everything.size, "and the whole history is still there") },
                { assertEquals(listOf(after), everything.last().ops, "including the one written after the restart") },
            )
        }

    /**
     * A crash mid-append leaves a partial frame after the last complete one. Opening the directory
     * clears it, and the archive keeps taking appends.
     *
     * **Why clearing it is right rather than reporting it forever.** The partial frame was never
     * acknowledged — its `append` never returned — so no caller believes it landed. And a replay
     * stops at the first frame that will not parse, so leaving the remnant in place would make every
     * *subsequent* append permanently unreachable: the archive would be wedged at the crash point
     * for the rest of its life. That is a far worse trade than losing bytes nobody was promised.
     *
     * **This fixture cannot tell the repair from a wrong one, and its partner is where that lives.**
     * There is nothing but padding behind the torn prefix here, so "zero the torn tail" and "zero
     * everything to the end of the segment" emit identical events — the fixture vacuity
     * [BoltConformanceSuite.newTruncatedBolt]'s KDoc warns about, one level down. The discriminating
     * case is [MappedBoltDamageTest.midSegmentDamageKeepsItsIntactFramesAndItsVerdictAcrossARestart],
     * which puts CRC-valid frames behind the damage. Do not "strengthen" this test by adding one:
     * with a committed frame behind it, it is no longer a torn tail, the repair correctly refuses,
     * and the subject of this test — that the archive stays appendable — inverts. Two cases, two
     * tests.
     */
    @Test
    fun aTornTailFromACrashIsClearedAtOpenSoTheArchiveStaysAppendable() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = tempArchiveDirectory()
        val writer = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = SEGMENT_BUDGET_BYTES)
        val (afterFirst, first) = Rga.empty<String>().insertAt(ALICE, 0, "first")
        val (afterSecond, second) = afterFirst.insertAt(ALICE, 1, "second")
        assertIs<AppendResult.Written>(writer.append(listOf(first)))
        val complete = assertIs<AppendResult.Written>(writer.append(listOf(second)))

        // The bytes a crash halfway through the third append would have left behind.
        writeRaw(segmentsIn(directory).single(), at = segmentHeaderBytes() + complete.endOffset, bytes = TORN_PREFIX)
        val reopened = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = SEGMENT_BUDGET_BYTES)
        val events = reopened.replay(ReplayScope.All).toList()
        val (_, third) = afterSecond.insertAt(ALICE, 2, "third")
        val resumed = assertIs<AppendResult.Written>(reopened.append(listOf(third)))
        val afterResume = reopened.replay(ReplayScope.All).toList()

        assertAll(
            { assertEquals(2, events.filterIsInstance<Archived<RgaOp<String>>>().size, "both whole frames survive") },
            { assertEquals(CleanTail, events.last(), "the torn remnant was cleared, so the tail reads clean") },
            { assertEquals(complete.endOffset, resumed.offset, "and the next append overwrites it") },
            { assertEquals(3, afterResume.filterIsInstance<Archived<RgaOp<String>>>().size) },
            { assertEquals(CleanTail, afterResume.last(), "the archive is not wedged at the crash point") },
        )
    }

    /**
     * A crash between pre-allocating a segment and writing its header leaves an all-zero file. It
     * can hold no frame — frames are only ever written after the header — so opening the directory
     * drops it rather than reporting the archive as damaged.
     */
    @Test
    fun anAllZeroSegmentLeftByACrashedPreAllocationIsDroppedAtOpen() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = tempArchiveDirectory()
        val writer = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = 1L)
        val (afterOne, one) = Rga.empty<String>().insertAt(ALICE, 0, "one")
        val (afterTwo, two) = afterOne.insertAt(ALICE, 1, "two")
        writer.append(listOf(one))
        val second = assertIs<AppendResult.Written>(writer.append(listOf(two)))
        val orphan = File(directory, "segment-000000000002.bolt")
        RandomAccessFile(orphan, "rw").use { it.write(ByteArray(ORPHAN_BYTES)) }

        val reopened = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = 1L)
        // Sampled HERE, not after the append below: the append rolls a fresh segment and the next
        // free index is the orphan's, so a check at the end of the test would see the new file and
        // conclude the orphan had survived.
        val reclaimed = !orphan.exists()
        val events = reopened.replay(ReplayScope.All).toList()
        val (_, three) = afterTwo.insertAt(ALICE, 2, "three")
        val resumed = assertIs<AppendResult.Written>(reopened.append(listOf(three)))

        assertAll(
            { assertTrue(reclaimed, "an all-zero segment holds nothing, so opening reclaims it") },
            { assertEquals(2, events.filterIsInstance<Archived<RgaOp<String>>>().size) },
            { assertEquals(CleanTail, events.last(), "an unwritten segment is not damage") },
            { assertEquals(second.endOffset, resumed.offset, "and the append point is unmoved") },
        )
    }

    /**
     * A directory removed underneath a live archive is recreated, by **both** the append path and
     * the availability probe.
     *
     * Two arms, deliberately, because one would leave the other's claim untested: the first appends
     * *without* asking [Bolt.availability] first, so it can only pass if `roll` carries its own
     * `mkdirs`; the second only probes, so it can only pass if `availability` does. If either path
     * lacked it the two answers would disagree, and `Available` would stop meaning "an append is
     * accepted" — the one thing that signal exists to promise.
     */
    @Test
    fun aDirectoryRemovedUnderneathIsRecreatedByBothTheAppendAndTheProbe() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val directory = tempArchiveDirectory()
            // One frame per segment, so the append below must roll — which is the path that allocates.
            val bolt = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = 1L)
            val (afterFirst, first) = Rga.empty<String>().insertAt(ALICE, 0, "first")
            assertIs<AppendResult.Written>(bolt.append(listOf(first)))

            directory.deleteRecursively()
            val (_, second) = afterFirst.insertAt(ALICE, 1, "second")
            val appendedIntoAVanishedDirectory = bolt.append(listOf(second))
            directory.deleteRecursively()
            val probed = bolt.availability()
            val recreatedByTheProbe = directory.isDirectory

            assertAll(
                {
                    assertIs<AppendResult.Written>(
                        appendedIntoAVanishedDirectory,
                        "the append path recreates the directory rather than raising FileNotFoundException",
                    )
                },
                { assertEquals(BoltAvailability.Available, probed, "and the probe agrees with it") },
                { assertTrue(recreatedByTheProbe, "because the probe recreates it too") },
            )
        }

    /**
     * Synchronous and asynchronous are **one mechanism and a flag**: they must produce the same
     * archive, byte for byte, or they are two implementations pretending to be one.
     *
     * The control arm is the whole test — an assertion about either bolt's own replay would pass
     * whatever the other one wrote.
     */
    @Test
    fun theDurabilityFlagChangesWhenBytesAreFlushedAndNothingElse() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val synchronous = tempArchiveDirectory()
        val asynchronous = tempArchiveDirectory()
        var live = Rga.empty<String>()
        val ops = (0 until FLUSH_COMPARISON_FRAMES).map { index ->
            val (next, op) = live.insertAt(ALICE, live.size, "record-$index")
            live = next
            op
        }

        val forced = mappedBolt(FixedClock(EPOCH), synchronous, forceOnAppend = true, segmentFrameBytes = 1L)
        val unforced = mappedBolt(FixedClock(EPOCH), asynchronous, forceOnAppend = false, segmentFrameBytes = 1L)
        ops.forEach { op ->
            forced.append(listOf(op))
            unforced.append(listOf(op))
        }
        val forcedFiles = segmentsIn(synchronous)
        val unforcedFiles = segmentsIn(asynchronous)
        val unforcedReplay = unforced.replay(ReplayScope.All).frames().toList()

        assertAll(
            { assertEquals(forcedFiles.map { it.name }, unforcedFiles.map { it.name }, "same segments") },
            {
                forcedFiles.zip(unforcedFiles).forEach { (left, right) ->
                    assertContentEquals(left.readBytes(), right.readBytes(), "same bytes in ${left.name}")
                }
            },
            { assertEquals(ops.map { listOf(it) }, unforcedReplay.map { it.ops }, "and an unforced archive still replays") },
        )
    }

    /**
     * An archive that cannot be opened for writing **reports** it. Constructing a bolt over one must
     * not throw.
     *
     * A read-only mount is the classic Linux response to a disk I/O error — precisely the moment the
     * application most needs to survive, and the module's whole failure posture is that a broken
     * archive never takes down the process whose telemetry it archives. The tell that this was always
     * the intent: an *empty* read-only directory already constructs fine and reports
     * `Unavailable("… is not writable")`. It was only the branch that matters — an archive with
     * segments already in it — that threw.
     *
     * The archive stays **readable**, which is the other half of the answer: replay opens its
     * segments for reading, so an operator can still get the history off a volume that has gone
     * read-only under them.
     */
    @Test
    fun anArchiveThatCannotBeOpenedForWritingReportsUnavailableInsteadOfThrowing() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val directory = tempArchiveDirectory()
            val writer = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = 1L)
            val (afterFirst, first) = Rga.empty<String>().insertAt(ALICE, 0, "written-while-writable")
            assertIs<AppendResult.Written>(writer.append(listOf(first)))
            val segment = segmentsIn(directory).single()
            val readOnly = segment.setWritable(false) && !segment.canWrite()

            val reopened = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = 1L)
            val availability = reopened.availability()
            val replayed = reopened.replay(ReplayScope.All).frames().toList()
            val (_, second) = afterFirst.insertAt(ALICE, 1, "attempted-while-read-only")
            val refused = reopened.append(listOf(second))
            segment.setWritable(true)

            assertAll(
                {
                    assertTrue(
                        readOnly,
                        "the fixture must actually make the segment unwritable — as root it cannot, and every " +
                            "assertion below would then pass for the wrong reason",
                    )
                },
                { assertIs<BoltAvailability.Unavailable>(availability, "an archive it cannot write must say so") },
                { assertIs<AppendResult.Failed>(refused, "and refuse the append rather than claim a write") },
                { assertEquals(1, replayed.size, "while still replaying every frame — a read-only archive is READABLE") },
            )
        }

    /**
     * Opening a directory whose archive holds a different element type **throws**, rather than
     * reporting an empty archive.
     *
     * Every other unreadable-bytes case here is a damaged tail and stops quietly. This one is not
     * damage: it is a reader pointed at the wrong directory, and answering "there is nothing here"
     * would send them looking for a bug in the writer.
     */
    @Test
    fun openingAnArchiveOfADifferentElementTypeFailsLoudly() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = tempArchiveDirectory()
        val strings = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = 1L)
        val (_, op) = Rga.empty<String>().insertAt(ALICE, 0, "written-as-a-string")
        strings.append(listOf(op))

        val failure = assertFailsWith<BoltFormatException> {
            MappedBolt(directory, BoltArchiveFormat.rga(serializer<Int>()), FixedClock(EPOCH), segmentFrameBytes = 1L)
        }

        assertTrue(
            failure.message.orEmpty().contains("kotlin.Int"),
            "the message must name what the reader expected: ${failure.message}",
        )
    }

    /**
     * A doubt raised by a failed `force()` is **cleared** by the next one that succeeds.
     *
     * The conformance property cannot reach this. Its fixture's flush can never succeed, so the whole
     * recovery half of "sticky until a later flush covers the same range" is unasserted there — and a
     * signal that latches forever after one transient failure is a signal a consumer learns to ignore.
     *
     * `force()` syncs the **whole mapping**, so the covering range is this segment's frames, and the
     * default 1 MiB budget keeps all four in one segment. That is the configuration recovery is
     * reachable in, and it is deliberately the one under test: with a one-byte budget every append
     * rolls, the doubt spans retired mappings, and nothing this bolt can still flush covers it. Both
     * facts are worth having written down — see [MappedBolt.durability].
     *
     * **Mutation receipts.** Deleting the `ledger.flushSucceeded(...)` call reddens assertion 3 alone
     * (the doubt never clears) and leaves 1 and 2 green — the pairing that stops 3 from riding on
     * them. Widening `flushSucceeded` to clear unconditionally leaves all three green here, and is
     * caught by `DurabilityLedgerTest` instead: this test cannot distinguish "cleared because it
     * covered" from "cleared because it was asked to".
     */
    @Test
    fun aDoubtRaisedByAFailedFlushIsClearedByTheNextSuccessfulOne() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = mappedBolt(FixedClock(EPOCH), tempArchiveDirectory())
        val (afterFirst, first) = Rga.empty<String>().insertAt(ALICE, 0, "flushed")
        val (_, second) = afterFirst.insertAt(ALICE, 1, "not-flushed")

        assertIs<AppendResult.Written>(bolt.append(listOf(first)))
        val healthy = bolt.durability()
        bolt.rigFlushFailure(RIGGED_FLUSH_FAILURE)
        assertIs<AppendResult.Written>(bolt.append(listOf(second)))
        val degraded = bolt.durability()
        bolt.rigFlushFailure(null)
        val (_, third) = Rga.empty<String>().insertAt(ALICE, 0, "flushed-again")
        assertIs<AppendResult.Written>(bolt.append(listOf(third)))
        val recovered = bolt.durability()

        assertAll(
            { assertEquals(DurabilityState.AsPromised, healthy, "a flush that worked is not a degradation") },
            {
                assertEquals(
                    RIGGED_FLUSH_FAILURE,
                    assertIs<DurabilityState.Degraded>(degraded).cause?.message,
                    "and one that did not is reported with the failure that caused it, not a bare flag",
                )
            },
            {
                assertEquals(
                    DurabilityState.AsPromised,
                    recovered,
                    "a later flush covering the same range clears the doubt — this is sticky, not permanent",
                )
            },
        )
    }

    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }

    private companion object {
        val ALICE = ReplicaId("alice")
        val EPOCH: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        const val ZERO: Byte = 0

        /** Small enough that one oversized record cannot share a segment with anything. */
        const val SEGMENT_BUDGET_BYTES = 4096L
        const val OVERSIZE_ELEMENT_CHARS = 8192

        const val BEFORE_RESTART = 3
        const val FLUSH_COMPARISON_FRAMES = 3
        const val ORPHAN_BYTES = 128

        /** Non-zero, and too short to be a frame — what a crash mid-write leaves. */
        val TORN_PREFIX = byteArrayOf(0, 0, 0, 42, 7)

        fun writeRaw(file: File, at: Long, bytes: ByteArray) {
            RandomAccessFile(file, "rw").use { handle ->
                handle.seek(at)
                handle.write(bytes)
            }
        }
    }
}
