@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Crash-recovery and correctness tests for [NSFileManagerDurableStore].
 *
 * Each test creates a fresh temporary directory under `NSTemporaryDirectory()` so
 * there is no cross-test state. The crash-recovery test verifies the durability
 * contract by constructing a second store instance over the *same directory* and
 * confirming the previously-written bytes are returned — simulating a process
 * restart.
 */
class NSFileManagerDurableStoreTest {

    /**
     * Run a [DurableStore.write] and hand back the failure it reported, or `null`
     * if it reported none.
     *
     * Deliberately not `assertFailsWith`: "did it throw?" and "did the destination
     * survive?" are two independent facts about the same call, and an
     * `assertFailsWith` that trips first would hide the second — which is the one
     * that actually names the data loss. Catching [IllegalStateException] only,
     * never [Throwable], keeps cancellation propagating.
     */
    private suspend fun DurableStore.writeFailure(key: StoreKey, bytes: ByteArray): IllegalStateException? =
        try {
            write(key, bytes)
            null
        } catch (failure: IllegalStateException) {
            failure
        }

    private fun tempDir(): String {
        val base = NSTemporaryDirectory()
        val dir = base + "kuilt-otel-test-${kotlin.random.Random.nextLong()}/"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    @Test
    fun crashRecoveryRoundTrip() = runTest {
        val dir = tempDir()
        val key = StoreKey("spans")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        // First store instance — write then let it go out of scope (simulates process exit).
        NSFileManagerDurableStore(dir).write(key, bytes)

        // Second store instance over the same directory — simulates process restart.
        val recovered = NSFileManagerDurableStore(dir).read(key)
        assertContentEquals(bytes, recovered)
    }

    @Test
    fun readReturnsNullForAbsentKey() = runTest {
        val store = NSFileManagerDurableStore(tempDir())
        assertNull(store.read(StoreKey("never-written")))
    }

    @Test
    fun overwriteReturnsLatestBytes() = runTest {
        val dir = tempDir()
        val key = StoreKey("k")
        val store = NSFileManagerDurableStore(dir)
        store.write(key, byteArrayOf(1))
        store.write(key, byteArrayOf(2, 3))
        assertContentEquals(byteArrayOf(2, 3), store.read(key))
    }

    @Test
    fun deleteRemovesKey() = runTest {
        val dir = tempDir()
        val key = StoreKey("k")
        val store = NSFileManagerDurableStore(dir)
        store.write(key, byteArrayOf(42))
        store.delete(key)
        assertNull(store.read(key))
    }

    @Test
    fun deleteOfAbsentKeyIsNoOp() = runTest {
        val store = NSFileManagerDurableStore(tempDir())
        // Must not throw.
        store.delete(StoreKey("never-written"))
    }

    @Test
    fun readReturnsCopyNotReference() = runTest {
        val dir = tempDir()
        val key = StoreKey("k")
        val store = NSFileManagerDurableStore(dir)
        store.write(key, byteArrayOf(1, 2, 3))
        val read = store.read(key)!!
        read[0] = 99
        // A fresh read must still return the original bytes.
        assertEquals(1, store.read(key)!![0])
    }

    @Test
    fun keyWithSpecialCharsSanitisedSafely() = runTest {
        val dir = tempDir()
        val key = StoreKey("otel/spans.v1")
        val bytes = byteArrayOf(7, 8, 9)
        val store = NSFileManagerDurableStore(dir)
        store.write(key, bytes)
        assertContentEquals(bytes, store.read(key))
    }

    /**
     * A failing write must name its cause (#1860).
     *
     * Pointing the store at a subdirectory of a *regular file* makes the
     * directory creation and then the temp write both fail — the same class of
     * failure a device hits when its storage rejects a write. Before the
     * `NSError` was captured, this threw `"write to temp file failed"` and
     * nothing else, which is exactly why a field occurrence could not be
     * diagnosed from the device's own logs.
     */
    @Test
    fun writeFailureNamesItsCause() = runTest {
        val blocker = NSTemporaryDirectory() + "kuilt-otel-blocker-${kotlin.random.Random.nextLong()}"
        NSFileManager.defaultManager.createFileAtPath(blocker, contents = null, attributes = null)
        // `blocker` is a regular file, so nothing can live underneath it.
        val store = NSFileManagerDurableStore("$blocker/nested/")

        val failure = assertFailsWith<IllegalStateException> {
            store.write(StoreKey("otel.logs"), byteArrayOf(1, 2, 3))
        }

        val message = failure.message.orEmpty()
        assertAll(
            { assertContains(message, "otel.logs", message = "names the key") },
            { assertContains(message, "bytes=3", message = "names the payload size") },
            // Anchored to `cause=` deliberately. A bare "NSError(domain=" would also
            // be satisfied by the directory= field alone, so the assertion would
            // still pass with the write's own error discarded — the exact defect
            // under repair.
            { assertContains(message, "cause=NSError(domain=", message = "carries the WRITE's own NSError") },
            { assertContains(message, "directoryExists=false", message = "names the missing directory") },
        )
    }

    /**
     * A rename that cannot commit must leave the destination untouched (#2120).
     *
     * The destination path is occupied by a non-empty directory, so the rename in
     * step 2 cannot possibly succeed: POSIX `rename(2)` refuses to replace a
     * directory with a regular file (`EISDIR`). What the test pins is what happens
     * to the *destination* while the write is failing.
     *
     * The removed implementation unlinked the destination unconditionally, before
     * anything knew whether the rename would commit — `removeItemAtPath` deletes a
     * directory tree recursively, so `marker` was destroyed and the subsequent move
     * then succeeded against the now-vacant path. [write] returned normally having
     * eaten the destination. That is the same shape as the field failure: every
     * previously committed record gone rather than merely stale.
     *
     * A round-trip test cannot see this — it passes identically either way. This
     * test is the one that distinguishes, which is why the assertions are on the
     * *destination*, not on the bytes just written.
     */
    @Test
    fun destinationSurvivesARenameThatCannotCommit() = runTest {
        val dir = tempDir()
        val fm = NSFileManager.defaultManager
        // "otel.spans" sanitises to "otel_spans" — the path write() will target.
        val key = StoreKey("otel.spans")
        val dest = dir + "otel_spans"
        val marker = "$dest/marker"
        fm.createDirectoryAtPath(dest, withIntermediateDirectories = true, attributes = null, error = null)
        fm.createFileAtPath(marker, contents = null, attributes = null)

        val store = NSFileManagerDurableStore(dir)
        val failure = store.writeFailure(key, byteArrayOf(1, 2, 3))
        val readBack = store.read(key)

        assertAll(
            { assertTrue(fm.fileExistsAtPath(marker), "the destination's contents survived the failed write") },
            // `dest` is still the directory it was, so there is nothing readable there.
            // The removed implementation left the never-committed payload sitting at
            // `dest` as a regular file, so this read returned it.
            { assertNull(readBack, "the payload that could not commit never became readable") },
            { assertNotNull(failure, "write reported the rename it could not commit instead of returning") },
            // The cause must survive the move off NSFileManager: errno is a better
            // cause than the NSError it replaces, not a worse one (#1860).
            { assertContains(failure?.message.orEmpty(), "errno=", message = "the failure names its errno") },
        )
    }

    /**
     * The rest of this suite tops out at a 5-byte payload, which exercises no
     * buffering or chunking at all. 256 KiB crosses every page and I/O-buffer
     * boundary the write path could hide a truncation behind.
     */
    @Test
    fun largePayloadRoundTrips() = runTest {
        val dir = tempDir()
        val key = StoreKey("otel.spans")
        val bytes = ByteArray(256 * 1024) { (it * 31 + 7).toByte() }
        val store = NSFileManagerDurableStore(dir)
        store.write(key, bytes)
        assertContentEquals(bytes, store.read(key))
    }

    /**
     * An overwrite must replace the destination wholesale. The second payload is
     * deliberately *shorter* than the first: an in-place write would leave the
     * first payload's tail behind, and only a length-changing overwrite catches it.
     */
    @Test
    fun largeOverwriteLeavesNoTailOfThePreviousPayload() = runTest {
        val dir = tempDir()
        val key = StoreKey("otel.spans")
        val first = ByteArray(128 * 1024) { 0xA5.toByte() }
        val second = ByteArray(64 * 1024) { 0x5A.toByte() }
        val store = NSFileManagerDurableStore(dir)
        store.write(key, first)
        store.write(key, second)
        assertContentEquals(second, store.read(key))
    }

    @Test
    fun independentKeysDoNotInterfere() = runTest {
        val dir = tempDir()
        val store = NSFileManagerDurableStore(dir)
        val k1 = StoreKey("alpha")
        val k2 = StoreKey("beta")
        store.write(k1, byteArrayOf(1))
        store.write(k2, byteArrayOf(2))
        assertEquals(1, store.read(k1)!![0])
        assertEquals(2, store.read(k2)!![0])
    }
}
