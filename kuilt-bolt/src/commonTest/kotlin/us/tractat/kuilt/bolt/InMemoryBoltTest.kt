package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Behaviour specific to [InMemoryBolt] — capacity, segment rolling, and replay-during-append. */
class InMemoryBoltTest {

    private val alice = ReplicaId("alice")
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }

    private fun bolt(segmentFrameBytes: Long = InMemoryBolt.DEFAULT_SEGMENT_FRAME_BYTES, capacityBytes: Long = Long.MAX_VALUE) =
        InMemoryBolt(BoltArchiveFormat.rga(serializer<String>()), clock, segmentFrameBytes, capacityBytes)

    /**
     * A full archive reports **which** records it lost, not how many.
     *
     * That distinction is the whole failure posture: the live replica will window these records away
     * next, so a failed append loses them from both sides. A consumer holding the dots can defer
     * windowing, re-feed, or correlate the gap; a consumer holding `failed++` can do none of those.
     */
    @Test
    fun anAppendPastCapacityFailsWithTheIdentitiesItCouldNotKeep() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val tiny = bolt(capacityBytes = TINY_CAPACITY)
        val (_, ops) = mintInserts(count = 2)

        val result = tiny.append(ops)
        val availability = tiny.availability()
        val archived = tiny.replay(ReplayScope.All).frames().toList()

        assertAll(
            { assertIs<AppendResult.Failed>(result, "an append that does not fit must fail, not throw") },
            {
                assertEquals(
                    ops.map { it.id.dot }.toSet(),
                    assertIs<AppendResult.Failed>(result).insertDots,
                    "the failure names the dots it lost",
                )
            },
            { assertEquals(0L, assertIs<AppendResult.Failed>(result).offset, "and where the gap is") },
            { assertIs<BoltAvailability.Unavailable>(availability, "a full archive reports itself unavailable") },
            { assertTrue(archived.isEmpty(), "a failed append leaves no partial frame behind") },
        )
    }

    /**
     * A frame larger than the segment budget is archived alone in its own segment rather than
     * refused. The budget bounds how much a segment accumulates; it is not a maximum record size,
     * and a bolt that rejected a big record would silently lose exactly the records most worth
     * keeping.
     */
    @Test
    fun aFrameLargerThanTheSegmentBudgetIsArchivedInASegmentOfItsOwn() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val oneBytePerSegment = bolt(segmentFrameBytes = 1L)
        val (_, ops) = mintInserts(count = 3)

        val first = oneBytePerSegment.append(ops)
        val second = oneBytePerSegment.append(ops.take(1))
        val frames = oneBytePerSegment.replay(ReplayScope.All).frames().toList()

        assertAll(
            { assertIs<AppendResult.Written>(first, "an oversized frame is still written") },
            { assertEquals(listOf(ops, ops.take(1)), frames.map { it.ops }, "and both segments replay in order") },
            {
                assertEquals(
                    assertIs<AppendResult.Written>(first).endOffset,
                    assertIs<AppendResult.Written>(second).offset,
                    "offsets stay contiguous across a segment roll — headers occupy no offset space",
                )
            },
        )
    }

    /**
     * [Bolt.replay] is a **cold** flow: collecting it twice, with an append in between, sees the
     * archive as it stood at each collection. A snapshot taken once at construction would make the
     * second collection stale, which is the shape of bug an archive can hide for a long time.
     */
    @Test
    fun replayIsColdSoALaterCollectionSeesLaterFrames() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val archive = bolt()
        val (live, first) = mintInserts(count = 2)
        val (_, second) = mintInserts(count = 2, from = live)
        val flow = archive.replay(ReplayScope.All).frames()

        archive.append(first)
        val afterFirst = flow.toList()
        archive.append(second)
        val afterSecond = flow.toList()

        assertAll(
            { assertEquals(listOf(first), afterFirst.map { it.ops }, "the first collection sees one frame") },
            { assertEquals(listOf(first, second), afterSecond.map { it.ops }, "the second sees both") },
        )
    }

    @Test
    fun aRejectedConfigurationFailsAtConstructionRatherThanAtTheFirstAppend() {
        val badSegment = assertFailsWith<IllegalArgumentException> { bolt(segmentFrameBytes = 0L) }
        val badCapacity = assertFailsWith<IllegalArgumentException> { bolt(capacityBytes = 0L) }

        assertAll(
            { assertTrue(badSegment.message.orEmpty().contains("segmentFrameBytes"), "names the bad parameter") },
            { assertTrue(badCapacity.message.orEmpty().contains("capacityBytes"), "names the bad parameter") },
        )
    }

    private fun mintInserts(count: Int, from: Rga<String> = Rga.empty()): Pair<Rga<String>, List<RgaOp.Insert<String>>> {
        var live = from
        val ops = (0 until count).map { index ->
            val (next, op) = live.insertAt(alice, live.size, "value-$index")
            live = next
            op
        }
        return live to ops
    }

    private companion object {
        /** Smaller than a single segment header, so the very first append cannot fit. */
        const val TINY_CAPACITY = 8L
    }
}
