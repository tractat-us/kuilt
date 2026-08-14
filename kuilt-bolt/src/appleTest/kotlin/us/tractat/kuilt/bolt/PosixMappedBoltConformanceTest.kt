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

    /**
     * With a real pre-allocated tail on every segment — the shipped shape, and the one where the
     * zero-tail/recorded-extent machinery is actually consulted. The default 1 MiB budget cannot be
     * used as-is: it puts the whole fixture in ONE segment, leaving no middle to lose.
     */
    override suspend fun newDiscontinuousBolt(
        clock: Clock,
        intactFrames: Int,
        lostSegments: Int,
    ): DiscontinuousFixture =
        discontinuousPosixMappedBolt(clock, intactFrames, lostSegments, synchronous = true, PRE_ALLOCATED_TAIL_BYTES)

    /** With the same pre-allocated tail, so the segment before the jump ends the shipped way. */
    override suspend fun newBackwardsJumpBolt(clock: Clock, intactFrames: Int): BackwardsJumpFixture =
        backwardsJumpPosixMappedBolt(clock, intactFrames, synchronous = true, PRE_ALLOCATED_TAIL_BYTES)

    override fun newBoltThatCannotFlush(clock: Clock): DurabilityFixture =
        unflushablePosixMappedBolt(clock, synchronous = true)

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

    /**
     * The complement of the default subclass, and the reason this class exists: **no** pre-allocated
     * tail, so the segment before the hole runs out of bytes exactly on a frame boundary and exits
     * the parse loop normally — never consulting the recorded extent at all. The two configurations
     * reach the continuity check by different routes and must reach the same verdict.
     */
    override suspend fun newDiscontinuousBolt(
        clock: Clock,
        intactFrames: Int,
        lostSegments: Int,
    ): DiscontinuousFixture =
        discontinuousPosixMappedBolt(clock, intactFrames, lostSegments, synchronous = true, NO_PRE_ALLOCATED_TAIL)

    /** The complement again: the segment before the jump ends exactly on a frame boundary. */
    override suspend fun newBackwardsJumpBolt(clock: Clock, intactFrames: Int): BackwardsJumpFixture =
        backwardsJumpPosixMappedBolt(clock, intactFrames, synchronous = true, NO_PRE_ALLOCATED_TAIL)

    /**
     * One frame per segment, so a durability doubt is carried across **rolls** rather than staying
     * inside one mapping — the configuration where widening has to compose with a retiring segment's
     * header flush, and the default 1 MiB budget never reaches.
     */
    override fun newBoltThatCannotFlush(clock: Clock): DurabilityFixture =
        unflushablePosixMappedBolt(clock, synchronous = true, segmentFrameBytes = 1L)

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

    override suspend fun newDiscontinuousBolt(
        clock: Clock,
        intactFrames: Int,
        lostSegments: Int,
    ): DiscontinuousFixture =
        discontinuousPosixMappedBolt(clock, intactFrames, lostSegments, synchronous = false, PRE_ALLOCATED_TAIL_BYTES)

    override suspend fun newBackwardsJumpBolt(clock: Clock, intactFrames: Int): BackwardsJumpFixture =
        backwardsJumpPosixMappedBolt(clock, intactFrames, synchronous = false, PRE_ALLOCATED_TAIL_BYTES)

    override fun newBoltThatCannotFlush(clock: Clock): DurabilityFixture =
        unflushablePosixMappedBolt(clock, synchronous = false)

    @AfterTest
    fun removeArchives(): Unit = removeBoltTestDirectories()
}

internal fun rgaArchiveFormat(): BoltArchiveFormat<RgaId, String, RgaOp<String>> =
    BoltArchiveFormat.rga(serializer<String>())

