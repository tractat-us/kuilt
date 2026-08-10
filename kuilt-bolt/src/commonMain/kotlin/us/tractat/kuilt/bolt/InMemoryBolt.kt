package us.tractat.kuilt.bolt

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.io.Buffer
import us.tractat.kuilt.crdt.Dot
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A [Bolt] whose segments live in memory — the reference implementation, and the one every target
 * has, including the browser.
 *
 * It writes the **same bytes** a disk-backed backend writes: the same segment header, the same
 * length-prefixed, CRC-checked frames, the same canonical CBOR ops. That is deliberate rather than
 * incidental. A reference implementation that kept ops as objects would leave the archive format
 * unexercised until the first mmap backend existed, which is the worst possible moment to discover
 * a field is missing from a format whose entire premise is that it is expensive to change.
 *
 * **Bounded, and honest about it.** [capacityBytes] caps the whole archive; past it, [append]
 * returns [AppendResult.Failed] carrying the dots it could not keep, and [availability] flips to
 * [BoltAvailability.Unavailable]. An in-memory archive that grew without limit would be a
 * memory leak wearing an archive's clothes.
 *
 * @param format how ops are classified and encoded — see [BoltArchiveFormat].
 * @param clock stamps each frame's arrival time. **Required**: time is a dependency here, and a
 *   bolt that reached for `Clock.System` itself could not be tested deterministically.
 * @param segmentFrameBytes the frame-byte budget after which a new segment is started. A single
 *   frame larger than this still gets archived — it lands alone in its own segment rather than
 *   being refused.
 * @param capacityBytes the total byte budget for the whole archive, headers included.
 */
