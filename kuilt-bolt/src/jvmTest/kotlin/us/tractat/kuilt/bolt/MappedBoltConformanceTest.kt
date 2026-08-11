package us.tractat.kuilt.bolt

import kotlinx.serialization.serializer
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.assertIs
import kotlin.time.Clock

/** [MappedBolt] against the shared [BoltConformanceSuite] — the JVM/Android disk backend. */
class MappedBoltConformanceTest : BoltConformanceSuite() {
    override fun newBolt(clock: Clock): Bolt<RgaOp<String>> = mappedBolt(clock)

    /**
     * A byte budget smaller than one segment header, so the archive is out of room before it has
     * pre-allocated anything. Not a real full disk — that cannot be produced deterministically, and
     * trying would make the obligation depend on the machine the suite runs on.
     */
    override fun newExhaustedBolt(clock: Clock): Bolt<RgaOp<String>> = mappedBolt(clock, capacityBytes = 8L)

    override suspend fun newTruncatedBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        truncatedMappedBolt(clock, intactFrames)

    /**
     * With a real pre-allocated tail on every segment — the shipped shape. The default 1 MiB budget
     * cannot be used as-is: it puts the whole fixture in ONE file, leaving no middle to lose.
     */
    override suspend fun newDiscontinuousBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        discontinuousMappedBolt(clock, intactFrames, PRE_ALLOCATED_TAIL_BYTES)
}

/**
 * The same suite against a bolt whose segment budget is small enough that every append rolls a new
 * segment — one frame per file.
 *
 * Segment rolling is where the offset space and the physical layout come apart: offsets count frame
 * bytes only, so a segment header has to be invisible to every cursor and every scope. On this
 * backend a header is also a *file* boundary, which the default 1 MiB budget would never reach in a
 * test.
 */
class TinySegmentMappedBoltConformanceTest : BoltConformanceSuite() {
    override fun newBolt(clock: Clock): Bolt<RgaOp<String>> = mappedBolt(clock, segmentFrameBytes = 1L)

    override fun newExhaustedBolt(clock: Clock): Bolt<RgaOp<String>> =
        mappedBolt(clock, segmentFrameBytes = 1L, capacityBytes = 8L)

    override suspend fun newTruncatedBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        truncatedMappedBolt(clock, intactFrames)

    /** The complement of the default subclass: **no** pre-allocated tail behind the last frame. */
    override suspend fun newDiscontinuousBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        discontinuousMappedBolt(clock, intactFrames, NO_PRE_ALLOCATED_TAIL)
}

/** The archive format every test in this source set uses: an `Rga` of `String`. */
internal fun rgaStringFormat(): BoltArchiveFormat<RgaId, String, RgaOp<String>> =
    BoltArchiveFormat.rga(serializer<String>())

/** A fresh, empty directory. Each bolt owns one — two bolts over one directory is undefined. */
internal fun tempArchiveDirectory(): File = createTempDirectory("kuilt-bolt-mapped").toFile()

internal fun mappedBolt(
    clock: Clock,
    directory: File = tempArchiveDirectory(),
    forceOnAppend: Boolean = true,
    segmentFrameBytes: Long = InMemoryBolt.DEFAULT_SEGMENT_FRAME_BYTES,
    capacityBytes: Long = Long.MAX_VALUE,
): MappedBolt<RgaId, String, RgaOp<String>> =
    MappedBolt(directory, rgaStringFormat(), clock, forceOnAppend, segmentFrameBytes, capacityBytes)

/** This archive's segment files, oldest first. */
internal fun segmentsIn(directory: File): List<File> =
    directory.listFiles().orEmpty().sortedBy { it.name }

/** The fixed size of this format's segment header, computed the way the backend computes it. */
internal fun segmentHeaderBytes(): Int {
    val format = rgaStringFormat()
    return encodeSegmentHeader(
        SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset = 0L),
    ).size
}

/**
 * Flip every bit of the byte at [index].
 *
 * A frame damaged this way stays exactly as long as it was, so every later segment's `baseOffset`
 * still describes where its frames really are: integrity is destroyed without moving anything.
 */
internal fun flipByteAt(file: File, index: Long) {
    RandomAccessFile(file, "rw").use { handle ->
        handle.seek(index)
        val original = handle.readByte().toInt()
        handle.seek(index)
        handle.writeByte(original xor BYTE_MASK)
    }
}

/**
 * An archive of [intactFrames] ordinary frames, then a segment whose only frame has had a byte
 * flipped, then a **healthy** segment behind the damage.
 *
 * Every frame here is written through [Bolt.append], so they are the real thing rather than bytes
 * this fixture believes are right; only the corruption is applied by hand, directly to the file,
 * which is what a disk-backed backend makes easy and an in-memory one does not.
 *
 * **The healthy segment behind the damage is the point, and not for the obvious reason** — see
 * [BoltConformanceSuite.newTruncatedBolt]. Hoisting the verdict out of the segment loop and
 * `continue`-ing past a damaged segment emits exactly one plausible-looking verdict, and is green
 * against a fixture whose damage is last.
 *
 * A one-byte segment budget puts exactly one frame in each file, so "the segment after the intact
 * prefix" is just the file at index [intactFrames] — no offset arithmetic to get wrong.
 */
