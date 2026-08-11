package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
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
        // Sampled HERE, before the append below. An append that resumed at a reused offset would
        // write its own frame back over this range, so a check at the end of the test would find it
        // non-zero and conclude the frames had survived — the very bug, papering over itself.
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
    }
}
