package us.tractat.kuilt.bolt

import kotlinx.io.Buffer
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The archive format itself, exercised without a [Bolt].
 *
 * This is where the expensive-to-change decisions are pinned: the magic number, the version field,
 * the segment's self-description, the frame's reserved key slot, and the length-prefix + checksum
 * pair that makes a torn tail recoverable instead of fatal.
 */
class BoltFrameCodecTest {

    private val opFormat = "us.tractat.kuilt.crdt.RgaOp"
    private val elementType = "kotlin.String"
    private val arrivedAt = Instant.fromEpochMilliseconds(1_700_000_000_123L)

    @Test
    fun aSegmentHeaderRoundTripsAndCarriesItsVersionAndSelfDescription() {
        val header = SegmentHeader(BOLT_FORMAT_VERSION, opFormat, elementType, baseOffset = 4096L)
        val buffer = Buffer().apply { write(encodeSegmentHeader(header)) }

        val read = readSegmentHeader(buffer, opFormat, elementType)

        assertAll(
            { assertEquals(header, read, "a header round-trips field for field") },
            { assertEquals(0L, buffer.size, "and consumes exactly its own bytes") },
            { assertEquals(1, BOLT_FORMAT_VERSION, "v1 is the format this build writes") },
        )
    }

    @Test
    fun bytesThatAreNotABoltArchiveAreRejectedLoudly() {
        val notAnArchive = Buffer().apply { write(ByteArray(size = 64)) }

        val failure = assertFailsWith<BoltFormatException> { readSegmentHeader(notAnArchive, opFormat, elementType) }

        assertAll({ assertTrue(failure.message.orEmpty().contains("magic"), "the message names the magic number") })
    }

    @Test
    fun anArchiveFromAFutureFormatVersionIsRejectedRatherThanMisread() {
        val bytes = encodeSegmentHeader(SegmentHeader(BOLT_FORMAT_VERSION, opFormat, elementType, baseOffset = 0L))
        // The version is the second big-endian Int; forge a v2 archive.
        bytes[VERSION_OFFSET + 3] = (BOLT_FORMAT_VERSION + 1).toByte()
        val buffer = Buffer().apply { write(bytes) }

        val failure = assertFailsWith<BoltFormatException> { readSegmentHeader(buffer, opFormat, elementType) }

        assertAll(
            {
                assertTrue(
                    failure.message.orEmpty().contains("version"),
                    "a reader that cannot read the format says so — this is what the version field buys",
                )
            },
        )
    }

    @Test
    fun anArchiveOfADifferentOpOrElementTypeIsRejected() {
        val bytes = encodeSegmentHeader(SegmentHeader(BOLT_FORMAT_VERSION, opFormat, elementType, baseOffset = 0L))

        val wrongOp = assertFailsWith<BoltFormatException> {
            readSegmentHeader(Buffer().apply { write(bytes) }, "us.tractat.kuilt.crdt.FugueOp", elementType)
        }
        val wrongElement = assertFailsWith<BoltFormatException> {
            readSegmentHeader(Buffer().apply { write(bytes) }, opFormat, "kotlin.Int")
        }

        assertAll(
            { assertTrue(wrongOp.message.orEmpty().contains("FugueOp"), "the mismatch names both sides") },
            { assertTrue(wrongElement.message.orEmpty().contains("kotlin.Int"), "the mismatch names both sides") },
        )
    }

