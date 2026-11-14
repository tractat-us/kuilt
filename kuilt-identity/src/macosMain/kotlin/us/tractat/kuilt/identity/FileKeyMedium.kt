@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.identity

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.F_FULLFSYNC
import platform.posix.LOCK_EX
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.flock
import platform.posix.free
import platform.posix.fsync
import platform.posix.lstat
import platform.posix.mkstemps
import platform.posix.open
import platform.posix.read
import platform.posix.realpath
import platform.posix.rename
import platform.posix.stat
import platform.posix.strerror_r
import platform.posix.unlink
import platform.posix.write

/**
 * The key as a file, `device-key.jwk`, in [directory]: what the macOS store keeps instead of a Keychain
 * item (Decided 28).
 *
 * ## Writing
 *
 * A key is written to a fresh temporary file `device-key.jwk.<random>.tmp` (mode `0600`, from
 * `mkstemps`), flushed with `F_FULLFSYNC`, renamed over `device-key.jwk`, and then the directory is
 * flushed, so the key file always holds a whole key: none until the rename, the new one after. A
 * crash leaves at most a temporary file, which nothing reads and the next key write deletes.
 *
 * ## Reading
 *
 * Only `ENOENT` from opening the key file means "no key yet". Any other failure to open or read it —
 * no permission, a symbolic-link loop, an I/O error — throws [IllegalStateException] naming the
 * `errno`, and nothing is written.
 *
 * A refused key file is not overwritten: it is renamed to the first free `device-key.jwk.refused-<n>`,
 * so a key judged wrongly can still be put back by hand.
 *
 * ## Across processes
 *
 * [exclusively] holds an exclusive `flock` on `device-key.lock` in the directory, so two processes
 * over one directory take turns, as two stores in one process do through the store's mutex. Waiting
 * for that lock blocks the calling thread, as the desktop JVM store's does.
 */
internal class FileKeyMedium(directory: String) : KeyMedium {
    /** The directory, created with any missing ancestors, and resolved through any symbolic links. */
    val directory: String = createdAndResolved(directory.trimEnd('/'))

    private val keyFile = "${this.directory}/$KEY_FILE"
    private val lockFile = "${this.directory}/$LOCK_FILE"

    override val description: String = "the file $keyFile"

    override suspend fun <T> exclusively(block: suspend () -> T): T {
        val fd = open(lockFile, O_CREAT or O_RDWR or O_CLOEXEC, OWNER_ONLY)
        if (fd < 0) throw posixFailed("open", lockFile, errno)
        try {
            while (flock(fd, LOCK_EX) != 0) {
                val code = errno
                if (code != EINTR) throw posixFailed("flock", lockFile, code)
            }
            return block() // closing the descriptor releases the lock
        } finally {
            close(fd)
        }
    }

    override fun read(): ByteArray? {
        val fd = open(keyFile, O_RDONLY or O_CLOEXEC)
        if (fd < 0) {
            val code = errno
            if (code == ENOENT) return null
            throw unreadable("open", code)
        }
        try {
            return readAll(fd) ?: throw unreadable("read", errno)
        } finally {
            close(fd)
        }
    }

    override fun write(jwk: ByteArray) {
        removeLeftovers()
        val (fd, temp) = memScoped {
            val template = "$keyFile.XXXXXX$TEMP_SUFFIX".cstr.getPointer(this)
            mkstemps(template, TEMP_SUFFIX.length) to template.toKString()
        }
        if (fd < 0) throw posixFailed("mkstemps", temp, errno)
        var renamed = false
        try {
            val failed = writeAll(fd, jwk) ?: flush(fd)
            val closed = close(fd) == 0
            if (failed != null) throw posixFailed(failed.first, temp, failed.second)
            if (!closed) throw posixFailed("close", temp, errno)
            if (rename(temp, keyFile) != 0) throw posixFailed("rename", keyFile, errno)
            renamed = true
        } finally {
            if (!renamed) unlink(temp)
        }
        flushDirectory()
    }

    override fun replaceRefused(jwk: ByteArray): String {
        val aside = generateSequence(1) { it + 1 }.map { "$keyFile.refused-$it" }.first { !exists(it) }
        if (rename(keyFile, aside) != 0) throw posixFailed("rename", aside, errno)
        write(jwk)
        return "kept as $aside"
    }