private suspend fun truncatedMappedBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> {
    require(intactFrames >= 1) { "the fixture stops AFTER a frame, so it needs at least one" }
    val directory = tempArchiveDirectory()
    val format = rgaStringFormat()
    val bolt = MappedBolt(directory, format, clock, segmentFrameBytes = 1L)

    var live = Rga.empty<String>()
    repeat(intactFrames + FRAMES_BEHIND_THE_PREFIX) { index ->
        val (next, op) = live.insertAt(FIXTURE_REPLICA, live.size, "frame-$index")
        live = next
        assertIs<AppendResult.Written>(bolt.append(listOf(op)), "every fixture frame must be written")
    }
    // With a one-byte segment budget that byte is the last of the frame's CRC-32 trailer.
    val damaged = segmentsIn(directory)[intactFrames]
    flipByteAt(damaged, damaged.length() - 1)

    // Reopen so the replay reads the corrupted bytes back off the disk rather than out of a mapping
    // this process wrote. Recovery only ever scans the NEWEST segment, which is the healthy one, so
    // the damage is discovered by the replay — exactly as it would be after a restart.
    return MappedBolt(directory, format, clock, segmentFrameBytes = 1L)
}

/**
 * An archive of [intactFrames] ordinary frames, then a **deleted segment file**, then two healthy
 * frames behind the hole.
 *
 * Every frame is written through [Bolt.append] and then one whole file is removed — nothing is
 * rewritten, nothing is corrupted. That is the point: the surviving segments are byte-for-byte intact
 * and every one of their headers reads perfectly, so no checksum anywhere can notice, and the archive
 * is discontinuous only in the sense that one header's absolute `baseOffset` no longer follows the
 * previous segment's last frame. A backend without that comparison replays this as a [CleanTail] over
 * a history with a gap in it, which is what shipped (#2240).
 *
 * A one-byte segment budget puts exactly one frame in each file regardless of which subclass asks, so
 * "the segment after the intact prefix" is just the file at index [intactFrames].
 *
 * Reopened afterwards so the replay reads the surviving files from disk rather than out of a mapping
 * this process wrote, exactly as it would after a restart. Recovery only ever scans the newest
 * segment, which is healthy, so the hole is discovered by the replay.
 */
private suspend fun discontinuousMappedBolt(
    clock: Clock,
    intactFrames: Int,
    zeroTailBytes: Long,
): Bolt<RgaOp<String>> {
    require(intactFrames >= 1) { "the fixture stops AFTER a frame, so it needs at least one" }
    val directory = tempArchiveDirectory()
    val format = rgaStringFormat()
    val ops = buildList {
        var live = Rga.empty<String>()
        repeat(intactFrames + 1 + FRAMES_BEHIND_THE_HOLE) { index ->
            val (next, op) = live.insertAt(FIXTURE_REPLICA, live.size, "frame-$index")
            live = next
            add(op)
        }
    }
    // One frame per segment, plus whatever tail this subclass asked for. Measured from a real
    // encoded frame rather than guessed: the budget has to be big enough for one frame and too
    // small for two, and a fixture that silently packed two frames into a segment would have no
    // middle to lose.
    val frameBytes = encodeFrame(
        RawFrame(clock.now(), setOf(ops[0].id.dot), null, listOf(format.encode(ops[0]))),
    ).size.toLong()
    check(zeroTailBytes < frameBytes) { "a $zeroTailBytes-byte pad would leave room for a second frame" }
    val budget = frameBytes + zeroTailBytes
    val bolt = MappedBolt(directory, format, clock, segmentFrameBytes = budget)

    ops.forEach { assertIs<AppendResult.Written>(bolt.append(listOf(it)), "every fixture frame must be written") }
    check(segmentsIn(directory).size == ops.size) {
        "the fixture needs one frame per segment, or there is no middle segment to lose"
    }
    check(segmentsIn(directory)[intactFrames].delete()) { "the fixture's hole must actually be punched" }

    return MappedBolt(directory, format, clock, segmentFrameBytes = budget)
}

/** The frame that gets damaged, and the healthy one behind it. */
private const val FRAMES_BEHIND_THE_PREFIX = 2

/** Frames behind the hole. More than one, so "stepped over it" is unmistakable rather than off-by-one. */
private const val FRAMES_BEHIND_THE_HOLE = 2

/**
 * A pre-allocated tail on every fixture segment, so the segment before the hole ends the way this
 * backend's ordinary segments end. Smaller than a frame, so only one frame lands per segment.
 */
private const val PRE_ALLOCATED_TAIL_BYTES = 32L

/** No tail: each segment is sized to the one frame that forced it, ending exactly on a frame boundary. */
private const val NO_PRE_ALLOCATED_TAIL = 0L
internal const val BYTE_MASK = 0xFF
private val FIXTURE_REPLICA = ReplicaId("alice")
