@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.store

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import us.tractat.kuilt.conformance.DurableStoreConformanceSuite
import us.tractat.kuilt.conformance.DurableStoreFilenameConformanceSuite
import us.tractat.kuilt.conformance.RestartFixture
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
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
 * the shared contract, and the six **filename-level** ones (#2506) followed them into
 * [DurableStoreFilenameConformanceSuite], which [NSFileManagerDurableStoreFilenameTest] binds — they
 * had been duplicated verbatim in the JVM/Android backend's test file, which is the shape that
 * produced #2506 in the first place (#2515).
 *
 * What stays is the **failure-path** pair: a write failure that has to name its cause (#1860) and a
 * rename that cannot commit having to leave the destination alone (#2120). Neither is expressible
 * against [DurableStore] — the contract has no failure injection and no view of the medium — and
 * neither is shared with any other backend, since both are about `NSFileManager` and POSIX
 * `rename(2)` specifically. So they stay here, where the medium is reachable.
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
     * that actually names the data loss.
     *
     * Catching [IllegalStateException] rather than [Throwable] does **not**, on its own, keep
     * cancellation propagating — which is what this KDoc used to claim (#2535).
     * `CancellationException` extends [IllegalStateException], so [write] being a `suspend` call makes
     * this arm a cancellation swallow: a cancelled test would come back holding the cancellation as if
     * it were a reported write failure, and the assertions below would run on it. Narrowing the type is
     * not the guard; `ensureActive()` is — it rethrows only a cancellation of THIS job and lets an
     * ordinary `check()`/`error()` failure fall through to be returned.
     */
    private suspend fun DurableStore.writeFailure(key: StoreKey, bytes: ByteArray): IllegalStateException? =
        try {
            write(key, bytes)
            null
        } catch (failure: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            failure
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