/**
 * A [PosixMappedBolt] whose `msync` cannot succeed, labelled with what [synchronous] promised.
 *
 * The rig hands `msync` an address the kernel refuses, so the syscall really runs and the errno is
 * real — see `PosixMappedBolt.rigFlushFailure`.
 *
 * **The asynchronous arm here is weaker than its JVM counterpart, and the difference is worth
 * stating rather than leaving for a reader to assume symmetry.** `MappedBolt` flushes the *retiring*
 * segment at a roll whatever its durability flag says, so its asynchronous fixture can be made to
 * attempt a flush, fail it, and still answer [DurabilityState.AsPromised] — a real claim, and one
 * that caught a real defect. `PosixMappedBolt` has no such ungated flush: both of its durability
 * syncs are `if (synchronous)`, so an asynchronous bolt of this backend issues **none** at any
 * segment budget, and the rig genuinely cannot be reached. The arm still reds an *absolute* reading
 * of durability, which is what it is for; it cannot red a "records a flush it did not promise" bug,
 * because this backend has no site that could commit one. `AsynchronousMappedBoltConformanceTest`
 * carries that half.
 */
internal fun unflushablePosixMappedBolt(
    clock: Clock,
    synchronous: Boolean,
    segmentFrameBytes: Long = PosixMappedBolt.DEFAULT_SEGMENT_FRAME_BYTES,
): DurabilityFixture {
    val bolt = PosixMappedBolt(rgaArchiveFormat(), clock, boltTestDirectory(), synchronous, segmentFrameBytes)
    bolt.rigFlushFailure(true)
    return if (synchronous) DurabilityFixture.Promised(bolt) else DurabilityFixture.PromisedNothingAndNeverFlushes(bolt)
}

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
 * An on-disk archive of [intactFrames] ordinary frames, then [lostSegments] **deleted segment files**,
 * then two healthy frames behind the hole.
 *
 * Every frame is written through [Bolt.append] and then whole files are removed — nothing is
 * rewritten, nothing is corrupted. That is the point: the surviving files are byte-for-byte intact
 * and every one of their headers reads perfectly, so no checksum anywhere can notice, and the archive
 * is discontinuous only in the sense that one header's absolute `baseOffset` no longer follows the
 * previous segment's last frame. Neither of this backend's other defences sees it — a zero tail is
 * what a healthy pre-allocated segment ends in, and the recorded-extent check is derived across the
 * hole and so is corrupted by exactly the state it would have to detect.
 *
 * One frame per segment file, so "the segment after the intact prefix" is just the file at index
 * [intactFrames] — and one removed file is one lost frame, which is what makes
 * [DiscontinuousFixture]'s count mean lost *segments* on this backend. The writer is closed before any
 * file is deleted, and the archive is re-opened afterwards, so the replay adopts what is actually on
 * disk rather than a segment list that predates the hole.
 *
 * Both edges of the hole are handed back as the **appends themselves** rather than as offsets computed
 * from file sizes. Removing a file frees its bytes without moving anything: every surviving header
 * still carries the absolute `baseOffset` it was written with, which is exactly why the archive is
 * discontinuous rather than short.
 */
private suspend fun discontinuousPosixMappedBolt(
    clock: Clock,
    intactFrames: Int,
    lostSegments: Int,
    synchronous: Boolean,
    zeroTailBytes: Long,
): DiscontinuousFixture {
    require(intactFrames >= 1) { "the fixture stops AFTER a frame, so it needs at least one" }
    require(lostSegments >= 1) { "an archive that lost nothing has no hole in it" }
    val archive = oneFramePerSegmentArchive(
        clock,
        intactFrames + lostSegments + FRAMES_BEHIND_THE_HOLE,
        synchronous,
        zeroTailBytes,
    )
    archive.checkTailBefore(intactFrames, "the hole")
    repeat(lostSegments) {
        // Always the file at [intactFrames]: the listing closes up behind each removal, so removing
        // that index repeatedly takes CONSECUTIVE segments and leaves one wider hole rather than
        // several narrow ones — which is what `DiscontinuousFixture` checks from the other side.
        val hole = segmentFiles(archive.directory)[intactFrames]
        check(NSFileManager.defaultManager.removeItemAtPath(hole, error = null)) {
            "the fixture's hole must actually be punched — $hole is still there"
        }
    }

    return DiscontinuousFixture(
        archive.reopen(),
        lostFrames = archive.written.subList(intactFrames, intactFrames + lostSegments),
        firstSurvivor = archive.written[intactFrames + lostSegments],
    )
}

