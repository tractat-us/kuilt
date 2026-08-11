@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.bolt

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.serializer
import platform.Foundation.NSFileManager
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import kotlin.test.AfterTest
import kotlin.test.assertIs
import kotlin.time.Clock

/** [PosixMappedBolt] against the shared [BoltConformanceSuite], at its shipped segment budget. */
class PosixMappedBoltConformanceTest : BoltConformanceSuite() {
    override fun newBolt(clock: Clock): Bolt<RgaOp<String>> =
        PosixMappedBolt(rgaArchiveFormat(), clock, boltTestDirectory())

    /**
     * Smaller than a single segment file, so the archive is full before the first append — and no
     * segment is allocated, which is what lets the suite assert nothing partial was left behind.
     */
    override fun newExhaustedBolt(clock: Clock): Bolt<RgaOp<String>> =
        PosixMappedBolt(rgaArchiveFormat(), clock, boltTestDirectory(), capacityBytes = 8L)

    override suspend fun newTruncatedBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        truncatedPosixMappedBolt(clock, intactFrames, PosixMappedBolt.DEFAULT_SEGMENT_FRAME_BYTES)

    override suspend fun newDiscontinuousBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        discontinuousPosixMappedBolt(clock, intactFrames, synchronous = true)

    @AfterTest
    fun removeArchives(): Unit = removeBoltTestDirectories()
}

/**
 * The same suite against a bolt whose segment budget is small enough that **every** append rolls a
 * new segment file.
 *
 * Two things only a multi-segment archive exercises. Offsets count frame bytes only, so a segment
 * header has to be invisible to every cursor and every scope — with the 1 MiB default budget the
 * whole roll path would be dead code here. And these segments are sized to the frame that forced the
 * roll, so they carry **no** pre-allocated zero tail, which is the complement of the default
 * subclass: between them, the "a segment ends in zeroes" and "a segment ends exactly on a frame
 * boundary" replay paths are both driven.
 */
class TinySegmentPosixMappedBoltConformanceTest : BoltConformanceSuite() {
    override fun newBolt(clock: Clock): Bolt<RgaOp<String>> =
        PosixMappedBolt(rgaArchiveFormat(), clock, boltTestDirectory(), segmentFrameBytes = 1L)

    override fun newExhaustedBolt(clock: Clock): Bolt<RgaOp<String>> =
        PosixMappedBolt(rgaArchiveFormat(), clock, boltTestDirectory(), segmentFrameBytes = 1L, capacityBytes = 8L)

    override suspend fun newTruncatedBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        truncatedPosixMappedBolt(clock, intactFrames, segmentFrameBytes = 1L)

    override suspend fun newDiscontinuousBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        discontinuousPosixMappedBolt(clock, intactFrames, synchronous = true)

    @AfterTest
    fun removeArchives(): Unit = removeBoltTestDirectories()
}

/** The same suite with the durability flag off — asynchronous is the same mechanism without `msync`. */
class AsynchronousPosixMappedBoltConformanceTest : BoltConformanceSuite() {
    override fun newBolt(clock: Clock): Bolt<RgaOp<String>> =
        PosixMappedBolt(rgaArchiveFormat(), clock, boltTestDirectory(), synchronous = false)

    override fun newExhaustedBolt(clock: Clock): Bolt<RgaOp<String>> =
        PosixMappedBolt(rgaArchiveFormat(), clock, boltTestDirectory(), synchronous = false, capacityBytes = 8L)

    override suspend fun newTruncatedBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        truncatedPosixMappedBolt(clock, intactFrames, PosixMappedBolt.DEFAULT_SEGMENT_FRAME_BYTES)

    override suspend fun newDiscontinuousBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        discontinuousPosixMappedBolt(clock, intactFrames, synchronous = false)

    @AfterTest
    fun removeArchives(): Unit = removeBoltTestDirectories()
}

internal fun rgaArchiveFormat(): BoltArchiveFormat<RgaId, String, RgaOp<String>> =
    BoltArchiveFormat.rga(serializer<String>())

/**
 * An on-disk archive of [intactFrames] ordinary frames, then a segment whose frame is **a byte
 * short**, then a **healthy** segment behind the damage.
 *
 * The intact prefix is written through [Bolt.append], so those frames are the real thing rather than
 * bytes this fixture believes are right. Only the last two segments are seeded raw, because nothing a
 * consumer can call produces a torn one — which is exactly why the conformance suite needs the hook.
 *
 * **The healthy segment behind the damage is the point of the fixture, and NOT for the obvious
 * reason.** Replacing `replay`'s `return@flow` with a bare `continue` reddens the property whatever
 * the fixture looks like — the loop falls through to the unconditional `emit(CleanTail)` and the
 * verdict is simply wrong. What the follower defends against is the *tidier* rewrite: hoist the
 * verdict out of the loop and `continue` past a damaged segment. That version emits exactly one
 * correct-looking verdict and is green against a fixture whose damage is last.
 *
 * The damage is a truncated frame rather than a torn header so the *frame* stop path is what the
 * suite drives; the header stop path is covered byte-for-byte in `BoltFrameCodecTest`.
 */
