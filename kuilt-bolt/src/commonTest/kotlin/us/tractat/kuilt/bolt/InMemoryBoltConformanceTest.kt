package us.tractat.kuilt.bolt

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.serializer
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import kotlin.test.assertIs
import kotlin.time.Clock

/** [InMemoryBolt] against the shared [BoltConformanceSuite] — the reference backend. */
class InMemoryBoltConformanceTest : BoltConformanceSuite() {
    override fun newBolt(clock: Clock): Bolt<RgaOp<String>> =
        InMemoryBolt(BoltArchiveFormat.rga(serializer<String>()), clock)

    /** Smaller than a single segment header, so the archive is full before the first append. */
    override fun newExhaustedBolt(clock: Clock): Bolt<RgaOp<String>> =
        InMemoryBolt(BoltArchiveFormat.rga(serializer<String>()), clock, capacityBytes = 8L)

    override suspend fun newTruncatedBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        truncatedInMemoryBolt(clock, intactFrames, InMemoryBolt.DEFAULT_SEGMENT_FRAME_BYTES)

    override suspend fun newDiscontinuousBolt(
        clock: Clock,
        intactFrames: Int,
        lostSegments: Int,
    ): DiscontinuousFixture = discontinuousInMemoryBolt(clock, intactFrames, lostSegments)

    override suspend fun newBackwardsJumpBolt(clock: Clock, intactFrames: Int): BackwardsJumpFixture =
        backwardsJumpInMemoryBolt(clock, intactFrames)

    override fun newBoltThatCannotFlush(clock: Clock): DurabilityFixture = inMemoryPromisedNothing(clock)
}

/**
 * The same suite against a bolt whose segment budget is small enough that almost every append rolls
 * a new segment.
 *
 * Segment rolling is where the offset space and the physical layout come apart — offsets count
 * frame bytes only, so a segment header has to be invisible to every cursor and every scope. That
 * property is only exercised by an archive with more than one segment, and the default budget is
 * 1 MiB, so without this subclass the whole multi-segment path would be dead code until the first
 * disk-backed backend.
 */
class TinySegmentInMemoryBoltConformanceTest : BoltConformanceSuite() {
    override fun newBolt(clock: Clock): Bolt<RgaOp<String>> =
        InMemoryBolt(BoltArchiveFormat.rga(serializer<String>()), clock, segmentFrameBytes = 1L)

    override fun newExhaustedBolt(clock: Clock): Bolt<RgaOp<String>> =
        InMemoryBolt(BoltArchiveFormat.rga(serializer<String>()), clock, segmentFrameBytes = 1L, capacityBytes = 8L)

    override suspend fun newTruncatedBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>> =
        truncatedInMemoryBolt(clock, intactFrames, segmentFrameBytes = 1L)

    override suspend fun newDiscontinuousBolt(
        clock: Clock,
        intactFrames: Int,
        lostSegments: Int,
    ): DiscontinuousFixture = discontinuousInMemoryBolt(clock, intactFrames, lostSegments)

    override suspend fun newBackwardsJumpBolt(clock: Clock, intactFrames: Int): BackwardsJumpFixture =
        backwardsJumpInMemoryBolt(clock, intactFrames)

    override fun newBoltThatCannotFlush(clock: Clock): DurabilityFixture = inMemoryPromisedNothing(clock)
}

/**
 * The one obligation in the suite this backend answers by **not being able to break it**.
 *
 * There is nothing to rig. [InMemoryBolt] issues no flush at all — no `msync`, no `force`, no volume
 * to refuse one — so "a bolt whose durability operation cannot succeed" is, for this backend, an
 * ordinary bolt. That is not a hole in the fixture, it is the contract: [Bolt.durability] reports
 * whether a backend is meeting the level *it* promised, and this one promised nothing, so nothing can
 * fall short of it. See `BoltConformanceSuite.newBoltThatCannotFlush` for why that had to be a
 * *declaration* rather than a nullable opt-out.
 *
 * The arm is still asserted rather than skipped: this bolt must answer
 * [DurabilityState.AsPromised] after every append, which reds an implementation that reported
 * absolute durability instead of relative — and would have every in-memory archive on every target,
 * browser included, latched at "not durable" forever.
 */
private fun inMemoryPromisedNothing(clock: Clock): DurabilityFixture =
    DurabilityFixture.PromisedNothingAndNeverFlushes(InMemoryBolt(BoltArchiveFormat.rga(serializer<String>()), clock))

