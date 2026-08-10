@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.bolt

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.io.Buffer
import platform.Foundation.NSFileManager
import platform.posix.MAP_SHARED
import platform.posix.MS_SYNC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.errno
import platform.posix.getpagesize
import platform.posix.lseek
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.mmap
import platform.posix.msync
import platform.posix.munmap
import platform.posix.open
import platform.posix.read
import platform.posix.strerror_r
import platform.posix.write
import us.tractat.kuilt.crdt.Dot
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A [Bolt] whose segments are memory-mapped files, for iOS and macOS.
 *
 * It writes the **same bytes** [InMemoryBolt] writes — same segment header, same length-prefixed,
 * CRC-checked frames, same canonical CBOR ops — so an archive written here is byte-for-byte readable
 * by any other backend. What differs is where the bytes live: one file per segment under
 * [directory], each mapped with `mmap(2)` and appended to with `memcpy`.
 *
 * ### This is NOT the default archive on Apple, and here is why
 *
 * The server is this backend's customer. On a phone every one of the four hazards below is a routine
 * state rather than an edge case, and a phone should retain least anyway — that asymmetry is the
 * whole premise of the module. Reach for it on macOS, or on iOS only with a small [capacityBytes]
 * and eyes open.
 *
 * 1. **A full volume under mmap is SIGBUS, not an exception.** `ftruncate`ing a file to segment size
 *    allocates **sparsely**: the physical blocks are not committed until the first page-touch, and on
 *    a full volume that touch raises `SIGBUS`, which kills the process and takes the application's
 *    logging down with it — the one outcome a best-effort archive promises to avoid. A full iOS
 *    device is *routine*. So every segment is **eagerly, physically pre-allocated with a real
 *    `write(2)` of zeroes**, never `ftruncate` — exhaustion then surfaces as a catchable `ENOSPC` at
 *    a segment boundary, becoming an [AppendResult.Failed] that names the dots it could not keep.
 *    This is mandatory on this backend, not advisory.
 *
 * 2. **Pre-allocation writes zeroes, and a zero run must never read as a frame.** The tail of every
 *    live segment is therefore a zero-filled region, and `crc32(ByteArray(0)) == 0` — so with a
 *    body-only checksum, eight zero bytes would be a *syntactically valid empty frame* and a reader
 *    would walk an unbounded run of them. Two independent barriers in the shared codec stop that: the
 *    frame CRC covers the **length prefix as well as the body** (a zero prefix checksums to
 *    `0x2144DF1C`, which no zero field can equal), and `MINIMUM_BODY_BYTES` rejects an impossibly
 *    small body. **Both are load-bearing and neither is redundant** — mutating either one alone
 *    leaves the other refusing the zero run, so a single-mutation reading would wrongly conclude one
 *    could be deleted. Do not "simplify" either; `PosixMappedBoltTest.aPreAllocatedTailReplaysClean`
 *    pins the pair.
 *
 *    Because a zero tail is *expected* rather than damage, replay treats it as the end of a segment's
 *    written frames and moves on — see [emitFrames]. A frame that fails to read with **non-zero**
 *    bytes behind it is damage, and stops the whole replay.
 *
 * 3. **Jetsam.** Mapped dirty pages count against an iOS app's memory footprint, so a growing mapped
 *    archive is a way to get the app killed. Only the **active** segment is ever mapped here — a roll
 *    `munmap`s its predecessor — so the resident dirty set is bounded by one segment rather than by
 *    the archive. Replay does not map at all (see below). Keep [segmentFrameBytes] small on a phone.
 *
 * 4. **Data Protection.** A file inherits a protection class that can make it unreadable while the
 *    device is locked — which is exactly when a background writer runs. **This backend chooses none
 *    explicitly, and so inherits the process default** (`NSFileProtectionCompleteUntilFirstUserAuthentication`
 *    for most apps). That is the deliberate choice: it keeps the archive readable after the first
 *    unlock following a boot, which covers a background writer, without asking for the weaker
 *    `…None`, which would leave a year of records readable on a lost device. The consequence is that
 *    an archive is **not** writable between boot and first unlock, and a caller that needs that must
 *    set `NSFileProtectionNone` on [directory] itself and accept what it means. When the archive
 *    directory cannot be probed with `EACCES`/`EPERM`, [availability] reports
 *    [BoltAvailability.Unknown] rather than [BoltAvailability.Unavailable] — the state is neither
 *    available nor permanently unavailable, and the next unlock may resolve it. **This path is
 *    reachable only on real hardware; nothing in this repo's test suite covers it**, and no test
 *    here should be read as evidence that it works.
 *
 * ### Durability is `msync`, and it is one flag
 *
 * A `memcpy` into a mapping publishes bytes to every reader of the file immediately, but does not
 * make them durable. [synchronous] is the whole difference between the two modes this module
 * describes: `true` calls `msync(MS_SYNC)` over the frame's page range before [append] returns;
 * `false` lets the OS flush when it likes. One type, one flag — two implementations would be two
 * things to keep in agreement.
 *
 * If that `msync` fails the frame is still in the archive (it is in the file, visible to readers), so
 * the append cursor advances — but [append] returns [AppendResult.Failed] carrying the errno, the
 * dots and the byte range, because a *synchronous* bolt that silently downgraded to asynchronous
 * would be lying about the only thing the flag promises.
 *
 * ### Fixed-size segments, not one remapped growing file
 *
 * A mapping is a fixed-size window, so growing an archive means either remapping one file over and
 * over or chunking it. Chunking wins twice: it bounds a remap, and it is what makes eager
 * pre-allocation affordable — one allocation per segment rather than per record. A frame **larger**
 * than [segmentFrameBytes] is not refused; it lands alone in a segment sized to fit it, matching
 * [InMemoryBolt].
 *
 * ### Replay reads, it does not map
 *
 * [replay] opens each segment with `read(2)` rather than mapping it. Two reasons: the codec parses
 * through a `kotlinx.io.Buffer` so the bytes are copied either way, and reading a *mapped* file that
 * something else truncates is a second `SIGBUS` surface where `read(2)` merely returns short. It also
 * means a replay never touches the live mapping, so a concurrent [append] that rolls a segment
 * cannot pull a mapping out from under a reader.
 *
 * ### Failures name their errno
 *
 * Every failing posix call is reported with its `errno` and the `strerror` text, alongside the path.
 * A bare "write failed" makes every recovery unimplementable; this module's contract is to report
 * identities and state, never a tally.
 *
 * ### Thread safety
 *
 * All mutable state is guarded by an explicit `reentrantLock`, and nothing inside the locked section
 * suspends. Dispatcher confinement is deliberately **not** used: Kotlin/Native is genuinely
 * multi-threaded and correctness here has to be a local property of each field, not an emergent
 * property of where coroutines happen to run.
 *
 * @param format how ops are classified and encoded — see [BoltArchiveFormat].
 * @param clock stamps each frame's arrival time. **Required**: time is a dependency, and a bolt that
 *   reached for `Clock.System` itself could not be tested deterministically.
 * @param directory the archive directory. Created on first use if absent; an existing archive in it
 *   is re-opened and appended to. One directory holds one archive — do not share it.
 * @param synchronous `msync(MS_SYNC)` per append when `true`; let the OS flush when `false`.
 * @param segmentFrameBytes the frame capacity of one segment file. The file itself is this plus a
 *   header. Small on a phone (jetsam), large on a server (fewer rolls).
 * @param capacityBytes the total byte budget for the whole archive, headers and unused pre-allocated
 *   tails included. Unbounded by default, which is the server's answer; a phone should set one.
 */
