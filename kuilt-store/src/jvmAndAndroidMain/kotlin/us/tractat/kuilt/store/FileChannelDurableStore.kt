package us.tractat.kuilt.store

import java.io.File

/**
 * A crash-safe [DurableStore] for JVM and Android.
 *
 * Each [StoreKey] maps to a single file under [dir]. A [write] commits in three steps. Each one
 * throws on failure; none reports it through a flag the store could ignore:
 *
 * 1. Write the bytes to a sibling `.tmp` file and call `FileChannel.force(true)`, which flushes data
 *    **and** metadata to the device (`fsync`).
 * 2. Replace the destination with the temp file using `Files.move` with `ATOMIC_MOVE` — `rename(2)`
 *    on Linux, macOS and Android. If the filesystem cannot move atomically the JDK throws
 *    `AtomicMoveNotSupportedException`, and so does [write]: there is no copying fallback.
 * 3. Force the directory, so the rename itself is on the device before [write] returns.
 *
 * [delete] removes the file with `Files.deleteIfExists` (a file that is present and cannot be
 * removed throws) and then forces the directory the same way. Creating [dir] forces each newly
 * created directory's parent, outermost first, so a store whose directory did not exist yet cannot
 * lose that directory, and everything in it, to a power cut after its first [write] returns.
 *
 * ## What survives what
 *
 * **Process** death cannot lose a committed record: the kernel's buffer cache outlives the process.
 * A crash before step 2 completes leaves only the `.tmp` file, which nothing reads. The destination
 * always names *some* complete record: the previous one until the rename, the new one after.
 *
 * **Power loss on Linux, macOS and Android:** once [write] returns, the new record is the one that
 * comes back. Step 1 means the renamed file cannot come back empty or torn, and step 3 means the
 * rename cannot be undone. The same holds for a [delete] that has returned. If the directory cannot
 * be forced, the call throws instead of returning.
 *
 * **Power loss on Windows is weaker.** The JVM cannot open a directory there at all (it never asks
 * `CreateFileW` for `FILE_FLAG_BACKUP_SEMANTICS`), and its atomic move is `MoveFileEx` without
 * `MOVEFILE_WRITE_THROUGH`, so step 3 has nothing to call and is skipped. The rename then rests on
 * NTFS's metadata journal: a power cut just after [write] returns can bring back the **previous**
 * complete record rather than the new one (old or new, never torn), and a [delete] can come undone
 * the same way. No native code is used to close this. That is reasoning about NTFS, not something
 * any test here observes.
 *
 * `NSFileManagerDurableStore` forces the file before its rename but not the directory after it, so
 * on Apple platforms the guarantee is the Windows one, not the Linux one.
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
 * Needs `java.nio.file`: Android API 26, or a desugared `java.nio.file` below it.
 *
 * @param dir The directory that holds the store's files. Created, with any missing ancestors, if it
 *   does not exist; must be a directory if it does. Must be writable.
 */
public class FileChannelDurableStore internal constructor(
    private val dir: File,
    private val fs: StoreFileSystem,
    private val directorySync: DirectorySync,
) : DurableStore {

    public constructor(dir: File) : this(dir, NioStoreFileSystem, DirectorySync.forHost())

    init {
        createWithAncestry(dir)
        require(dir.isDirectory) { "$dir exists and is not a directory" }
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
            fs.deleteIfExists(file)
            // Even when nothing was there: an earlier delete may have unlinked and then failed to
            // sync, and this call returning promises that the absence is durable.
            directorySync.after(fs, dir)
        }
    }

    // ---- private helpers ----

    private fun fileFor(key: StoreKey): File = File(dir, key.filename)

    private fun tmpFileFor(key: StoreKey): File = File(dir, key.filename + ".tmp")

    private fun writeAtomically(tmp: File, dest: File, bytes: ByteArray) {
        fs.writeAndForce(tmp, bytes)
        fs.moveAtomically(tmp, dest)
        directorySync.after(fs, dir)
    }

    /**
     * Creates every missing directory from the outermost down to [target], forcing each one's parent
     * as it goes: a new directory is an entry in its parent, and a power cut can lose an entry nobody
     * forced just as it can lose a rename.
     */
    private fun createWithAncestry(target: File) {
        val missing = generateSequence(target.absoluteFile) { it.parentFile }
            .takeWhile { !fs.exists(it) }
            .toList()
            .asReversed()
        for (created in missing) {
            fs.createDirectory(created)
            directorySync.after(fs, checkNotNull(created.parentFile) { "no filesystem root above $target" })
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
