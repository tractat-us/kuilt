@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package us.tractat.kuilt.store

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.create
// NSData.writeToFile:options:error: lives in the NSExtendedData category, which
// Kotlin/Native exposes as an extension function — hence the explicit import.
import platform.Foundation.writeToFile
import platform.posix.errno
import platform.posix.memcpy
import platform.posix.rename
import platform.posix.strerror_r

/**
 * A file-backed [DurableStore] for iOS and macOS backed by `NSFileManager`.
 *
 * Each [StoreKey] maps to a single file under [directory]. Writes use an atomic
 * write-temp-then-rename strategy:
 *
 * 1. Write bytes to a sibling `.tmp` file via `NSData.writeToFile:options:error:`.
 * 2. Replace the destination with the temp file using POSIX `rename(2)`, which
 *    swaps the two directory entries atomically.
 *
 * The destination path therefore always names *some* complete record: the previous
 * one until the rename commits, the new one after. A crash before step 2 leaves
 * only the `.tmp` file, which nothing reads; a crash after it leaves the new bytes
 * in the file system's cache for the next [read]. **Process** termination cannot
 * lose a committed record.
 *
 * That guarantee is about the *directory entry*, not the bytes: the temp file is
 * never flushed before the rename, so power loss can commit the new name with the
 * new extents unwritten, leaving [read] a file that is present and empty. See
 * #2141 — unlike `FileChannelDurableStore`, which pays `FileChannel.force(true)`
 * before its own rename, this store does not force, and what that would cost
 * against a caller's per-write budget has not been measured.
 *
 * `rename(2)` rather than `NSFileManager.moveItemAtPath:toPath:error:` because
 * the latter refuses to replace an existing destination, which forced an
 * `removeItemAtPath(dest)` first — and *that* unlink was unconditional, so a
 * rename which then failed (or a crash between the two) destroyed every committed
 * record rather than leaving it stale (#2120). `replaceItemAtURL:…` is not an
 * option either: against a *directory* destination it silently succeeds and
 * deletes the tree — the same unconditional destroy this fix removes — where
 * `rename(2)` refuses with `EISDIR`.
 *
 * ## Failures carry their cause
 *
 * Every failure path here reports the underlying cause, and a thrown write failure
 * names it alongside the path, the byte count and whether the parent directory
 * exists. This is not incidental: these calls previously all passed `error = null`,
 * so a device whose writes began failing threw `"atomic rename failed"` with **no
 * cause at all** — no errno, no domain, no code — which is why a field occurrence
 * of the silent-death bug could not be diagnosed from the device's own logs
 * (#1860). Keep the causes wired. Step 1 reports its `NSError` (domain, code,
 * localized description); step 2 reports `errno` and its `strerror` text, which
 * is a *strictly better* cause than the `NSError` it replaced: `moveItemAtPath`
 * reported an `NSCocoaErrorDomain` code and carried the POSIX errno only nested
 * inside `NSUnderlyingError`, which [describe] does not unwrap — so the actual
 * reason the rename failed never reached the message at all.
 *
 * ## Directory
 *
 * Pass the path to a directory that your application owns (e.g. a subdirectory
 * of `NSApplicationSupportDirectory` or a temporary directory in tests). The
 * directory is created automatically on first write if it does not already exist.
 *
 * ## Key encoding
 *
 * The filename is [encodeStoreKeyName] of [StoreKey.name] — a lossless, path-safe
 * percent-encoding shared with `FileChannelDurableStore`. **Distinct keys are
 * always distinct files**, so a caller owes this store nothing about its key names:
 * a key may contain dots, spaces, a `/`, a `%`, non-ASCII text, or differ from
 * another key only in case, and it still addresses its own entry.
 *
 * That is a repair, not a long-standing promise. This class used to fold every
 * character outside `[a-zA-Z0-9_-]` onto `_` and document the resulting collision
 * as the *caller's* problem to avoid — under which `a.b`, `a/b`, `a b` and `a:b`
 * all shared one file and a write under one destroyed the value under another,
 * silently (#2506). The two file backends did not even agree with each other:
 * `Char.isLetterOrDigit()` is true for Cyrillic and `[^a-zA-Z0-9_-]` is not, so a
 * key that survived here collided on JVM. Hence one shared encoder rather than two
 * that must agree by inspection.
 *
 * Files written under the old scheme are **orphaned, not migrated**. The encoding's
 * safe set is deliberately narrow (`[a-z0-9-]`) so that an orphan can never be
 * misread as some other key's entry; [encodeStoreKeyName] carries that argument,
 * and states what is *not* promised — filename length.
 *
 * ## Thread safety
 *
 * `NSFileManager.defaultManager` operations and `NSData.create(contentsOfFile:)`
 * are documented thread-safe on Apple platforms. No additional locking is needed.
 *
 * Every method **throws [IllegalArgumentException] if the key's name is not
 * well-formed text** — an unpaired surrogate has no UTF-8 encoding and so no
 * filename. Note this is a property of the *file-backed* stores only:
 * `InMemoryDurableStore` and `IndexedDbDurableStore` accept such a key happily,
 * so a key that passes against an in-memory fake can throw in production. Keys
 * are normally fixed literals, where the question does not arise; it arises when
 * they are built from data.
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
        val directoryError = ensureDirectoryExists()
        val dest = filePath(key)
        val tmp = "$dest.tmp"
        memScoped {
            // Step 1: write bytes to the temp file. NSData.writeToFile reports an
            // NSError; NSFileManager.createFileAtPath (which this replaces) returns
            // a bare Boolean, which is why an on-device write failure previously
            // carried no cause at all — see the diagnosis note in the class KDoc.
            val writeError = alloc<ObjCObjectVar<NSError?>>()
            if (!bytes.toNSData().writeToFile(tmp, options = 0uL, error = writeError.ptr)) {
                error(
                    "NSFileManagerDurableStore: write to temp file failed for key=${key.name} " +
                        "path=$tmp bytes=${bytes.size} " +
                        "cause=${writeError.value.describe()} " +
                        "directory=${directoryError.describe()} " +
                        "directoryExists=${directoryExists()}",
                )
            }
            // Step 2: atomically replace dest with tmp. `rename(2)` swaps the two
            // directory entries in one step, so the destination is never absent —
            // it names the previous record until the instant it names the new one.
            // Deliberately NOT moveItemAtPath: that refuses an existing destination,
            // which is what forced an unconditional removeItemAtPath(dest) before
            // it, and that unlink lost every committed record whenever the move
            // then failed (#2120). On failure the destination is untouched and the
            // temp file is left behind for the next write to overwrite.
            if (rename(tmp, dest) != 0) {
                val code = errno
                error(
                    "NSFileManagerDurableStore: atomic rename failed for key=${key.name} " +
                        "from=$tmp to=$dest bytes=${bytes.size} " +
                        "cause=errno=$code (${describeErrno(code)}) " +
                        "directory=${directoryError.describe()} " +
                        "directoryExists=${directoryExists()}",
                )
            }
        }
    }

    override suspend fun delete(key: StoreKey) {
        // removeItemAtPath returns false when the file is absent — that is fine; it's a no-op.
        NSFileManager.defaultManager.removeItemAtPath(filePath(key), error = null)
    }

    // ---- helpers ----

    private fun filePath(key: StoreKey): String =
        normalizedDirectory() + key.filename

    private fun normalizedDirectory(): String =
        if (directory.endsWith("/")) directory else "$directory/"

    /**
     * Create [directory] if absent, returning the `NSError` if that failed.
     *
     * The error is returned rather than thrown because a failure here is not
     * necessarily fatal — the directory may already exist in a form the call
     * rejects — but it is very often the *reason* the subsequent write fails, so
     * it is carried into that failure's message instead of being discarded.
     */
    private fun ensureDirectoryExists(): NSError? = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        val created = NSFileManager.defaultManager.createDirectoryAtPath(
            path = normalizedDirectory().trimEnd('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = err.ptr,
        )
        if (created) null else err.value
    }

    private fun directoryExists(): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(normalizedDirectory().trimEnd('/'))
}

