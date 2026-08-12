package us.tractat.kuilt.bolt

import kotlinx.atomicfu.atomic
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
import java.io.UncheckedIOException
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
 * ### Three ways an archive can be short, and each is answered differently
 *
 * The parse stopping is not one situation, and treating it as one loses data. A **torn tail** —
 * non-zero bytes with nothing parseable behind them — is the ordinary crash, and [repair] clears it
 * so the archive stays appendable. A **hole** — a frame that will not parse with committed frames
 * behind it — is not repairable at all: zeroing would destroy acknowledged records and hand their
 * offsets out twice, so the archive goes read-only and keeps reporting the damage. And a **gap
 * between segments** — a segment file that is missing, or truncated to a frame boundary — presents
 * nothing to fail a checksum, and is caught only by comparing each header's absolute `baseOffset`
 * against where the previous segment ended (see [emitSegment]).
 *
 * ### Durability is `force()`, and synchronous versus asynchronous is one flag
 *
 * Bytes written into a mapped buffer are not durable until `msync`. [forceOnAppend] `= true` calls
 * it before [append] returns — the synchronous backend. `false` leaves the flush to the OS — the
 * asynchronous one. One type and one flag rather than two implementations, because two
 * implementations are two things to keep in agreement.
 *
 * If that flush fails the append still reports [AppendResult.Written], because the frame **is** in
 * the archive: it is whole, CRC-valid and visible to every reader of the file. What failed is the
 * durability upgrade, not the append, and saying otherwise costs records — [AppendResult.Failed]
 * means "the ops are lost" and invites the consumer to re-feed them, which would write a second copy
 * of a record already on disk. So a `forceOnAppend` bolt whose volume refuses to flush degrades to
 * the asynchronous guarantee rather than to a lie in either direction.
 *
 * That degradation is **reported**, not merely admitted in prose: [durability] answers
 * [DurabilityState.Degraded] over the offsets a failed flush left in doubt, and keeps answering it.
 * A `forceOnAppend = false` bolt promised no more than the asynchronous guarantee, so it stays
 * [DurabilityState.AsPromised] whatever the volume does — the signal is relative to what *this*
 * configuration offered (#2243). A real flush failure is still unreachable in this repo's tests, so
 * the path is driven through [rigFlushFailure]; what that does and does not prove is stated there.
 *
 * **`MappedByteBuffer.force()` reports that failure as an [UncheckedIOException]**, a
 * `RuntimeException`, where `FileChannel.force(boolean)` a few lines away declares the checked
 * `IOException`. Two flush calls, two hierarchies, and no compiler complaint about confusing them —
 * so [Segment.force] translates once at the call, and every `catch (IOException)` here covers both.
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
 * [replay] copies the **active** segment's written prefix while holding the lock — that is what makes
 * a concurrent append unobservable half-written — so a replay stalls appends for the length of one
 * `memcpy` of at most [segmentFrameBytes]. Every older segment is read from its file, outside the
 * lock. A [ReplayScope.FromOffset] resume does **not** read the whole archive: each segment header
 * carries an absolute `baseOffset` and is a fixed size, so a header-sized probe per segment prunes
 * a whole prefix of files without touching their frames — see [firstSegmentToRead] for the
 * predicate, and for the one segment it deliberately declines to prune (#2236).
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

    /** The outstanding durability doubt — what [durability] reports. Guarded by [lock]. */
    private val ledger = DurabilityLedger()

    /**
     * Non-null to make every subsequent flush fail with this reason. **Test-only**; see
     * [rigFlushFailure].
     */
    private var riggedFlushFailure: String? = null

    /** How many flushes [riggedFlushFailure] has made fail. **Test-only**; see [riggedFlushFailures]. */
    private var riggedFlushCount: Int = 0

    /**
     * Bytes read back off segment **files**, ever, by any replay on this instance.
     *
     * Deliberately **not** guarded by [lock]: a replay reads its files outside the lock, which is the
     * whole reason a slow reader does not stall appends. See [segmentFileBytesRead].
     */
    private val fileBytesRead = atomic(0L)

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
     *
     * It **recreates a directory that has gone missing**, rather than only reporting it. A probe with
     * a side effect is unusual, and it is here so the two answers cannot disagree: an append would
     * recreate it too, so reporting `Unavailable` for something the very next call repairs would make
     * this signal wrong in the direction it exists to rule out.
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

    /**
     * [DurabilityState.AsPromised] unless a [forceOnAppend] flush has failed and no later flush has
     * re-covered the range it left in doubt.
     *
     * **Relative to [forceOnAppend].** With it `true` a refused `force()` is exactly the promise
     * broken, and [DurabilityState.Degraded] names the offsets. With it `false` this bolt promised
     * only that the operating system would flush when it chose, so nothing it does can fall short and
     * this stays [DurabilityState.AsPromised] on a volume that would refuse a flush.
     *
     * **Not because no flush happens — one does.** The append path issues none, but [roll] flushes the
     * **retiring** segment whatever this flag says, because a retired segment is read back from its
     * file and that roll is the only point at which flushing it is guaranteed to have happened. That
     * flush can fail on an asynchronous bolt; it is simply not *recorded*, because it was never
     * promised. The gate therefore lives on what [flushQuietly] records, not on what it flushes — and
     * a reader who "simplifies" it away because this paragraph once claimed no flush occurs
     * reintroduces the defect #2243 shipped and then fixed.
     *
     * `force()` syncs the **whole mapping**, so the range a failure puts in doubt is every frame in
     * that segment, and a later successful flush of the same segment clears it. A doubt carried
     * across a segment roll is cleared only by re-flushing the segment it started in, which by then
     * has been retired — in practice it stands until the process does.
     */
    override fun durability(): DurabilityState = lock.withLock { ledger.state() }

    /**
     * Make every subsequent flush fail with [reason], or `null` to stop. **Test-only.**
     *
     * A `force()` fails on dying hardware. There is no unprivileged, deterministic condition that
     * makes a healthy volume refuse one — not a read-only mount (the mapping is already established),
     * not a deleted file, not a full disk (the blocks were claimed at pre-allocation, which is the
     * whole point of [preallocate]). So the alternative to this hook is that
     * [DurabilityState.Degraded] is unreachable on this backend and nothing asserts it, which is the
     * vacuity `BoltConformanceSuite`'s other fixture hooks exist to remove.
     *
     * It rigs the **verdict**, not the syscall: `MappedByteBuffer.force()` takes no arguments, so
     * unlike the Apple backend there is no input to make the kernel refuse. What that costs is stated
     * in `BoltConformanceSuite.newBoltThatCannotFlush` — the wiring from "the flush said no" to
     * "[durability] says so" is driven end to end; "the flush really can say no" is not, on this
     * backend, by anything.
     *
     * **Scoped to the durability flush, structurally.** [repair]'s tail flush passes `null`
     * explicitly rather than reading this field, so a rigged bolt cannot report itself unappendable
     * over a torn archive for a reason a test invented — unreachable by construction rather than by a
     * "call it after construction" rule a future `close()`/reopen would quietly invalidate.
     *
     * [riggedFlushFailures] is how a test proves the rig actually fired, which on an asynchronous bolt
     * is the difference between an assertion and a coincidence.
     */
    internal fun rigFlushFailure(reason: String?): Unit = lock.withLock { riggedFlushFailure = reason }

    /**
     * How many flushes the rig has made fail. **Test-only.**
     *
     * A conformance arm that asserts "this bolt attempted a flush, it failed, and durability still
     * reported [DurabilityState.AsPromised]" is only saying something while the first clause is true —
     * and on an asynchronous bolt that clause depends entirely on the fixture choosing a segment
     * budget that rolls. That made it an *emergent* property of a literal in a test helper: change the
     * budget, add an append, raise this backend's minimum segment size, and the arm silently reverts
     * to asserting nothing while its mutation table still claims the row. This is what lets the suite
     * demand it instead of hoping for it.
     *
     * Counts rig firings rather than flush attempts on purpose: it is dead in production (the field is
     * always null there), and "the rigged flush failed" is the exact clause the assertion needs, where
     * "a flush happened" is one inference away from it.
     */
    internal fun riggedFlushFailures(): Int = lock.withLock { riggedFlushCount }

    /**
     * How many bytes this bolt has read back off its segment **files**, across every replay so far.
     * **Test-only.**
     *
     * The quantity #2236 is about, and the only one that can settle it. What [firstSegmentToRead]
     * claims is an **I/O** saving, and every cheaper observable is one inference away from it: a
     * pruned segment's frames never being *emitted* is already true of the scope filter, and its
     * bytes never being *parsed* would still be true of an implementation that read every file whole
     * and looked only at the header — which is precisely the cost this pruning exists to remove. A
     * wall-clock assertion would say the same thing far less reliably, and on a contended box would
     * say it wrongly.
     *
     * Counts the active segment as free, because it is: its bytes are copied out of the live mapping
     * under [lock], not read from disk.
     *
     * Monotonic across the life of the instance, so a test measures a **difference** across one
     * replay rather than trusting an absolute — which also makes it immune to a fixture that happens
     * to replay twice.
     */
    internal fun segmentFileBytesRead(): Long = fileBytesRead.value

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
            // `offset` is the refusal's, not this frame's: an archive whose newest segment cannot be
            // read has no append offset to report, and reporting `0` there would name a byte range
            // the failure has nothing to do with.
            is SegmentOutcome.Refused ->
                AppendResult.Failed(outcome.reason, insertDots, outcome.offset, endOffset = null, cause = outcome.cause)

            is SegmentOutcome.Ready -> {
                outcome.segment.write(frame)
                nextOffset += frame.size
                // A failed flush is NOT a failed append. The frame IS in the archive — a write into
                // a mapped region is visible to every reader of the file immediately, and it is whole
                // and CRC-valid — so `Failed`, whose contract is "the ops are lost from the archive"
                // and whose remedy is to re-feed them, would have a consumer write a second copy of a
                // record already on disk. Between a consumer that believes a present frame is durable
                // when it may not be, and a consumer that duplicates every record on a failing disk,
                // the first is the smaller harm, and best-effort is this module's stated posture.
                // What is genuinely lost is the DURABILITY UPGRADE `forceOnAppend` promises, and that
                // is reported through `durability()` rather than smuggled into a result type meaning
                // something else. `:kuilt-bolt`'s Apple backend answers this identically (#2243) —
                // one contract across backends, deliberately.
                if (forceOnAppend) flushQuietly(outcome.segment)
                AppendResult.Written(offset, nextOffset, opCount, insertDots)
            }
        }
    }

    /**
     * The segment a [frameBytes]-byte frame will land in — the active one if it still has room,
     * otherwise a freshly rolled one — or the reason none could be had. Called under [lock].
     */
    private fun segmentFor(frameBytes: Int): SegmentOutcome {
        unappendable?.let { return SegmentOutcome.Refused(it, offset = null, cause = null) }
        active?.takeIf { it.hasRoomFor(frameBytes) }?.let { return SegmentOutcome.Ready(it) }
        // A frame bigger than the budget lands ALONE in a segment sized to fit it rather than being
        // refused: the budget bounds accumulation, it is not a record-size cap.
        val sizeBytes = headerBytes + maxOf(segmentFrameBytes, frameBytes.toLong())
        if (sizeBytes > Int.MAX_VALUE) {
            return SegmentOutcome.Refused(
                "a $frameBytes-byte frame needs a $sizeBytes-byte segment, larger than one mapping can be",
                offset = nextOffset,
                cause = null,
            )
        }
        if (usedBytes + sizeBytes > capacityBytes) {
            return SegmentOutcome.Refused(
                "mapped archive is full: $sizeBytes more bytes needed, " +
                    "${capacityBytes - usedBytes} of $capacityBytes free",
                offset = nextOffset,
                cause = null,
            )
        }
        return try {
            SegmentOutcome.Ready(roll(nextOffset, sizeBytes))
        } catch (failure: IOException) {
            // The catchable failure the eager pre-allocation exists to produce: a full or read-only
            // volume surfaces HERE, as a refused append, rather than as a SIGBUS on a later page.
            SegmentOutcome.Refused("could not pre-allocate a segment under $directory: $failure", nextOffset, failure)
        }
    }

    /**
     * Flush [segment], swallowing an I/O failure.
     *
     * **Every flush on the append path is a durability operation, and none of them may fail an
     * append.** That rule is easy to state and easy to break site by site, which is why it lives in
     * one function: two of these calls sit lexically inside [segmentFor]'s `catch (IOException)`, so
     * merely letting the failure propagate would have been *covered* — and covered here means turned
     * into [AppendResult.Failed], which is exactly the answer the durability note in this class's
     * KDoc argues against. Being caught is not the same as being handled correctly.
     *
     * The write operations around them — [preallocate], and [allocate]'s channel flush — keep
     * propagating, because a segment that could not be created really does fail the append.
     *
     * **Quiet to the caller, not to the archive.** The failure is recorded on [ledger] and surfaces
     * through [durability], because a swallowed flush failure can be the *only* notification that
     * ever arrives: on Linux an `EIO` from `msync` may be reported once and then cleared.
     *
     * The range is this segment's frames in the archive's append-offset space, because `force()`
     * syncs the whole mapping. Every call site flushes the segment holding the append cursor, so its
     * base is that cursor minus the frames it holds — including at a roll, where the freshly written
     * header has no frames behind it yet and the range is correctly empty.
     *
     * ### The LEDGER is gated on [forceOnAppend], not the flush — and they are not the same gate
     *
     * Two of the three call sites are already `if (forceOnAppend)`. [roll]'s flush of the **retiring**
     * segment is deliberately not: a retired segment is read back from its file, and that roll is the
     * only point at which flushing it is guaranteed to have happened, so an asynchronous archive
     * flushes there too. While the failure was swallowed that asymmetry was invisible. Recording it
     * would make it *visible and wrong*: an asynchronous bolt promised only that the operating system
     * would flush in its own time, so a refused flush is not a promise broken — and because the doubt
     * would cover a segment that has just been retired, no later flush could ever re-cover it, latching
     * [DurabilityState.Degraded] for the life of the bolt on a bolt that promised nothing.
     *
     * So the gate belongs here, on what is *recorded*, rather than on what is flushed. Do not "tidy"
     * this by gating the call at [roll] instead — that would stop flushing a retiring mapping, which
     * is a durability change wearing a refactor's clothes.
     */
    private fun flushQuietly(segment: Segment) {
        val frameBytes = segment.writePosition - headerBytes
        val from = nextOffset - frameBytes
        if (riggedFlushFailure != null) riggedFlushCount++
        try {
            segment.force(riggedFlushFailure)
            if (forceOnAppend) ledger.flushSucceeded(from, nextOffset)
        } catch (failure: IOException) {
            if (forceOnAppend) {
                ledger.flushFailed(from, nextOffset, "could not flush ${segment.file.name}: $failure", failure)
            }
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
        active?.let(::flushQuietly)
        // A directory removed underneath a live archive is recreated rather than turned into a
        // FileNotFoundException, so that this agrees with `availability`, which does the same. If it
        // cannot be recreated the RandomAccessFile below fails, which is the answer either way.
        directory.mkdirs()
        val file = File(directory, segmentName(nextIndex))
        val segment = Segment(file, allocate(file, sizeBytes), sizeBytes.toInt(), writePosition = 0)
        segment.write(
            encodeSegmentHeader(
                SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset = offset),
            ),
        )
        if (forceOnAppend) flushQuietly(segment)
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
     * directory — and reporting it as an empty archive would be worse than failing to open. An
     * [IOException] is the opposite case and gets the opposite treatment: the directory is the right
     * one, the volume is simply not co-operating, so it becomes [unappendable] and the archive stays
     * readable rather than taking the caller down with it.
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
        try {
            while (kept.isNotEmpty() && isEntirelyZero(kept.last())) {
                kept.removeAt(kept.lastIndex).delete()
            }
        } catch (failure: IOException) {
            unappendable = "the newest segment under $directory could not be read: $failure"
        }
        // Recorded BEFORE any refusal below, so an archive this instance cannot append to is still
        // one it can replay: `segments` is what the read path walks.
        segments += kept
        usedBytes = kept.sumOf { it.length() }
        nextIndex = kept.lastOrNull()?.let { segmentIndexOf(it) + 1 } ?: 0
        if (unappendable != null) return
        kept.lastOrNull()?.let { newest ->
            try {
                reopen(newest)
            } catch (failure: IOException) {
                // A read-only mount is the classic response to a disk I/O error, and it is exactly
                // when the application must survive. Reporting it through `availability` rather than
                // throwing out of `init` is the same posture `append` takes for a full disk — and an
                // EMPTY read-only directory already reported it that way, so this is the branch that
                // was missing rather than a new decision.
                unappendable = "the newest segment ${newest.name} could not be opened for appending: $failure"
            }
        }
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
        if (!repair(segment, bytes, writePosition, header.baseOffset)) return
        segment.rewindTo(writePosition)
        active = segment
    }

    /**
     * Make [segment] appendable again from [writePosition], or refuse to. `true` if it is now the
     * archive's active segment; `false` if the archive is damaged past repair and [unappendable] has
     * been set. Called under [lock].
     *
     * ### A torn tail and mid-segment damage look identical here, and must not be treated alike
     *
     * The parse stopped at [writePosition] either way, so the *only* thing that tells them apart is
     * what lies behind. Three cases:
     *
     * 1. **Nothing but zeroes** — the segment simply ended. Nothing to repair.
     * 2. **Non-zero, and nothing behind it parses** — a crash part-way through an append. That frame
     *    was never acknowledged (its `append` never returned), so no caller believes it landed, and
     *    leaving it would *wedge the archive*: replay stops at the first frame that will not parse, so
     *    every later append would be permanently unreachable. Zero it and carry on.
     * 3. **Non-zero, with a whole CRC-valid frame behind it** — a hole, not a tail. Every frame behind
     *    it was acknowledged to a caller. Zeroing them would destroy committed records, launder the
     *    `Truncated` into a `CleanTail` on the next restart (and a server restarts routinely), and
     *    drop the append cursor onto offsets already handed out — after which a consumer resuming from
     *    `ReplayScope.FromOffset` silently skips frames, because the scope selects on `endOffset >`
     *    and the reused offsets straddle its cursor. So: repair nothing, append nothing. Replay keeps
     *    reporting the damage, and the operator points a fresh directory at the problem.
     *
     * Case 3 is not exotic. Under `forceOnAppend = false` the OS writes pages back in whatever order
     * it likes, so a hole followed by later-flushed pages is the *expected* artifact of a power loss.
     *
     * ### The scan may err, and it is built to err in one direction only
     *
     * **An archive may decline to repair; it may never repair wrongly.** A chance CRC-32 match on
     * misaligned bytes (~2⁻³²) reads case 2 as case 3 — the archive refuses a repair it could have
     * made, costing an operator a fresh directory. The opposite error costs committed records that
     * nothing anywhere can reconstruct. So every uncertain reading resolves to "do not touch it",
     * and any future change here has to keep that asymmetry rather than merely stay accurate.
     */
    private fun repair(segment: Segment, bytes: ByteArray, writePosition: Int, baseOffset: Long): Boolean {
        if (bytes.isZeroFrom(writePosition)) return true
        val committed = bytes.lastCommittedFrameEnd(from = writePosition)
        if (committed == null) {
            segment.zeroFrom(writePosition)
            // Not [flushQuietly]: this flush carries no frame — it is the discarding of a tail no
            // caller was ever told about — and a failure here really does fail the open, which is
            // what the `catch (IOException)` around [reopen] turns into [unappendable].
            //
            // Explicitly UNRIGGED, and passed rather than left to the field, for the reason the Apple
            // backend passes its rig as a parameter: the rig exists to drive the durability contract,
            // and this flush is not part of it. Reading `riggedFlushFailure` here is dead today (this
            // runs only from `init`, before any caller can rig anything) and would come alive the
            // moment this backend grew a `close()`/reopen the way `PosixMappedBolt` has one. A test
            // hook that can wedge an archive is worth making unreachable by construction rather than
            // by call ordering.
            segment.force(riggedFailure = null)
            return true
        }
        unappendable = "segment ${segment.file.name} is damaged at offset ${baseOffset + writePosition - headerBytes}" +
            ", with committed frames behind the damage ending at offset ${baseOffset + committed - headerBytes}. " +
            "Appending would reuse offsets already reported to a caller, and replay cannot reach past the " +
            "damage in any case — archive to a fresh directory."
        return false
    }

    // ── replay ────────────────────────────────────────────────────────────────

    override fun replay(scope: ReplayScope): Flow<ReplayEvent<Op>> = flow {
        // Snapshot under the lock, decode outside it. The active segment is copied out of its
        // mapping while the lock is held, so a concurrent append can never be observed half-written;
        // every earlier segment is immutable and was forced before its mapping was let go.
        val reads = lock.withLock { snapshot() }
        val from = firstSegmentToRead(reads, scope)
        // Null until the first segment has spoken. The archive's offset space starts wherever the
        // OLDEST segment this replay reads says it does, not at 0 — and after a prune that is not
        // the archive's oldest segment, which is why this cannot be seeded with a literal.
        var resumeOffset: Long? = null
        for (index in from until reads.size) {
            // A segment that stops early stops the WHOLE replay. An append-only log is ordered, so a
            // frame that does not validate makes everything behind it untrustworthy; carrying on to
            // the next segment would hand back a history with a silent hole and offsets that jump.
            val outcome = emitSegment(reads[index], resumeOffset, scope)
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

    /**
     * The oldest segment [scope] obliges this replay to **read**: segment-granularity pruning, minus
     * one, and the minus one is load-bearing.
     *
     * ### The predicate, and why it is the SUCCESSOR's base offset
     *
     * `InMemoryBolt` prunes on `baseOffset + frameBytes <= cursor` because it holds every segment's
     * exact frame extent in memory. This backend deliberately does not: [recover] scans only the
     * newest segment, so an older segment's extent is on disk and nowhere else, and reading it back
     * is the whole cost this pruning exists to avoid. What *is* cheap is each segment's absolute
     * `baseOffset`, because a header is fixed-size for a given [BoltArchiveFormat] — so a segment is
     * prunable exactly when its **successor** starts at or below the cursor, which bounds its own
     * frames from above without reading one of them.
     *
     * **The newest segment therefore has no successor and is never pruned.** That is correct rather
     * than a limitation: nothing in a header says where the archive's last frame ends.
     *
     * ### Minus one: the segment before the first survivor is READ, with every frame filtered out
     *
     * Its true frame end is the offset the first emitted segment's header must continue from, and
     * that boundary is the only one a [ReplayScope.FromOffset] replay has. Pruning it leaves the
     * boundary unchecked, and an unchecked boundary is a hole reported as a [CleanTail] to the one
     * caller most likely to act on it — `FromOffset` is the documented resume cursor, so the offset a
     * consumer hands back is precisely the offset a lost segment starts at (#2240, #2244).
     *
     * It is **parsed**, never taken from a segment's recorded extent. On this backend a non-last
     * segment's extent could only be *derived* as the successor's base minus its own, which across a
     * hole is inflated by exactly the hole — the check would compare a value with itself and stay
     * green over real damage. A non-last segment's recorded extent is an inference *from* continuity,
     * so it is never evidence *about* it. One extra segment read cannot lie.
     *
     * ### What this backend's predicate does and does not inherit from #2240
     *
     * Stated exactly, because the tempting summary is wrong in both directions. `InMemoryBolt`'s
     * exact extent lets it prune a segment while the hole *behind* that segment still straddles the
     * cursor — that is the defect #2244 fixed. The successor-base predicate here cannot reach that
     * state: pruning segment `k` requires `base(k+1) <= cursor`, so the missing region
     * `[end(k), base(k+1))` lies wholly at or below the cursor and no emitted frame depends on it.
     *
     * The minus-one read is kept anyway, and not out of caution alone. It is what makes this backend
     * answer a *lost middle* the same way the other two do when a consumer resumes from the far side
     * of the hole — `MappedBoltPruningTest.aResumeFromBeyondAHoleStillReportsIt` pins that agreement,
     * and it is the only test in the tree that reds when this line is dropped. Resting a correctness
     * property on an accident of which bookkeeping happens to be inflated is how the next reader
     * loses it; `PosixMappedBolt` reached the same conclusion for the same reason.
     *
     * ### What a pruning replay stops reporting, said out loud
     *
     * Damage lying **wholly below the cursor** — a torn frame, an unreadable file — is no longer seen
     * by a [ReplayScope.FromOffset] resume, where before this backend read every segment and stopped
     * at it. That is the pruning contract rather than a loss: those bytes are behind what the caller
     * asked for, [ReplayScope.All] still reports them, and both other backends have answered this way
     * since they grew a `skippable`. It is stated here because "the resume went quiet about damage
     * the whole-archive replay shouts about" is otherwise a bug report waiting to be filed.
     */
    private fun firstSegmentToRead(reads: List<SegmentRead>, scope: ReplayScope): Int {
        // Scope-gated: for `All` the header pass is pure overhead, and no offset predicate applies
        // to `Arrived` or `InsertsAbove` at all — a frame's arrival time and dots are in its body.
        if (scope !is ReplayScope.FromOffset) return 0
        var firstUnpruned = 0
        while (firstUnpruned < reads.lastIndex) {
            // A header that will not read leaves this boundary undecidable, so the scan stops and
            // the segment is read whole — which is where the damage gets classified and reported.
            val successorBase = baseOffsetOf(reads[firstUnpruned + 1]) ?: break
            if (successorBase > scope.offset) break
            firstUnpruned++
        }
        return maxOf(firstUnpruned - 1, 0)
    }

    /**
     * Where [read]'s frames start, from its header alone, or `null` if that header will not read.
     *
     * The active segment's bytes are already in hand, so it costs nothing; every other segment costs
     * one bounded [headerBytes] read rather than the whole file. A [BoltFormatException] — a foreign
     * archive, a version from the future — propagates exactly as it does from [emitSegment]: the
     * caller opened the wrong directory, and reporting that as "unprunable" would hide it until the
     * body read raised the same thing one step later.
     */
    private fun baseOffsetOf(read: SegmentRead): Long? {
        val bytes = read.inMemory ?: try {
            readHeaderOf(read.file)
        } catch (_: IOException) {
            return null
        }
        val buffer = Buffer().apply { write(bytes, 0, minOf(bytes.size, headerBytes)) }
        return readSegmentHeader(buffer, format.opFormat, format.elementType)?.baseOffset
    }

    /** Exactly [headerBytes] off the front of [file]; a shorter file raises an [IOException]. */
    private fun readHeaderOf(file: File): ByteArray {
        val probe = ByteArray(headerBytes)
        RandomAccessFile(file, "r").use { handle -> handle.readFully(probe) }
        fileBytesRead += headerBytes.toLong()
        return probe
    }

    /** [read]'s bytes: the active segment's snapshot, or the file, counted against [fileBytesRead]. */
    private fun bytesOf(read: SegmentRead): ByteArray =
        read.inMemory ?: read.file.readBytes().also { fileBytesRead += it.size.toLong() }

    /**
     * Emit [read]'s in-scope frames, and say how the segment ended.
     *
     * [resumeOffset] is where the previous segment's frames ended, or `null` if this is the first
     * segment to be read. It is both the offset a verdict about *this* segment reports and — for
     * every segment after the first — the offset this segment's header must claim to start at.
     *
     * ### The continuity check is not tidiness, it is the only thing that sees a missing segment
     *
     * Frames are validated one at a time, so damage *within* a segment is caught by its own checksum.
     * A segment that is **gone**, or one truncated to exactly a frame boundary, presents nothing to
     * fail a checksum: the reader simply moves to the next file, whose frames are perfectly intact
     * and start 133 bytes further along than the archive's own history says. Without this check that
     * is a `CleanTail` over a history with a hole punched in it — and the [Bolt] KDoc spells out what
     * a consumer does next, which is re-mint an already-used `(replica, seq)` dot mesh-wide.
     *
     * Every segment header carries an *absolute* `baseOffset`, so the gap is arithmetic rather than
     * guesswork. `InMemoryBolt` runs the same check and reports the same verdict — its segments are an
     * in-process list that can only lose one to a test hook, but the contract every backend is held to
     * is written in the reference backend's behaviour, so it reports rather than asserts (#2240).
     *
     * It is reported as [TruncationReason.MissingRegion], which is a reason of its own rather than
     * the segment-header layer's, because the *remedy* differs: a torn header may be completed by a
     * writer that catches up, and a missing segment never will be. See that constant's KDoc.
     */
    private suspend fun FlowCollector<ReplayEvent<Op>>.emitSegment(
        read: SegmentRead,
        resumeOffset: Long?,
        scope: ReplayScope,
    ): SegmentReplay {
        val stopsAt = resumeOffset ?: 0L
        val bytes = try {
            bytesOf(read)
        } catch (_: IOException) {
            // A segment we cannot read at all is indistinguishable, to a consumer, from one whose
            // header will not read — and throwing would discard every intact frame ahead of it.
            return SegmentReplay(Truncated(stopsAt, TruncationReason.SegmentHeader), stopsAt)
        }
        val buffer = Buffer().apply { write(bytes) }
        val header = readSegmentHeader(buffer, format.opFormat, format.elementType)
            ?: return SegmentReplay(endedAt(bytes, consumed = 0, offset = stopsAt), stopsAt)
        if (resumeOffset != null && header.baseOffset != resumeOffset) {
            // This header is intact — it simply describes the wrong place — so the fault is neither
            // the header layer's nor a frame's. `MissingRegion` says the thing a consumer branches
            // on: the offsets between here and there exist nowhere, so unlike a torn tail this is
            // not somewhere to resume from later.
            return SegmentReplay(Truncated(resumeOffset, TruncationReason.MissingRegion), resumeOffset)
        }
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
     * Two typed views of one buffer, and the typing is load-bearing rather than stylistic. Java 9
     * gave `ByteBuffer` covariant overrides of the `Buffer` positioning methods — `position`,
     * `limit`, `clear` — so a call bound to `ByteBuffer` compiles against
     * `ByteBuffer.position(I)Ljava/nio/ByteBuffer;`, which does not exist on the older Android
     * runtimes this module still targets (`minSdk` is 24) and fails there with `NoSuchMethodError` at
     * runtime rather than at build time. Binding those calls to [NioBuffer] picks
     * `Buffer.position(I)Ljava/nio/Buffer;`, which has been there since 1.4; `put`/`get`/`force` were
     * never re-declared and are bound normally.
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

        /**
         * `msync` this mapping, reporting a failure as a **checked** [IOException].
         *
         * `MappedByteBuffer.force()` throws [UncheckedIOException] — a `RuntimeException` — where
         * `FileChannel.force(boolean)`, called inches away in [allocate], declares the checked
         * `IOException`. Two flush calls, two exception hierarchies, no compiler complaint either
         * way: every `catch (IOException)` in this file silently covered one and not the other. The
         * translation happens here, once, at the only place the mapped call is made, so a caller
         * cannot be written against the wrong hierarchy by reading the surrounding code.
         *
         * The original failure is rethrown intact rather than wrapped, so its stack trace and errno
         * detail survive. `UncheckedIOException.getCause()` is declared to return `IOException` in
         * Java — but Kotlin reads that covariant override through its own mapped `Throwable.cause`,
         * which is `Throwable?`, so the property is `IOException?` here and not a platform non-null.
         * Hence the elvis: it is unreachable in practice (both `UncheckedIOException` constructors
         * null-check their cause) and it is not worth a `!!` to say so.
         *
         * A non-null [riggedFailure] raises an `UncheckedIOException` in place of the call, which is
         * deliberately *inside* the `try`: the translation below is the one subtlety this call site
         * has, so a rigged failure exercises it rather than stepping around it.
         */
        fun force(riggedFailure: String?) {
            try {
                if (riggedFailure != null) throw UncheckedIOException(IOException(riggedFailure))
                mapped.force()
            } catch (failure: UncheckedIOException) {
                throw failure.cause ?: IOException("could not flush the mapped segment", failure)
            }
        }
    }

    /** A segment to replay: the active one's bytes in hand, or a file to read them from. */
    private class SegmentRead(val file: File, val inMemory: ByteArray?)

    /** How one segment's replay ended: a verdict, or the offset the next segment resumes at. */
    private class SegmentReplay(val stopped: Truncated?, val endOffset: Long)

    private sealed interface SegmentOutcome {
        class Ready(val segment: Segment) : SegmentOutcome
        class Refused(val reason: String, val offset: Long?, val cause: Throwable?) : SegmentOutcome
    }

    private companion object {
        /** Zeroed in 64 KiB bites, so pre-allocating a segment costs one small buffer. */
        const val PREALLOCATE_CHUNK_BYTES: Int = 64 * 1024

        const val INDEX_DIGITS: Int = 12

        /** Fixed-width, so a lexicographic sort of the directory is an ordering by age. */
        val SEGMENT_NAME = Regex("""segment-\d{$INDEX_DIGITS}\.bolt""")

        const val ZERO_BYTE: Byte = 0

        const val INT_BYTES: Int = 4
        const val BYTE_MASK: Int = 0xFF

        /** A length prefix, the smallest body this codec can have written, and a CRC trailer. */
        const val MINIMUM_FRAME_BYTES: Int = INT_BYTES + MINIMUM_BODY_BYTES + INT_BYTES

        fun segmentName(index: Int): String = "segment-${index.toString().padStart(INDEX_DIGITS, '0')}.bolt"

        fun segmentIndexOf(file: File): Int =
            file.name.removePrefix("segment-").removeSuffix(".bolt").trimStart('0').ifEmpty { "0" }.toInt()

        fun ByteArray.isZeroFrom(index: Int): Boolean {
            for (position in index until size) {
                if (this[position] != ZERO_BYTE) return false
            }
            return true
        }

        /**
         * One past the last whole, CRC-valid frame at or after [from], or `null` if there is none.
         *
         * A byte-by-byte scan rather than a walk, because the byte that stopped the parse may be the
         * length prefix itself — in which case where the *next* frame starts is exactly what is no
         * longer known. It stays cheap: a position whose length word is impossible or overruns the
         * array is rejected in constant time, and only a plausible one pays for a checksum.
         *
         * The validation is [readFrame]'s, deliberately duplicated against the array rather than
         * reached through a `Buffer` — a `Buffer` per candidate position would allocate once per byte
         * of the region.
         */
        fun ByteArray.lastCommittedFrameEnd(from: Int): Int? {
            var found: Int? = null
            var position = from
            while (position + MINIMUM_FRAME_BYTES <= size) {
                val end = frameEndAt(position)
                if (end == null) {
                    position++
                } else {
                    found = end
                    position = end
                }
            }
            return found
        }

        /** One past the frame starting at [position], if a whole CRC-valid frame starts there. */
        private fun ByteArray.frameEndAt(position: Int): Int? {
            val bodyLength = intAt(position)
            if (bodyLength < MINIMUM_BODY_BYTES) return null
            val end = position.toLong() + INT_BYTES + bodyLength + INT_BYTES
            if (end > size) return null
            val trailer = end.toInt() - INT_BYTES
            return if (intAt(trailer) == crc32(this, position, trailer)) end.toInt() else null
        }

        /** The big-endian `Int` at [index] — the layout `Buffer.writeInt` produces. */
        private fun ByteArray.intAt(index: Int): Int {
            var value = 0
            for (byte in index until index + INT_BYTES) {
                value = (value shl Byte.SIZE_BITS) or (this[byte].toInt() and BYTE_MASK)
            }
            return value
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
