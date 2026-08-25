@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.store

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import us.tractat.kuilt.conformance.DurableStoreFilenameConformanceSuite

/**
 * Verifies [NSFileManagerDurableStore] satisfies the whole [DurableStoreFilenameConformanceSuite].
 *
 * The six properties this class used to hold by hand (#2511) are now the suite's, checked
 * identically here and on the JVM/Android backend — which is the point, since #2506 was two
 * independently written filename mappings that had to agree by inspection and did not.
 *
 * This is also where the case-folding half of that contract is actually *decided*: the macOS and
 * iOS-simulator temp roots are APFS, which compares filenames case-insensitively, so
 * `theLegacyOverlapACaseFoldingFilesystemExposesIsExactlyTheDocumentedOne` takes its case-folding
 * arm here and its case-sensitive arm on a Linux runner. See that property for what each arm is and
 * is not worth.
 */
class NSFileManagerDurableStoreFilenameTest : DurableStoreFilenameConformanceSuite<String>() {

    override fun newDirectory(): String = freshTempDir("kuilt-store-filename")

    override suspend fun newStore(dir: String): DurableStore = NSFileManagerDurableStore(dir)

    /**
     * A raw write, bypassing the store entirely — no encoder, no `.tmp` staging, no rename.
     *
     * Staging a *legacy* filename is the whole point: it is a name the store can no longer produce,
     * so it cannot be created through [DurableStore.write]. POSIX rather than Foundation because
     * `NSData.create(bytes:length:)` needs `BetaInteropApi` that nothing else here wants.
     */
    override fun plantRawFile(dir: String, name: String, bytes: ByteArray) {
        val path = dir + name
        val handle = fopen(path, "wb") ?: error("could not create $path")
        bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), handle) }
        fclose(handle)
    }
}