public class InMemoryBolt<Id : Any, V, Op : Any>(
    private val format: BoltArchiveFormat<Id, V, Op>,
    private val clock: Clock,
    private val segmentFrameBytes: Long = DEFAULT_SEGMENT_FRAME_BYTES,
    private val capacityBytes: Long = Long.MAX_VALUE,
) : Bolt<Op> {

    init {
        require(segmentFrameBytes > 0) { "segmentFrameBytes must be positive, was $segmentFrameBytes" }
        require(capacityBytes > 0) { "capacityBytes must be positive, was $capacityBytes" }
    }

    /**
     * Guards every field below.
     *
     * An explicit lock rather than dispatcher confinement: this type must be correct under a
     * multi-threaded dispatcher, and nothing inside the locked section suspends.
     */
    private val lock = reentrantLock()

    private val segments = mutableListOf<Segment>()

    /** The next frame's append offset. Counts frame bytes only — segment headers are not in it. */
    private var nextOffset: Long = 0L

    /** Total bytes held, headers included — what [capacityBytes] bounds. */
    private var usedBytes: Long = 0L

    /**
     * A segment header's size for this format. Fixed: every field is fixed-width except the two
     * self-description strings, which are this format's own and never vary per segment.
     */
    private val headerBytes: Long = encodeSegmentHeader(
        SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset = 0L),
    ).size.toLong()

    /**
     * A strict lower bound on any frame's size: the framing of a frame carrying nothing.
     *
     * Such a frame is never actually written — an append with no content is [AppendResult.Skipped] —
     * but it is what makes [availability] answerable. "Is this archive full?" is otherwise a
     * question about an append that has not happened yet.
     */
    private val minimumFrameBytes: Long =
        encodeFrame(RawFrame(Instant.fromEpochMilliseconds(0L), emptySet(), null, emptyList())).size.toLong()

    /**
     * [BoltAvailability.Available] exactly while the archive has room for the **smallest possible**
     * frame, plus the segment header one would need if the active segment is full.
     *
     * Not "usedBytes < capacityBytes", which was wrong in the direction that matters: an archive
     * with four bytes free reported itself writable while every append failed, and a bolt whose
     * `Available` does not imply "an append will be accepted" is the one thing this signal exists to
     * rule out. A single append **larger** than the remaining room still fails — and reports the
     * dots it lost — because no per-append size is knowable before the append arrives.
     */
    override fun availability(): BoltAvailability = lock.withLock {
        val free = capacityBytes - usedBytes
        if (free < minimumFrameBytes + headerIfSegmentRollNeeded(minimumFrameBytes)) {
            BoltAvailability.Unavailable("in-memory archive is full ($usedBytes/$capacityBytes bytes)")
        } else {
            BoltAvailability.Available
        }
    }

    /** [headerBytes] if a frame of [frameBytes] would have to start a new segment, else `0`. */
    private fun headerIfSegmentRollNeeded(frameBytes: Long): Long {
        val active = segments.lastOrNull()
        return if (active != null && active.frameBytes + frameBytes <= segmentFrameBytes) 0L else headerBytes
    }

    override suspend fun append(ops: List<Op>): AppendResult {
        // Classification and op encoding are pure and expensive; they happen OUTSIDE the lock.
        val content = format.contentOnly(ops)
        if (content.isEmpty()) return AppendResult.Skipped
        val insertDots = format.insertDotsOf(content)
        val encodedOps = content.map(format::encode)

        return lock.withLock { writeFrame(encodedOps, insertDots, content.size) }
    }

    /**
     * Assemble and append one frame. Called under [lock].
     *
     * The clock is read here rather than before acquiring, so two racing appends cannot stamp their
     * frames in the opposite order to the offsets they take. (The clock itself may still step
     * backwards — an NTP correction is nobody's invariant to hold — which is why
     * [ReplayScope.Arrived] filters frames rather than binary-searching them.)
     */
    private fun writeFrame(encodedOps: List<ByteArray>, insertDots: Set<Dot>, opCount: Int): AppendResult {
        val frame = encodeFrame(RawFrame(clock.now(), insertDots, key = null, ops = encodedOps))
        val offset = nextOffset
        // A new segment is needed when there is no active one, or the active one has already taken
        // its budget in frames. A frame bigger than the budget lands ALONE in a fresh segment
        // rather than being refused — the budget bounds accumulation, it is not a record-size cap.
        // The roll decision goes through the same helper `availability` reads, so the two can never
        // disagree about whether the next write needs a header.
        val rollCost = headerIfSegmentRollNeeded(frame.size.toLong())
        // Capacity is checked BEFORE anything is committed, so a refused append leaves no
        // half-rolled empty segment behind.
        val needed = frame.size + rollCost
        return if (usedBytes + needed > capacityBytes) {
            AppendResult.Failed(
                reason = "in-memory archive is full: $needed more bytes needed, " +
                    "${capacityBytes - usedBytes} of $capacityBytes free",
                insertDots = insertDots,
                offset = offset,
                endOffset = null,
            )
        } else {
            val segment = if (rollCost == 0L) segments.last() else rollSegment(offset)
            segment.write(frame)
            usedBytes += needed
            nextOffset += frame.size
            AppendResult.Written(offset, nextOffset, opCount, insertDots)
        }
    }

    /**
     * Append a segment built from raw [bytes] — [headerBytes] of header followed by whatever frame
     * bytes there are, intact or not — whose first frame sits at the current append cursor.
     *
     * **Only a damaged archive needs this, and only a test wants one.** Every path a consumer can
     * reach writes whole frames after a whole header, so [Truncated] is unreachable through the
     * public API of this backend — and an unreachable verdict is one nothing asserts, which is how a
     * "stop the whole replay" decision quietly becomes a "skip to the next segment" one. The hook
     * exists so the conformance suite can drive the other branch, the same way `capacityBytes` lets
     * it drive an exhausted one.
     *
     * The header inside [bytes] is the caller's to encode: it is being asked for archives whose
     * bytes are *wrong*, so it cannot be handed a header this class believes in. [baseOffset] is
     * passed in rather than taken silently so the caller's idea of where its bytes land is
     * *checked* — a fixture that quietly disagreed with the cursor would build an archive damaged in
     * a way it did not intend, and pass for the wrong reason.
     */
    internal fun seedRawSegment(bytes: ByteArray, baseOffset: Long, headerBytes: Int): Unit = lock.withLock {
        require(headerBytes in 0..bytes.size) { "headerBytes $headerBytes is not within ${bytes.size} bytes" }
        require(baseOffset == nextOffset) { "a segment seeded at $baseOffset would not follow the cursor $nextOffset" }
        segments += Segment(baseOffset, bytes, headerBytes)
        usedBytes += bytes.size
        nextOffset += bytes.size - headerBytes
    }

    /** Start a new segment whose first frame sits at [offset]. Called under [lock]. */
    private fun rollSegment(offset: Long): Segment {
        val header = encodeSegmentHeader(
            SegmentHeader(
                formatVersion = BOLT_FORMAT_VERSION,
                opFormat = format.opFormat,
                elementType = format.elementType,
                baseOffset = offset,
            ),
        )
        return Segment(offset, header, header.size).also { segments += it }
    }

    override fun replay(scope: ReplayScope): Flow<ReplayEvent<Op>> = flow {
        // Snapshot under the lock, decode outside it: an append that lands mid-replay either writes
        // past the captured `used` index of the same array, or reallocates and leaves the captured
        // array untouched. Either way the bytes this reader reads are already published to it by
        // the lock's happens-before edge, and are never rewritten.
        val snapshots = lock.withLock { segments.map(Segment::snapshot) }
        for (snapshot in snapshots) {
            if (skippable(snapshot, scope)) continue
            // A segment that stops early stops the WHOLE replay. An append-only log is ordered, so
            // a frame that does not validate makes everything behind it untrustworthy; carrying on
            // to the next segment would hand back a history with a silent hole and offsets that
            // jump, which is worse than a short answer that says it is short.
            val stopped = emitFrames(snapshot, scope)
            if (stopped != null) {
                emit(stopped)
                return@flow
            }
        }
        emit(CleanTail)
    }

    /** True if [scope] cannot possibly select a frame in [snapshot] — segment-granularity pruning. */
    private fun skippable(snapshot: SegmentSnapshot, scope: ReplayScope): Boolean =
        scope is ReplayScope.FromOffset && snapshot.baseOffset + snapshot.frameBytes <= scope.offset

    /** Emit [snapshot]'s in-scope frames; the [Truncated] verdict if it stopped early, else `null`. */
    private suspend fun FlowCollector<ReplayEvent<Op>>.emitFrames(
        snapshot: SegmentSnapshot,
        scope: ReplayScope,
    ): Truncated? {
        val buffer = Buffer().apply { write(snapshot.bytes, 0, snapshot.used) }
        // The header is the AUTHORITY on where this segment's frames start, not the in-memory
        // bookkeeping: for a file-backed backend the bytes on disk are all there is. `check`, not a
        // Truncated, because DAMAGE to that field can no longer reach here — the header's CRC
        // trailer rejects it as torn first — so a disagreement at this point is a bookkeeping bug in
        // this class, which is exactly what an assertion is for.
        val header = readSegmentHeader(buffer, format.opFormat, format.elementType)
            ?: return Truncated(snapshot.baseOffset, TruncationReason.SegmentHeader)
        check(header.baseOffset == snapshot.baseOffset) {
            "segment header says its frames start at ${header.baseOffset}, bookkeeping says ${snapshot.baseOffset}"
        }
        var offset = header.baseOffset
        while (buffer.size > 0) {
            val before = buffer.size
            val raw = readFrame(buffer, header.formatVersion)
                ?: return Truncated(offset, TruncationReason.Frame)
            val endOffset = offset + (before - buffer.size)
            val archived = Archived(
                offset = offset,
                endOffset = endOffset,
                arrivedAt = raw.arrivedAt,
                insertDots = raw.insertDots,
                key = raw.key,
                ops = raw.ops.map(format::decode),
            )
            offset = endOffset
            if (scope.selects(archived)) emit(archived)
        }
        return null
    }

    private fun ReplayScope.selects(frame: Archived<Op>): Boolean = when (this) {
        ReplayScope.All -> true
        is ReplayScope.FromOffset -> frame.endOffset > offset
        is ReplayScope.Arrived -> frame.arrivedAt >= from && frame.arrivedAt < untilExclusive
        is ReplayScope.InsertsAbove -> frame.insertDots.any { it.seq > floor[it.replica] }
    }

    /**
     * One segment: its header bytes, then its frames, in one growing array.
     *
     * [initial] is the segment's opening content and [headerBytes] how much of it is header. For an
     * ordinary roll those are the same thing; they are separate parameters only so a damaged archive
     * can be seeded, header plus torn remainder, in one go.
     */
    private class Segment(val baseOffset: Long, initial: ByteArray, private val headerBytes: Int) {
        private var bytes: ByteArray = initial.copyOf(newSize = maxOf(initial.size, INITIAL_SEGMENT_CAPACITY))
        private var used: Int = initial.size

        /** How many FRAME bytes this segment holds — the header does not count. */
        val frameBytes: Long get() = (used - headerBytes).toLong()

        fun write(frame: ByteArray) {
            if (used + frame.size > bytes.size) {
                var grown = maxOf(bytes.size * 2, used + frame.size)
                if (grown < 0) grown = used + frame.size // overflow guard on a pathological archive
                bytes = bytes.copyOf(grown)
            }
            frame.copyInto(bytes, destinationOffset = used)
            used += frame.size
        }

        fun snapshot(): SegmentSnapshot = SegmentSnapshot(baseOffset, bytes, used, frameBytes)
    }

    private class SegmentSnapshot(
        val baseOffset: Long,
        val bytes: ByteArray,
        val used: Int,
        val frameBytes: Long,
    )

    public companion object {
        /** 1 MiB of frames per segment — small enough to bound a re-map, large enough to be rare. */
        public const val DEFAULT_SEGMENT_FRAME_BYTES: Long = 1L shl 20

        private const val INITIAL_SEGMENT_CAPACITY: Int = 1024
    }
}