    @Test
    fun aFrameRoundTripsEveryFieldIncludingTheReservedKeySlot() {
        val frame = RawFrame(
            arrivedAt = arrivedAt,
            insertDots = setOf(Dot(ReplicaId("alice"), 7L), Dot(ReplicaId("bob"), 2L)),
            key = "2026-08-10T00:00:00Z",
            ops = listOf(byteArrayOf(1, 2, 3), byteArrayOf(), byteArrayOf(-1)),
        )
        val buffer = Buffer().apply { write(encodeFrame(frame)) }

        val read = assertNotNull(readFrame(buffer), "an intact frame must decode")

        assertAll(
            { assertEquals(frame.arrivedAt, read.arrivedAt, "arrival time survives") },
            { assertEquals(frame.insertDots, read.insertDots, "the insert dots survive") },
            {
                assertEquals(
                    frame.key,
                    read.key,
                    "the reserved key slot survives — encoded now so layering an index on later " +
                        "is not a format change",
                )
            },
            { assertEquals(frame.ops.size, read.ops.size, "every op survives") },
            { frame.ops.zip(read.ops).forEach { (a, b) -> assertContentEquals(a, b, "op bytes survive verbatim") } },
            { assertEquals(0L, buffer.size, "a frame consumes exactly its own bytes") },
        )
    }

    @Test
    fun aFrameWithNoKeyDecodesToNullRatherThanAnEmptyString() {
        val frame = RawFrame(arrivedAt, insertDots = emptySet(), key = null, ops = listOf(byteArrayOf(9)))
        val buffer = Buffer().apply { write(encodeFrame(frame)) }

        val read = assertNotNull(readFrame(buffer), "an intact frame must decode")

        assertAll(
            { assertNull(read.key, "absent is distinct from empty") },
            { assertEquals(emptySet(), read.insertDots, "a frame of removes carries no dots") },
        )
    }

    /**
     * A crash mid-append leaves a partial frame. Replay must stop at it and keep everything ahead of
     * it, so `readFrame` reports "stop" rather than throwing — throwing would discard an archive's
     * whole intact prefix over a damaged tail.
     */
    @Test
    fun aTruncatedTailStopsReplayWithoutDiscardingTheIntactFramesBeforeIt() {
        val intact = encodeFrame(RawFrame(arrivedAt, emptySet(), null, listOf(byteArrayOf(1))))
        val torn = encodeFrame(RawFrame(arrivedAt, emptySet(), null, listOf(byteArrayOf(2, 2, 2, 2))))
        val buffer = Buffer().apply {
            write(intact)
            write(torn, startIndex = 0, endIndex = torn.size - 1)
        }

        val first = assertNotNull(readFrame(buffer), "the intact frame decodes")
        val second = readFrame(buffer)

        assertAll(
            { assertContentEquals(byteArrayOf(1), first.ops.single(), "the intact frame is recovered whole") },
            { assertNull(second, "the torn frame reports stop, not failure") },
        )
    }

    @Test
    fun aFrameWhoseBytesWereCorruptedFailsItsChecksum() {
        val encoded = encodeFrame(RawFrame(arrivedAt, emptySet(), null, listOf(byteArrayOf(1, 2, 3, 4))))
        val corrupted = encoded.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2] + 1).toByte()

        val read = readFrame(Buffer().apply { write(corrupted) })

        assertAll(
            { assertNull(read, "a checksum mismatch is indistinguishable from a torn tail, and treated the same") },
            {
                assertNotNull(
                    readFrame(Buffer().apply { write(encoded) }),
                    "and the uncorrupted control decodes, or the above proves nothing",
                )
            },
        )
    }

    /**
     * The checksum is CRC-32/ISO-HDLC, hand-rolled because the stdlib has no multiplatform one.
     * Pinned against the standard check value so a JVM-only `java.util.zip.CRC32` can never be
     * swapped in on one target and silently make that target's archives unreadable by another.
     */
    @Test
    fun crc32MatchesTheStandardCheckValue() {
        val check = "123456789".encodeToByteArray()

        assertAll(
            { assertEquals(0xCBF43926.toInt(), crc32(check), "CRC-32(\"123456789\") is the standard check value") },
            { assertEquals(0, crc32(ByteArray(size = 0)), "the empty message checksums to zero") },
        )
    }

    private companion object {
        /** Byte offset of the format-version Int in a segment header: it follows the 4-byte magic. */
        const val VERSION_OFFSET = 4
    }
}