    /** Deletes what a crash between a temporary file's write and its rename left. Runs under the lock. */
    private fun removeLeftovers() {
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, error = null).orEmpty()
        names.filterIsInstance<String>()
            .filter { it.startsWith("$KEY_FILE.") && it.endsWith(TEMP_SUFFIX) }
            .forEach { unlink("$directory/$it") }
    }

    private fun flushDirectory() {
        val fd = open(directory, O_RDONLY or O_CLOEXEC)
        if (fd < 0) throw posixFailed("open", directory, errno)
        val failed = flush(fd)
        close(fd)
        if (failed != null) throw posixFailed(failed.first, directory, failed.second)
    }

    private fun unreadable(step: String, code: Int) = IllegalStateException(
        "kuilt-identity: the device key file $keyFile is there but cannot be read ($step: errno=$code, " +
            "${describeErrno(code)}), so it is kept and no key is made",
    )

    internal companion object {
        /** The key file's name in the store's directory. */
        const val KEY_FILE: String = "device-key.jwk"

        /** The file every call locks, so processes over one directory take turns. */
        const val LOCK_FILE: String = "device-key.lock"

        private const val TEMP_SUFFIX = ".tmp"
    }
}

/** `rw-------`. */
private val OWNER_ONLY = S_IRUSR or S_IWUSR

private const val READ_CHUNK = 4096

/** Every byte [fd] holds, or `null` when a read fails (with `errno` set). */
private fun readAll(fd: Int): ByteArray? {
    var bytes = ByteArray(0)
    val chunk = ByteArray(READ_CHUNK)
    while (true) {
        val count = chunk.usePinned { read(fd, it.addressOf(0), READ_CHUNK.convert()) }
        when {
            count > 0 -> bytes += chunk.copyOf(count.toInt())
            count == 0L -> return bytes.also { chunk.fill(0) }
            errno != EINTR -> return null.also { chunk.fill(0) }
        }
    }
}

/** `write(2)` until every byte is down, retrying a partial write and an `EINTR`; the failing step and errno, or `null`. */
private fun writeAll(fd: Int, bytes: ByteArray): Pair<String, Int>? {
    if (bytes.isEmpty()) return null
    return bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
            val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
            if (written >= 0) {
                offset += written.toInt()
            } else {
                val code = errno
                if (code != EINTR) return@usePinned "write" to code
            }
        }
        null
    }
}

/** `F_FULLFSYNC`, falling back to `fsync(2)` where the file system refuses it, as `NSFileManagerDurableStore` does. */
private fun flush(fd: Int): Pair<String, Int>? = when {
    fcntl(fd, F_FULLFSYNC) == 0 -> null
    fsync(fd) == 0 -> null
    else -> "fsync" to errno
}

/** Whether anything, a dangling symbolic link included, is at [path]. */
private fun exists(path: String): Boolean = memScoped {
    lstat(path, alloc<stat>().ptr) == 0 || errno != ENOENT
}

/** [path], created with any missing ancestors, then resolved through symbolic links. */
private fun createdAndResolved(path: String): String {
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val created = NSFileManager.defaultManager.createDirectoryAtPath(
            path = path,
            withIntermediateDirectories = true,
            attributes = null,
            error = error.ptr,
        )
        if (!created) {
            throw IllegalStateException(
                "kuilt-identity: could not create the device key directory $path (${error.value?.localizedDescription})",
            )
        }
    }
    val resolved = realpath(path, null) ?: throw posixFailed("realpath", path, errno)
    return try {
        resolved.toKString()
    } finally {
        free(resolved)
    }
}

private fun posixFailed(step: String, path: String, code: Int) =
    IllegalStateException("kuilt-identity: $step failed for $path (errno=$code, ${describeErrno(code)})")

private const val STRERROR_BUFFER_BYTES = 256

private fun describeErrno(code: Int): String = memScoped {
    val buffer = allocArray<ByteVar>(STRERROR_BUFFER_BYTES)
    if (strerror_r(code, buffer, STRERROR_BUFFER_BYTES.convert()) == 0) buffer.toKString() else "unknown"
}