private suspend fun truncatedPosixMappedBolt(
    clock: Clock,
    intactFrames: Int,
    segmentFrameBytes: Long,
): Bolt<RgaOp<String>> {
    require(intactFrames >= 1) { "the fixture stops AFTER a frame, so it needs at least one" }
    val format = rgaArchiveFormat()
    val bolt = PosixMappedBolt(format, clock, boltTestDirectory(), segmentFrameBytes = segmentFrameBytes)
    val alice = ReplicaId("alice")

    var live = Rga.empty<String>()
    var cursor = 0L
    repeat(intactFrames) { index ->
        val (next, op) = live.insertAt(alice, live.size, "intact-$index")
        live = next
        cursor = assertIs<AppendResult.Written>(bolt.append(listOf(op)), "the intact prefix must be written").endOffset
    }

    val (afterTorn, tornOp) = live.insertAt(alice, live.size, "torn")
    val tornFrame = encodeFrame(RawFrame(clock.now(), setOf(tornOp.id.dot), null, listOf(format.encode(tornOp))))
    val damaged = rawSegment(format, baseOffset = cursor) { write(tornFrame, endIndex = tornFrame.size - 1) }
    bolt.seedRawSegment(damaged.bytes, cursor, damaged.headerBytes)

    val behindTheDamage = cursor + tornFrame.size - 1
    val (_, healthyOp) = afterTorn.insertAt(alice, afterTorn.size, "behind-the-damage")
    val healthy = encodeFrame(RawFrame(clock.now(), setOf(healthyOp.id.dot), null, listOf(format.encode(healthyOp))))
    val behind = rawSegment(format, baseOffset = behindTheDamage) { write(healthy) }
    bolt.seedRawSegment(behind.bytes, behindTheDamage, behind.headerBytes)

    return bolt
}

/**
 * An on-disk archive of [intactFrames] ordinary frames, then a **deleted segment file**, then two
 * healthy frames behind the hole.
 *
 * Every frame is written through [Bolt.append] and then one whole file is removed — nothing is
 * rewritten, nothing is corrupted. That is the point: the surviving files are byte-for-byte intact
 * and every one of their headers reads perfectly, so no checksum anywhere can notice, and the archive
 * is discontinuous only in the sense that one header's absolute `baseOffset` no longer follows the
 * previous segment's last frame. Neither of this backend's other defences sees it — a zero tail is
 * what a healthy pre-allocated segment ends in, and the recorded-extent check is derived across the
 * hole and so is corrupted by exactly the state it would have to detect.
 *
 * A one-byte segment budget puts exactly one frame in each file regardless of which subclass asks, so
 * "the segment after the intact prefix" is just the file at index [intactFrames]. The writer is
 * closed before its file is deleted, and the archive is re-opened afterwards, so the replay adopts
 * what is actually on disk rather than a segment list that predates the hole.
 */
private suspend fun discontinuousPosixMappedBolt(
    clock: Clock,
    intactFrames: Int,
    synchronous: Boolean,
): Bolt<RgaOp<String>> {
    require(intactFrames >= 1) { "the fixture stops AFTER a frame, so it needs at least one" }
    val format = rgaArchiveFormat()
    val directory = boltTestDirectory()
    val writer = PosixMappedBolt(format, clock, directory, synchronous, segmentFrameBytes = 1L)
    val alice = ReplicaId("alice")

    var live = Rga.empty<String>()
    repeat(intactFrames + 1 + FRAMES_BEHIND_THE_HOLE) { index ->
        val (next, op) = live.insertAt(alice, live.size, "frame-$index")
        live = next
        assertIs<AppendResult.Written>(writer.append(listOf(op)), "every fixture frame must be written")
    }
    writer.close()
    val hole = segmentFiles(directory)[intactFrames]
    check(NSFileManager.defaultManager.removeItemAtPath(hole, error = null)) {
        "the fixture's hole must actually be punched — $hole is still there"
    }

    return PosixMappedBolt(format, clock, directory, synchronous, segmentFrameBytes = 1L)
}

/** Frames behind the hole. More than one, so "stepped over it" is unmistakable rather than off-by-one. */
private const val FRAMES_BEHIND_THE_HOLE = 2

/** A segment's bytes: a whole header for [format] at [baseOffset], then whatever [frames] writes. */
private fun rawSegment(
    format: BoltArchiveFormat<RgaId, String, RgaOp<String>>,
    baseOffset: Long,
    frames: Buffer.() -> Unit,
): SeededSegment {
    val header = encodeSegmentHeader(
        SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset),
    )
    val bytes = Buffer().apply {
        write(header)
        frames()
    }
    return SeededSegment(bytes.readByteArray(), header.size)
}

private class SeededSegment(val bytes: ByteArray, val headerBytes: Int)