/**
 * An on-disk archive of [intactFrames] ordinary frames, then a **stale segment file restored** over
 * the one that should follow them, then two healthy frames behind the jump.
 *
 * The oldest segment file is copied over the file just past the intact prefix — a backup restoring one
 * generation of a file over another, a directory holding two generations of the same archive. Nothing
 * is corrupted and nothing is missing: every file reads perfectly, and the archive's offset space
 * simply runs **backwards** at one boundary, because that file's header carries the *absolute* base
 * offset it was written with. A backend asking only whether a segment starts too far *along* is blind
 * to it, and every other fixture in this tree agrees with such a backend.
 *
 * The stale copy is the **oldest** segment rather than the immediately preceding one, so the jump goes
 * back past whole frames rather than to a boundary inside the last of them — [BackwardsJumpFixture]
 * refuses to be built otherwise. The frames behind the jump are therefore duplicates of frames the
 * replay has already emitted, which is the harm a backend stepping over the jump commits.
 *
 * Adoption derives every non-last segment's extent as its successor's base minus its own, so a base
 * offset that runs backwards makes one of those derivations **negative**. That is left alone rather
 * than smoothed over: a derived extent is an inference *from* continuity and this backend's replay
 * says so in as many words, refusing to treat it as evidence about the very thing it assumes. A
 * fixture that arranged the damage to keep those derivations tidy would be choosing the configuration
 * in which the claim cannot fail.
 */
private suspend fun backwardsJumpPosixMappedBolt(
    clock: Clock,
    intactFrames: Int,
    synchronous: Boolean,
    zeroTailBytes: Long,
): BackwardsJumpFixture {
    require(intactFrames >= 2) { "the jump must go back past a WHOLE frame, so it needs a frame to spare" }
    val archive = oneFramePerSegmentArchive(clock, intactFrames + 1 + FRAMES_BEHIND_THE_HOLE, synchronous, zeroTailBytes)
    archive.checkTailBefore(intactFrames, "the jump")
    val files = segmentFiles(archive.directory)
    val manager = NSFileManager.defaultManager
    check(manager.removeItemAtPath(files[intactFrames], error = null)) {
        "the segment the stale copy lands on must be cleared first — ${files[intactFrames]} is still there"
    }
    check(manager.copyItemAtPath(files[0], toPath = files[intactFrames], error = null)) {
        "the stale segment must actually be restored over ${files[intactFrames]}"
    }

    return BackwardsJumpFixture(
        archive.reopen(),
        revisited = archive.written[0],
        lastFrameBeforeTheJump = archive.written[intactFrames - 1],
    )
}

/**
 * [frames] appends into a fresh directory at a budget that puts exactly **one frame in each segment
 * file**, plus [zeroTailBytes] of pre-allocated tail on each, with the writer closed.
 *
 * The budget is measured from a real encoded frame rather than guessed: it has to be big enough for
 * one frame and too small for two, and a fixture that silently packed two frames into a segment would
 * have no middle segment to damage. A budget of ONE byte is how "no tail at all" is expressed — a
 * segment is allocated at `maxOf(budget, frame.size)`, so a one-byte budget sizes every segment to
 * exactly the frame that forced it. Adding the pad to a *measured* frame size would not do it: these
 * frames differ in size by a few bytes as the `Rga` ids grow, so every segment but one would still get
 * a small accidental tail and the "ends on a frame boundary" path would go undriven.
 */
