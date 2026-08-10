package us.tractat.kuilt.bolt

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.io.Buffer
import us.tractat.kuilt.crdt.Dot
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.time.Clock
import kotlin.time.Instant
import java.nio.Buffer as NioBuffer

/**
 * A [Bolt] whose segments are files under [directory], written through `FileChannel.map()`.
 *
 * The server case. A phone keeps an hour; the machine running this keeps as long as its disk lasts,
 * and neither one changes the other's mind. Writes land in a memory-mapped window, so an append is
 * a `memcpy` into the page cache rather than a syscall per record — which is what mmap is good at
 * and the reason an append-only archive is a good fit for it.
 *
 * It writes the **same bytes** [InMemoryBolt] writes: the same segment header, the same
 * length-prefixed CRC-checked frames, the same canonical CBOR ops. An archive written here is
 * readable by any backend on any target.
 *
 * ### Disk-full under mmap is a SIGNAL, not an exception — and that is why segments are pre-filled
 *
 * This is the hazard that would otherwise make the module's whole failure posture *unachievable*
 * rather than merely wrong. Growing a mapped file — or `setLength`/`ftruncate`-ing one to size,
 * which allocates **sparsely** — defers physical block allocation to the first touch of each page.
 * On a full volume that touch is a **SIGBUS** on POSIX and an unspecified VM error on the JVM.
 * Neither is catchable, so the process dies and takes the application's logging with it: precisely
 * the outcome [AppendResult.Failed] exists to prevent. External truncation of a mapped file is the
 * same class of hazard, and gets the same answer.
 *
 * So a segment is **eagerly, physically pre-allocated at roll time with real writes** — see
 * [preallocate], which loops over `FileChannel.write` and never calls `setLength`. Exhaustion then
 * surfaces as a catchable [IOException] at a **segment boundary**, once per segment rather than once
 * per record, and [append] turns it into a [AppendResult.Failed] carrying the dots it could not
 * keep. The cost is one segment-sized write per roll; that is the price of the guarantee.
 *
 * ### The pre-allocation is exactly what makes the frame format's two zero-run defences load-bearing
 *
 * Pre-filling with real writes leaves a **zero-filled region** at the end of every segment, and
 * `crc32(ByteArray(0)) == 0` — so a naive framing would read that region as an endless run of valid
 * *empty* frames. Two independent barriers in the shipped codec stop it, and **neither is redundant
 * with the other**: the frame CRC covers the length prefix **as well as** the body (so a zero run
 * checksums to `0x2144DF1C`, which no zero field can equal), and [MINIMUM_BODY_BYTES] rejects a body
 * shorter than its own fixed fields. Removing either one *alone* leaves the archive correct, which
 * is exactly how a future reader talks themself into deleting one. Removing **both** turns every
 * pre-allocated tail into phantom frames. Do not simplify either; `MappedBoltTest` pins the
 * compound case.
 *
 * A zero remainder is therefore also how this backend tells "the segment ended here" from "the
 * segment is damaged here": a frame that will not parse ends the segment **cleanly** when every byte
 * from it to the end of the segment is zero, and is [Truncated] when it is not. That is stronger
 * than inspecting the length prefix alone — bit-rot that zeroed one length word would otherwise
 * silently shorten the archive to a [CleanTail].
 *
 * ### Durability is `force()`, and synchronous versus asynchronous is one flag
 *
 * Bytes written into a mapped buffer are not durable until `msync`. [forceOnAppend] `= true` calls
 * it before [append] returns — the synchronous backend. `false` leaves the flush to the OS — the
 * asynchronous one. One type and one flag rather than two implementations, because two
 * implementations are two things to keep in agreement.
 *
 * ### Unmapping is not portable, so nothing here depends on it
 *
 * There is no `close()`. The `FileChannel` is closed as soon as the mapping exists (a mapping
 * outlives the channel that made it, by contract), so this type holds **no file handles at all** —
 * and a mapped region is released only when its buffer is collected, which no portable API can
 * force. A `close()` that promised prompt unmapping would be a promise the JVM does not keep.
 *
 * ### Blocking, and single-writer
 *
 * [append] does its file I/O on the calling thread — it is `suspend` for the contract's sake, not
 * because it yields. Confine a bolt to an I/O dispatcher if the caller's thread must not block.
 * One directory belongs to one bolt: there is no cross-process file lock, and two bolts over one
 * directory will interleave segment indices and corrupt each other.
 *
 * @param directory holds this archive's segment files. Created if absent.
 * @param format how ops are classified and encoded — see [BoltArchiveFormat].
 * @param clock stamps each frame's arrival time. **Required**: time is a dependency here.
 * @param forceOnAppend `true` to `msync` before [append] returns.
 * @param segmentFrameBytes the frame-byte budget per segment. A single frame larger than this still
 *   gets archived — it lands alone in a segment sized to fit it, rather than being refused.
 * @param capacityBytes the total budget for the whole archive, in **physically allocated** bytes:
 *   the sum of the segment files' sizes, pre-allocated padding included. Not free disk space, which
 *   no process can know without racing every other writer on the volume — see [availability].
 */
