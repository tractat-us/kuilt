@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package us.tractat.kuilt.otel

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.posix.memcpy

/**
 * A crash-safe [DurableStore] for iOS and macOS backed by `NSFileManager`.
 *
 * Each [StoreKey] maps to a single file under [directory]. Writes use an atomic
 * write-temp-then-rename strategy:
 *
 * 1. Write bytes to a sibling `.tmp` file via `NSFileManager.createFileAtPath:contents:attributes:`.
 * 2. Atomically rename the temp file to the final name using
 *    `NSFileManager.moveItemAtPath:toPath:error:` — on Apple file systems this
 *    maps to a POSIX `rename(2)` syscall, which is atomic.
 *
 * A crash after step 2 returns means the renamed file is on disk; the next
 * [read] returns the committed bytes. A crash before step 2 leaves only the
 * `.tmp` file, which is ignored on the next open.
 *
 * ## Directory
 *
 * Pass the path to a directory that your application owns (e.g. a subdirectory
 * of `NSApplicationSupportDirectory` or a temporary directory in tests). The
 * directory is created automatically on first write if it does not already exist.
 *
 * ## Key sanitization
 *
 * [StoreKey.name] is sanitized before being used as a filename: characters
 * outside `[a-zA-Z0-9_-]` are replaced with `_`. Keys that differ only in their
 * sanitized form will collide — callers should ensure keys are unique after
 * sanitization (the [WarpSpanExporter] only ever writes `"otel.spans"` →
 * `"otel_spans"`, so this is not a concern in practice).
 *
 * ## Thread safety
 *
 * `NSFileManager.defaultManager` operations and `NSData.create(contentsOfFile:)`
 * are documented thread-safe on Apple platforms. No additional locking is needed.
 *
 * @param directory Absolute path to the directory where files are stored.
 *   A trailing slash is accepted; the implementation normalises it.
 */
public class NSFileManagerDurableStore(private val directory: String) : DurableStore {

    override suspend fun read(key: StoreKey): ByteArray? {
        val data = NSData.create(contentsOfFile = filePath(key)) ?: return null
        return data.toByteArray()
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        ensureDirectoryExists()
        val dest = filePath(key)
        val tmp = "$dest.tmp"
        val fm = NSFileManager.defaultManager
        // Step 1: write bytes to the temp file.
        check(fm.createFileAtPath(tmp, contents = bytes.toNSData(), attributes = null)) {
            "NSFileManagerDurableStore: write to temp file failed for key=${key.name}"
        }
        // Step 2: atomically rename temp to dest (POSIX rename(2) under the hood).
        // Remove dest first so moveItem does not fail when dest already exists.
        fm.removeItemAtPath(dest, error = null)
        check(fm.moveItemAtPath(tmp, toPath = dest, error = null)) {
            "NSFileManagerDurableStore: atomic rename failed for key=${key.name}"
        }
    }

    override suspend fun delete(key: StoreKey) {
        // removeItemAtPath returns false when the file is absent — that is fine; it's a no-op.
        NSFileManager.defaultManager.removeItemAtPath(filePath(key), error = null)
    }

    // ---- helpers ----

    private fun filePath(key: StoreKey): String =
        normalizedDirectory() + sanitize(key.name)

    private fun normalizedDirectory(): String =
        if (directory.endsWith("/")) directory else "$directory/"

    private fun sanitize(name: String): String =
        buildString {
            for (ch in name) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_')
            }
        }

    private fun ensureDirectoryExists() {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = normalizedDirectory().trimEnd('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }
}

// ---- ByteArray ↔ NSData conversions (apple-only; private to this file) ----

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }

private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}
