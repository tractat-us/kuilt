@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.bolt

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDWR
import platform.posix.SEEK_SET
import platform.posix.chmod
import platform.posix.close
import platform.posix.lseek
import platform.posix.open
import platform.posix.stat
import platform.posix.write
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The hazards [PosixMappedBolt] has that no other backend does — the ones the shared
 * [BoltConformanceSuite] cannot ask about because they are about *files*.
 *
 * The Data Protection branch is deliberately absent: it fires only on a locked physical device, so
 * nothing here should be read as evidence that it works.
 */
class PosixMappedBoltTest {

    private val alice = ReplicaId("alice")
    private val epoch = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    /**
     * A segment must be **physically** allocated at roll time, not sparsely reserved.
     *
     * This is the hazard that would otherwise make the module's failure posture unachievable rather
     * than merely wrong: `ftruncate` reserves the extent without committing blocks, so the first
     * page-touch on a full volume raises `SIGBUS` — a signal, not an exception — and the process dies
     * taking the application's logging with it. Paying for the blocks up front turns that into an
     * `ENOSPC` return value at a segment boundary, which is the only shape an [AppendResult.Failed]
     * can be built from.
     *
     * `st_blocks` is what distinguishes the two, and it is the *only* thing that does: an
     * `ftruncate`d file has the same `st_size` and reads back the same zeroes. A sparse segment
     * reports far fewer allocated bytes than its size; a written one reports at least its size.
     */
    @Test
    fun eachSegmentIsPhysicallyAllocatedRatherThanSparse() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = boltTestDirectory()
        val bolt = PosixMappedBolt(rgaArchiveFormat(), FixedClock(epoch), directory, segmentFrameBytes = SEGMENT_BYTES)
        val (_, ops) = Rga.empty<String>().insertAt(alice, 0, "one")

        val written = bolt.append(listOf(ops))
        val files = segmentFiles(directory)
        val size = files.singleOrNull()?.let { fileSize(it) } ?: -1L
        val allocated = files.singleOrNull()?.let { allocatedBytes(it) } ?: -1L