/**
 * An in-memory archive of [intactFrames] ordinary frames, then a segment whose frame is **a byte
 * short**, then a **healthy** segment behind the damage.
 *
 * The intact prefix is written through [Bolt.append], so those frames are the real thing rather than
 * bytes this fixture believes are right. Only the last two segments are seeded raw, because nothing
 * a consumer can call produces a torn one — which is exactly why the conformance suite needs the
 * hook.
 *
 * **The healthy segment behind the damage is the point of the fixture, and NOT for the obvious
 * reason.** Replacing `replay`'s `return@flow` with a bare `continue` reddens the property whatever
 * the fixture looks like — the loop then falls through to the unconditional `emit(CleanTail)` and
 * the verdict is simply wrong. What the follower defends against is the *tidier* rewrite, the one a
 * future contributor would actually make: hoist the verdict out of the loop
 * (`var stoppedAt: Truncated? = null` … `emit(stoppedAt ?: CleanTail)`) and `continue` past a
 * damaged segment. That version emits exactly one correct-looking verdict and is **green against a
 * fixture whose damage is last** — measured, not assumed. With a healthy segment behind the damage
 * it reds on two assertions: a frame from beyond the damage is replayed, and `atOffset` reports the
 * *later* segment's stop rather than the first one.
 *
 * The damage is a truncated frame rather than a torn header so the *frame* stop path is what the
 * suite drives; the header stop path is covered byte-for-byte in `BoltFrameCodecTest`.
 */
private suspend fun truncatedInMemoryBolt(
    clock: Clock,
    intactFrames: Int,
    segmentFrameBytes: Long,
): Bolt<RgaOp<String>> {
    require(intactFrames >= 1) { "the fixture stops AFTER a frame, so it needs at least one" }
    val format: BoltArchiveFormat<RgaId, String, RgaOp<String>> = BoltArchiveFormat.rga(serializer<String>())
    val bolt = InMemoryBolt(format, clock, segmentFrameBytes)
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
    val damaged = segmentBytes(format, baseOffset = cursor) { write(tornFrame, endIndex = tornFrame.size - 1) }
    bolt.seedRawSegment(damaged.bytes, cursor, damaged.headerBytes)

    val behindTheDamage = cursor + tornFrame.size - 1
    val (_, healthyOp) = afterTorn.insertAt(alice, afterTorn.size, "behind-the-damage")
    val healthyFrame = encodeFrame(RawFrame(clock.now(), setOf(healthyOp.id.dot), null, listOf(format.encode(healthyOp))))
    val healthy = segmentBytes(format, baseOffset = behindTheDamage) { write(healthyFrame) }
    bolt.seedRawSegment(healthy.bytes, behindTheDamage, healthy.headerBytes)

    return bolt
}

/**
 * An in-memory archive of [intactFrames] ordinary frames, then a **stale segment restored** over the
 * one that should follow them, then two healthy frames behind the jump.
 *
 * The archive's oldest segment is copied — bytes and base offset together — over the segment just
 * past the intact prefix, which is what a backup restoring one generation of a file over another
 * leaves on a disk-backed backend. Nothing is corrupted and nothing is missing: every surviving
 * segment reads perfectly, and the archive's offset space simply runs **backwards** at one boundary.
 * `loseSegment` cannot express this, and that is the point — see
 * `InMemoryBolt.restoreStaleSegment` and `BoltConformanceSuite.newBackwardsJumpBolt`.
 *
 * The stale copy is the **oldest** segment rather than the immediately preceding one, so the jump
 * goes back past whole frames rather than to the boundary inside the last of them, and
 * [BackwardsJumpFixture]'s `check` demands exactly that. The frames behind the jump are therefore
 * duplicates of frames the replay has already emitted, which is the harm: a backend stepping over the
 * jump hands a consumer the same record twice at an offset it has already consumed.
 *
 * A one-byte segment budget puts exactly one frame in each segment regardless of which subclass asks,
 * so the frame at index `intactFrames` is the segment that gets overwritten and the frame at index 0
 * is the one the successor's header now claims to start at. The default 1 MiB budget would put the
 * whole archive in one segment, where there is no boundary to invert.
 */
private suspend fun backwardsJumpInMemoryBolt(clock: Clock, intactFrames: Int): BackwardsJumpFixture {
    require(intactFrames >= 2) { "the jump must go back past a WHOLE frame, so it needs a frame to spare" }
    val format: BoltArchiveFormat<RgaId, String, RgaOp<String>> = BoltArchiveFormat.rga(serializer<String>())
    val bolt = InMemoryBolt(format, clock, segmentFrameBytes = 1L)
    val alice = ReplicaId("alice")

    var live = Rga.empty<String>()
    val written = (0 until intactFrames + 1 + FRAMES_BEHIND_THE_HOLE).map { index ->
        val (next, op) = live.insertAt(alice, live.size, "frame-$index")
        live = next
        assertIs<AppendResult.Written>(bolt.append(listOf(op)), "every fixture frame must be written")
    }
    bolt.restoreStaleSegment(over = intactFrames, from = 0)

    return BackwardsJumpFixture(
        bolt,
        revisited = written[0],
        lastFrameBeforeTheJump = written[intactFrames - 1],
    )
}

