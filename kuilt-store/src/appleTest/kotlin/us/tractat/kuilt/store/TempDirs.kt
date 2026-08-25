@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.store

import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

/**
 * A path under `NSTemporaryDirectory()` that nothing else in this run uses, and that nothing left
 * behind by a *previous* run occupies either.
 *
 * Both halves matter. The counter is what keeps two stores alive inside one test apart — the
 * conformance suite's `twoFreshStoresDoNotShareState` holds two at once and asserts they share
 * nothing, which a reused path would make false by construction. Removing whatever is already there
 * is what keeps a fresh store fresh across runs: `NSTemporaryDirectory()` outlives the process, so a
 * name derived from a counter alone comes back on the next run holding the last run's files, and
 * every absence assertion in either suite would then be checking yesterday's state.
 *
 * A counter rather than a random name, because a test's randomness is a dependency like any other and
 * an unseeded one here would make a failure unreproducible.
 *
 * `internal` rather than file-private because both of this backend's test classes need it — the
 * [us.tractat.kuilt.conformance.DurableStoreConformanceSuite] subclass and the
 * [us.tractat.kuilt.conformance.DurableStoreFilenameConformanceSuite] one. A per-file copy of the
 * counter would hand the two classes overlapping directory names.
 */
internal fun freshTempPath(prefix: String): String {
    val path = NSTemporaryDirectory() + "$prefix-${nextTempId++}"
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    return path
}

/** [freshTempPath] as a directory, created and ready to be written into. */
internal fun freshTempDir(prefix: String = "kuilt-store-conformance"): String {
    val dir = freshTempPath(prefix) + "/"
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