        assertAll(
            { assertIs<AppendResult.Written>(written, "the append must land") },
            { assertEquals(1, files.size, "one append, one segment file") },
            { assertTrue(size >= SEGMENT_BYTES, "the whole segment is allocated up front, not grown per frame") },
            {
                assertTrue(
                    allocated >= size,
                    "a segment must be PHYSICALLY allocated: st_blocks reported ${allocated}B behind a " +
                        "${size}B file, which is what an ftruncate'd sparse segment looks like — and a " +
                        "sparse segment's first page-touch on a full volume is a SIGBUS, not a failure " +
                        "this class can report",
                )
            },
        )
    }

    /**
     * The other half of hazard 1, and the subtle one: pre-allocation writes **zeroes**, and a zero run
     * must never read as a frame.
     *
     * Two independent barriers in the shared codec stop it, and **neither is redundant**: the frame
     * CRC covers the length prefix as well as the body (so a zero prefix checksums to `0x2144DF1C`
     * rather than to the zero stored beside it), and `MINIMUM_BODY_BYTES` rejects a body too short to
     * be one this codec wrote. Mutating **either one alone leaves the other refusing the zero run**,
     * so a single-mutation table would report both as deletable. The receipt for this test is
     * therefore a *compound* mutation — see the PR body.
     *
     * The assertion that discriminates is the frame **count** together with the [CleanTail]: with both
     * barriers gone the zero tail decodes as an unbounded run of empty frames and the replay walks
     * into a body that is not there.
     */
    @Test
    fun aPreAllocatedTailReplaysCleanAndNeverAsPhantomFrames() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = boltTestDirectory()
        val bolt = PosixMappedBolt(rgaArchiveFormat(), FixedClock(epoch), directory, segmentFrameBytes = SEGMENT_BYTES)
        val (afterFirst, first) = Rga.empty<String>().insertAt(alice, 0, "first")
        val (_, second) = afterFirst.insertAt(alice, 1, "second")

        val one = bolt.append(listOf(first))
        val two = bolt.append(listOf(second))
        val events = bolt.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()
        val writtenFrameBytes = assertIs<AppendResult.Written>(two).endOffset

        assertAll(
            { assertIs<AppendResult.Written>(one, "the first append must land") },
            {
                assertTrue(
                    fileSize(segmentFiles(directory).single()) - writtenFrameBytes > SEGMENT_BYTES / 2,
                    "the fixture is only meaningful if a large zero-filled tail really follows the frames",
                )
            },
            { assertEquals(2, frames.size, "exactly the frames appended — a zero tail is not a frame") },
            { assertEquals(CleanTail, events.lastOrNull(), "an unwritten pre-allocated tail is not damage") },
            { assertEquals(1, events.count { it !is Archived<*> }, "exactly one terminal event, never two") },
            { assertEquals(listOf(listOf(first), listOf(second)), frames.map { it.ops }, "and they round-trip") },
        )
    }

    /**
     * A second bolt over the same directory picks the archive up where the first left it — the
     * property a *disk-backed* archive exists for, and one no in-memory backend can have.
     *
     * The reopened bolt appends into the segment that is already there rather than rolling a fresh
     * one, so this also pins that the append cursor is recovered from the **bytes**, not from
     * bookkeeping that died with the first instance.
     */
    @Test
    fun anArchiveReopensWhereItLeftOff() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = boltTestDirectory()
        val format = rgaArchiveFormat()
        val (afterFirst, first) = Rga.empty<String>().insertAt(alice, 0, "first")
        val (afterSecond, second) = afterFirst.insertAt(alice, 1, "second")
        val (_, third) = afterSecond.insertAt(alice, 2, "third")

        val opened = PosixMappedBolt(format, FixedClock(epoch), directory)
        opened.append(listOf(first))
        val closing = opened.append(listOf(second))
        opened.close()

        val reopened = PosixMappedBolt(format, FixedClock(epoch), directory)
        val resumed = reopened.append(listOf(third))
        val events = reopened.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()

        assertAll(
            { assertNull(reopened.repairedTailAt(), "an intact archive is adopted without repair") },
            { assertEquals(3, frames.size, "every frame the first instance wrote, plus the new one") },
            {
                assertEquals(
                    assertIs<AppendResult.Written>(closing).endOffset,
                    assertIs<AppendResult.Written>(resumed).offset,
                    "the reopened archive appends exactly where the first instance stopped",
                )
            },
            { assertEquals(1, segmentFiles(directory).size, "and into the same segment, not a fresh one") },
            {
                assertEquals(
                    listOf(listOf(first), listOf(second), listOf(third)),
                    frames.map { it.ops },
                    "in order, across the restart",
                )
            },
            { assertEquals(CleanTail, events.lastOrNull(), "the archive is intact") },
        )
    }

    /**
     * A crash mid-append — a jetsam kill, a power loss — leaves a partial frame at the tail. Opening
     * the archive discards it, **says where**, and keeps taking appends.
     *
     * That the repair is right rather than merely convenient rests on one fact: the torn frame was
     * never acknowledged. No [AppendResult.Written] was ever returned for it, so no committed record
     * depends on it, and zeroing it back restores the "intact frames then a zero tail" shape every
     * other path assumes. The alternative — wedge until a human intervenes — would permanently
     * disable archiving on a phone, where a mid-append kill is routine.
     *
     * `repairedTailAt` is what keeps that from being *silent*, which is the line this module draws
     * everywhere else too.
     */
    @Test
    fun aTornTailIsDiscardedOnReopenAndSaysWhere() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = boltTestDirectory()
        val format = rgaArchiveFormat()
        val (afterFirst, first) = Rga.empty<String>().insertAt(alice, 0, "first")
        val (afterSecond, second) = afterFirst.insertAt(alice, 1, "second")
        val (_, third) = afterSecond.insertAt(alice, 2, "third")

        val opened = PosixMappedBolt(format, FixedClock(epoch), directory)
        opened.append(listOf(first))
        val lastIntact = assertIs<AppendResult.Written>(opened.append(listOf(second)))
        opened.close()
        // A real frame, minus its last byte, exactly where the next append would have landed: the
        // shape a kill between the memcpy's first and last page leaves behind.
        val torn = encodeFrame(RawFrame(epoch, emptySet(), null, listOf(format.encode(third))))
        scribble(segmentFiles(directory).single(), headerBytesOf(format) + lastIntact.endOffset, torn.dropLast(1))

        val reopened = PosixMappedBolt(format, FixedClock(epoch), directory)
        val repaired = reopened.repairedTailAt()
        val resumed = reopened.append(listOf(third))
        val events = reopened.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()

        assertAll(
            { assertEquals(lastIntact.endOffset, repaired, "the repair names the offset the torn tail was cut at") },
            { assertEquals(3, frames.size, "the intact prefix survives, and the archive keeps taking appends") },
            {
                assertEquals(
                    lastIntact.endOffset,
                    assertIs<AppendResult.Written>(resumed).offset,
                    "the next frame lands exactly where the torn one started — no hole, no jump",
                )
            },
            { assertEquals(CleanTail, events.lastOrNull(), "the repair restored the zero-tail shape") },
            {
                assertEquals(
                    listOf(listOf(first), listOf(second), listOf(third)),
                    frames.map { it.ops },
                    "and nothing intact was thrown away with the torn tail",
                )
            },
        )
    }

    /**
     * A directory that cannot exist must report **why**, in both the availability signal and the
     * refused append.
     *
     * Anchored on `errno=` and on the absence of `(unknown)`, because a bare "write failed" makes
     * every recovery unimplementable — this module's rule is to report identities and state, never a
     * tally. Pointing the archive at a subdirectory of a *regular file* is the deterministic way to
     * get there: nothing can live underneath one, so `mkdir(2)` returns `ENOTDIR`.
     *
     * `mkdir(2)` rather than `NSFileManager` is what makes the errno trustworthy at all — Foundation
     * reports an `NSError` and promises nothing about `errno`, so reading it after a Foundation call
     * yields whatever the last unrelated syscall left behind.
     */
    @Test
    fun anUnusableDirectoryNamesItsErrnoAndRefusesTheAppend() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val blocker = NSTemporaryDirectory() + "kuilt-bolt-blocker-${Random.nextLong()}"
        NSFileManager.defaultManager.createFileAtPath(blocker, contents = null, attributes = null)
        val bolt = PosixMappedBolt(rgaArchiveFormat(), FixedClock(epoch), "$blocker/nested/")
        val (_, op) = Rga.empty<String>().insertAt(alice, 0, "lost")

        val availability = bolt.availability()
        val result = bolt.append(listOf(op))
        val events = bolt.replay(ReplayScope.All).toList()
        val reason = (availability as? BoltAvailability.Unavailable)?.reason.orEmpty()

        assertAll(
            { assertIs<BoltAvailability.Unavailable>(availability, "a path under a regular file is not usable") },
            { assertContains(reason, "errno=", message = "the availability signal names its errno") },
            { assertFalse(reason.contains("(unknown)"), "and the errno resolved to readable text") },
            { assertIs<AppendResult.Failed>(result, "and the append is refused rather than claiming a write") },
            {
                assertEquals(
                    setOf(op.id.dot),
                    assertIs<AppendResult.Failed>(result).insertDots,
                    "reporting WHICH records it lost — a tally makes every recovery unimplementable",
                )
            },
            { assertContains(assertIs<AppendResult.Failed>(result).reason, "errno=", message = "so does the refusal") },
            {
                assertEquals(
                    listOf(Truncated(0L, TruncationReason.SegmentHeader)),
                    events,
                    "an archive that could not be opened replays SHORT — 'I could not read it' and " +
                        "'it holds nothing' are different answers, and only one of them is true",
                )
            },
        )
    }

    /**
     * A roll that cannot create its segment file must not leave [PosixMappedBolt.availability]
     * claiming `Available`.
     *
     * The conformance suite's `availabilityAgreesWithWhetherAnAppendIsAccepted` cannot see this: it
     * probes a *fresh* bolt, where no roll has ever failed. But a bolt whose active segment is full
     * and whose volume refuses new files is exactly the state where `Available` is a lie, and
     * "Available must mean an append is accepted" is the one thing that signal exists to promise.
     *
     * A read-and-execute-only directory is the deterministic way there: existing segments stay
     * readable, so the archive still opens and replays, and only `open(O_CREAT)` fails — the shape a
     * full volume, a read-only remount and a revoked sandbox extension all produce.
     */
    @Test
    fun aRollThatCannotCreateItsSegmentDoesNotLieAboutAvailability() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = boltTestDirectory()
        // Every append rolls, so the second one needs a file the sealed directory will not give it.
        val bolt = PosixMappedBolt(rgaArchiveFormat(), FixedClock(epoch), directory, segmentFrameBytes = 1L)
        val (afterFirst, first) = Rga.empty<String>().insertAt(alice, 0, "first")
        val (afterSecond, second) = afterFirst.insertAt(alice, 1, "second")
        val (_, third) = afterSecond.insertAt(alice, 2, "third")

        bolt.append(listOf(first))
        check(chmod(directory.trimEnd('/'), READ_ONLY_DIRECTORY.convert()) == 0) { "could not seal the directory" }
        val refused = bolt.append(listOf(second))
        val whileSealed = bolt.availability()
        check(chmod(directory.trimEnd('/'), OWNER_ONLY_DIRECTORY.convert()) == 0) { "could not unseal" }
        val accepted = bolt.append(listOf(third))
        val afterUnsealing = bolt.availability()
        val frames = bolt.replay(ReplayScope.All).toList().filterIsInstance<Archived<RgaOp<String>>>()

        assertAll(
            { assertIs<AppendResult.Failed>(refused, "a roll into a sealed directory cannot succeed") },
            { assertContains(assertIs<AppendResult.Failed>(refused).reason, "errno=", message = "and names why") },
            {
                assertIs<BoltAvailability.Unavailable>(
                    whileSealed,
                    "a bolt whose last roll was refused must not keep reporting Available — that is " +
                        "precisely the 'Available while every append fails' the signal rules out",
                )
            },
            { assertIs<AppendResult.Written>(accepted, "and it recovers once the volume does") },
            { assertIs<BoltAvailability.Available>(afterUnsealing, "reporting itself usable again") },
            { assertEquals(listOf(listOf(first), listOf(third)), frames.map { it.ops }, "no phantom frame") },
        )
    }

    /**
     * A segment file left behind at the index the next roll wants must not disable the bolt forever.
     *
     * `rollSegment` creates with `O_CREAT|O_EXCL` and derives the next index from the segments it
     * knows about — so an orphan at that index makes every subsequent roll fail `EEXIST`, on this
     * instance, permanently, while `availability` still says `Available`.
     *
     * Two things leave one: a roll of ours that failed after creating the file, and a process killed
     * between `open` and the header write. Neither can have written a frame — the header goes down
     * before any frame does — so the orphan is discardable by construction.
     */
    @Test
    fun anOrphanedSegmentFileDoesNotBlockTheNextRoll() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = boltTestDirectory()
        val bolt = PosixMappedBolt(rgaArchiveFormat(), FixedClock(epoch), directory, segmentFrameBytes = 1L)
        val (afterFirst, first) = Rga.empty<String>().insertAt(alice, 0, "first")
        val (_, second) = afterFirst.insertAt(alice, 1, "second")

        val one = bolt.append(listOf(first))
        // A half-built segment: pre-allocated zeroes, no header — the shape a kill between open(2)
        // and the header write leaves behind.
        orphanSegmentAfter(segmentFiles(directory).single(), ByteArray(ORPHAN_BYTES))
        val two = bolt.append(listOf(second))
        val events = bolt.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()

        assertAll(
            { assertIs<AppendResult.Written>(one, "the first append lands") },
            {
                assertIs<AppendResult.Written>(
                    two,
                    "an orphan at the next index must not end this bolt's life — the file provably " +
                        "holds no frame, so the roll discards it and carries on",
                )
            },
            {
                assertEquals(
                    assertIs<AppendResult.Written>(one).endOffset,
                    assertIs<AppendResult.Written>(two).offset,
                    "and the offset space is unbroken across the discarded orphan",
                )
            },
            { assertEquals(2, frames.size, "both frames replay") },
            { assertEquals(CleanTail, events.lastOrNull(), "and the archive is intact") },
        )
    }

    /**
     * A read that fails for a **transient** reason must be retried, not treated as structural damage.
     *
     * `wedged` is sticky by design — it means "appending past this would write records no replay can
     * reach". A file that cannot be *opened* is not that: an `EACCES` from a Data-Protection-locked
     * device, an `EMFILE`, an `EINTR` all clear on their own, and the archive behind them is intact.
     *
     * This is also where the Data Protection story actually lands. On a locked device whose archive
     * directory already exists — the steady state — `mkdir` returns `EEXIST` and the directory
     * metadata reads fine, so the refusal never reaches the directory-creation check and surfaces
     * here instead. `chmod 000` on a segment file reproduces it exactly.
     */
    @Test
    fun aTransientReadFailureIsRetriedRatherThanWedging() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = boltTestDirectory()
        val format = rgaArchiveFormat()
        val (afterFirst, first) = Rga.empty<String>().insertAt(alice, 0, "first")
        val (_, second) = afterFirst.insertAt(alice, 1, "second")
        val opened = PosixMappedBolt(format, FixedClock(epoch), directory)
        opened.append(listOf(first))
        opened.append(listOf(second))
        opened.close()
        val segment = segmentFiles(directory).single()

        check(chmod(segment, NO_ACCESS.convert()) == 0) { "could not make the segment unreadable" }
        val bolt = PosixMappedBolt(format, FixedClock(epoch), directory)
        val whileUnreadable = bolt.availability()
        check(chmod(segment, OWNER_ONLY_FILE.convert()) == 0) { "could not restore the segment" }
        val afterRestoring = bolt.availability()
        val frames = bolt.replay(ReplayScope.All).toList().filterIsInstance<Archived<RgaOp<String>>>()

        assertAll(
            {
                assertIs<BoltAvailability.Unknown>(
                    whileUnreadable,
                    "a segment that cannot be READ is neither available nor permanently unavailable — " +
                        "this is the Data Protection state, and Unknown is the answer it exists for",
                )
            },
            {
                assertContains(
                    (whileUnreadable as? BoltAvailability.Unknown)?.reason.orEmpty(),
                    "errno=",
                    message = "naming the errno a bare null discarded",
                )
            },
            {
                assertIs<BoltAvailability.Available>(
                    afterRestoring,
                    "and the SAME instance recovers once the read succeeds — a transient failure must " +
                        "not reach the sticky wedge, which nothing ever clears",
                )
            },
            { assertEquals(listOf(listOf(first), listOf(second)), frames.map { it.ops }, "the archive was intact") },
        )
    }

    /**
     * A segment whose readable frames stop **short of its recorded extent** is damage, even though
     * what follows them is all zeroes.
     *
     * The zero-tail rule alone cannot tell the two apart: a pre-allocated tail and a region that
     * never reached disk read back identically, because pre-allocation writes real zeroes. So the
     * predicate has to be checked against where the frames were supposed to end — bookkeeping this
     * class already holds.
     *
     * Reachable in `synchronous = false`, a shipped and conformance-tested configuration: a page of
     * segment *k* may miss writeback while segment *k+1*'s file lands, and cross-file ordering is not
     * guaranteed. Without the extent check the replay walks straight past the hole and reports
     * [CleanTail] over a history whose offsets jump — the exact outcome `replay`'s own comment says
     * must never happen.
     *
     * The fixture zeroes a real frame's bytes after a real append, so the bookkeeping is genuine
     * rather than asserted, and puts a healthy segment behind the hole so "stop" and "skip ahead"
     * emit different events.
     */
    @Test
    fun aSegmentShortOfItsRecordedExtentStopsTheReplay() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = boltTestDirectory()
        val format = rgaArchiveFormat()
        val bolt = PosixMappedBolt(format, FixedClock(epoch), directory, segmentFrameBytes = SEGMENT_BYTES)
        val (r1, first) = Rga.empty<String>().insertAt(alice, 0, "first")
        val (r2, second) = r1.insertAt(alice, 1, "second")
        val (r3, third) = r2.insertAt(alice, 2, "third")
        val (_, behind) = r3.insertAt(alice, 3, "behind-the-hole")

        bolt.append(listOf(first))
        val lastReadable = assertIs<AppendResult.Written>(bolt.append(listOf(second)))
        val unflushed = assertIs<AppendResult.Written>(bolt.append(listOf(third)))
        // The page carrying the third frame never reached disk: it reads back as the zeroes
        // pre-allocation put there, while the bookkeeping still counts it.
        val hole = (unflushed.endOffset - lastReadable.endOffset).toInt()
        scribble(segmentFiles(directory).single(), headerBytesOf(format) + lastReadable.endOffset, List(hole) { 0 })
        val healthy = encodeFrame(RawFrame(epoch, setOf(behind.id.dot), null, listOf(format.encode(behind))))
        bolt.seedRawSegment(
            segmentWithHeader(format, unflushed.endOffset, healthy),
            unflushed.endOffset,
            headerBytesOf(format).toInt(),
        )

        val events = bolt.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()

        assertAll(
            {
                assertEquals(
                    listOf(listOf(first), listOf(second)),
                    frames.map { it.ops },
                    "replay stops at the hole — it must not walk past it into the healthy segment and " +
                        "hand back a history with jumping offsets",
                )
            },
            {
                assertEquals(
                    Truncated(lastReadable.endOffset, TruncationReason.Frame),
                    events.lastOrNull(),
                    "and says so, at the last intact frame's end — a zero run short of the recorded " +
                        "extent is damage, not an unwritten tail",
                )
            },
            { assertEquals(1, events.count { it !is Archived<*> }, "exactly one terminal event") },
        )
    }

    /**
     * Removing a trailing segment that has no header is not a "repaired tail", and must not be
     * reported as one.
     *
     * Such a file holds no frame — the header is written before any frame is — so nothing is
     * discarded, and `repairedTailAt`'s contract is `null` when nothing was. Reporting an offset
     * there tells a consumer the archive was cut at that point; reporting `0` tells it the archive
     * was thrown away entirely, while every frame is still sitting there readable.
     */
    @Test
    fun aHeaderlessTrailingSegmentIsNotReportedAsARepairedTail() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val directory = boltTestDirectory()
        val format = rgaArchiveFormat()
        val (r1, first) = Rga.empty<String>().insertAt(alice, 0, "first")
        val (r2, second) = r1.insertAt(alice, 1, "second")
        val (_, third) = r2.insertAt(alice, 2, "third")
        val opened = PosixMappedBolt(format, FixedClock(epoch), directory)
        opened.append(listOf(first))
        opened.append(listOf(second))
        val closing = assertIs<AppendResult.Written>(opened.append(listOf(third)))
        opened.close()
        orphanSegmentAfter(segmentFiles(directory).single(), ByteArray(ORPHAN_BYTES))

        val reopened = PosixMappedBolt(format, FixedClock(epoch), directory)
        val repaired = reopened.repairedTailAt()
        val events = reopened.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()

        assertAll(
            {
                assertNull(
                    repaired,
                    "a headerless trailing segment holds no frame, so nothing was discarded — and a " +
                        "consumer reading 0 here concludes the whole archive was cut at its start",
                )
            },
            { assertEquals(3, frames.size, "every frame is still there") },
            { assertEquals(CleanTail, events.lastOrNull(), "and the archive reads clean") },
            { assertEquals(closing.endOffset, frames.last().endOffset, "with the cursor where it was left") },
            { assertEquals(1, segmentFiles(directory).size, "the orphan is gone rather than adopted") },
        )
    }

    @AfterTest
    fun removeArchives(): Unit = removeBoltTestDirectories()

    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }

    private companion object {
        /** Big enough that a two-frame archive leaves an unmistakable zero tail behind it. */
        const val SEGMENT_BYTES = 1L shl 16

        /** `r-x------`: existing segments stay readable, nothing new can be created. */
        const val READ_ONLY_DIRECTORY = 320 // 0o500

        const val OWNER_ONLY_DIRECTORY = 448 // 0o700
        const val OWNER_ONLY_FILE = 384 // 0o600

        /** Enough to look like a started segment; far too few to hold a header. */
        const val ORPHAN_BYTES = 32

        /** `---------`: the owner cannot read it either, which is the point. */
        const val NO_ACCESS = 0
    }
}

