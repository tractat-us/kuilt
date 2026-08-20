package us.tractat.kuilt.store

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
 * The filename is [encodeStoreKeyName] of the key's name — a lossless, path-safe
 * percent-encoding shared with `NSFileManagerDurableStore`, so **distinct keys are
 * always distinct files** and a key that looks like a path is still just a key.
 * The scheme it replaced folded everything outside `[a-zA-Z0-9_-]` onto `_`, which
 * silently merged distinct keys (#2506); files written under those names are
 * orphaned rather than migrated, and the encoding is chosen so that one can never
 * be misread as a different key's entry.
 *
 * Thread-safe: each call acquires a per-key lock via `synchronized` on a
 * canonical key string — `read` and `write` for different keys never block each
 * other. An explicit per-key lock is used (not `limitedParallelism(1)` —
 * confinement-as-mutex is banned by kuilt policy).
 *
 * Every method **throws [IllegalArgumentException] if the key's name is not
 * well-formed text** — an unpaired surrogate has no UTF-8 encoding and so no
 * filename. Note this is a property of the *file-backed* stores only:
 * `InMemoryDurableStore` and `IndexedDbDurableStore` accept such a key happily,
 * so a key that passes against an in-memory fake can throw in production. Keys
 * are normally fixed literals, where the question does not arise; it arises when
 * they are built from data.
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

    private fun fileFor(key: StoreKey): File = File(dir, key.filename)

    private fun tmpFileFor(key: StoreKey): File = File(dir, key.filename + ".tmp")

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
     * store instance. Intern on the **encoded filename** so that two calls with
     * the same key always lock on the same object.
     *
     * It has to be the same string the entry is addressed by, not the raw key
     * name: the lock's whole job is to serialize access to one file, so two keys
     * sharing a file must share a lock and two keys with distinct files must not.
     * Encoding is injective, so under this scheme those two facts coincide — one
     * lock per key — but the coupling is what keeps it true if either ever changes.
     *
     * Using [String.intern] here is safe and intentional: the set of keys is
     * small and application-controlled. The alternative — a
     * `ConcurrentHashMap<String, Any>` of locks — is heavier and adds no
     * meaningful benefit for this use case.
     */
    private fun lockFor(key: StoreKey): String = key.filename.intern()
}