public class MappedBolt<Id : Any, V, Op : Any>(
    private val directory: File,
    private val format: BoltArchiveFormat<Id, V, Op>,
    private val clock: Clock,
    private val forceOnAppend: Boolean = true,
    private val segmentFrameBytes: Long = InMemoryBolt.DEFAULT_SEGMENT_FRAME_BYTES,
    private val capacityBytes: Long = Long.MAX_VALUE,
) : Bolt<Op> {

    /**
     * Guards every field below.
     *
     * An explicit lock rather than dispatcher confinement: this type must be correct under a
     * multi-threaded dispatcher, and nothing inside the locked section suspends.
     */
    private val lock = reentrantLock()

    /**
     * A segment header's size for this format. Fixed: every field is fixed-width except the two
     * self-description strings, which are this format's own and never vary per segment.
     */
    private val headerBytes: Int = encodeSegmentHeader(
        SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset = 0L),
    ).size

    /** A strict lower bound on any frame's size — what makes [availability] answerable. */
    private val minimumFrameBytes: Int =
        encodeFrame(RawFrame(Instant.fromEpochMilliseconds(0L), emptySet(), null, emptyList())).size

    /** This archive's segment files, oldest first. */
    private val segments = mutableListOf<File>()

    /** The segment currently taking appends, or `null` before the first one is rolled. */
    private var active: Segment? = null

    /** The next frame's append offset. Counts frame bytes only — segment headers are not in it. */
    private var nextOffset: Long = 0L

    /** Physically allocated bytes across every segment file — what [capacityBytes] bounds. */
    private var usedBytes: Long = 0L

    private var nextIndex: Int = 0

    /** Why this archive cannot take appends, if it cannot. Set only by [recover]. */
    private var unappendable: String? = null

    init {
        require(segmentFrameBytes > 0) { "segmentFrameBytes must be positive, was $segmentFrameBytes" }
        require(capacityBytes > 0) { "capacityBytes must be positive, was $capacityBytes" }
        lock.withLock { recover() }
    }

    /**
     * [BoltAvailability.Available] exactly while there is room for the **smallest possible** frame:
     * either in the active segment, or in a new one this archive's byte budget still allows.
     *
     * **Stated limit.** Free space on the volume is not part of this answer, and cannot be: any
     * number read now is stale by the time an append uses it, and every other writer on the disk is
     * racing you for it. So a genuinely full disk shows up as [AppendResult.Failed] from [append]
     * while this said [BoltAvailability.Available] — that is the *achievable* guarantee, and the
     * eager pre-allocation above is what makes it a `Failed` rather than a dead process.
     */
    override fun availability(): BoltAvailability = lock.withLock {
        val damaged = unappendable
        when {
            damaged != null -> BoltAvailability.Unavailable(damaged)
            !directory.isDirectory && !directory.mkdirs() ->
                BoltAvailability.Unavailable("archive directory $directory does not exist and cannot be created")
            !directory.canWrite() -> BoltAvailability.Unavailable("archive directory $directory is not writable")
            !hasRoomFor(minimumFrameBytes) ->
                BoltAvailability.Unavailable("mapped archive is full ($usedBytes/$capacityBytes bytes allocated)")
            else -> BoltAvailability.Available
        }
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
     * frames in the opposite order to the offsets they take.
     */
    private fun writeFrame(encodedOps: List<ByteArray>, insertDots: Set<Dot>, opCount: Int): AppendResult {
        val frame = encodeFrame(RawFrame(clock.now(), insertDots, key = null, ops = encodedOps))
        val offset = nextOffset
        return when (val outcome = segmentFor(frame.size)) {
            is SegmentOutcome.Refused ->
                AppendResult.Failed(outcome.reason, insertDots, offset, endOffset = null, cause = outcome.cause)

            is SegmentOutcome.Ready -> {
                outcome.segment.write(frame)
                if (forceOnAppend) outcome.segment.force()
                nextOffset += frame.size
                AppendResult.Written(offset, nextOffset, opCount, insertDots)
            }
        }
    }

    /**
     * The segment a [frameBytes]-byte frame will land in — the active one if it still has room,
     * otherwise a freshly rolled one — or the reason none could be had. Called under [lock].
     */
    private fun segmentFor(frameBytes: Int): SegmentOutcome {
        unappendable?.let { return SegmentOutcome.Refused(it, cause = null) }
        active?.takeIf { it.hasRoomFor(frameBytes) }?.let { return SegmentOutcome.Ready(it) }
        // A frame bigger than the budget lands ALONE in a segment sized to fit it rather than being
        // refused: the budget bounds accumulation, it is not a record-size cap.
        val sizeBytes = headerBytes + maxOf(segmentFrameBytes, frameBytes.toLong())
        if (sizeBytes > Int.MAX_VALUE) {
            return SegmentOutcome.Refused(
                "a $frameBytes-byte frame needs a $sizeBytes-byte segment, larger than one mapping can be",
                cause = null,
            )
        }
        if (usedBytes + sizeBytes > capacityBytes) {
            return SegmentOutcome.Refused(
                "mapped archive is full: $sizeBytes more bytes needed, " +
                    "${capacityBytes - usedBytes} of $capacityBytes free",
                cause = null,
            )
        }
        return try {
            SegmentOutcome.Ready(roll(nextOffset, sizeBytes))
        } catch (failure: IOException) {
            // The catchable failure the eager pre-allocation exists to produce: a full or read-only
            // volume surfaces HERE, as a refused append, rather than as a SIGBUS on a later page.
            SegmentOutcome.Refused("could not pre-allocate a segment under $directory: $failure", failure)
        }
    }

    /** Whether a [frameBytes]-byte frame has somewhere to go. Called under [lock]. */
    private fun hasRoomFor(frameBytes: Int): Boolean =
        active?.hasRoomFor(frameBytes) == true ||
            usedBytes + headerBytes + maxOf(segmentFrameBytes, frameBytes.toLong()) <= capacityBytes

    /**
     * Pre-allocate a new segment of [sizeBytes] whose first frame sits at [offset], write its
     * header, and make it active. Called under [lock].
     */
    private fun roll(offset: Long, sizeBytes: Long): Segment {
        // A retired segment is read back from its FILE, so flush it before letting go of its
        // mapping — this is the only point at which that is guaranteed to have happened.
        active?.force()
        val file = File(directory, segmentName(nextIndex))
        val segment = Segment(file, allocate(file, sizeBytes), sizeBytes.toInt(), writePosition = 0)
        segment.write(
            encodeSegmentHeader(
                SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset = offset),
            ),
        )
        if (forceOnAppend) segment.force()
        segments += file
        active = segment
        nextIndex++
        usedBytes += sizeBytes
        return segment
    }

    /**
     * Create [file] at exactly [sizeBytes], **physically**, and map it read-write.
     *
     * The channel is closed before this returns: a mapping does not depend on the channel that
     * created it, so the archive holds no file handles.
     */
    private fun allocate(file: File, sizeBytes: Long): MappedByteBuffer =
        try {
            RandomAccessFile(file, "rw").use { handle ->
                preallocate(handle.channel, sizeBytes)
                handle.channel.force(true)
                handle.channel.map(FileChannel.MapMode.READ_WRITE, 0L, sizeBytes)
            }
        } catch (failure: IOException) {
            // A half-allocated segment is not an archive of anything — leave no ambiguity behind for
            // the next open to have to classify.
            file.delete()
            throw failure
        }

    /**
     * Write [sizeBytes] of real zeroes through [channel].
     *
     * **A real write, deliberately, and never `setLength`.** `setLength` (`ftruncate`) produces a
     * *sparse* file whose blocks are allocated on first page-touch — which under a mapping is a
     * SIGBUS on a full volume rather than an exception. Writing the bytes forces the filesystem to
     * commit the blocks now, where the failure is an ordinary [IOException].
     */
    private fun preallocate(channel: FileChannel, sizeBytes: Long) {
        val chunk = ByteBuffer.allocate(PREALLOCATE_CHUNK_BYTES)
        val window: NioBuffer = chunk
        var written = 0L
        while (written < sizeBytes) {
            window.clear()
            window.limit(minOf(PREALLOCATE_CHUNK_BYTES.toLong(), sizeBytes - written).toInt())
            while (chunk.hasRemaining()) {
                val advanced = channel.write(chunk)
                if (advanced <= 0) {
                    throw IOException("segment pre-allocation stalled at $written of $sizeBytes bytes")
                }
                written += advanced
            }
        }
    }

    // ── opening an existing archive ───────────────────────────────────────────

    /**
     * Rebuild this archive's cursor from the bytes on disk. Called under [lock], from `init`.
     *
     * Only the **newest** segment is scanned. Every earlier one's frames are already accounted for
     * by the newest header's `baseOffset`, which is absolute — that is what the offset space being
     * frame-bytes-only buys, and it keeps opening a large archive O(one segment) rather than O(all).
     *
     * Throws [BoltFormatException] if the newest segment is an archive of a different op or element
     * type, or of a format version this build cannot read. That is a reader mistake — the wrong
     * directory — and reporting it as an empty archive would be worse than failing to open.
     */
    private fun recover() {
        directory.mkdirs()
        val found = directory.listFiles()
            ?.filter { it.isFile && SEGMENT_NAME.matches(it.name) }
            ?.sortedBy { it.name }
            .orEmpty()
        // A trailing all-zero segment is a crash between pre-allocating a segment and writing its
        // header. It can hold no frame — frames are only ever written after the header — so deleting
        // it returns the space and restores a clean append point, losing nothing readable.
        val kept = found.toMutableList()
        while (kept.isNotEmpty() && isEntirelyZero(kept.last())) {
            kept.removeAt(kept.lastIndex).delete()
        }
        segments += kept
        usedBytes = kept.sumOf { it.length() }
        nextIndex = kept.lastOrNull()?.let { segmentIndexOf(it) + 1 } ?: 0
        kept.lastOrNull()?.let { reopen(it) }
    }

    /** Make [file] the active segment, with its cursor after its last intact frame. Under [lock]. */
    private fun reopen(file: File) {
        val length = file.length()
        if (length > Int.MAX_VALUE) {
            unappendable = "the newest segment ${file.name} is $length bytes, larger than one mapping can be"
            return
        }
        val sizeBytes = length.toInt()
        val mapped = RandomAccessFile(file, "rw").use { handle ->
            handle.channel.map(FileChannel.MapMode.READ_WRITE, 0L, length)
        }
        val segment = Segment(file, mapped, sizeBytes, writePosition = sizeBytes)
        val bytes = segment.snapshot()
        val buffer = Buffer().apply { write(bytes) }
        val header = readSegmentHeader(buffer, format.opFormat, format.elementType)
        if (header == null) {
            unappendable = "the newest segment ${file.name} has a damaged header, so no append can be " +
                "placed after it; replay still reads every intact frame ahead of it"
            return
        }
        var frameBytes = 0L
        while (true) {
            val before = buffer.size
            readFrame(buffer, header.formatVersion) ?: break
            frameBytes += before - buffer.size
        }
        nextOffset = header.baseOffset + frameBytes
        val writePosition = (sizeBytes - buffer.size).toInt()
        // A crash mid-append leaves a partial frame here. It was never acknowledged by `append`, so
        // no caller believes it landed — and leaving it would wedge the archive: replay stops at the
        // first frame that will not parse, so every later append would be permanently unreachable.
        // Zeroing it restores the "clean segment tail" the pre-allocation invariant assumes.
        if (!bytes.isZeroFrom(writePosition)) {
            segment.zeroFrom(writePosition)
            segment.force()
        }
        segment.rewindTo(writePosition)
        active = segment
    }

    // ── replay ────────────────────────────────────────────────────────────────

    override fun replay(scope: ReplayScope): Flow<ReplayEvent<Op>> = flow {
        // Snapshot under the lock, decode outside it. The active segment is copied out of its
        // mapping while the lock is held, so a concurrent append can never be observed half-written;
        // every earlier segment is immutable and was forced before its mapping was let go.
        val reads = lock.withLock { snapshot() }
        var resumeOffset = 0L
        for (read in reads) {
            // A segment that stops early stops the WHOLE replay. An append-only log is ordered, so a
            // frame that does not validate makes everything behind it untrustworthy; carrying on to
            // the next segment would hand back a history with a silent hole and offsets that jump.
            val outcome = emitSegment(read, resumeOffset, scope)
            outcome.stopped?.let {
                emit(it)
                return@flow
            }
            resumeOffset = outcome.endOffset
        }
        emit(CleanTail)
    }

    /** One entry per segment: the active one's bytes are copied under the lock, the rest are read. */
    private fun snapshot(): List<SegmentRead> {
        val current = active
        return segments.map { file ->
            SegmentRead(file, if (file == current?.file) current.snapshot() else null)
        }
    }

    /** Emit [read]'s in-scope frames, and say how the segment ended. */
    private suspend fun FlowCollector<ReplayEvent<Op>>.emitSegment(
        read: SegmentRead,
        resumeOffset: Long,
        scope: ReplayScope,
    ): SegmentReplay {
        val bytes = try {
            read.bytes()
        } catch (_: IOException) {
            // A segment we cannot read at all is indistinguishable, to a consumer, from one whose
            // header will not read — and throwing would discard every intact frame ahead of it.
            return SegmentReplay(Truncated(resumeOffset, TruncationReason.SegmentHeader), resumeOffset)
        }
        val buffer = Buffer().apply { write(bytes) }
        val header = readSegmentHeader(buffer, format.opFormat, format.elementType)
            ?: return SegmentReplay(endedAt(bytes, consumed = 0, offset = resumeOffset), resumeOffset)
        var offset = header.baseOffset
        while (buffer.size > 0) {
            val before = buffer.size
            val raw = readFrame(buffer, header.formatVersion)
                ?: return SegmentReplay(endedAt(bytes, (bytes.size - buffer.size).toInt(), offset), offset)
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
        return SegmentReplay(stopped = null, endOffset = offset)
    }

    /**
     * The verdict for a segment whose parse stopped at [consumed]: `null` — a clean end — when every
     * byte from there on is zero, and a [Truncated] at [offset] when they are not.
     *
     * The zero remainder is the pre-allocated region a segment is born with, so reaching it is the
     * ordinary way a segment ends. Anything else there is a frame that started and did not finish,
     * or bytes that rotted.
     */
    private fun endedAt(bytes: ByteArray, consumed: Int, offset: Long): Truncated? = when {
        bytes.isZeroFrom(consumed) -> null
        consumed == 0 -> Truncated(offset, TruncationReason.SegmentHeader)
        else -> Truncated(offset, TruncationReason.Frame)
    }

    private fun ReplayScope.selects(frame: Archived<Op>): Boolean = when (this) {
        ReplayScope.All -> true
        is ReplayScope.FromOffset -> frame.endOffset > offset
        is ReplayScope.Arrived -> frame.arrivedAt >= from && frame.arrivedAt < untilExclusive
        is ReplayScope.InsertsAbove -> frame.insertDots.any { it.seq > floor[it.replica] }
    }

    // ── segment files ─────────────────────────────────────────────────────────

    /**
     * One segment's mapped window, and how much of it has been written.
     *
     * Three typed views of one buffer, and the typing is load-bearing rather than stylistic: `Java 9`
     * gave `ByteBuffer`/`MappedByteBuffer` covariant overrides of `position`, `clear` and
     * `duplicate`, so a call bound to the subtype compiles to a signature that does not exist on
     * older Android runtimes (`minSdk` here is 24) and fails with `NoSuchMethodError` at runtime, not
     * at build time. Binding `position` to [NioBuffer] and `put`/`get` to [ByteBuffer] picks the
     * signatures that have been there since 1.4.
     */
    private class Segment(
        val file: File,
        private val mapped: MappedByteBuffer,
        private val sizeBytes: Int,
        writePosition: Int,
    ) {
        private val cursor: NioBuffer = mapped
        private val bytes: ByteBuffer = mapped

        var writePosition: Int = writePosition
            private set

        fun hasRoomFor(frameBytes: Int): Boolean = writePosition + frameBytes <= sizeBytes

        fun write(frame: ByteArray) {
            cursor.position(writePosition)
            bytes.put(frame)
            writePosition += frame.size
        }

        /** A copy of the written prefix — a consistent read of a buffer another thread appends to. */
        fun snapshot(): ByteArray {
            val copy = ByteArray(writePosition)
            cursor.position(0)
            bytes.get(copy)
            return copy
        }

        fun zeroFrom(index: Int) {
            cursor.position(index)
            bytes.put(ByteArray(sizeBytes - index))
        }

        fun rewindTo(position: Int) {
            writePosition = position
        }

        fun force() {
            mapped.force()
        }
    }

    /** A segment to replay: the active one's bytes in hand, or a file to read them from. */
    private class SegmentRead(val file: File, private val inMemory: ByteArray?) {
        fun bytes(): ByteArray = inMemory ?: file.readBytes()
    }

    /** How one segment's replay ended: a verdict, or the offset the next segment resumes at. */
    private class SegmentReplay(val stopped: Truncated?, val endOffset: Long)

    private sealed interface SegmentOutcome {
        class Ready(val segment: Segment) : SegmentOutcome
        class Refused(val reason: String, val cause: Throwable?) : SegmentOutcome
    }

    private companion object {
        /** Zeroed in 64 KiB bites, so pre-allocating a segment costs one small buffer. */
        const val PREALLOCATE_CHUNK_BYTES: Int = 64 * 1024

        const val INDEX_DIGITS: Int = 12

        /** Fixed-width, so a lexicographic sort of the directory is an ordering by age. */
        val SEGMENT_NAME = Regex("""segment-\d{$INDEX_DIGITS}\.bolt""")

        const val ZERO_BYTE: Byte = 0

        fun segmentName(index: Int): String = "segment-${index.toString().padStart(INDEX_DIGITS, '0')}.bolt"

        fun segmentIndexOf(file: File): Int =
            file.name.removePrefix("segment-").removeSuffix(".bolt").trimStart('0').ifEmpty { "0" }.toInt()

        fun ByteArray.isZeroFrom(index: Int): Boolean {
            for (position in index until size) {
                if (this[position] != ZERO_BYTE) return false
            }
            return true
        }

        fun isEntirelyZero(file: File): Boolean = RandomAccessFile(file, "r").use { handle ->
            val chunk = ByteArray(PREALLOCATE_CHUNK_BYTES)
            var read = handle.read(chunk)
            while (read > 0) {
                for (position in 0 until read) {
                    if (chunk[position] != ZERO_BYTE) return@use false
                }
                read = handle.read(chunk)
            }
            true
        }
    }
}