/**
 * A fresh, empty directory under `NSTemporaryDirectory()`, so no test shares an archive with another.
 *
 * Every path handed out is remembered so [removeBoltTestDirectories] can delete exactly those and
 * nothing else. A mapped archive pre-allocates its segments eagerly, so a suite that leaves them
 * behind is not leaving a few files — six runs of this module left 407 directories and 222 MB, and a
 * simulator keeps its temporary directory across runs.
 */
internal fun boltTestDirectory(): String {
    val directory = NSTemporaryDirectory() + "kuilt-bolt-test-${Random.nextLong()}/"
    createdBoltTestDirectories += directory
    return directory
}

/**
 * Delete the directories [boltTestDirectory] handed out, and forget them.
 *
 * **Only those paths — never a pattern sweep of the shared temporary directory.** Concurrent
 * sessions run against this repo, and a glob over `NSTemporaryDirectory()` cannot tell this run's
 * archives from a sibling worker's live ones.
 */
internal fun removeBoltTestDirectories() {
    createdBoltTestDirectories.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    createdBoltTestDirectories.clear()
}

private val createdBoltTestDirectories = mutableListOf<String>()

/** The segment files in [directory], in append order. */
private fun segmentFiles(directory: String): List<String> {
    val base = directory.trimEnd('/')
    val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(base, error = null) ?: emptyList<Any?>()
    return names.mapNotNull { it as? String }.sorted().map { "$base/$it" }
}

