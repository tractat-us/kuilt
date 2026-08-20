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
import us.tractat.kuilt.conformance.DurableStoreConformanceSuite
import us.tractat.kuilt.conformance.RestartFixture
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [NSFileManagerDurableStore] satisfies the whole [DurableStoreConformanceSuite], and keeps
 * the tests the suite cannot reach.
 *
 * The round-trip, overwrite, delete, defensive-copy, large-payload, independent-key and
 * second-instance-over-the-same-directory tests this class used to hold by hand are now properties of
 * the shared contract. What stays is what is genuinely this backend's own, in two groups. The
 * **filename-level** ones (#2506) — the legacy fold, the orphaned legacy files the fix deliberately
 * does not migrate, the `.tmp` sidecar namespace, and the case-insensitive filesystem — are about how
 * a key becomes a path, which [DurableStore] does not expose. The **failure-path** ones are a write
 * failure that has to name its cause (#1860) and a rename that cannot commit having to leave the
 * destination alone (#2120); neither is expressible against [DurableStore] either, since the contract
 * has no failure injection and no view of the medium. Both groups stay here, where the medium is
 * reachable.
 */
class NSFileManagerDurableStoreTest : DurableStoreConformanceSuite() {

    private val directories = mutableMapOf<DurableStore, String>()

    override suspend fun newStore(): DurableStore {
        val dir = freshTempDir()
        return NSFileManagerDurableStore(dir).also { directories[it] = dir }
    }

    /**
     * A restart, modelled the way this store's own contract defines one: a **brand-new instance over
     * the same directory**, holding no state from the original, so everything it answers came off the
     * file system the original wrote to.
     */
    override suspend fun restart(store: DurableStore): RestartFixture =
        RestartFixture.Durable(
            NSFileManagerDurableStore(
                requireNotNull(directories[store]) { "restart() was handed a store this fixture did not create" },
            ),
        )

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
        val dir = freshTempDir()
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
        val dir = freshTempDir()
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
        val dir = freshTempDir()
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
        val dir = freshTempDir()
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
        val dir = freshTempDir()
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
        val dir = freshTempDir()
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
    fun writeFailureNamesItsCause() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val blocker = freshTempPath("kuilt-store-blocker")
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
     * then succeeded against the now-vacant path. [DurableStore.write] returned
     * normally having eaten the destination. That is the same shape as the field
     * failure: every previously committed record gone rather than merely stale.
     *
     * A round-trip test cannot see this — it passes identically either way. This
     * test is the one that distinguishes, which is why the assertions are on the
     * *destination*, not on the bytes just written.
     */
    @Test
    fun destinationSurvivesARenameThatCannotCommit() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val dir = freshTempDir()
        val fm = NSFileManager.defaultManager
        val key = StoreKey("otel.spans")
        // The path write() will target. Derived rather than spelled out, so an
        // encoding change moves the rig with it; `assertNotNull(failure)` below is
        // what catches a rig that stopped blocking the destination.
        val dest = dir + encodeStoreKeyName(key.name)
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
}

/**
 * A path under `NSTemporaryDirectory()` that nothing else in this run uses, and that nothing left
 * behind by a *previous* run occupies either.
 *
 * Both halves matter. The counter is what keeps two stores alive inside one test apart — the suite's
 * `twoFreshStoresDoNotShareState` holds two at once and asserts they share nothing, which a reused
 * path would make false by construction. Removing whatever is already there is what keeps a fresh
 * store fresh across runs: `NSTemporaryDirectory()` outlives the process, so a name derived from a
 * counter alone comes back on the next run holding the last run's files, and every absence assertion
 * in the suite would then be checking yesterday's state.
 *
 * A counter rather than a random name, because a test's randomness is a dependency like any other and
 * an unseeded one here would make a failure unreproducible.
 */
private fun freshTempPath(prefix: String): String {
    val path = NSTemporaryDirectory() + "$prefix-${nextTempId++}"
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    return path
}

/** [freshTempPath] as a directory, created and ready to be written into. */
private fun freshTempDir(): String {
    val dir = freshTempPath("kuilt-store-conformance") + "/"
    NSFileManager.defaultManager.createDirectoryAtPath(
        dir,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return dir
}

/**
 * File-level, not a class property: the test framework builds a fresh instance of the test class for
 * every test function, so a per-instance counter would restart at zero in each of them and hand every
 * test the same directory.
 */
private var nextTempId = 0
