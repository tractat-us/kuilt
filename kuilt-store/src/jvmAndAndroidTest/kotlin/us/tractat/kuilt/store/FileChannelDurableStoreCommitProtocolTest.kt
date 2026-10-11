package us.tractat.kuilt.store

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The order in which [FileChannelDurableStore] calls the filesystem, and what it does when a call
 * fails (#2875).
 *
 * This proves the **protocol**, not physical power-loss behaviour. [RecordingFileSystem] runs every
 * operation for real against a temp directory and records it; nothing here cuts power. The claim is
 * narrower and checkable: the store forces the temp file, then renames, then forces the directory;
 * it forces the directory after a delete and above every directory it creates; and a failed move,
 * delete or directory sync reaches the caller as an exception instead of a return. Whether a
 * directory `fsync` really makes a rename survive power loss is the kernel's promise, and no
 * in-process test can observe it. `DurableStoreConformanceSuite`'s residual row says the same about
 * the file `force`.
 *
 * In `jvmAndAndroidTest`, like [FileChannelDurableStoreTest], so the Android variant runs it too.
 */
class FileChannelDurableStoreCommitProtocolTest {

    private val key = StoreKey("k")
    private val dest = key.filename
    private val tmp = key.filename + ".tmp"

    @Test
    fun aWriteForcesTheTempFileThenRenamesItThenSyncsTheDirectory() = runTest {
        val root = freshTempDir("kuilt-store-protocol")
        val fs = RecordingFileSystem(root)
        val store = FileChannelDurableStore(root, fs, DirectorySync.Forced)

        store.write(key, byteArrayOf(1))

        assertEquals(
            listOf(Op.WriteAndForce(tmp), Op.Move(tmp, dest), Op.SyncDirectory("")),
            fs.ops,
        )
    }

    @Test
    fun aDeleteRemovesTheFileThenSyncsTheDirectory() = runTest {
        val root = freshTempDir("kuilt-store-protocol")
        val fs = RecordingFileSystem(root)
        val store = FileChannelDurableStore(root, fs, DirectorySync.Forced)
        store.write(key, byteArrayOf(1))
        fs.ops.clear()

        store.delete(key)

        assertEquals(listOf(Op.Delete(dest), Op.SyncDirectory("")), fs.ops)
    }

    /**
     * An earlier delete may have unlinked the file and then failed its sync. A later delete finds
     * nothing to remove, and if it returned without syncing it would promise an absence that a
     * power cut could still undo.
     */
    @Test
    fun aDeleteOfAnAbsentKeyStillSyncsTheDirectory() = runTest {
        val root = freshTempDir("kuilt-store-protocol")
        val fs = RecordingFileSystem(root)
        val store = FileChannelDurableStore(root, fs, DirectorySync.Forced)

        store.delete(key)

        assertEquals(listOf(Op.Delete(dest), Op.SyncDirectory("")), fs.ops)
    }

    @Test
    fun aStoreOverAMissingDirectoryCreatesEachAncestorOutermostFirstAndSyncsItsParent() = runTest {
        val root = freshTempDir("kuilt-store-protocol")
        val fs = RecordingFileSystem(root)

        FileChannelDurableStore(File(root, "a/b/c"), fs, DirectorySync.Forced)

        assertEquals(
            listOf(
                Op.CreateDirectory("a"),
                Op.SyncDirectory(""),
                Op.CreateDirectory("a/b"),
                Op.SyncDirectory("a"),
                Op.CreateDirectory("a/b/c"),
                Op.SyncDirectory("a/b"),
            ),
            fs.ops,
        )
    }

    @Test
    fun aStoreOverAnExistingDirectoryCreatesAndSyncsNothing() = runTest {
        val root = freshTempDir("kuilt-store-protocol")
        val fs = RecordingFileSystem(root)

        FileChannelDurableStore(root, fs, DirectorySync.Forced)

        assertEquals(emptyList(), fs.ops)
    }

    @Test
    fun aFailedMoveThrowsSyncsNothingAndLeavesThePreviousRecord() = runTest {
        val root = freshTempDir("kuilt-store-protocol")
        val fs = RecordingFileSystem(root)
        val store = FileChannelDurableStore(root, fs, DirectorySync.Forced)
        store.write(key, byteArrayOf(1))
        fs.ops.clear()
        fs.failOn = { it is Op.Move }

        assertFailsWith<IOException> { store.write(key, byteArrayOf(2)) }

        val ops = fs.ops.toList()
        val survivor = store.read(key)
        assertAll(
            { assertEquals(listOf(Op.WriteAndForce(tmp), Op.Move(tmp, dest)), ops) },
            { assertContentEquals(byteArrayOf(1), survivor) },
        )
    }

    @Test
    fun aFailedDeleteThrowsAndSyncsNothing() = runTest {
        val root = freshTempDir("kuilt-store-protocol")
        val fs = RecordingFileSystem(root)
        val store = FileChannelDurableStore(root, fs, DirectorySync.Forced)
        store.write(key, byteArrayOf(1))
        fs.ops.clear()
        fs.failOn = { it is Op.Delete }

        assertFailsWith<IOException> { store.delete(key) }

        assertEquals(listOf<Op>(Op.Delete(dest)), fs.ops)
    }

    @Test
    fun aFailedDirectorySyncThrowsFromWriteDeleteAndConstruction() = runTest {
        val root = freshTempDir("kuilt-store-protocol")
        val fs = RecordingFileSystem(root)
        val store = FileChannelDurableStore(root, fs, DirectorySync.Forced)
        fs.failOn = { it is Op.SyncDirectory }

        val fromWrite = ioFailureOf { store.write(key, byteArrayOf(1)) }
        val fromDelete = ioFailureOf { store.delete(key) }
        val fromConstruction = ioFailureOf { FileChannelDurableStore(File(root, "new"), fs, DirectorySync.Forced) }

        assertAll(
            { assertNotNull(fromWrite, "write returned instead of throwing") },
            { assertNotNull(fromDelete, "delete returned instead of throwing") },
            { assertNotNull(fromConstruction, "construction returned instead of throwing") },
        )
    }

    /**
     * Windows cannot force a directory from the JVM, so the store skips that step there. This pins
     * that it skips **only** that step: the same scenario under both policies issues the same
     * operations once the syncs are filtered out, and ends with the same contents.
     */
    @Test
    fun onWindowsEveryOperationButTheDirectorySyncIsUnchanged() = runTest {
        suspend fun scenario(policy: DirectorySync): Pair<List<Op>, ByteArray?> {
            val root = freshTempDir("kuilt-store-protocol")
            val fs = RecordingFileSystem(root)
            val store = FileChannelDurableStore(File(root, "a/b"), fs, policy)
            store.write(key, byteArrayOf(1))
            store.write(key, byteArrayOf(2))
            store.write(StoreKey("other"), byteArrayOf(3))
            store.delete(StoreKey("other"))
            return fs.ops.toList() to store.read(key)
        }

        val (forced, forcedRead) = scenario(DirectorySync.Forced)
        val (windows, windowsRead) = scenario(DirectorySync.UnavailableOnWindows)

        assertAll(
            { assertEquals(6, forced.count { it is Op.SyncDirectory }, "positive control: the forced run syncs") },
            { assertEquals(forced.filterNot { it is Op.SyncDirectory }, windows) },
            { assertContentEquals(forcedRead, windowsRead) },
        )
    }

    @Test
    fun onlyAWindowsHostSkipsTheDirectorySync() {
        assertAll(
            { assertEquals(DirectorySync.UnavailableOnWindows, DirectorySync.forHost("Windows 11")) },
            { assertEquals(DirectorySync.UnavailableOnWindows, DirectorySync.forHost("Windows Server 2022")) },
            { assertEquals(DirectorySync.Forced, DirectorySync.forHost("Linux")) },
            { assertEquals(DirectorySync.Forced, DirectorySync.forHost("Mac OS X")) },
            { assertEquals(DirectorySync.Forced, DirectorySync.forHost("")) },
        )
    }

    /**
     * The production filesystem reports each failure by throwing. The recorder above injects
     * failures, so it cannot show that the real implementation does not swallow one; this does,
     * with failures the host filesystem produces itself.
     */
    @Test
    fun theNioFileSystemThrowsOnEveryRealFailure() {
        val root = freshTempDir("kuilt-store-protocol")
        val nonEmpty = File(root, "full").also { it.mkdir() }
        File(nonEmpty, "child").writeBytes(byteArrayOf(1))
        val regularFile = File(root, "file").also { it.writeBytes(byteArrayOf(1)) }

        assertAll(
            { assertFailsWith<NoSuchFileException> { NioStoreFileSystem.syncDirectory(File(root, "missing")) } },
            { assertFailsWith<NoSuchFileException> { NioStoreFileSystem.moveAtomically(File(root, "missing"), File(root, "dest")) } },
            { assertFailsWith<DirectoryNotEmptyException> { NioStoreFileSystem.deleteIfExists(nonEmpty) } },
            { assertFailsWith<FileAlreadyExistsException> { NioStoreFileSystem.createDirectory(regularFile) } },
        )
    }

    @Test
    fun theNioFileSystemAcceptsWhatIsNotAFailure() {
        val root = freshTempDir("kuilt-store-protocol")

        NioStoreFileSystem.deleteIfExists(File(root, "absent"))
        NioStoreFileSystem.createDirectory(root)
        NioStoreFileSystem.syncDirectory(root)

        assertTrue(root.isDirectory)
    }
}

/** The [IOException] [block] threw, or `null` if it returned. Inline so [block] may suspend. */
private inline fun ioFailureOf(block: () -> Unit): IOException? =
    try {
        block()
        null
    } catch (e: IOException) {
        e
    }

/** One filesystem call, with paths relative to the test's root so two runs can be compared. */
private sealed interface Op {
    data class CreateDirectory(val dir: String) : Op
    data class WriteAndForce(val file: String) : Op
    data class Move(val src: String, val dest: String) : Op
    data class Delete(val file: String) : Op
    data class SyncDirectory(val dir: String) : Op
}

/**
 * Runs every operation on [NioStoreFileSystem] for real, records it first, and throws instead when
 * [failOn] matches. Existence checks are queries, not protocol steps, so they are not recorded.
 */
private class RecordingFileSystem(private val root: File) : StoreFileSystem {
    val ops = mutableListOf<Op>()
    var failOn: (Op) -> Boolean = { false }

    private fun File.rel(): String = relativeTo(root).path

    private fun record(op: Op) {
        ops += op
        if (failOn(op)) throw IOException("injected failure: $op")
    }

    override fun exists(file: File): Boolean = NioStoreFileSystem.exists(file)

    override fun createDirectory(dir: File) {
        record(Op.CreateDirectory(dir.rel()))
        NioStoreFileSystem.createDirectory(dir)
    }

    override fun writeAndForce(file: File, bytes: ByteArray) {
        record(Op.WriteAndForce(file.rel()))
        NioStoreFileSystem.writeAndForce(file, bytes)
    }

    override fun moveAtomically(src: File, dest: File) {
        record(Op.Move(src.rel(), dest.rel()))
        NioStoreFileSystem.moveAtomically(src, dest)
    }

    override fun deleteIfExists(file: File) {
        record(Op.Delete(file.rel()))
        NioStoreFileSystem.deleteIfExists(file)
    }

    override fun syncDirectory(dir: File) {
        record(Op.SyncDirectory(dir.rel()))
        NioStoreFileSystem.syncDirectory(dir)
    }
}