private fun headerBytesOf(format: BoltArchiveFormat<*, *, *>): Long = encodeSegmentHeader(
    SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset = 0L),
).size.toLong()

private fun fileSize(path: String): Long = memScoped {
    val info = alloc<stat>()
    check(stat(path, info.ptr) == 0) { "could not stat $path" }
    info.st_size
}

/**
 * How many bytes are **physically allocated** to [path], from `st_blocks`.
 *
 * The only observable difference between a real write and an `ftruncate` — `st_size` and the bytes
 * read back are identical either way.
 */
private fun allocatedBytes(path: String): Long = memScoped {
    val info = alloc<stat>()
    check(stat(path, info.ptr) == 0) { "could not stat $path" }
    info.st_blocks * STAT_BLOCK_BYTES
}

/** Overwrite [bytes] into [path] at [at] — how a test manufactures damage a consumer cannot. */
private fun scribble(path: String, at: Long, bytes: List<Byte>) {
    val payload = bytes.toByteArray()
    val fd = open(path, O_RDWR)
    check(fd >= 0) { "could not open $path for damage" }
    try {
        check(lseek(fd, at, SEEK_SET) == at) { "could not seek to $at in $path" }
        payload.usePinned { pinned ->
            check(write(fd, pinned.addressOf(0), payload.size.convert()) == payload.size.toLong()) {
                "could not write ${payload.size}B of damage to $path"
            }
        }
    } finally {
        close(fd)
    }
}

