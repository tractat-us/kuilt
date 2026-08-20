@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.store

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /**
     * Write [bytes] straight to [path], bypassing the store entirely.
     *
     * Staging a *legacy* filename is the whole point: it is a name the store can no
     * longer produce, so it cannot be created through [DurableStore.write]. POSIX
     * rather than Foundation because `NSData.create(bytes:length:)` needs
     * `BetaInteropApi` that nothing else in this file wants.
     */
    private fun plantFile(path: String, bytes: ByteArray) {
        val handle = fopen(path, "wb") ?: error("could not create $path")
        bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), handle) }
        fclose(handle)
    }

    private fun tempDir(): String {
        val base = NSTemporaryDirectory()
        val dir = base + "kuilt-store-test-${kotlin.random.Random.nextLong()}/"
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

    /**
     * A key containing a path separator is still exactly one key.
     *
     * `otel/spans.v1` is a real shipped key. Nothing in [DurableStore]'s contract
     * says a key may not look like a path, so the store must not let the `/` become
     * a subdirectory write — which is why the raw key name can never be used as a
     * filename directly.
     */
    @Test
    fun aKeyThatLooksLikeAPathIsJustAKey() = runTest {
        val dir = tempDir()
        val key = StoreKey("otel/spans.v1")
        val bytes = byteArrayOf(7, 8, 9)
        val store = NSFileManagerDurableStore(dir)
        store.write(key, bytes)
        assertContentEquals(bytes, store.read(key))
    }

    // ---- a key is stored losslessly: distinct keys are distinct entries (#2506) ----

    /**
     * Five distinct keys the legacy filename mapping folded onto **one** file.
     *
     * `Char.isLetterOrDigit()` kept letters and digits and sent everything else to
     * `_`, so `a.b`, `a/b`, `a b` and `a:b` all became `a_b` — which is itself a
     * key. Four of the five writes below destroyed a value written under a
     * *different* key, silently, with no listing surface for a caller to notice it
     * through.
     */
    @Test
    fun keysThatFoldedOntoOneFilenameAddressDistinctEntries() = runTest {
        val dir = tempDir()
        val names = listOf("a.b", "a/b", "a b", "a:b", "a_b")
        val store = NSFileManagerDurableStore(dir)
        names.forEachIndexed { index, name -> store.write(StoreKey(name), byteArrayOf(index.toByte())) }

        val readBack = names.map { store.read(StoreKey(it)) }
        assertAll(
            *names.mapIndexed { index, name ->
                { assertContentEquals(byteArrayOf(index.toByte()), readBack[index], "key \"$name\"") }
            }.toTypedArray(),
        )
    }

    /**
     * Two keys differing only in a non-ASCII letter.
     *
     * This backend's `Char.isLetterOrDigit()` is true for Cyrillic, so `мир` and
     * `миг` survived here — while `FileChannelDurableStore`'s `[^a-zA-Z0-9_-]` folded
     * both to `___`. Two sanitisers that each read as correct, disagreeing on
     * non-ASCII, is why the encoding is now one shared thing rather than two that
     * must agree by inspection; this test is the Apple half of that agreement.
     */
    @Test
    fun keysDifferingOnlyInANonAsciiLetterAddressDistinctEntries() = runTest {
        val dir = tempDir()
        val store = NSFileManagerDurableStore(dir)
        val peace = StoreKey("мир")
        val moment = StoreKey("миг")
        store.write(peace, byteArrayOf(1))
        store.write(moment, byteArrayOf(2))

        val first = store.read(peace)
        val second = store.read(moment)
        assertAll(
            { assertContentEquals(byteArrayOf(1), first, "key \"мир\"") },
            { assertContentEquals(byteArrayOf(2), second, "key \"миг\"") },
        )
    }

    /**
     * Two keys differing only in case.
     *
     * `StoreKey("a")` and `StoreKey("A")` are distinct keys, and the legacy mapping
     * passed both letters straight through — so on APFS, which is case-insensitive
     * by default, they shared one file. That made this the *same* #2506 defect, on
     * the same backend, that nobody had measured: a case-differing pair simply never
     * appeared in a test. Escaping uppercase closes it, and closes it on every
     * filesystem rather than only the case-sensitive ones.
     */
    @Test
    fun keysDifferingOnlyInCaseAddressDistinctEntries() = runTest {
        val dir = tempDir()
        val store = NSFileManagerDurableStore(dir)
        val lower = StoreKey("a")
        val upper = StoreKey("A")
        store.write(lower, byteArrayOf(1))
        store.write(upper, byteArrayOf(2))

        val first = store.read(lower)
        val second = store.read(upper)
        assertAll(
            { assertContentEquals(byteArrayOf(1), first, "key \"a\"") },
            { assertContentEquals(byteArrayOf(2), second, "key \"A\"") },
        )
    }

    /**
     * A file left behind by the legacy scheme must never be readable as a
     * **different** key.
     *
     * The fix ships no migration, so legacy files stay on disk in the same
     * directory. `otel.logs` was stored as `otel_logs`; a scheme that treated `_` as
     * a safe character would hand a future `StoreKey("otel_logs")` the abandoned
     * buffer of `otel.logs` — silent wrong-key data, strictly worse than the loss
     * orphaning already accepts.
     */
    @Test
    fun aKeyNeverAdoptsAnotherKeysLegacyOrphan() = runTest {
        val dir = tempDir()
        // Exactly the filenames the legacy sanitiser produced for "otel.logs" and "otel.spans".
        plantFile(dir + "otel_logs", byteArrayOf(11))
        plantFile(dir + "otel_spans", byteArrayOf(22))

        val store = NSFileManagerDurableStore(dir)
        val logs = store.read(StoreKey("otel_logs"))
        val spans = store.read(StoreKey("otel_spans"))
        assertAll(
            { assertNull(logs, "StoreKey(\"otel_logs\") must not adopt otel.logs' orphaned file") },
            { assertNull(spans, "StoreKey(\"otel_spans\") must not adopt otel.spans' orphaned file") },
        )
    }

    /**
     * The one case where reading a legacy file *is* correct: the key was already
     * inside the safe set, so both schemes are the identity on it and the "orphan"
     * is that key's own file. `spans` and `span-state` carry over for free.
     */
    @Test
    fun aKeyAlreadyInsideTheSafeSetStillFindsItsOwnFile() = runTest {
        val dir = tempDir()
        plantFile(dir + "spans", byteArrayOf(33))
        plantFile(dir + "span-state", byteArrayOf(44))

        val store = NSFileManagerDurableStore(dir)
        val spans = store.read(StoreKey("spans"))
        val spanState = store.read(StoreKey("span-state"))
        assertAll(
            { assertContentEquals(byteArrayOf(33), spans, "key \"spans\"") },
            { assertContentEquals(byteArrayOf(44), spanState, "key \"span-state\"") },
        )
    }

    /**
     * An entry's filename can never equal another entry's `.tmp` sidecar.
     *
     * [NSFileManagerDurableStore] writes `<path>.tmp` beside `<path>`, so if a key
     * could encode to a name ending in `.tmp` its entry would sit exactly where
     * another key's in-flight write lands. Escaping `.` closes it: no encoded name
     * contains a dot. The pair below is the smallest witness — `x` owns the sidecar
     * `x.tmp`, and `x.tmp` is a key in its own right.
     */
    @Test
    fun anEntryNeverLandsOnAnotherEntrysTempSidecar() = runTest {
        val dir = tempDir()
        val store = NSFileManagerDurableStore(dir)
        val plain = StoreKey("x")
        val sidecarShaped = StoreKey("x.tmp")
        store.write(plain, byteArrayOf(1))
        store.write(sidecarShaped, byteArrayOf(2))
        store.write(plain, byteArrayOf(3))

        val first = store.read(plain)
        val second = store.read(sidecarShaped)
        assertAll(
            { assertContentEquals(byteArrayOf(3), first, "key \"x\"") },
            { assertContentEquals(byteArrayOf(2), second, "key \"x.tmp\" survived x's write") },
        )
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
        val blocker = NSTemporaryDirectory() + "kuilt-store-blocker-${kotlin.random.Random.nextLong()}"
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
            // cause than the NSError it replaces, not a worse one (#1860). Both
            // halves matter — a bare "errno=21" would pass the first assertion with
            // strerror_r silently failing, and an unreadable cause is the thing
            // #1860 is about.
            { assertContains(failure?.message.orEmpty(), "errno=", message = "the failure names its errno") },
            { assertFalse(failure?.message.orEmpty().contains("(unknown)"), "the errno resolved to readable text") },
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
