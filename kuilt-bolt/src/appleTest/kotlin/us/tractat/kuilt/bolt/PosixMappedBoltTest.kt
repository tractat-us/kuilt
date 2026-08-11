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
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.posix.O_RDWR
import platform.posix.SEEK_SET
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

    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }

    private companion object {
        /** Big enough that a two-frame archive leaves an unmistakable zero tail behind it. */
        const val SEGMENT_BYTES = 1L shl 16
    }
}

/** A fresh, empty directory under `NSTemporaryDirectory()`, so no test shares an archive with another. */
internal fun boltTestDirectory(): String =
    NSTemporaryDirectory() + "kuilt-bolt-test-${Random.nextLong()}/"

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

/** `st_blocks` is counted in 512-byte units on every POSIX system, Darwin included. */
private const val STAT_BLOCK_BYTES = 512L
