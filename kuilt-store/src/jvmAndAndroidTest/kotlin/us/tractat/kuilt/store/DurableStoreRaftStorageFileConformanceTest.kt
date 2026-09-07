package us.tractat.kuilt.store

import us.tractat.kuilt.conformance.RaftStorageConformanceSuite
import us.tractat.kuilt.raft.DurableStoreRaftStorage
import us.tractat.kuilt.raft.RaftStorage
import java.io.File

/**
 * Verifies [DurableStoreRaftStorage] satisfies the whole [RaftStorageConformanceSuite] over
 * [FileChannelDurableStore] — a medium that really does survive a process exit — **on both the JVM
 * and Android**.
 *
 * ## Why this lives in the medium's module rather than beside the adapter
 *
 * The adapter is in `:kuilt-raft`; this test is in `:kuilt-store`. Two things make that the right
 * side of the line. `:kuilt-store` owns the temp-directory fixture ([freshTempDir]) that both of
 * its own backends' conformance subclasses already use, and it owns the hand-wired
 * `jvmAndAndroidTest` source set — the one that makes the **Android** variant actually run rather
 * than merely compile. A subclass in `:kuilt-raft`'s `jvmTest` would be green on a target it had
 * never executed against, which is the trap `FileChannelDurableStoreTest`'s own KDoc names. The
 * dependency edge is already there and points the right way: `:kuilt-conformance` `api`s
 * `:kuilt-raft`, and this module's `commonTest` depends on `:kuilt-conformance`.
 *
 * The reopen here is the strongest one available anywhere in the tree: a **new
 * [FileChannelDurableStore] over the same directory**, then a fresh [DurableStoreRaftStorage.open]
 * on it. Nothing in-process is shared with the original — every value the second handle reports was
 * read off the filesystem the first one wrote to, and decoded again.
 */
class DurableStoreRaftStorageFileConformanceTest : RaftStorageConformanceSuite() {

    /**
     * The directory each storage's store was opened over, so [reopen] can build a new store on it.
     *
     * Keyed on the storage instance for the same reason [FileChannelDurableStoreTest] keys its own
     * map: several properties hold two live storages at once, and a single field would hand the
     * second one's directory back for a reopen of the first.
     */
    private val directories = mutableMapOf<RaftStorage, File>()

    override suspend fun newStorage(): RaftStorage = openOver(freshTempDir("kuilt-raft-storage-conformance"))

    override suspend fun reopen(storage: RaftStorage): RaftStorage =
        openOver(requireNotNull(directories[storage]) { "reopen() was handed a storage this fixture did not create" })

    private suspend fun openOver(dir: File): RaftStorage =
        DurableStoreRaftStorage.open(FileChannelDurableStore(dir)).also { directories[it] = dir }
}
