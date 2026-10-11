package us.tractat.kuilt.store

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The filesystem operations [FileChannelDurableStore] commits through, one per step of its protocol.
 *
 * Every operation **throws on failure** — none returns a success flag for the caller to forget to
 * check, which is how the store's `delete` and its old `renameTo` fallback once lost their failures
 * (#2875). The seam exists so a test can record the order the store calls these in and make any one
 * of them fail; [NioStoreFileSystem] is the only production implementation.
 */
internal interface StoreFileSystem {
    fun exists(file: File): Boolean

    /** Creates [dir], whose parent must exist. Succeeds if [dir] is already a directory. */
    fun createDirectory(dir: File)

    /** Writes [bytes] to [file], replacing its contents, and forces data and metadata to the device. */
    fun writeAndForce(file: File, bytes: ByteArray)

    /** Replaces [dest] with [src] as one atomic filesystem operation, or throws. Never copies. */
    fun moveAtomically(src: File, dest: File)

    /** Removes [file] if present. Throws when it is present and cannot be removed. */
    fun deleteIfExists(file: File)

    /** Forces [dir]'s entries — renames, unlinks, new children — to the device. */
    fun syncDirectory(dir: File)
}

/** [StoreFileSystem] over `java.nio.file`. */
internal object NioStoreFileSystem : StoreFileSystem {
    override fun exists(file: File): Boolean = file.exists()

    override fun createDirectory(dir: File) {
        try {
            Files.createDirectory(dir.toPath())
        } catch (e: FileAlreadyExistsException) {
            // Another opener created it between our existence check and this call. Only a
            // directory is acceptable; a regular file squatting on the name is not.
            if (!dir.isDirectory) throw e
        }
    }

    override fun writeAndForce(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { fos ->
            fos.write(bytes)
            fos.channel.force(true)
        }
    }

    override fun moveAtomically(src: File, dest: File) {
        // The default providers replace an existing target under ATOMIC_MOVE (`rename(2)` on Unix,
        // `MoveFileEx` with `MOVEFILE_REPLACE_EXISTING` on Windows). Where the move cannot be atomic
        // the JDK throws `AtomicMoveNotSupportedException`, which propagates: there is no
        // non-atomic fallback.
        Files.move(src.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun deleteIfExists(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    override fun syncDirectory(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}

/**
 * Whether [FileChannelDurableStore] can force a directory after changing its entries.
 *
 * Decided once per host, never per call, and never by the caller: a store that could sync but
 * skipped it would return from `write` before the rename was durable, which is the failure #2875
 * exists to remove.
 */
internal enum class DirectorySync {
    /**
     * Linux, macOS and Android: a directory opens read-only as a [FileChannel] and `force` reaches
     * `fsync(2)` on it. A failure to sync throws.
     */
    Forced {
        override fun after(fs: StoreFileSystem, dir: File) = fs.syncDirectory(dir)
    },

    /**
     * Windows: the JVM cannot open a directory as a channel at all (it never passes
     * `FILE_FLAG_BACKUP_SEMANTICS` to `CreateFileW`), and its atomic move is `MoveFileEx` without
     * `MOVEFILE_WRITE_THROUGH`, so there is nothing to call. A committed rename rests on NTFS's
     * metadata journal instead — see [FileChannelDurableStore] for what that does and does not
     * promise. No native code is used to close the gap.
     */
    UnavailableOnWindows {
        override fun after(fs: StoreFileSystem, dir: File) = Unit
    },
    ;

    /** Makes the entry changes just made in [dir] durable, where this host can. */
    abstract fun after(fs: StoreFileSystem, dir: File)

    companion object {
        /** The policy for the host whose `os.name` is [osName]. Android reports `Linux`. */
        fun forHost(osName: String = System.getProperty("os.name").orEmpty()): DirectorySync =
            if (osName.startsWith("Windows", ignoreCase = true)) UnavailableOnWindows else Forced
    }
}
