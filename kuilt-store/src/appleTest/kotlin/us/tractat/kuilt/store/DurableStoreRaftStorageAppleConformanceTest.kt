package us.tractat.kuilt.store

import us.tractat.kuilt.conformance.RaftStorageConformanceSuite
import us.tractat.kuilt.raft.DurableStoreRaftStorage
import us.tractat.kuilt.raft.RaftStorage

/**
 * Verifies [DurableStoreRaftStorage] satisfies the whole [RaftStorageConformanceSuite] over
 * [NSFileManagerDurableStore], on iOS and macOS.
 *
 * Placed in `:kuilt-store`'s `appleTest` for the same reason as
 * [DurableStoreRaftStorageFileConformanceTest]: this module owns the [freshTempDir] fixture and the
 * hand-wired Apple test source set, and the dependency edge to `:kuilt-conformance` (which `api`s
 * `:kuilt-raft`) already exists.
 *
 * ## What this pins, and what belongs to the medium
 *
 * The reopen is a **new [NSFileManagerDurableStore] over the same directory** followed by a fresh
 * [DurableStoreRaftStorage.open], so every restart property here crosses a real filesystem and the
 * adapter's real encoder/decoder.
 *
 * It does **not** pin the medium's durability. `NSFileManagerDurableStore` commits with
 * `NSData.writeToFile` plus POSIX `rename(2)`, and issue #2141 records that this stops short of an
 * `fsync` on the containing directory — so a power loss between the rename and the filesystem's own
 * flush can lose a write that already returned. That caveat is invisible to every assertion below,
 * because nothing here kills the machine; a second handle in the same process reads back through the
 * page cache whether or not anything reached the platter. It is the **medium's** caveat, not the
 * adapter's: this adapter's whole durability claim is "I called `DurableStore.write` and it
 * returned", and it is `DurableStore`'s contract that says what that return means.
 */
class DurableStoreRaftStorageAppleConformanceTest : RaftStorageConformanceSuite() {

    private val directories = mutableMapOf<RaftStorage, String>()

    override suspend fun newStorage(): RaftStorage = openOver(freshTempDir("kuilt-raft-storage-conformance"))

    override suspend fun reopen(storage: RaftStorage): RaftStorage =
        openOver(requireNotNull(directories[storage]) { "reopen() was handed a storage this fixture did not create" })

    private suspend fun openOver(dir: String): RaftStorage =
        DurableStoreRaftStorage.open(NSFileManagerDurableStore(dir)).also { directories[it] = dir }
}