/**
 * Render an `NSError` for a failure message: domain, code and localized text.
 *
 * Every Foundation call in this store used to pass `error = null`, so an
 * on-device write failure produced a message with no cause whatsoever — no
 * errno, no domain, no code. That is the single reason a device that silently
 * stopped accepting writes could not be diagnosed from its own logs (#1860).
 */
private fun NSError?.describe(): String =
    if (this == null) {
        "none"
    } else {
        "NSError(domain=$domain, code=$code, desc=${localizedDescription})"
    }

/** Size of the `strerror_r` buffer; every Darwin errno string fits well inside it. */
private const val STRERROR_BUFFER_BYTES = 256

/**
 * Render an `errno` as its human-readable `strerror` text.
 *
 * `strerror_r`, not `strerror`: the latter may format an unrecognised code into a
 * shared static buffer, and this class documents itself as needing no external
 * locking. A cause that another thread can garble is exactly the kind of
 * untrustworthy diagnostic #1860 is about, so pay the three lines.
 */
private fun MemScope.describeErrno(code: Int): String {
    val buffer = allocArray<ByteVar>(STRERROR_BUFFER_BYTES)
    val filled = strerror_r(code, buffer, STRERROR_BUFFER_BYTES.toULong()) == 0
    return if (filled) buffer.toKString() else "unknown"
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