/**
 * Write [bytes] to the segment file one index past [existing] — an orphan at exactly the index the
 * next roll will ask for.
 *
 * The index is parsed out of a real segment's own name rather than reconstructed from the naming
 * convention, so this fixture cannot drift away from the writer it is aimed at.
 */
private fun orphanSegmentAfter(existing: String, bytes: ByteArray) {
    val name = existing.substringAfterLast('/')
    val digits = name.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
    val next = (digits.toLong() + 1).toString().padStart(digits.length, '0')
    val path = existing.substringBeforeLast('/') + "/" + name.replace(digits, next)
    val fd = open(path, O_RDWR or O_CREAT or O_EXCL, OWNER_ONLY)
    check(fd >= 0) { "could not create the orphan segment $path" }
    try {
        bytes.usePinned { pinned ->
            check(write(fd, pinned.addressOf(0), bytes.size.convert()) == bytes.size.toLong()) {
                "could not write the orphan segment $path"
            }
        }
    } finally {
        close(fd)
    }
}

/** A whole header for [format] at [baseOffset], then [frames] — a segment file's exact bytes. */
private fun segmentWithHeader(
    format: BoltArchiveFormat<*, *, *>,
    baseOffset: Long,
    frames: ByteArray,
): ByteArray = Buffer().apply {
    write(encodeSegmentHeader(SegmentHeader(BOLT_FORMAT_VERSION, format.opFormat, format.elementType, baseOffset)))
    write(frames)
}.readByteArray()

private const val OWNER_ONLY = 384 // 0o600

/** `st_blocks` is counted in 512-byte units on every POSIX system, Darwin included. */
private const val STAT_BLOCK_BYTES = 512L
