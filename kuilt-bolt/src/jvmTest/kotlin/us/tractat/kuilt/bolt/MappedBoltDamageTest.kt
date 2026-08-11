package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Damage that is **not** a torn tail.
 *
 * A torn tail — a partial frame after the last complete one, with nothing but pre-allocated zeroes
 * behind it — is the ordinary crash, and `MappedBoltTest` pins that it is repaired at open. Every
 * case here is the other kind: bytes that fail to parse with **committed frames behind them**. The
 * two look identical from the parse loop's point of view and must not be treated identically, and a
 * fixture whose damage has nothing behind it cannot tell them apart — the same vacuity
 * [BoltConformanceSuite.newTruncatedBolt]'s KDoc warns about, one level down.
 *
 * Nothing exotic is required to produce one. With `forceOnAppend = false` the OS writes pages back
 * in whatever order it likes, so a hole followed by later-flushed pages is the *expected* artifact
 * of a power loss, not a bit-rot curiosity.
 */
class MappedBoltDamageTest {

    /**
     * Damage in the middle of a segment must survive a restart — both the frames behind it and the
     * verdict about it — and must never let an acknowledged offset be handed out twice.
     *
     * The repair that clears a torn tail zeroes from the first unparseable byte to the end of the
     * segment. Applied here it would destroy four CRC-valid frames, turn the honest `Truncated` into
     * a `CleanTail` (permanently — a server restarts routinely), and drop the append cursor back onto
     * an offset a caller was already told its frame occupied. A consumer resuming from
     * `ReplayScope.FromOffset` past that point would then silently skip a frame, because the scope
     * selects on `endOffset >` and the re-used offsets straddle its cursor.
     *
     * So the repair has to **discriminate** before it repairs: bytes that will not parse are a torn
     * tail only when nothing behind them parses either.
     */
    @Test
    fun midSegmentDamageKeepsItsIntactFramesAndItsVerdictAcrossARestart() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = tempArchiveDirectory()
        val writer = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = ONE_SEGMENT_BYTES)
        var live = Rga.empty<String>()
        val written = (0 until FRAMES_IN_ONE_SEGMENT).map { index ->
            val (next, op) = live.insertAt(ALICE, live.size, "record-$index")
            live = next
            assertIs<AppendResult.Written>(writer.append(listOf(op)), "the fixture's frames must be written")
        }
        val segment = segmentsIn(directory).single()
        // Inside frame 2's BODY, so its length prefix survives and frames 3..5 stay exactly where
        // their offsets say they are — committed, CRC-valid, and behind the damage.
        flipByteAt(segment, segmentHeaderBytes() + written[DAMAGED].offset + INTO_THE_BODY)

        val beforeRestart = writer.replay(ReplayScope.All).toList()
        val reopened = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = ONE_SEGMENT_BYTES)
        // Sampled HERE, and the trap is worth spelling out because this assertion PASSED against the
        // broken code on its first draft, when it was sampled at the end of the test with the others.
        // The broken repair zeroed this range and then resumed appending at the offset it had just
        // freed — so by the end of the test the new frame had been written back OVER the range being
        // checked, it read non-zero, and the assertion concluded the frames had survived. The bug
        // erased the evidence and then forged it, inside the assertion written to catch it. Anything
        // asserted about bytes the append path can reach must be sampled before that path runs.
        val behindTheDamage = segment.readBytes().copyOfRange(
            segmentHeaderBytes() + written[DAMAGED + 1].offset.toInt(),
            segmentHeaderBytes() + written.last().endOffset.toInt(),
        )
        val afterRestart = reopened.replay(ReplayScope.All).toList()
        val availability = reopened.availability()
        val (_, next) = live.insertAt(ALICE, live.size, "after-the-restart")
        val appended = reopened.append(listOf(next))
        val expected = Truncated(written[DAMAGED - 1].endOffset, TruncationReason.Frame)

        assertAll(
            { assertEquals(DAMAGED, beforeRestart.filterIsInstance<Archived<RgaOp<String>>>().size) },
            { assertEquals(expected, beforeRestart.last(), "before the restart the verdict is honest") },
            { assertEquals(expected, afterRestart.last(), "and a restart must not launder it into a CleanTail") },
            { assertEquals(DAMAGED, afterRestart.filterIsInstance<Archived<RgaOp<String>>>().size) },
            {
                assertTrue(
                    behindTheDamage.any { it != ZERO },
                    "the CRC-valid frames behind the damage are committed data — a restart must not erase them",
                )
            },
            {
                assertTrue(
                    (appended as? AppendResult.Written)?.offset.let { it == null || it >= written.last().endOffset },
                    "an offset already acknowledged to a caller must never be handed out twice; got $appended",
                )
            },
            {
                assertIs<BoltAvailability.Unavailable>(
                    availability,
                    "an archive damaged behind committed frames cannot be appended to without reusing offsets, " +
                        "so it must say so rather than accept writes into a region replay can never reach",
                )
            },
        )
    }

    /**
     * A segment that is gone is a **hole**, and a hole is damage — not something to step over.
     *
     * Every segment header carries the absolute append offset its frames start at, so a gap is
     * arithmetic, not guesswork: the next segment simply does not begin where the previous one
     * ended. Without that check the replay hands back a history with frames missing from the middle,
     * offsets that jump, and a `CleanTail` claiming it is complete — which is the one failure
     * [Bolt] cannot afford, because a replica seeded from a replay missing frames re-mints an
     * already-used `(replica, seq)` dot mesh-wide and nothing anywhere purges it.
     *
     * `InMemoryBolt` has the equivalent guard and asserts on it; this backend must report it, because
     * on a file-backed archive a missing segment is a thing that can actually happen.
     */
    @Test
    fun aMissingSegmentIsAHoleTheReplayRefusesToPaperOver() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = tempArchiveDirectory()
        val written = oneFramePerSegment(directory, SEGMENTS_AROUND_THE_HOLE)
        segmentsIn(directory)[HOLE].delete()

        val reopened = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = 1L)
        val events = reopened.replay(ReplayScope.All).toList()

        assertAll(
            {
                assertEquals(
                    HOLE,
                    events.filterIsInstance<Archived<RgaOp<String>>>().size,
                    "replay stops at the hole — the frames beyond it are real, but their history is not",
                )
            },
            {
                assertEquals(
                    Truncated(written[HOLE - 1].endOffset, TruncationReason.SegmentHeader),
                    events.last(),
                    "and says so at the offset the missing segment should have started at",
                )
            },
        )
    }

    /**
     * The same hole reached by the other path: a segment truncated to exactly its own header parses
     * zero frames and exits the frame loop *normally*, so nothing about it looks damaged until the
     * next segment's `baseOffset` fails to line up.
     *
     * Worth its own test rather than folding into the one above: the missing-segment case never
     * enters `emitSegment` for the gap at all, this one enters and returns a clean verdict, and only
     * a continuity check catches both.
     */
    @Test
    fun aSegmentTruncatedToItsHeaderIsAHoleTheReplayRefusesToPaperOver() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = tempArchiveDirectory()
        val written = oneFramePerSegment(directory, SEGMENTS_AROUND_THE_HOLE)
        RandomAccessFile(segmentsIn(directory)[HOLE], "rw").use { it.setLength(segmentHeaderBytes().toLong()) }

        val reopened = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = 1L)
        val events = reopened.replay(ReplayScope.All).toList()

        assertAll(
            { assertEquals(HOLE, events.filterIsInstance<Archived<RgaOp<String>>>().size, "replay stops at the hole") },
            {
                assertEquals(
                    Truncated(written[HOLE - 1].endOffset, TruncationReason.SegmentHeader),
                    events.last(),
                    "a segment that lost its frames is a gap in the offset space, however tidily it parses",
                )
            },
        )
    }

    /** [frames] frames in [frames] segments — a one-byte budget puts exactly one in each file. */
    private suspend fun oneFramePerSegment(directory: File, frames: Int): List<AppendResult.Written> {
        val bolt = mappedBolt(FixedClock(EPOCH), directory, segmentFrameBytes = 1L)
        var live = Rga.empty<String>()
        return (0 until frames).map { index ->
            val (next, op) = live.insertAt(ALICE, live.size, "record-$index")
            live = next
            assertIs<AppendResult.Written>(bolt.append(listOf(op)), "the fixture's frames must be written")
        }
    }

    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }

    private companion object {
        val ALICE = ReplicaId("alice")
        val EPOCH: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        const val ZERO: Byte = 0

        /** Room for every fixture frame, so the whole archive is one segment and one file. */
        const val ONE_SEGMENT_BYTES = 4096L
        const val FRAMES_IN_ONE_SEGMENT = 6

        /** Which frame gets damaged — with frames behind it, which is the whole point. */
        const val DAMAGED = 2

        /** Past the 4-byte length prefix, so the prefix survives and the frame stays locatable. */
        const val INTO_THE_BODY = 12L

        /** Enough segments that frames sit BEHIND the hole, which is what a silent skip would show. */
        const val SEGMENTS_AROUND_THE_HOLE = 4

        /** Which segment goes missing. Not the first, so there is an intact prefix to keep. */
        const val HOLE = 1
    }
}
