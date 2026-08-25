package us.tractat.kuilt.store

import us.tractat.kuilt.conformance.DurableStoreFilenameConformanceSuite
import java.io.File

/**
 * Verifies [FileChannelDurableStore] satisfies the whole [DurableStoreFilenameConformanceSuite], on
 * both the JVM and Android.
 *
 * In `jvmAndAndroidTest`, not `jvmTest`, for the same reason
 * [FileChannelDurableStoreTest] is: `FileChannelDurableStore` lives in `jvmAndAndroidMain` and
 * Android is the target an app actually depends on for durable storage, so a subclass in `jvmTest`
 * would leave the Android variant compiled and never run.
 *
 * The six properties this class used to hold by hand (#2511) are now the suite's, checked
 * identically here and on the Apple backend — which is the point, since #2506 was two
 * independently written filename mappings that had to agree by inspection and did not.
 */
class FileChannelDurableStoreFilenameTest : DurableStoreFilenameConformanceSuite<File>() {

    override fun newDirectory(): File = freshTempDir("kuilt-store-filename")

    override suspend fun newStore(dir: File): DurableStore = FileChannelDurableStore(dir)

    /**
     * A raw write, bypassing the store entirely — no encoder, no `.tmp` staging, no rename.
     *
     * Staging a *legacy* filename is the whole point: it is a name the store can no longer produce,
     * so it cannot be created through [DurableStore.write].
     */
    override fun plantRawFile(dir: File, name: String, bytes: ByteArray) {
        File(dir, name).writeBytes(bytes)
    }
}
