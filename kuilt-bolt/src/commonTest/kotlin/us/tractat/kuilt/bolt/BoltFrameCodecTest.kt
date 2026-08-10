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

        val read = assertNotNull(readSegmentHeader(buffer, opFormat, elementType), "an intact header decodes")

        assertAll(
            { assertEquals(header, read, "a header round-trips field for field") },
            { assertEquals(0L, buffer.size, "and consumes exactly its own bytes") },
            { assertEquals(1, BOLT_FORMAT_VERSION, "v1 is the format this build writes") },
        )
    }

    /**
     * Bytes with a non-zero foreign magic are a **reader mistake** and throw. (The all-zero case is
     * the opposite verdict — a pre-allocated segment — and is covered by
     * [aTornOrUnwrittenSegmentHeaderStopsQuietlyWhileAForeignOneStillThrows].)
     */
    @Test
    fun bytesThatAreNotABoltArchiveAreRejectedLoudly() {
        val notAnArchive = Buffer().apply { write(ByteArray(size = 64) { (it + 1).toByte() }) }

        val failure = assertFailsWith<BoltFormatException> { readSegmentHeader(notAnArchive, opFormat, elementType) }

        assertAll({ assertTrue(failure.message.orEmpty().contains("magic"), "the message names the magic number") })
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

        val read = assertNotNull(readFrame(buffer, BOLT_FORMAT_VERSION), "an intact frame must decode")

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

        val read = assertNotNull(readFrame(buffer, BOLT_FORMAT_VERSION), "an intact frame must decode")

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

        val first = assertNotNull(readFrame(buffer, BOLT_FORMAT_VERSION), "the intact frame decodes")
        val second = readFrame(buffer, BOLT_FORMAT_VERSION)

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

        val read = readFrame(Buffer().apply { write(corrupted) }, BOLT_FORMAT_VERSION)

        assertAll(
            { assertNull(read, "a checksum mismatch is indistinguishable from a torn tail, and treated the same") },
            {
                assertNotNull(
                    readFrame(Buffer().apply { write(encoded) }, BOLT_FORMAT_VERSION),
                    "and the uncorrupted control decodes, or the above proves nothing",
                )
            },
        )
    }

    /**
     * **A zero-filled tail must stop replay, not blow up in it.**
     *
     * This is the shape every disk-backed backend produces by construction: Tasks 3 and 4 require
     * each segment be *eagerly, physically* pre-allocated at roll time (a real write, not
     * `ftruncate`, so disk exhaustion surfaces as a catchable failure rather than a SIGBUS). That
     * leaves a zero-filled region immediately after the last written frame of every live segment,
     * and replay walks straight into it.
     *
     * Eight zero bytes are a *syntactically valid* frame unless the framing says otherwise:
     * `bodyLength` reads as `0`, the stored checksum reads as `0`, and CRC-32 of an empty body is
     * `0` — so the checksum **matches**. Two independent guards close it: a body shorter than
     * [MINIMUM_BODY_BYTES] can never be a real frame, and the checksum now covers the length prefix
     * as well as the body, so a run of zeroes checksums to `0x2144DF1C` and fails.
     */
    @Test
    fun aZeroFilledTailStopsReplayAfterTheLastRealFrame() {
        val first = RawFrame(arrivedAt, emptySet(), null, listOf(byteArrayOf(1)))
        val second = RawFrame(arrivedAt, setOf(Dot(ReplicaId("alice"), 1L)), null, listOf(byteArrayOf(2)))
        val buffer = Buffer().apply {
            write(encodeSegmentHeader(SegmentHeader(BOLT_FORMAT_VERSION, opFormat, elementType, 0L)))
            write(encodeFrame(first))
            write(encodeFrame(second))
            write(ByteArray(size = PREALLOCATED_TAIL)) // the pre-allocated, never-written remainder
        }

        val header = assertNotNull(readSegmentHeader(buffer, opFormat, elementType), "the header is intact")
        val recovered = buildList {
            while (true) add(readFrame(buffer, header.formatVersion) ?: break)
        }

        assertAll(
            { assertEquals(2, recovered.size, "exactly the two real frames replay") },
            { assertContentEquals(byteArrayOf(1), recovered[0].ops.single(), "frame 1 is intact") },
            { assertContentEquals(byteArrayOf(2), recovered[1].ops.single(), "frame 2 is intact") },
            { assertEquals(PREALLOCATED_TAIL.toLong(), buffer.size, "and the stop path consumed none of the tail") },
        )
    }

    /**
     * The same hazard one layer up: a segment pre-allocated but crashed before its header landed.
     *
     * A torn or absent header must stop replay quietly — it is a damaged tail, exactly like a torn
     * frame. A **complete** header with a foreign magic is the opposite case and must still throw:
     * that is bytes handed to the wrong decoder, and swallowing it would silently report an empty
     * archive where the real answer is "you opened the wrong file".
     */
    @Test
    fun aTornOrUnwrittenSegmentHeaderStopsQuietlyWhileAForeignOneStillThrows() {
        val unwritten = Buffer().apply { write(ByteArray(size = 128)) }
        val short = Buffer().apply { write(byteArrayOf(0x42, 0x4F, 0x4C)) } // "BOL" — torn mid-magic
        val tornAfterMagic = Buffer().apply {
            write(encodeSegmentHeader(SegmentHeader(BOLT_FORMAT_VERSION, opFormat, elementType, 0L)), 0, 6)
        }
        val foreign = Buffer().apply { write("PKZIP-ish payload, definitely not ours".encodeToByteArray()) }

        assertAll(
            { assertNull(readSegmentHeader(unwritten, opFormat, elementType), "a pre-allocated segment is a stop") },
            { assertNull(readSegmentHeader(short, opFormat, elementType), "so is one torn inside its magic") },
            { assertNull(readSegmentHeader(tornAfterMagic, opFormat, elementType), "so is one torn after it") },
            {
                assertFailsWith<BoltFormatException>("a complete foreign header is a reader mistake, not a torn tail") {
                    readSegmentHeader(foreign, opFormat, elementType)
                }
            },
        )
    }

    /**
     * A stop must leave the source untouched. `InMemoryBolt` builds a fresh `Buffer` per collection
     * so a partially-consumed one is invisible to it, but a memory-mapped backend reads through a
     * persistent cursor — and a stop that had already eaten the length prefix would advance that
     * cursor past the boundary it just refused to cross.
     */
    @Test
    fun aStoppedReadConsumesNothing() {
        val torn = encodeFrame(RawFrame(arrivedAt, emptySet(), null, listOf(byteArrayOf(7, 7, 7, 7))))
        val truncated = Buffer().apply { write(torn, startIndex = 0, endIndex = torn.size - 1) }
        val zeroes = Buffer().apply { write(ByteArray(size = 16)) }
        val truncatedSizeBefore = truncated.size
        val zeroesSizeBefore = zeroes.size

        val tornRead = readFrame(truncated, BOLT_FORMAT_VERSION)
        val zeroRead = readFrame(zeroes, BOLT_FORMAT_VERSION)

        assertAll(
            { assertNull(tornRead, "a truncated frame stops") },
            { assertNull(zeroRead, "so does a zero run") },
            { assertEquals(truncatedSizeBefore, truncated.size, "and neither consumed a byte of its source") },
            { assertEquals(zeroesSizeBefore, zeroes.size, "and neither consumed a byte of its source") },
        )
    }

    /**
     * The version field only earns its bytes if it is a **seam**, not a tripwire. Two halves:
     * a reader rejects a version it cannot possibly understand, and the frame reader is *given* the
     * version so a later layout has somewhere to branch.
     *
     * Only the reject-the-future direction is testable while [BOLT_FORMAT_VERSION] is 1 — accepting
     * an older archive needs a v2 build to assert from, and this test should grow that half then.
     */
    @Test
    fun theVersionFieldIsASeamAndRejectsOnlyWhatItCannotRead() {
        val forged = { version: Int ->
            val bytes = encodeSegmentHeader(SegmentHeader(BOLT_FORMAT_VERSION, opFormat, elementType, 0L))
            bytes[VERSION_OFFSET + 3] = version.toByte()
            Buffer().apply { write(bytes) }
        }

        val current = readSegmentHeader(forged(BOLT_FORMAT_VERSION), opFormat, elementType)
        val future = assertFailsWith<BoltFormatException> {
            readSegmentHeader(forged(BOLT_FORMAT_VERSION + 1), opFormat, elementType)
        }
        val nonsense = assertFailsWith<BoltFormatException> { readSegmentHeader(forged(0), opFormat, elementType) }
        val unknownFrameVersion = assertFailsWith<BoltFormatException> {
            readFrame(Buffer().apply { write(encodeFrame(RawFrame(arrivedAt, emptySet(), null, emptyList()))) }, 99)
        }

        assertAll(
            { assertEquals(BOLT_FORMAT_VERSION, assertNotNull(current).formatVersion, "the current version reads") },
            { assertTrue(future.message.orEmpty().contains("version"), "a future version is refused") },
            { assertTrue(nonsense.message.orEmpty().contains("version"), "so is a version below the first one") },
            {
                assertTrue(
                    unknownFrameVersion.message.orEmpty().contains("99"),
                    "and the frame reader dispatches on version, which is the branch point v2 needs",
                )
            },
        )
    }

    /**
     * Arrival timestamps are stored as **epoch milliseconds** and sub-millisecond precision is
     * truncated. A permanent format decision, so it is pinned here rather than left to be
     * rediscovered: every existing assertion in the suite uses whole seconds and would not notice.
     */
    @Test
    fun arrivalTimestampsAreStoredToMillisecondResolution() {
        val subMillisecond = Instant.fromEpochSeconds(1_700_000_000L, nanosecondAdjustment = 123_999_999L)
        val buffer = Buffer().apply { write(encodeFrame(RawFrame(subMillisecond, emptySet(), null, listOf(byteArrayOf(1))))) }

        val read = assertNotNull(readFrame(buffer, BOLT_FORMAT_VERSION), "the frame decodes")

        assertAll(
            {
                assertEquals(
                    Instant.fromEpochMilliseconds(1_700_000_000_123L),
                    read.arrivedAt,
                    "sub-millisecond precision is truncated, not rounded",
                )
            },
            { assertTrue(read.arrivedAt < subMillisecond, "truncation always moves the stamp earlier") },
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
            {
                assertEquals(
                    0x2144DF1C,
                    crc32(ByteArray(size = 4)),
                    "and four zero bytes do NOT — which is precisely why the checksum covers the length " +
                        "prefix: a zero run must never validate as a frame",
                )
            },
            {
                assertEquals(
                    crc32(byteArrayOf(1, 2, 3, 4, 5), fromIndex = 1, toIndex = 4),
                    crc32(byteArrayOf(2, 3, 4)),
                    "the ranged form agrees with the whole-array form over the same bytes",
                )
            },
        )
    }

    private companion object {
        /** Byte offset of the format-version Int in a segment header: it follows the 4-byte magic. */
        const val VERSION_OFFSET = 4

        /** Stand-in for a segment's eagerly pre-allocated, not-yet-written remainder. */
        const val PREALLOCATED_TAIL = 64
    }
}