private suspend fun oneFramePerSegmentArchive(
    clock: Clock,
    frames: Int,
    synchronous: Boolean,
    zeroTailBytes: Long,
): FixtureArchive {
    val format = rgaArchiveFormat()
    val alice = ReplicaId("alice")
    val ops = buildList {
        var live = Rga.empty<String>()
        repeat(frames) { index ->
            val (next, op) = live.insertAt(alice, live.size, "frame-$index")
            live = next
            add(op)
        }
    }
    val frameBytes = encodedFrameBytes(format, clock, ops[0])
    check(zeroTailBytes < frameBytes) { "a $zeroTailBytes-byte pad would leave room for a second frame" }
    val budget = if (zeroTailBytes == NO_PRE_ALLOCATED_TAIL) 1L else frameBytes + zeroTailBytes
    val directory = boltTestDirectory()
    val writer = PosixMappedBolt(format, clock, directory, synchronous, budget)
    val written = ops.map { assertIs<AppendResult.Written>(writer.append(listOf(it)), "every fixture frame is written") }
    writer.close()
    check(segmentFiles(directory).size == ops.size) {
        "the fixture needs one frame per segment, or there is no middle segment to damage — and one " +
            "removed file would then be more than one lost frame, which is what the suite counts"
    }
    return FixtureArchive(directory, format, clock, synchronous, budget, ops, written, zeroTailBytes)
}

/** The bytes one frame carrying [op] encodes to, computed the way the backend computes them. */
private fun encodedFrameBytes(
    format: BoltArchiveFormat<RgaId, String, RgaOp<String>>,
    clock: Clock,
    op: RgaOp.Insert<String>,
): Long = encodeFrame(RawFrame(clock.now(), setOf(op.id.dot), null, listOf(format.encode(op)))).size.toLong()

/** One frame per segment file, and everything the damage fixtures need to say what they did. */
private class FixtureArchive(
    val directory: String,
    private val format: BoltArchiveFormat<RgaId, String, RgaOp<String>>,
    private val clock: Clock,
    private val synchronous: Boolean,
    private val budget: Long,
    private val ops: List<RgaOp.Insert<String>>,
    val written: List<AppendResult.Written>,
    private val zeroTailBytes: Long,
) {
    /**
     * **VERIFIED, not merely configured.** Which shape the segment before [index] ends in decides
     * which of this backend's two stop paths the replay takes to reach the continuity check, and a
     * fixture that quietly produced the other one would leave the property blind exactly the way
     * #2240 describes — so the tail is measured off the file rather than assumed from the budget.
     * [damage] names what follows, for the failure message.
     */
    fun checkTailBefore(index: Int, damage: String) {
        val frameBytes = encodedFrameBytes(format, clock, ops[index - 1])
        val tail = fileSize(segmentFiles(directory)[index - 1]) - headerBytesOf(format) - frameBytes
        check(if (zeroTailBytes == NO_PRE_ALLOCATED_TAIL) tail == 0L else tail > 0L) {
            "the segment before $damage must end the way this subclass says it does, and its " +
                "pre-allocated tail measured $tail bytes against a request for $zeroTailBytes"
        }
    }

    /** The archive as it is on disk now — the shape a restart sees, and the only one that is damaged. */
    fun reopen(): PosixMappedBolt<RgaId, String, RgaOp<String>> =
        PosixMappedBolt(format, clock, directory, synchronous, budget)
}

/** Frames behind the hole. More than one, so "stepped over it" is unmistakable rather than off-by-one. */
private const val FRAMES_BEHIND_THE_HOLE = 2

/**
 * A pre-allocated tail on every fixture segment, so the segment before the hole ends the way this
 * backend's ordinary segments end — the configuration in which `reachedRecordedExtent` is actually
 * consulted. Smaller than a frame, so only one frame lands per segment.
 */
private const val PRE_ALLOCATED_TAIL_BYTES = 32L

/** No tail: each segment is sized to the one frame that forced it, ending exactly on a frame boundary. */
private const val NO_PRE_ALLOCATED_TAIL = 0L

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