/**
 * An in-memory archive of [intactFrames] ordinary frames, then [lostSegments] **lost segments**, then
 * two healthy frames behind the hole.
 *
 * Every frame here is written through [Bolt.append], so the archive is the real thing rather than
 * bytes this fixture believes are right — the only damage is that one whole segment is dropped out of
 * the middle afterwards, leaving the append cursor and every surviving segment untouched. That is
 * exactly what a deleted segment file is on a disk-backed backend, and it is the one archive shape
 * `seedRawSegment` deliberately cannot express: its `baseOffset == nextOffset` requirement is what
 * *checks* a fixture's idea of where its bytes land, and relaxing it would trade a real guard for
 * something [InMemoryBolt.loseSegment] gives for free.
 *
 * A one-byte segment budget puts exactly one frame in each segment regardless of which subclass asks,
 * so "the segment after the intact prefix" is just the segment at index [intactFrames] — no offset
 * arithmetic to get wrong, and one lost frame per lost segment, which is what
 * [DiscontinuousFixture]'s count is obliged to mean. The default 1 MiB budget would put the whole
 * archive in one segment, where there is no middle to lose.
 *
 * [lostSegments] consecutive segments are removed at that same index — the list closes up behind each
 * removal, so the one after the prefix is always the next to go — leaving **one** wider hole rather
 * than several narrow ones. Which is the distinction [DiscontinuousFixture]'s contiguity `check`
 * enforces from the other side.
 *
 * The suite's obligation asks a fixture to end its pre-hole segment the way this backend's ordinary
 * segments end, **pre-allocated tail included**. This backend has no such tail to reproduce: nothing
 * is pre-allocated, a segment's array holds exactly the bytes written into it, and `snapshot.used`
 * bounds every parse. So the zero-tail configuration the mmap fixtures both drive does not exist
 * here, and one budget covers this backend completely.
 *
 * Both edges of the hole are handed back as the **appends themselves** rather than as offsets this
 * fixture computed: the destroyed frames are the run at `[intactFrames, intactFrames + lostSegments)`
 * and the first survivor is the one after it. Losing a segment moves nothing — the offsets of the
 * frames behind it are untouched, which is precisely what makes the archive discontinuous rather than
 * short — so those appends' own reported offsets are still where those frames sit, and where a
 * consumer that had already read past them resumes from.
 */
private suspend fun discontinuousInMemoryBolt(
    clock: Clock,
    intactFrames: Int,
    lostSegments: Int,
): DiscontinuousFixture {
    require(intactFrames >= 1) { "the fixture stops AFTER a frame, so it needs at least one" }
    require(lostSegments >= 1) { "an archive that lost nothing has no hole in it" }
    val format: BoltArchiveFormat<RgaId, String, RgaOp<String>> = BoltArchiveFormat.rga(serializer<String>())
    val bolt = InMemoryBolt(format, clock, segmentFrameBytes = 1L)
    val alice = ReplicaId("alice")

    var live = Rga.empty<String>()
    val written = (0 until intactFrames + lostSegments + FRAMES_BEHIND_THE_HOLE).map { index ->
        val (next, op) = live.insertAt(alice, live.size, "frame-$index")
        live = next
        assertIs<AppendResult.Written>(bolt.append(listOf(op)), "every fixture frame must be written")
    }
    repeat(lostSegments) { bolt.loseSegment(intactFrames) }

    return DiscontinuousFixture(
        bolt,
        lostFrames = written.subList(intactFrames, intactFrames + lostSegments),
        firstSurvivor = written[intactFrames + lostSegments],
    )
}

/** Frames behind the hole. More than one, so "stepped over it" is unmistakable rather than off-by-one. */
private const val FRAMES_BEHIND_THE_HOLE = 2

/** A segment's bytes: a whole header for [format] at [baseOffset], then whatever [frames] writes. */
private fun segmentBytes(
    format: BoltArchiveFormat<RgaId, String, RgaOp<String>>,
    baseOffset: Long,
    frames: Buffer.() -> Unit,
): RawSegment {
    val header = encodeSegmentHeader(
        SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset),
    )
    val bytes = Buffer().apply {
        write(header)
        frames()
    }
    return RawSegment(bytes.readByteArray(), header.size)
}

private class RawSegment(val bytes: ByteArray, val headerBytes: Int)