public class PosixMappedBolt<Id : Any, V, Op : Any>(
    private val format: BoltArchiveFormat<Id, V, Op>,
    private val clock: Clock,
    private val directory: String,
    private val synchronous: Boolean = true,
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

    /** The mapping of `segments.last()`, and the only mapping this bolt ever holds. */
    private var active: Mapping? = null

    /** The next frame's append offset. Counts frame bytes only — segment headers are not in it. */
    private var nextOffset: Long = 0L

    /** Total bytes allocated on disk, unused pre-allocated tails included — what [capacityBytes] bounds. */
    private var usedBytes: Long = 0L

    /** Whether [openArchive] has succeeded; cleared so a transient failure is retried. */
    private var opened: Boolean = false

    /** A structural problem no retry can fix. Sticky, deliberately — see [openArchive]. */
    private var wedged: BoltAvailability.Unavailable? = null

    private var repaired: Long? = null

    /**
     * The append offset a torn tail was discarded at when this archive was re-opened, or `null` if
     * nothing was discarded.
     *
     * A crash between the start and the end of an append — a jetsam kill, a power loss — leaves a
     * partial frame at the tail of the last segment. That frame was never acknowledged to anyone: no
     * [AppendResult.Written] was returned for it, so no committed record depends on it. Re-opening
     * therefore **zeroes it back to the last intact frame**, restoring the "intact frames then a zero
     * tail" shape every other path here assumes, so the archive keeps accepting appends and a replay
     * reads [CleanTail] again.
     *
     * The alternative — refuse to append until a human intervenes — is wrong for a phone, where a
     * mid-append kill is routine and would permanently disable archiving. But the repair is not
     * *silent*: this property is how a consumer learns it happened, and where.
     *
     * Damage anywhere other than the tail is never repaired. A middle segment with an unreadable
     * header wedges the bolt at [BoltAvailability.Unavailable], because appending past it would write
     * records no replay could ever reach.
     */
    public val repairedTailAt: Long? get() = lock.withLock { repaired }

    /**
     * A segment header's size for this format. Fixed: every field is fixed-width except the two
     * self-description strings, which are this format's own and never vary per segment.
     */
    private val headerBytes: Int = encodeSegmentHeader(
        SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset = 0L),
    ).size

    /**
     * A strict lower bound on any frame's size: the framing of a frame carrying nothing.
     *
     * Such a frame is never actually written — an append with no content is [AppendResult.Skipped] —
     * but it is what makes [availability] answerable ahead of an append that has not happened yet.
     */
    private val minimumFrameBytes: Long =
        encodeFrame(RawFrame(Instant.fromEpochMilliseconds(0L), emptySet(), null, emptyList())).size.toLong()

    // ── the contract ──────────────────────────────────────────────────────────

    /**
     * [BoltAvailability.Available] exactly while the archive has room for the **smallest possible**
     * frame — in the active segment's remaining capacity, or in a segment it still has the budget to
     * allocate.
     *
     * Not "usedBytes < capacityBytes": an archive with four bytes free would report itself writable
     * while every append failed, and a bolt whose `Available` does not imply "an append will be
     * accepted" is the one thing this signal exists to rule out. A single append **larger** than the
     * remaining room still fails, and reports the dots it lost, because no per-append size is knowable
     * before the append arrives.
     *
     * Probes the filesystem, so it is not free — and it can report [BoltAvailability.Unknown] when the
     * probe fails in the way a Data-Protection-locked device fails.
     */
    override fun availability(): BoltAvailability = lock.withLock {
        val open = ensureOpen()
        if (open !is BoltAvailability.Available) {
            open
        } else if (roomForMinimumFrame()) {
            BoltAvailability.Available
        } else {
            BoltAvailability.Unavailable(
                "archive at $directory is full ($usedBytes/$capacityBytes bytes allocated)",
            )
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

    override fun replay(scope: ReplayScope): Flow<ReplayEvent<Op>> = flow {
        // Snapshot the segment LIST under the lock and read the FILES outside it. The files are the
        // authority on their own bytes, and MAP_SHARED writes are coherent with read(2), so a
        // concurrent append is either wholly visible to this reader or wholly invisible to it —
        // never half of a frame, because a frame's CRC covers its own length prefix.
        val views = lock.withLock {
            wedged?.let { return@flow emit(Truncated(nextOffset, TruncationReason.SegmentHeader)) }
            segments.map { SegmentView(it.path, it.baseOffset, it.writtenFrameBytes) }
        }
        for (view in views) {
            if (skippable(view, scope)) continue
            val bytes = readWholeFile(view.path)
                ?: return@flow emit(Truncated(view.baseOffset, TruncationReason.SegmentHeader))
            // A segment that stops early stops the WHOLE replay. An append-only log is ordered, so a
            // frame that does not validate makes everything behind it untrustworthy; carrying on to
            // the next segment would hand back a history with a silent hole and offsets that jump,
            // which is worse than a short answer that says it is short.
            val stopped = emitFrames(bytes, view, scope)
            if (stopped != null) {
                emit(stopped)
                return@flow
            }
        }
        emit(CleanTail)
    }

    /**
     * Release the active mapping and its descriptor.
     *
     * Not on the [Bolt] interface: an in-memory or wasm bolt has nothing to release, and putting a
     * `close` there would make every consumer of every backend carry a lifecycle it does not have.
     * Frames already written are durable independently of this call — it frees a mapping, it does not
     * flush one. Idempotent; the bolt re-opens on the next [append].
     */
    public fun close(): Unit = lock.withLock {
        unmapActive()
        opened = false
    }

    // ── the append path ───────────────────────────────────────────────────────

    /**
     * Assemble and append one frame. Called under [lock].
     *
     * The clock is read here rather than before acquiring, so two racing appends cannot stamp their
     * frames in the opposite order to the offsets they take.
     */
    @Suppress("ReturnCount")
    private fun writeFrame(encodedOps: List<ByteArray>, insertDots: Set<Dot>, opCount: Int): AppendResult {
        val open = ensureOpen()
        if (open !is BoltAvailability.Available) {
            return AppendResult.Failed(open.reasonOrBlank(), insertDots, offset = nextOffset)
        }
        val frame = encodeFrame(RawFrame(clock.now(), insertDots, key = null, ops = encodedOps))
        val offset = nextOffset
        val current = segments.lastOrNull()?.takeIf { active != null && it.remaining >= frame.size }
        val segment = if (current != null) {
            current
        } else {
            // Capacity is checked BEFORE anything is committed, so a refused append leaves no
            // half-allocated segment behind.
            val allocation = headerBytes + maxOf(segmentFrameBytes, frame.size.toLong())
            if (usedBytes + allocation > capacityBytes) {
                return AppendResult.Failed(
                    "archive at $directory is full: $allocation more bytes needed, " +
                        "${capacityBytes - usedBytes} of $capacityBytes free",
                    insertDots,
                    offset,
                )
            }
            try {
                rollSegment(offset, allocation)
            } catch (failure: PosixFailure) {
                return AppendResult.Failed(failure.reason, insertDots, offset, cause = failure)
            }
        }
        return commit(segment, frame, offset, opCount, insertDots)
    }

    /** `memcpy` [frame] into the active mapping, flush it if [synchronous], and advance the cursor. */
    private fun commit(
        segment: Segment,
        frame: ByteArray,
        offset: Long,
        opCount: Int,
        insertDots: Set<Dot>,
    ): AppendResult {
        val mapping = checkNotNull(active) { "a segment was selected for append with no mapping behind it" }
        val at = segment.headerBytes + segment.writtenFrameBytes
        copyInto(mapping, at, frame)
        segment.writtenFrameBytes += frame.size
        nextOffset += frame.size
        val syncFailure = if (synchronous) syncRange(mapping, at, frame.size.toLong()) else null
        // The frame IS in the archive whether or not the flush landed — a memcpy into a MAP_SHARED
        // mapping is visible to every reader of the file immediately — so the cursor advances either
        // way, and the failure reports the byte range rather than pretending nothing was written.
        return if (syncFailure == null) {
            AppendResult.Written(offset, nextOffset, opCount, insertDots)
        } else {
            AppendResult.Failed(syncFailure, insertDots, offset, endOffset = nextOffset)
        }
    }

    /**
     * Create, physically pre-allocate and map a new segment of [allocation] bytes whose first frame
     * sits at [offset]. Called under [lock]; throws [PosixFailure] with an errno-bearing reason.
     *
     * The order is load-bearing. The zero fill is a **real `write(2)`**, not `ftruncate`, so the
     * blocks are committed here — where `ENOSPC` is a return value — rather than at first page-touch,
     * where it would be a `SIGBUS` that kills the process. Only once the new segment is fully live is
     * the previous mapping released, so a failure anywhere above leaves the old active segment usable.
     */
    private fun rollSegment(offset: Long, allocation: Long): Segment {
        val index = (segments.lastOrNull()?.index ?: -1L) + 1L
        val path = directory.withTrailingSlash() + segmentName(index)
        val fd = open(path, O_RDWR or O_CREAT or O_EXCL, S_IRUSR or S_IWUSR)
        if (fd < 0) throw PosixFailure(posixFailure("could not create segment $path"))
        val mapping = try {
            preallocate(fd, allocation, path)
            val address = mmap(null, allocation.convert(), PROT_READ or PROT_WRITE, MAP_SHARED, fd, 0)
            if (address == null || address.rawValue.toLong() == MMAP_FAILED) {
                throw PosixFailure(posixFailure("could not map segment $path"))
            }
            Mapping(fd, address.reinterpret(), allocation)
        } catch (failure: PosixFailure) {
            close(fd)
            throw failure
        }
        val header = encodeSegmentHeader(
            SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset = offset),
        )
        copyInto(mapping, 0L, header)
        if (synchronous) syncRange(mapping, 0L, header.size.toLong())?.let { throw PosixFailure(it) }
        unmapActive()
        active = mapping
        usedBytes += allocation
        return Segment(index, path, offset, headerBytes, allocation, writtenFrameBytes = 0L)
            .also { segments += it }
    }

    /** True while the archive has room for the smallest frame there can be. Called under [lock]. */
    private fun roomForMinimumFrame(): Boolean {
        val current = segments.lastOrNull()
        if (current != null && active != null && current.remaining >= minimumFrameBytes) return true
        // A roll for the smallest possible frame still allocates a whole segment, and a segment must
        // be big enough to hold that frame — which is why the budget is a floor, not the answer.
        return capacityBytes - usedBytes >= headerBytes + maxOf(segmentFrameBytes, minimumFrameBytes)
    }

    // ── the replay path ───────────────────────────────────────────────────────

    /** True if [scope] cannot possibly select a frame in [view] — segment-granularity pruning. */
    private fun skippable(view: SegmentView, scope: ReplayScope): Boolean =
        scope is ReplayScope.FromOffset && view.baseOffset + view.writtenFrameBytes <= scope.offset

    /**
     * Emit the in-scope frames of one segment's [bytes]; the [Truncated] verdict if it stopped early,
     * else `null`.
     *
     * **A zero-filled remainder is not damage.** Every live segment ends in one, because segments are
     * eagerly pre-allocated, and a segment rolled early for an oversized frame keeps one forever. So a
     * frame that fails to read is checked against what follows it: all zeroes means this segment's
     * written frames are done and replay moves to the next; anything else is a partial write or
     * corruption, and stops the whole replay.
     */
    private suspend fun FlowCollector<ReplayEvent<Op>>.emitFrames(
        bytes: ByteArray,
        view: SegmentView,
        scope: ReplayScope,
    ): Truncated? {
        val buffer = Buffer().apply { write(bytes) }
        // The header on disk is the AUTHORITY on where this segment's frames start, not the in-memory
        // bookkeeping. `check`, not a Truncated, because DAMAGE to that field can no longer reach
        // here — the header's CRC trailer rejects it as torn first — so a disagreement at this point
        // is a bookkeeping bug in this class, which is exactly what an assertion is for.
        val header = readSegmentHeader(buffer, format.opFormat, format.elementType)
            ?: return Truncated(view.baseOffset, TruncationReason.SegmentHeader)
        check(header.baseOffset == view.baseOffset) {
            "segment ${view.path} says its frames start at ${header.baseOffset}, " +
                "bookkeeping says ${view.baseOffset}"
        }
        var offset = header.baseOffset
        while (buffer.size > 0) {
            val before = buffer.size
            val raw = readFrame(buffer, header.formatVersion)
            if (raw == null) {
                val cursor = bytes.size - buffer.size.toInt()
                return if (isZeroFrom(bytes, cursor)) null else Truncated(offset, TruncationReason.Frame)
            }
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

    // ── opening an archive ────────────────────────────────────────────────────

    /**
     * Open the archive if it is not open yet: create [directory], adopt any segments already in it,
     * and map the last one for appending. Called under [lock].
     *
     * A failure is **retried** on the next call, deliberately — the common cause on iOS is a Data
     * Protection class that makes the directory unreadable while the device is locked, and the next
     * unlock resolves it. The one exception is structural damage, which no retry fixes and which
     * therefore [wedged] holds sticky.
     */
    private fun ensureOpen(): BoltAvailability {
        wedged?.let { return it }
        if (opened) return BoltAvailability.Available
        val outcome = openArchive()
        opened = outcome is BoltAvailability.Available
        return outcome
    }

    private fun openArchive(): BoltAvailability {
        val created = NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory.withoutTrailingSlash(),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        if (!created) {
            val code = errno
            val reason = "archive directory $directory is not usable: errno=$code (${errnoText(code)})"
            // EACCES/EPERM is how a Data-Protection-locked device refuses, and the next unlock may
            // resolve it — neither available nor permanently unavailable.
            return if (code == platform.posix.EACCES || code == platform.posix.EPERM) {
                BoltAvailability.Unknown(reason)
            } else {
                BoltAvailability.Unavailable(reason)
            }
        }
        return adoptExistingSegments()
    }

    /**
     * Adopt the segment files already in [directory], deriving the append cursor from them.
     *
     * Each segment's own header carries its `baseOffset`, so every segment but the last has its frame
     * extent for free — it is the next segment's base minus its own. Only the last one has to be
     * scanned frame by frame, and only the last one can have a torn tail, because a crash can only
     * ever interrupt the segment being appended to.
     */
    @Suppress("ReturnCount")
    private fun adoptExistingSegments(): BoltAvailability {
        segments.clear()
        unmapActive()
        nextOffset = 0L
        usedBytes = 0L
        val names = NSFileManager.defaultManager
            .contentsOfDirectoryAtPath(directory.withoutTrailingSlash(), error = null)
            ?: return BoltAvailability.Unavailable("archive directory $directory could not be listed")
        val indices = names.mapNotNull { segmentIndexOf(it as? String ?: return@mapNotNull null) }.sorted()
        if (indices.isEmpty()) return BoltAvailability.Available

        val adopted = mutableListOf<Segment>()
        for (index in indices) {
            val path = directory.withTrailingSlash() + segmentName(index)
            val bytes = readWholeFile(path)
                ?: return wedge("segment $path could not be read")
            val buffer = Buffer().apply { write(bytes) }
            val header = readSegmentHeader(buffer, format.opFormat, format.elementType)
            if (header == null) {
                // A header is always written before any frame, so a segment whose header does not
                // read holds no frame — deleting it loses nothing, and is the only recovery that
                // leaves the archive appendable. Only ever true of the LAST segment for our own
                // writer; a middle one means real corruption, and wedges.
                if (index != indices.last()) return wedge("segment $path has an unreadable header")
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
                repaired = adopted.lastOrNull()?.let { it.baseOffset + it.writtenFrameBytes } ?: 0L
                break
            }
            adopted += Segment(
                index = index,
                path = path,
                baseOffset = header.baseOffset,
                headerBytes = bytes.size - buffer.size.toInt(),
                fileBytes = bytes.size.toLong(),
                writtenFrameBytes = 0L,
            )
            usedBytes += bytes.size
        }
        adopted.forEachIndexed { position, segment ->
            val next = adopted.getOrNull(position + 1)
            if (next != null) segment.writtenFrameBytes = next.baseOffset - segment.baseOffset
        }
        segments += adopted
        val last = adopted.lastOrNull() ?: return BoltAvailability.Available
        return adoptLastSegment(last)
    }

    /** Scan [last]'s frames for the append cursor, repairing a torn tail, then map it. */
    private fun adoptLastSegment(last: Segment): BoltAvailability {
        val bytes = readWholeFile(last.path) ?: return wedge("segment ${last.path} could not be re-read")
        val scan = scanFrameExtent(bytes, last.headerBytes)
        last.writtenFrameBytes = scan.frameBytes
        nextOffset = last.baseOffset + scan.frameBytes
        if (scan.torn) repaired = nextOffset
        val fd = open(last.path, O_RDWR)
        if (fd < 0) return BoltAvailability.Unavailable(posixFailure("could not open segment ${last.path}"))
        val address = mmap(null, last.fileBytes.convert(), PROT_READ or PROT_WRITE, MAP_SHARED, fd, 0)
        if (address == null || address.rawValue.toLong() == MMAP_FAILED) {
            val reason = posixFailure("could not map segment ${last.path}")
            close(fd)
            return BoltAvailability.Unavailable(reason)
        }
        val mapping = Mapping(fd, address.reinterpret(), last.fileBytes)
        active = mapping
        if (scan.torn) {
            // Zero from the last intact frame to the end of the segment, restoring the "intact frames
            // then a zero tail" shape. The discarded bytes were never a committed record: no
            // AppendResult.Written was ever returned for them. See `repairedTailAt`.
            val from = last.headerBytes + scan.frameBytes
            zeroFrom(mapping, from, last.fileBytes - from)
            if (synchronous) syncRange(mapping, from, last.fileBytes - from)
        }
        return BoltAvailability.Available
    }

    /** How many frame bytes of [bytes] are intact, and whether what follows them is damage. */
    private fun scanFrameExtent(bytes: ByteArray, headerBytes: Int): FrameExtent {
        val buffer = Buffer().apply { write(bytes, startIndex = headerBytes) }
        var frameBytes = 0L
        while (buffer.size > 0) {
            val before = buffer.size
            readFrame(buffer, BOLT_FORMAT_VERSION) ?: return FrameExtent(
                frameBytes = frameBytes,
                torn = !isZeroFrom(bytes, bytes.size - buffer.size.toInt()),
            )
            frameBytes += before - buffer.size
        }
        return FrameExtent(frameBytes, torn = false)
    }

    private fun wedge(reason: String): BoltAvailability.Unavailable =
        BoltAvailability.Unavailable("$reason — appending past it would write records no replay can reach")
            .also { wedged = it }

    // ── a damaged archive, for the conformance suite ──────────────────────────

    /**
     * Append a segment file built from raw [bytes] — [headerBytes] of header followed by whatever
     * frame bytes there are, intact or not — whose first frame sits at the current append cursor.
     *
     * **Only a damaged archive needs this, and only a test wants one.** Every path a consumer can
     * reach writes whole frames after a whole header, so [Truncated] is unreachable through the public
     * API — and an unreachable verdict is one nothing asserts, which is how a "stop the whole replay"
     * decision quietly becomes a "skip to the next segment" one. The hook exists so the conformance
     * suite can drive the other branch, exactly as `InMemoryBolt.seedRawSegment` does.
     *
     * The file is written at its exact size with no pre-allocated tail, and is **not** mapped: the
     * active mapping is dropped so a subsequent append rolls a fresh segment rather than writing into
     * a segment this bolt does not believe in.
     */
    internal fun seedRawSegment(bytes: ByteArray, baseOffset: Long, headerBytes: Int): Unit = lock.withLock {
        require(headerBytes in 0..bytes.size) { "headerBytes $headerBytes is not within ${bytes.size} bytes" }
        require(baseOffset == nextOffset) { "a segment seeded at $baseOffset would not follow the cursor $nextOffset" }
        check(ensureOpen() is BoltAvailability.Available) { "cannot seed a segment into an unopened archive" }
        val index = (segments.lastOrNull()?.index ?: -1L) + 1L
        val path = directory.withTrailingSlash() + segmentName(index)
        val fd = open(path, O_RDWR or O_CREAT or O_EXCL, S_IRUSR or S_IWUSR)
        check(fd >= 0) { posixFailure("could not create seeded segment $path") }
        writeAll(fd, bytes, path)
        close(fd)
        unmapActive()
        segments += Segment(
            index = index,
            path = path,
            baseOffset = baseOffset,
            headerBytes = headerBytes,
            fileBytes = bytes.size.toLong(),
            writtenFrameBytes = bytes.size.toLong() - headerBytes,
        )
        usedBytes += bytes.size
        nextOffset += bytes.size - headerBytes
    }

    // ── posix ─────────────────────────────────────────────────────────────────

    /**
     * Fill [fd] with [bytes] zero bytes using a real `write(2)`.
     *
     * **Not `ftruncate`.** `ftruncate` allocates sparsely, deferring the physical blocks to first
     * page-touch — which under a mapping, on a full volume, is a `SIGBUS` that kills the process.
     * Paying for the blocks here turns disk exhaustion into an `ENOSPC` return value at a segment
     * boundary, which is the only shape [AppendResult.Failed] can be built from.
     */
    private fun preallocate(fd: Int, bytes: Long, path: String) {
        val chunk = ByteArray(minOf(bytes, PREALLOCATION_CHUNK_BYTES.toLong()).toInt())
        var remaining = bytes
        chunk.usePinned { pinned ->
            while (remaining > 0) {
                val span = minOf(remaining, chunk.size.toLong())
                val written = write(fd, pinned.addressOf(0), span.convert())
                if (written <= 0) {
                    throw PosixFailure(posixFailure("could not pre-allocate ${bytes}B for segment $path"))
                }
                remaining -= written
            }
        }
    }

    private fun writeAll(fd: Int, bytes: ByteArray, path: String) {
        if (bytes.isEmpty()) return
        var filled = 0
        bytes.usePinned { pinned ->
            while (filled < bytes.size) {
                val written = write(fd, pinned.addressOf(filled), (bytes.size - filled).convert())
                check(written > 0) { posixFailure("could not write ${bytes.size}B to $path") }
                filled += written.toInt()
            }
        }
    }

    private fun copyInto(mapping: Mapping, at: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            memcpy(mapping.address + at, pinned.addressOf(0), bytes.size.convert())
        }
    }

    private fun zeroFrom(mapping: Mapping, at: Long, length: Long) {
        if (length <= 0) return
        memset(mapping.address + at, 0, length.convert())
    }

    /**
     * `msync(MS_SYNC)` the page range covering `[at, at + length)`, or the errno-bearing reason it
     * failed.
     *
     * Page-aligned down because `msync` requires it, and bounded to the frame's own pages rather than
     * the whole mapping because flushing a megabyte per record would make the synchronous mode
     * unusable.
     */
    private fun syncRange(mapping: Mapping, at: Long, length: Long): String? {
        val page = getpagesize().toLong()
        val start = at / page * page
        val span = at - start + length
        val address = mapping.address + start
        return if (msync(address, span.convert(), MS_SYNC) != 0) {
            posixFailure("could not flush ${span}B at $start of the active segment")
        } else {
            null
        }
    }

    private fun unmapActive() {
        val mapping = active ?: return
        active = null
        munmap(mapping.address, mapping.bytes.convert())
        close(mapping.fd)
    }

    /**
     * The whole of [path], or `null` if it could not be read.
     *
     * `read(2)` rather than a mapping: the codec copies the bytes into a `Buffer` regardless, and a
     * mapped read of a file something else truncates is a second `SIGBUS` surface where a `read` just
     * comes up short.
     */
    @Suppress("ReturnCount")
    private fun readWholeFile(path: String): ByteArray? {
        val fd = open(path, O_RDONLY)
        if (fd < 0) return null
        try {
            val size = lseek(fd, 0, SEEK_END)
            if (size < 0 || size > Int.MAX_VALUE || lseek(fd, 0, SEEK_SET) < 0) return null
            if (size == 0L) return ByteArray(0)
            val out = ByteArray(size.toInt())
            var filled = 0
            out.usePinned { pinned ->
                while (filled < out.size) {
                    val n = read(fd, pinned.addressOf(filled), (out.size - filled).convert())
                    if (n <= 0) return null
                    filled += n.toInt()
                }
            }
            return out
        } finally {
            close(fd)
        }
    }

    private fun posixFailure(what: String): String {
        val code = errno
        return "$what: errno=$code (${errnoText(code)})"
    }

    // ── plumbing ──────────────────────────────────────────────────────────────

    private fun segmentName(index: Long): String =
        SEGMENT_PREFIX + index.toString().padStart(SEGMENT_INDEX_DIGITS, '0') + SEGMENT_SUFFIX

    private fun segmentIndexOf(name: String): Long? =
        if (name.startsWith(SEGMENT_PREFIX) && name.endsWith(SEGMENT_SUFFIX)) {
            name.removePrefix(SEGMENT_PREFIX).removeSuffix(SEGMENT_SUFFIX).toLongOrNull()
        } else {
            null
        }

    /** One segment file. [writtenFrameBytes] is the only mutable field, and it moves only under [lock]. */
    private class Segment(
        val index: Long,
        val path: String,
        val baseOffset: Long,
        val headerBytes: Int,
        val fileBytes: Long,
        var writtenFrameBytes: Long,
    ) {
        /** Frame bytes still free in this segment's pre-allocated region. */
        val remaining: Long get() = fileBytes - headerBytes - writtenFrameBytes
    }

    private class SegmentView(val path: String, val baseOffset: Long, val writtenFrameBytes: Long)

    private class Mapping(val fd: Int, val address: CPointer<ByteVar>, val bytes: Long)

    private class FrameExtent(val frameBytes: Long, val torn: Boolean)

    /** A failing posix call, carrying the errno-bearing text an [AppendResult.Failed] needs. */
    private class PosixFailure(val reason: String) : Exception(reason)

    public companion object {
        /** 1 MiB of frames per segment — small enough to bound a mapping, large enough that rolls are rare. */
        public const val DEFAULT_SEGMENT_FRAME_BYTES: Long = 1L shl 20

        private const val SEGMENT_PREFIX = "segment-"
        private const val SEGMENT_SUFFIX = ".bolt"
        private const val SEGMENT_INDEX_DIGITS = 16
        private const val PREALLOCATION_CHUNK_BYTES = 1 shl 16

        /** `mmap` reports failure as `(void *) -1`, which is what `MAP_FAILED` expands to. */
        private const val MMAP_FAILED = -1L
    }
}

private fun BoltAvailability.reasonOrBlank(): String = when (this) {
    BoltAvailability.Available -> ""
    is BoltAvailability.Unavailable -> reason
    is BoltAvailability.Unknown -> reason
}

private fun String.withTrailingSlash(): String = if (endsWith("/")) this else "$this/"

private fun String.withoutTrailingSlash(): String = trimEnd('/')

/** True when every byte of [bytes] from [from] on is zero — an unwritten, pre-allocated region. */
private fun isZeroFrom(bytes: ByteArray, from: Int): Boolean {
    for (index in from until bytes.size) if (bytes[index] != ZERO_BYTE) return false
    return true
}

private const val ZERO_BYTE: Byte = 0

/**
 * Render an `errno` as its human-readable `strerror` text.
 *
 * `strerror_r`, not `strerror`: the latter may format an unrecognised code into a shared static
 * buffer, and a cause another thread can garble is exactly the kind of untrustworthy diagnostic this
 * module's report-identities rule exists to prevent.
 */
private fun errnoText(code: Int): String = memScoped {
    val buffer = allocArray<ByteVar>(STRERROR_TEXT_BYTES)
    if (strerror_r(code, buffer, STRERROR_TEXT_BYTES.convert()) == 0) buffer.toKString() else "unknown"
}

private const val STRERROR_TEXT_BYTES = 256
