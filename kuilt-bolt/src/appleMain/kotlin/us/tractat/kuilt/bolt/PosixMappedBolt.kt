@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.bolt

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.io.Buffer
import platform.Foundation.NSFileManager
import platform.posix.EACCES
import platform.posix.EEXIST
import platform.posix.EFBIG
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.EPERM
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
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.errno
import platform.posix.getpagesize
import platform.posix.lseek
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.mkdir
import platform.posix.mmap
import platform.posix.msync
import platform.posix.munmap
import platform.posix.read
import platform.posix.stat
import platform.posix.strerror_r
import platform.posix.unlink
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
 *    could be deleted. Do not "simplify" either; `PosixMappedBoltTest.aPreAllocatedTailReplaysCleanAndNeverAsPhantomFrames`
 *    pins the pair.
 *
 *    Because a zero tail is *expected* rather than damage, replay treats it as the end of a segment's
 *    written frames and moves on — but **only where the bookkeeping says the frames end**. That
 *    second half is load-bearing and not obvious: an un-flushed region and a pre-allocated one read
 *    back identically, so the byte predicate alone cannot tell "nothing was ever written here" from
 *    "this never reached disk", and without the extent check a replay walks past a hole and reports
 *    a clean history whose offsets jump. See [emitFrames].
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
 *    set `NSFileProtectionNone` on [directory] itself and accept what it means.
 *
 *    Where that surfaces is worth stating exactly, because the obvious answer is wrong. In the steady
 *    state the archive directory already exists, so `mkdir` returns `EEXIST` and the directory's own
 *    metadata reads fine — the refusal never reaches the directory check, and appears one level
 *    deeper, when a segment file cannot be opened. An `EACCES`/`EPERM` from **either** place is
 *    reported as [BoltAvailability.Unknown] rather than [BoltAvailability.Unavailable]: the state is
 *    neither available nor permanently unavailable, the next unlock may resolve it, and the bolt
 *    retries rather than latching. `chmod 000` on a segment file reproduces the shape, which is what
 *    `aTransientReadFailureIsRetriedRatherThanWedging` does — but **a locked device is still not
 *    something this repo's tests can produce**, and no test here should be read as evidence that the
 *    protection class itself behaves as described.
 *
 * ### Durability is `msync`, and it is one flag
 *
 * A `memcpy` into a mapping publishes bytes to every reader of the file immediately, but does not
 * make them durable. [synchronous] is the whole difference between the two modes this module
 * describes: `true` calls `msync(MS_SYNC)` over the frame's page range before [append] returns;
 * `false` lets the OS flush when it likes. One type, one flag — two implementations would be two
 * things to keep in agreement.
 *
 * If that `msync` fails the append still reports [AppendResult.Written], because the frame **is** in
 * the archive: it is whole, CRC-valid and visible to every reader of the file. What failed is the
 * durability upgrade, not the append, and saying otherwise costs records — [AppendResult.Failed] means
 * "the ops are lost" and invites the consumer to re-feed them, which would write a second copy of a
 * record already on disk. So a `synchronous` bolt whose volume refuses to flush degrades to the
 * asynchronous guarantee rather than to a lie in either direction, and this paragraph is where that
 * is admitted. Nothing in this repo's tests reaches it — `msync` fails on dying hardware.
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

    /**
     * Why the most recent segment roll was refused, or `null` if none was.
     *
     * [availability] consults it because the byte budget is not the authority on whether a segment
     * can be allocated — the volume is. A full disk, a read-only remount or a revoked sandbox
     * extension all leave `capacityBytes - usedBytes` looking roomy while every roll fails, and
     * "Available means an append will be accepted" is the whole content of that signal. Cleared by
     * the next roll that succeeds, so recovery needs no intervention.
     */
    private var lastRollFailure: String? = null

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
     *
     * A **trailing** segment with an unreadable header is removed rather than repaired, and does not
     * set this: a header is written before any frame is, so such a file holds no record and nothing
     * is discarded when it goes.
     *
     * **Reading this opens the archive**, exactly as [availability] does. An archive is adopted
     * lazily — the first append, replay or probe is what reads the directory — so a property that
     * merely reported the current field would answer `null` on a freshly constructed bolt over a
     * damaged archive, which is the one moment a consumer actually asks.
     *
     * A function rather than a `val` **because** of that: this takes a lock, creates directories,
     * maps a file, mutates six fields and can throw [BoltFormatException]. A property that reads like
     * a field but does all of that is a trap at every call site.
     */
    public fun repairedTailAt(): Long? = lock.withLock {
        ensureOpen()
        repaired
    }

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
        val current = segments.lastOrNull()
        when {
            open !is BoltAvailability.Available -> open
            current != null && active != null && current.remaining >= minimumFrameBytes ->
                BoltAvailability.Available
            // The next frame needs a NEW segment, and the last attempt to allocate one was refused.
            // The budget says there is room; the volume said otherwise, and the volume decides.
            lastRollFailure != null -> BoltAvailability.Unavailable(checkNotNull(lastRollFailure))
            // A roll for the smallest possible frame still allocates a whole segment, and a segment
            // must be big enough to hold that frame — so the budget is a floor, not the answer.
            capacityBytes - usedBytes >= headerBytes + maxOf(segmentFrameBytes, minimumFrameBytes) ->
                BoltAvailability.Available
            else -> BoltAvailability.Unavailable(
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
        // Nothing is EMITTED under the lock — `emit` suspends for as long as the collector takes, and
        // holding a mutex across that would stall every append behind a slow reader. So the locked
        // section only decides, and the emitting happens after it.
        val opened = lock.withLock {
            // Opening is what ADOPTS an archive already on disk, so a consumer that only ever reads —
            // never appends — must still go through it, or it would replay an empty archive over a
            // directory full of frames.
            val usable = ensureOpen() is BoltAvailability.Available
            Opened(
                views = if (usable) segments.map { SegmentView(it.path, it.baseOffset, it.writtenFrameBytes) } else null,
                cursor = nextOffset,
            )
        }
        // An archive that cannot be opened replays as Truncated at the start rather than as a clean
        // empty one: "I could not read it" and "it holds nothing" are different answers, and only one
        // of them is true.
        val views = opened.views ?: return@flow emit(Truncated(opened.cursor, TruncationReason.SegmentHeader))
        for (view in views) {
            if (skippable(view, scope)) continue
            val bytes = try {
                readFile(view.path).bytes
            } catch (failure: PosixFailure) {
                // A segment this replay cannot read is a replay that stopped, not an archive that is
                // empty — and the verdict is what says so.
                emit(Truncated(view.baseOffset, TruncationReason.SegmentHeader))
                return@flow
            }
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
     * flush one. Idempotent; the bolt re-opens on the next [append], and that re-open is a full
     * reset: every remembered failure, [wedged] included, is re-derived from what is on disk rather
     * than carried across. A closed-and-reopened bolt and a freshly constructed one over the same
     * directory must reach the same verdict, and before this they did not.
     */
    public fun close(): Unit = lock.withLock {
        unmapActive()
        opened = false
        wedged = null
        lastRollFailure = null
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
                // Remembered so `availability` stops claiming Available on the strength of a byte
                // budget the volume does not honour.
                lastRollFailure = failure.reason
                return AppendResult.Failed(failure.reason, insertDots, offset, cause = failure)
            }
        }
        return commit(segment, frame, offset, opCount, insertDots)
    }

    /**
     * `memcpy` [frame] into the active mapping, flush it if [synchronous], and advance the cursor.
     *
     * Always [AppendResult.Written] once the copy lands: see the note below on why a failed flush is
     * not a failed append.
     */
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
        // A failed flush does NOT make this a failed append, and the earlier version that returned
        // `AppendResult.Failed` here was wrong in a way that costs records. The frame is in the
        // archive — a memcpy into a MAP_SHARED mapping is visible to every reader of the file
        // immediately, and it is whole and CRC-valid — so `Failed`, whose contract is "the ops are
        // lost from the archive" and whose remedy is to re-feed them, would have a consumer write a
        // second copy of a record that is already there. Between a consumer that believes a present
        // frame is durable when it may not be, and a consumer that duplicates every record on a
        // failing disk, the first is strictly the smaller harm — and best-effort is this module's
        // stated posture anyway.
        //
        // What is genuinely lost is the DURABILITY upgrade [synchronous] promises, and that is
        // documented on the parameter rather than smuggled into a result type that means something
        // else. `msync` fails on a dying volume, so no test here reaches this branch.
        if (synchronous) syncRange(mapping, at, frame.size.toLong())
        return AppendResult.Written(offset, nextOffset, opCount, insertDots)
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
        val fd = createSegmentFile(path)
        var mapping: Mapping? = null
        try {
            preallocate(fd, allocation, path)
            val address = mmap(null, allocation.convert(), PROT_READ or PROT_WRITE, MAP_SHARED, fd, 0)
            if (address == null || address.rawValue.toLong() == MMAP_FAILED) {
                throw posixFailure("could not map segment $path")
            }
            val mapped = Mapping(fd, address.reinterpret(), allocation)
            mapping = mapped
            val header = encodeSegmentHeader(
                SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset = offset),
            )
            copyInto(mapped, 0L, header)
            if (synchronous) syncRange(mapped, 0L, header.size.toLong())?.let { throw it }
        } catch (failure: PosixFailure) {
            // Everything is undone before the failure escapes. The OLD active segment stays mapped
            // and appendable, so a refused roll is survivable rather than the end of this bolt's
            // life — and the half-built file is UNLINKED rather than left behind. Leaving it was a
            // permanent wedge: the next roll recomputes the same index, `O_EXCL` fails EEXIST, and
            // since this instance never re-opens, every subsequent append fails forever. The file
            // holds no frame, so unlinking it can lose nothing.
            mapping?.let { munmap(it.address, it.bytes.convert()) }
            platform.posix.close(fd)
            unlink(path)
            throw failure
        }
        val live = checkNotNull(mapping) { "a segment rolled without failing must have a mapping" }
        unmapActive()
        active = live
        usedBytes += allocation
        lastRollFailure = null
        return Segment(index, path, offset, BOLT_FORMAT_VERSION, headerBytes, allocation, writtenFrameBytes = 0L)
            .also { segments += it }
    }

    /**
     * `open(O_CREAT|O_EXCL)` [path], discarding an orphan already sitting there.
     *
     * `O_EXCL` is deliberate — a segment file is never silently overwritten. But an orphan at the
     * index the next roll wants is a *guaranteed* dead end otherwise, and it is reachable two ways:
     * a roll of ours that failed after creating the file (which the caller now unlinks, but whose
     * unlink can itself fail), and a process killed between this `open` and the header write.
     *
     * Discarding it is safe by construction rather than by hope. The index asked for here is one
     * past every segment this bolt knows about, and adoption knows about every index carrying a
     * readable header — so a file at this index has no readable header, and a header is written
     * before any frame is. It therefore holds no record.
     */
    private fun createSegmentFile(path: String): Int {
        val fd = platform.posix.open(path, O_RDWR or O_CREAT or O_EXCL, S_IRUSR or S_IWUSR)
        if (fd >= 0) return fd
        val failure = posixFailure("could not create segment $path")
        if (failure.code != EEXIST) throw failure
        unlink(path)
        val replaced = platform.posix.open(path, O_RDWR or O_CREAT or O_EXCL, S_IRUSR or S_IWUSR)
        if (replaced < 0) throw posixFailure("could not replace the orphaned segment $path")
        return replaced
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
                // A zero run is an unwritten PRE-ALLOCATED tail only if it begins where this
                // segment's frames were recorded to end. Short of that, identical bytes mean a
                // region that never reached disk — pre-allocation writes real zeroes, so the two
                // are indistinguishable by inspection — and continuing would hand back a history
                // with a hole in it and offsets that jump.
                //
                // `>=`, not `==`: a replay collected while an append lands legitimately reads the
                // NEW frame too, so the cursor can sit PAST the extent this replay snapshotted.
                // Only stopping SHORT of it is damage.
                val reachedRecordedExtent = offset >= view.baseOffset + view.writtenFrameBytes
                return if (isZeroFrom(bytes, cursor) && reachedRecordedExtent) {
                    null
                } else {
                    Truncated(offset, TruncationReason.Frame)
                }
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
     * Protection class that makes the archive unreadable while the device is locked, and the next
     * unlock resolves it. The one exception is **structural damage**, which no retry fixes and which
     * therefore [wedged] holds sticky: a segment in the middle of the archive whose header will not
     * read, where appending past it would write records no replay could ever reach.
     *
     * An unreadable *file* is not that, and used to be treated as though it were — which turned a
     * condition that clears on its own into a permanent one, on the exact path this paragraph
     * promises is retried.
     */
    private fun ensureOpen(): BoltAvailability {
        wedged?.let { return it }
        if (opened) return BoltAvailability.Available
        val outcome = openArchive()
        opened = outcome is BoltAvailability.Available
        return outcome
    }

    private fun openArchive(): BoltAvailability {
        val code = makeDirectories(directory.withoutTrailingSlash())
        if (code != null) {
            val reason = "archive directory $directory is not usable: errno=$code (${errnoText(code)})"
            // EACCES/EPERM is how a Data-Protection-locked device refuses, and the next unlock may
            // resolve it — neither available nor permanently unavailable. Unreachable off real
            // hardware, so nothing in this repo's suite covers this branch.
            return if (code == EACCES || code == EPERM) {
                BoltAvailability.Unknown(reason)
            } else {
                BoltAvailability.Unavailable(reason)
            }
        }
        return adoptExistingSegments()
    }

    /**
     * Create [path] and any missing parents, returning the `errno` that stopped it or `null`.
     *
     * `mkdir(2)` rather than `NSFileManager.createDirectoryAtPath`, and the reason is the whole point
     * of this class's failure reporting: Foundation reports an `NSError` and makes **no promise about
     * `errno`**, so reading `errno` after a failed Foundation call yields whatever the last unrelated
     * syscall left there. A cause that is merely plausible is worse than none.
     */
    private fun makeDirectories(path: String): Int? {
        if (path.isEmpty() || path == "/") return null
        if (mkdir(path, DIRECTORY_MODE.convert()) == 0) return null
        val code = errno
        if (code == EEXIST) return directoryFault(path)
        if (code != ENOENT) return code
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isEmpty()) return code
        makeDirectories(parent)?.let { return it }
        return if (mkdir(path, DIRECTORY_MODE.convert()) == 0) null else errno
    }

    /**
     * `null` if [path] is a directory, else the errno that says why it is not usable as one.
     *
     * A boolean was not enough, and the gap was the one thing choosing `mkdir(2)` over Foundation was
     * meant to prevent: "exists but is not a directory" and "`stat` itself failed" both read as
     * `false`, so a permission problem or a symlink loop was reported as `ENOTDIR` — a fabricated
     * cause, which is worse than none because a reader stops looking.
     */
    private fun directoryFault(path: String): Int? = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) return errno
        if (info.st_mode.toInt() and S_IFMT == S_IFDIR) null else ENOTDIR
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
        // A re-open re-derives everything, this included: a repair reported from a PREVIOUS open of
        // the same bolt would be a claim about bytes that are no longer there.
        repaired = null
        val names = NSFileManager.defaultManager
            .contentsOfDirectoryAtPath(directory.withoutTrailingSlash(), error = null)
            ?: return BoltAvailability.Unavailable("archive directory $directory could not be listed")
        val indices = names.mapNotNull { segmentIndexOf(it as? String ?: return@mapNotNull null) }.sorted()
        if (indices.isEmpty()) return BoltAvailability.Available
        return try {
            adoptSegments(indices)
        } catch (failure: PosixFailure) {
            // NOT the sticky wedge, and that distinction is the whole fix. A file that cannot be
            // READ is an I/O condition — a Data-Protection-locked device, a descriptor limit, an
            // interrupted read — and the archive behind it is intact. Wedging here made a condition
            // that clears on its own permanently disable archiving on this instance, which is the
            // outcome the tail repair was chosen to avoid, reached by an unrelated route.
            //
            // EACCES/EPERM is the locked-device shape specifically: neither available nor
            // permanently unavailable, which is what Unknown exists to say.
            if (failure.code == EACCES || failure.code == EPERM) {
                BoltAvailability.Unknown(failure.reason)
            } else {
                BoltAvailability.Unavailable(failure.reason)
            }
        }
    }

    /**
     * Adopt each segment named by [indices], newest last. Throws [PosixFailure] if a file cannot be
     * read; returns the wedge only for damage no retry can fix.
     *
     * **Only the last segment is read whole.** Every other one carries its `baseOffset` in its own
     * header, and its frame extent is simply the next segment's base minus its own — so a bounded
     * probe of the first page tells adoption everything it needs. That matters at both ends of this
     * backend's range: a server with a year of 1 MiB segments would otherwise read the entire archive
     * on every open, and a phone would allocate and discard a megabyte per segment inside a class
     * whose whole premise is bounding the resident footprint.
     */
    private fun adoptSegments(indices: List<Long>): BoltAvailability {
        val adopted = mutableListOf<Segment>()
        var lastBytes: ByteArray? = null
        for (index in indices) {
            val path = directory.withTrailingSlash() + segmentName(index)
            val whole = index == indices.last()
            val file = readFile(path, if (whole) Long.MAX_VALUE else HEADER_PROBE_BYTES)
            val bytes = file.bytes
            val buffer = Buffer().apply { write(bytes) }
            val header = readSegmentHeader(buffer, format.opFormat, format.elementType)
            if (header == null) {
                // A header is always written before any frame, so a segment whose header does not
                // read holds no frame — deleting it loses nothing, and is the only recovery that
                // leaves the archive appendable. Only ever true of the LAST segment for our own
                // writer; a middle one means real corruption, and wedges.
                if (!whole) return wedge("segment $path has an unreadable header")
                // NOT a repaired tail, and `repaired` is deliberately left alone. Nothing was
                // discarded: a header goes down before any frame does, so a segment whose header
                // does not read holds no record at all. Reporting an offset here would tell a
                // consumer the archive was CUT at that point — and reporting the 0 this line used
                // to compute (every adopted segment still has writtenFrameBytes = 0 at this point,
                // they are filled in below) would tell it the whole archive was thrown away, with
                // every frame still sitting there readable.
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
                break
            }
            adopted += Segment(
                index = index,
                path = path,
                baseOffset = header.baseOffset,
                formatVersion = header.formatVersion,
                headerBytes = bytes.size - buffer.size.toInt(),
                fileBytes = file.fileBytes,
                writtenFrameBytes = 0L,
            )
            usedBytes += file.fileBytes
            if (whole) lastBytes = bytes
        }
        adopted.forEachIndexed { position, segment ->
            val next = adopted.getOrNull(position + 1)
            if (next != null) segment.writtenFrameBytes = next.baseOffset - segment.baseOffset
        }
        segments += adopted
        val last = adopted.lastOrNull() ?: return BoltAvailability.Available
        // `lastBytes` is null exactly when the final index was discarded for having no header, so
        // the segment now at the end was only probed and has to be read whole after all.
        adoptLastSegment(last, lastBytes ?: readFile(last.path).bytes)
        return BoltAvailability.Available
    }

    /**
     * Scan [bytes] for [last]'s append cursor, repair a torn tail, then map it for appending.
     *
     * [bytes] is passed in rather than re-read: adoption has already read this file whole, and
     * reading it twice doubled the cost of every open for nothing.
     */
    private fun adoptLastSegment(last: Segment, bytes: ByteArray) {
        val scan = scanFrameExtent(bytes, last.headerBytes, last.formatVersion)
        last.writtenFrameBytes = scan.frameBytes
        nextOffset = last.baseOffset + scan.frameBytes
        if (scan.torn) repaired = nextOffset
        val fd = platform.posix.open(last.path, O_RDWR)
        if (fd < 0) throw posixFailure("could not open segment ${last.path}")
        val address = mmap(null, last.fileBytes.convert(), PROT_READ or PROT_WRITE, MAP_SHARED, fd, 0)
        if (address == null || address.rawValue.toLong() == MMAP_FAILED) {
            val failure = posixFailure("could not map segment ${last.path}")
            platform.posix.close(fd)
            throw failure
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
    }

    /** How many frame bytes of [bytes] are intact, and whether what follows them is damage. */
    private fun scanFrameExtent(bytes: ByteArray, headerBytes: Int, formatVersion: Int): FrameExtent {
        val buffer = Buffer().apply { write(bytes, startIndex = headerBytes) }
        var frameBytes = 0L
        while (buffer.size > 0) {
            val before = buffer.size
            // The SEGMENT's declared version, never this build's: a later build adopting an earlier
            // archive has to read that archive's frames with that archive's reader.
            readFrame(buffer, formatVersion) ?: return FrameExtent(
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
        val fd = platform.posix.open(path, O_RDWR or O_CREAT or O_EXCL, S_IRUSR or S_IWUSR)
        check(fd >= 0) { posixFailure("could not create seeded segment $path").reason }
        try {
            writeAll(fd, bytes, path)
        } finally {
            platform.posix.close(fd)
        }
        unmapActive()
        segments += Segment(
            index = index,
            path = path,
            baseOffset = baseOffset,
            formatVersion = BOLT_FORMAT_VERSION,
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
                if (written <= 0) throw posixFailure("could not pre-allocate ${bytes}B for segment $path")
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
                check(written > 0) { posixFailure("could not write ${bytes.size}B to $path").reason }
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
    private fun syncRange(mapping: Mapping, at: Long, length: Long): PosixFailure? {
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
        platform.posix.close(mapping.fd)
    }

    /**
     * The first [limit] bytes of [path], with the file's true size alongside them.
     *
     * `read(2)` rather than a mapping: the codec copies the bytes into a `Buffer` regardless, and a
     * mapped read of a file something else truncates is a second `SIGBUS` surface where a `read` just
     * comes up short.
     *
     * **Failures throw [PosixFailure] rather than returning `null`.** A bare `null` conflated every
     * reason a file might not open — a locked device, a descriptor limit, an interrupted read, real
     * corruption — and the caller, having nothing to tell them apart by, treated all of them as
     * permanent damage. It also discarded the errno, in a class whose whole failure-reporting rule is
     * to name identities and state rather than report that something went wrong.
     */
    private fun readFile(path: String, limit: Long = Long.MAX_VALUE): FileBytes {
        val fd = platform.posix.open(path, O_RDONLY)
        if (fd < 0) throw posixFailure("could not open segment $path")
        try {
            val size = lseek(fd, 0, SEEK_END)
            if (size < 0) throw posixFailure("could not size segment $path")
            if (lseek(fd, 0, SEEK_SET) < 0) throw posixFailure("could not rewind segment $path")
            val wanted = minOf(size, limit)
            if (wanted > Int.MAX_VALUE) {
                throw PosixFailure("segment $path is ${size}B, more than one read can carry", EFBIG)
            }
            val out = ByteArray(wanted.toInt())
            var filled = 0
            out.usePinned { pinned ->
                while (filled < out.size) {
                    val n = read(fd, pinned.addressOf(filled), (out.size - filled).convert())
                    if (n < 0) throw posixFailure("could not read segment $path")
                    // A short file is not a failure here — the parse downstream decides what a
                    // segment with too few bytes in it means.
                    if (n == 0L) break
                    filled += n.toInt()
                }
            }
            return FileBytes(if (filled == out.size) out else out.copyOf(filled), size)
        } finally {
            platform.posix.close(fd)
        }
    }

    /**
     * The failure the *most recent* posix call left behind.
     *
     * `errno` is read once, first: building the message calls `strerror_r`, and reading `errno`
     * afterwards would report whatever that left there rather than what actually failed.
     */
    private fun posixFailure(what: String): PosixFailure {
        val code = errno
        return PosixFailure("$what: errno=$code (${errnoText(code)})", code)
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
        val formatVersion: Int,
        val headerBytes: Int,
        val fileBytes: Long,
        var writtenFrameBytes: Long,
    ) {
        /** Frame bytes still free in this segment's pre-allocated region. */
        val remaining: Long get() = fileBytes - headerBytes - writtenFrameBytes
    }

    private class SegmentView(val path: String, val baseOffset: Long, val writtenFrameBytes: Long)

    /** What one locked look at the archive tells [replay]: the segments to read, or why there are none. */
    private class Opened(val views: List<SegmentView>?, val cursor: Long)

    private class Mapping(val fd: Int, val address: CPointer<ByteVar>, val bytes: Long)

    private class FrameExtent(val frameBytes: Long, val torn: Boolean)

    /** What [readFile] read, and how big the file actually is — the two differ under a bounded probe. */
    private class FileBytes(val bytes: ByteArray, val fileBytes: Long)

    /**
     * A failing posix call, carrying both the errno-bearing text an [AppendResult.Failed] needs and
     * the raw [code], which is what lets a caller tell a transient refusal from a permanent one.
     */
    private class PosixFailure(val reason: String, val code: Int) : Exception(reason)

    public companion object {
        /** 1 MiB of frames per segment — small enough to bound a mapping, large enough that rolls are rare. */
        public const val DEFAULT_SEGMENT_FRAME_BYTES: Long = 1L shl 20

        private const val SEGMENT_PREFIX = "segment-"
        private const val SEGMENT_SUFFIX = ".bolt"
        private const val SEGMENT_INDEX_DIGITS = 16
        private const val PREALLOCATION_CHUNK_BYTES = 1 shl 16

        /** One page: far more than any plausible segment header, and O(1) per segment at open. */
        private const val HEADER_PROBE_BYTES = 4096L

        /** `rwx------`: an archive is the owning application's business and nobody else's. */
        private const val DIRECTORY_MODE = 448 // 0o700

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
