@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package us.tractat.kuilt.store

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
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
import platform.posix.EINTR
import platform.posix.F_FULLFSYNC
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fsync
import platform.posix.memcpy
import platform.posix.open
import platform.posix.rename
import platform.posix.strerror_r
import platform.posix.unlink

/**
 * A file-backed [DurableStore] for iOS and macOS backed by `NSFileManager`.
 *
 * Each [StoreKey] maps to a single file under [directory]. Writes use an atomic
 * write-temp-then-rename strategy:
 *
 * 1. Write the bytes to a sibling `.tmp` file through a POSIX descriptor —
 *    `open(O_WRONLY | O_CREAT | O_TRUNC, 0600)`, then `write(2)` until every byte
 *    is down (retrying a partial write and an `EINTR`).
 * 2. Flush that file with `fcntl(fd, F_FULLFSYNC)`, then `close` it.
 * 3. Replace the destination with the temp file using POSIX `rename(2)`, which
 *    swaps the two directory entries atomically.
 *
 * The destination path therefore always names *some* complete record: the previous
 * one until the rename commits, the new one after. A crash before step 3 leaves
 * only the `.tmp` file, which nothing reads; a write or rename that fails unlinks it.
 *
 * ## What survives what
 *
 * **Process** termination (crash, jetsam, force-quit) cannot lose a committed
 * record: the kernel's buffer cache outlives the process.
 *
 * **Power loss** cannot leave the destination empty or torn. Step 2 is what buys
 * that (#2141): without it, `rename(2)` could reach the disk before the new file's
 * data did, so a power cut left a destination that was present, named the new
 * record, and held nothing — with the previous record already gone. `F_FULLFSYNC`
 * rather than `fsync(2)` because on Apple platforms `fsync` hands the data to the
 * drive but does not make the drive flush its own write cache; `F_FULLFSYNC` is
 * Apple's documented way to ask for that. Where the file system refuses it (some
 * network volumes do) the store falls back to `fsync`, the strongest flush such a
 * volume offers.
 *
 * What power loss **can** still do is undo the *rename*: the parent directory is
 * not flushed after step 3, so a power cut just after [write] returns may come back
 * to the **previous** complete record rather than the new one. Old or new, never
 * torn. `FileChannelDurableStore` makes exactly the same trade — it forces the file
 * before its rename and does not force the directory after — so the two file
 * backends promise the same thing.
 *
 * The flush has a cost on every write, which matters because this store sits on a
 * telemetry exporter's per-export path (#1860, #2126); the measurement that
 * accepted it is recorded on #2141.
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
 * (#1860). Keep the causes wired. Every write step reports `errno`, its
 * `strerror` text and the system call that set it (`open`, `write`, `fsync`,
 * `close`, `rename`); directory creation still goes through `NSFileManager` and
 * reports its `NSError`. `errno` is a *strictly better* cause than the `NSError`s
 * it replaced: Foundation reported an `NSCocoaErrorDomain` code and carried the
 * POSIX errno only nested inside `NSUnderlyingError`, which [describe] does not
 * unwrap — so the actual reason a write failed never reached the message at all.
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
 * are documented thread-safe on Apple platforms, and the write path holds nothing
 * outside its own call — its descriptor is local and `errno` is per-thread. No
 * additional locking is needed.
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
            // Steps 1 and 2: write the temp file through a POSIX descriptor and flush it to
            // stable storage BEFORE the rename can publish it (#2141). Nothing here suspends,
            // so there is no cancellation point between the open and the close to guard.
            writeFlushed(tmp, bytes)?.let { failure ->
                unlink(tmp)
                error(
                    "NSFileManagerDurableStore: write to temp file failed for key=${key.name} " +
                        "path=$tmp bytes=${bytes.size} " +
                        "cause=errno=${failure.errno} (${describeErrno(failure.errno)}) step=${failure.step} " +
                        "directory=${directoryError.describe()} " +
                        "directoryExists=${directoryExists()}",
                )
            }
            // Step 3: atomically replace dest with tmp. `rename(2)` swaps the two
            // directory entries in one step, so the destination is never absent —
            // it names the previous record until the instant it names the new one.
            // Deliberately NOT moveItemAtPath: that refuses an existing destination,
            // which is what forced an unconditional removeItemAtPath(dest) before
            // it, and that unlink lost every committed record whenever the move
            // then failed (#2120). On failure the destination is untouched and the
            // temp file is unlinked.
            if (rename(tmp, dest) != 0) {
                val code = errno
                unlink(tmp)
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

/** The system call that failed while writing a temp file, and the `errno` it left. */
private class PosixFailure(val step: String, val errno: Int)

/** `rw-------`: the temp file — and so, after the rename, the record — is the owner's alone. */
private val TEMP_FILE_MODE = S_IRUSR or S_IWUSR

/**
 * Write [bytes] to a fresh file at [path] and flush it to stable storage, returning
 * `null` on success or the step that failed.
 *
 * The descriptor is closed on every path, a failed write or flush included; the
 * caller unlinks [path] on failure. Each `errno` is read straight after the call
 * that set it, before `close` can overwrite it.
 */
private fun writeFlushed(path: String, bytes: ByteArray): PosixFailure? {
    val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC, TEMP_FILE_MODE)
    if (fd < 0) return PosixFailure("open", errno)
    val failure = writeFully(fd, bytes) ?: flush(fd)
    val closeFailure = if (close(fd) == 0) null else PosixFailure("close", errno)
    return failure ?: closeFailure
}

/** `write(2)` until every byte is down, retrying a partial write and an `EINTR`. */
private fun writeFully(fd: Int, bytes: ByteArray): PosixFailure? {
    if (bytes.isEmpty()) return null
    return bytes.usePinned { pinned ->
        var offset = 0
        var failure: PosixFailure? = null
        while (offset < bytes.size && failure == null) {
            val written = platform.posix.write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
            if (written >= 0) {
                offset += written.toInt()
            } else {
                val code = errno
                if (code != EINTR) failure = PosixFailure("write", code)
            }
        }
        failure
    }
}

/**
 * Flush [fd] with `F_FULLFSYNC`, falling back to `fsync(2)` where the file system
 * refuses the full flush — the same fallback SQLite uses on Apple platforms. Only a
 * failure of the fallback is reported; a refused `F_FULLFSYNC` alone is not.
 */
private fun flush(fd: Int): PosixFailure? = when {
    fcntl(fd, F_FULLFSYNC) == 0 -> null
    fsync(fd) == 0 -> null
    else -> PosixFailure("fsync", errno)
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

// ---- NSData → ByteArray (apple-only; private to this file) ----

private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}
