package us.tractat.kuilt.otel

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A crash-safe [DurableStore] for JVM and Android.
 *
 * Each [StoreKey] maps to a single file under [dir]. Writes use an atomic
 * write-temp-then-rename strategy:
 *
 * 1. Write bytes to a sibling `.tmp` file.
 * 2. Call `FileChannel.force(true)` to flush data **and** metadata to the
 *    underlying storage device (equivalent to `fsync`).
 * 3. Atomically rename the temp file to the final name (POSIX atomic rename;
 *    on Android this uses `Files.move` with `ATOMIC_MOVE` on API 26+ or a
 *    best-effort fallback on older APIs).
 *
 * A crash after step 3 returns means the renamed file is on disk; the next
 * [read] returns the committed bytes. A crash before step 3 completes leaves
 * the `.tmp` file, which is ignored on the next open. There is no window
 * where neither the old nor the new value is visible.
 *
 * Thread-safe: each call acquires a per-key lock via `synchronized` on a
 * canonical key string — `read` and `write` for different keys never block each
 * other. An explicit per-key lock is used (not `limitedParallelism(1)` —
 * confinement-as-mutex is banned by kuilt policy).
 *
 * @param dir The directory that holds the store's files. Created if it does
 *   not exist. Must be writable.
 */
public class FileChannelDurableStore(private val dir: File) : DurableStore {

    init {
        dir.mkdirs()
    }

    override suspend fun read(key: StoreKey): ByteArray? {
        val file = fileFor(key)
        return synchronized(lockFor(key)) {
            if (!file.exists()) null else file.readBytes()
        }
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        val dest = fileFor(key)
        val tmp = tmpFileFor(key)
        synchronized(lockFor(key)) {
            writeAtomically(tmp, dest, bytes)
        }
    }

    override suspend fun delete(key: StoreKey) {
        val file = fileFor(key)
        synchronized(lockFor(key)) {
            file.delete()
        }
    }

    // ---- private helpers ----

    private fun fileFor(key: StoreKey): File = File(dir, sanitize(key.name))

    private fun tmpFileFor(key: StoreKey): File = File(dir, sanitize(key.name) + ".tmp")

    /**
     * Sanitizes a [StoreKey.name] to a safe filename.
     *
     * Dots (used in keys like `otel.spans`) are replaced with `_` to avoid
     * confusion with file extensions. Characters outside `[a-zA-Z0-9_-]` are
     * replaced with `_` so the filename is safe on all JVM/Android filesystems.
     */
    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    private fun writeAtomically(tmp: File, dest: File, bytes: ByteArray) {
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.channel.force(true)
        }
        moveAtomically(tmp, dest)
    }

    /**
     * Moves [src] to [dest] atomically. Uses [Files.move] with
     * [StandardCopyOption.ATOMIC_MOVE] where supported; falls back to
     * [File.renameTo] for environments where `ATOMIC_MOVE` is not available.
     */
    private fun moveAtomically(src: File, dest: File) {
        try {
            Files.move(src.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            // Android < API 26 or some non-POSIX filesystem: best-effort rename.
            src.renameTo(dest)
        }
    }

    /**
     * Returns a lock object that is canonical for the given key within this
     * store instance. Intern on the sanitized name so that two calls with the
     * same key always lock on the same object.
     *
     * Using [String.intern] here is safe and intentional: the set of keys is
     * small and application-controlled (one per CRDT). The alternative — a
     * `ConcurrentHashMap<String, Any>` of locks — is heavier and adds no
     * meaningful benefit for this use case.
     */
    private fun lockFor(key: StoreKey): String = sanitize(key.name).intern()
}
