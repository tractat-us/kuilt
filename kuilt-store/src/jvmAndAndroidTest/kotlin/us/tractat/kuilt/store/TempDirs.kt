package us.tractat.kuilt.store

import java.io.File

/**
 * A directory named for [prefix], under the system temp root, that no other store in this run shares.
 *
 * `java.io.File`, not `kotlin.io.path.createTempDirectory`: this source set compiles for Android at
 * `minSdk 24` and `java.nio.file.Files.createTempDirectory` is API 26. The unit-test variant runs on
 * the host JVM so it would work in practice, but a test that only compiles by accident of where it
 * runs is not a thing to leave lying in a source set whose whole point is that it targets both.
 *
 * `internal` rather than file-private because both of this backend's test classes need it — the
 * [us.tractat.kuilt.conformance.DurableStoreConformanceSuite] subclass and the
 * [us.tractat.kuilt.conformance.DurableStoreFilenameConformanceSuite] one.
 */
internal fun freshTempDir(prefix: String): File {
    val stem = File.createTempFile(prefix, "")
    check(stem.delete()) { "could not clear the placeholder temp file at $stem" }
    check(stem.mkdirs()) { "could not create the temp directory at $stem" }
    return stem
}
