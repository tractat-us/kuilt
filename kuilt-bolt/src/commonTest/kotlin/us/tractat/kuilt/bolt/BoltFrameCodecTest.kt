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
     * The same hazard one layer up: a segment pre-allocated but crashed part-way through its header.
     *
     * A torn or absent header must stop replay quietly — it is a damaged tail, exactly like a torn
     * frame. A **complete** header with a foreign magic is the opposite case and must still throw:
     * that is bytes handed to the wrong decoder, and swallowing it would silently report an empty
     * archive where the real answer is "you opened the wrong file".
     *
     * ### Every torn case here carries a PRE-ALLOCATED TAIL, and that is the whole test
     *
     * Tasks 3 and 4 pre-allocate each segment with a real zero write, so a crash mid-header leaves a
     * *prefix* of a header followed by a kilobyte of zeroes — never a short file. Written the
     * obvious way, with `write(header, 0, 6)` and nothing after it, every case below collapses onto
     * the length guard [aHeaderShorterThanTheFormatAllowsIsAStop] already covers, and the named
     * paths are not exercised at all. The two that matter are the two the fixed fields cannot
     * distinguish from a real header on their own:
     *
     * - **torn after the magic** — version reads `0`, which a bare range check calls a version from
     *   before the first one, i.e. a reader mistake, i.e. a throw;
     * - **torn after the fixed run** — both self-description strings read empty, which a bare
     *   comparison calls an archive of a different op/element type, i.e. a throw.
     *
     * A throw out of either propagates through `emitFrames` and out of the replay flow, discarding
     * every intact frame in every earlier segment — the one thing `Bolt.replay` promises it will not
     * do. The header's CRC trailer is what makes them stops instead.
     */
    @Test
    fun aTornOrUnwrittenSegmentHeaderStopsQuietlyWhileAForeignOneStillThrows() {
        val whole = encodeSegmentHeader(SegmentHeader(BOLT_FORMAT_VERSION, opFormat, elementType, 0L))
        val unwritten = preallocated { }
        val tornAfterMagic = preallocated { write(whole, 0, MAGIC_BYTES + 2) }
        val tornAfterTheFixedRun = preallocated { write(whole, 0, FIXED_RUN_BYTES) }
        val tornInsideTheSelfDescription = preallocated { write(whole, 0, whole.size - 6) }
        val foreign = Buffer().apply { write("PKZIP-ish payload, definitely not ours".encodeToByteArray()) }

        assertAll(
            { assertNull(readSegmentHeader(unwritten, opFormat, elementType), "a pre-allocated segment is a stop") },
            {
                assertNull(
                    readSegmentHeader(tornAfterMagic, opFormat, elementType),
                    "so is one whose magic landed and whose version did not — version 0 is damage, not a demand",
                )
            },
            {
                assertNull(
                    readSegmentHeader(tornAfterTheFixedRun, opFormat, elementType),
                    "so is one whose fixed fields landed and whose self-description did not — empty is not a " +
                        "different op format, it is an unfinished write",
                )
            },
            {
                assertNull(
                    readSegmentHeader(tornInsideTheSelfDescription, opFormat, elementType),
                    "so is one torn INSIDE a length-prefixed string, where the length word is right and " +
                        "the characters it counts are not",
                )
            },
            {
                assertFailsWith<BoltFormatException>("a complete foreign header is a reader mistake, not a torn tail") {
                    readSegmentHeader(foreign, opFormat, elementType)
                }
            },
            {
                assertNotNull(
                    readSegmentHeader(preallocated { write(whole) }, opFormat, elementType),
                    "and a WHOLE header followed by the same pre-allocated tail still reads, or the above " +
                        "would pass for a bolt that refused every segment",
                )
            },
        )
    }

    /**
     * Bytes that run out **before** the header does — a short file rather than a pre-allocated one.
     *
     * Two different guards catch these, and both matter. Three bytes are refused on length before a
     * field is read at all. A header one byte short of whole clears that check easily and is caught
     * by the bounds check that locates the CRC trailer: without it, the reader believes a
     * self-description length word it cannot satisfy and walks straight off the end of the buffer —
     * an `EOFException` out of `readString`, an exception type `Bolt.replay` never documented and
     * which discards every intact frame ahead of it.
     */
    @Test
    fun aHeaderShorterThanTheFormatAllowsIsAStop() {
        val short = Buffer().apply { write(byteArrayOf(0x42, 0x4F, 0x4C)) } // "BOL" — torn mid-magic
        val header = encodeSegmentHeader(SegmentHeader(BOLT_FORMAT_VERSION, opFormat, elementType, 0L))
        val almost = Buffer().apply { write(header, 0, header.size - 1) }

        assertAll(
            { assertNull(readSegmentHeader(short, opFormat, elementType), "three bytes cannot be a header") },
            { assertNull(readSegmentHeader(almost, opFormat, elementType), "nor can one a byte short of whole") },
        )
    }

    /**
     * A header whose bytes were corrupted after the fact is a **stop**, not a throw.
     *
     * Corruption anywhere in the header — the base offset, a length word, a self-description
     * character — is indistinguishable from a write that never finished, and both are damage. The
     * control matters as much as the mutation: without it, a `readSegmentHeader` that returned
     * `null` unconditionally would pass.
     */
    @Test
    fun aCorruptedSegmentHeaderFailsItsChecksum() {
        val whole = encodeSegmentHeader(SegmentHeader(BOLT_FORMAT_VERSION, opFormat, elementType, 4096L))
        val flippedOffset = whole.copyOf().also { it[BASE_OFFSET_END] = (it[BASE_OFFSET_END] + 1).toByte() }
        val flippedTrailer = whole.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertAll(
            {
                assertNull(
                    readSegmentHeader(Buffer().apply { write(flippedOffset) }, opFormat, elementType),
                    "a flipped bit in the base offset is caught — an offset the reader trusted would " +
                        "misplace every frame in the segment",
                )
            },
            {
                assertNull(
                    readSegmentHeader(Buffer().apply { write(flippedTrailer) }, opFormat, elementType),
                    "and so is one in the trailer itself",
                )
            },
            {
                assertNotNull(
                    readSegmentHeader(Buffer().apply { write(whole) }, opFormat, elementType),
                    "and the uncorrupted control decodes, or the above proves nothing",
                )
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
     *
     * ### A version from the future must survive a CRC it cannot possibly match
     *
     * The last two cases pin the **read order**, which is the one part of the header layer where the
     * obvious arrangement is wrong. A genuine v2 archive is expected to have a *different header
     * length* — that is what a version field buys — so a v1 reader computes its CRC over the wrong
     * range and the trailer necessarily fails. Check the trailer first and a real v2 archive is
     * reported as a **torn tail**: replay stops silently, the operator is told the archive is
     * damaged, and the one diagnostic the version field exists to produce is destroyed. So the
     * version is read *before* the CRC, from bytes that have not been vouched for yet — and
     * [aTornOrUnwrittenSegmentHeaderStopsQuietlyWhileAForeignOneStillThrows] is what keeps that
     * cheap: a torn pre-allocated header reads version `0`, which is not from the future, so it
     * falls through to the trailer and is still correctly called damage.
     */
    @Test
    fun theVersionFieldIsASeamAndRejectsOnlyWhatItCannotRead() {
        val sealed = { version: Int ->
            Buffer().apply { write(encodeSegmentHeader(SegmentHeader(version, opFormat, elementType, 0L))) }
        }
        val versionPatched = { version: Int ->
            val bytes = encodeSegmentHeader(SegmentHeader(BOLT_FORMAT_VERSION, opFormat, elementType, 0L))
            bytes[VERSION_OFFSET + 3] = version.toByte() // the trailer is left STALE on purpose
            Buffer().apply { write(bytes) }
        }

        val current = readSegmentHeader(sealed(BOLT_FORMAT_VERSION), opFormat, elementType)
        val future = assertFailsWith<BoltFormatException> {
            readSegmentHeader(sealed(BOLT_FORMAT_VERSION + 1), opFormat, elementType)
        }
        val nonsense = assertFailsWith<BoltFormatException> { readSegmentHeader(sealed(0), opFormat, elementType) }
        val futureWithABadTrailer = assertFailsWith<BoltFormatException> {
            readSegmentHeader(versionPatched(BOLT_FORMAT_VERSION + 1), opFormat, elementType)
        }
        val belowFirstWithABadTrailer = readSegmentHeader(versionPatched(0), opFormat, elementType)
        val unknownFrameVersion = assertFailsWith<BoltFormatException> {
            readFrame(Buffer().apply { write(encodeFrame(RawFrame(arrivedAt, emptySet(), null, emptyList()))) }, 99)
        }

        assertAll(
            { assertEquals(BOLT_FORMAT_VERSION, assertNotNull(current).formatVersion, "the current version reads") },
            { assertTrue(future.message.orEmpty().contains("version"), "a future version is refused") },
            { assertTrue(nonsense.message.orEmpty().contains("version"), "so is a version below the first one") },
            {
                assertTrue(
                    futureWithABadTrailer.message.orEmpty().contains("version"),
                    "a future version is refused BEFORE the checksum is consulted — a v2 archive whose " +
                        "header this build cannot even measure must say 'too new', never 'torn'",
                )
            },
            {
                assertNull(
                    belowFirstWithABadTrailer,
                    "while below-first is checked AFTER, because 0 is what a half-written header holds — " +
                        "damage, not a demand",
                )
            },
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

    /**
     * [prefix]'s bytes followed by a segment's eagerly pre-allocated, never-written remainder.
     *
     * Tasks 3 and 4 mandate that remainder — a real zero write at roll time, so disk exhaustion
     * surfaces as a catchable failure rather than a SIGBUS. Every torn-header fixture is built
     * through here rather than as a short buffer, because a short buffer is caught by the length
     * guard and never reaches the field checks the fixture is aimed at.
     */
    private fun preallocated(prefix: Buffer.() -> Unit): Buffer = Buffer().apply {
        prefix()
        write(ByteArray(size = PREALLOCATED_TAIL))
    }

    private companion object {
        /** Byte offset of the format-version Int in a segment header: it follows the 4-byte magic. */
        const val VERSION_OFFSET = 4

        /** The `BOLT` magic, in bytes. */
        const val MAGIC_BYTES = 4

        /** Magic, version, flags and base offset — everything before the self-description strings. */
        const val FIXED_RUN_BYTES = 4 + 4 + 4 + 8

        /** Index of the base offset's last byte: it is the eighth byte of the field at [FIXED_RUN_BYTES] - 8. */
        const val BASE_OFFSET_END = FIXED_RUN_BYTES - 1

        /** Stand-in for a segment's eagerly pre-allocated, not-yet-written remainder. */
        const val PREALLOCATED_TAIL = 64
    }
}
