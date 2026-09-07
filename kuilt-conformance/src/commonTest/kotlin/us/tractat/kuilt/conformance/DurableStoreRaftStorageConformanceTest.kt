package us.tractat.kuilt.conformance

import us.tractat.kuilt.raft.DurableStoreRaftStorage
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore

/**
 * Verifies [DurableStoreRaftStorage] satisfies the whole [RaftStorageConformanceSuite], over the
 * one [DurableStore] every target has.
 *
 * ## What this subclass can model that [InMemoryRaftStorageConformanceTest] cannot
 *
 * That class's `reopen` KDoc states its own limit plainly: it rebuilds a fresh
 * `InMemoryRaftStorage` from the old one's public read surface, so **there is no medium** and no
 * encode/decode boundary — the five restart properties pass structurally, and a write that never
 * reached stable storage is a failure it cannot have.
 *
 * Here [reopen] discards the handle entirely and calls [DurableStoreRaftStorage.open] again on the
 * **same store instance**, so every value the second handle reports came back through the real
 * encoder, out of the store, and through the real decoder. A field the format drops, a field the
 * decoder mis-maps, a mutator that updates its cache without writing — each of those is invisible
 * to the reference and reds here. The mutation rows on this PR are the receipt.
 *
 * What it still cannot model is the *fsync* half: [InMemoryDurableStore] keeps nothing across a
 * process exit, so "the write returned before the bytes were committed" is not reachable through
 * it. `DurableStoreRaftStorageFileConformanceTest` and its Apple and wasmJs siblings bind the same
 * suite over media that really are durable — and even there, no test in this tree can see an fsync
 * that did not happen. That obligation is [DurableStore]'s, not this adapter's.
 */
class DurableStoreRaftStorageConformanceTest : RaftStorageConformanceSuite() {

    /**
     * The store each handle was opened over, so [reopen] can open a second one onto it.
     *
     * Keyed on the storage instance rather than kept in a single field because several properties
     * hold two live storages at once (`saveVotedForNull_clearsVote`'s cleared sibling, for one),
     * and a single field would hand the second storage's store back for a reopen of the first.
     */
    private val stores = mutableMapOf<RaftStorage, DurableStore>()

    override suspend fun newStorage(): RaftStorage {
        val store = InMemoryDurableStore()
        return DurableStoreRaftStorage.open(store).also { stores[it] = store }
    }

    override suspend fun reopen(storage: RaftStorage): RaftStorage {
        val store = requireNotNull(stores[storage]) { "reopen() was handed a storage this fixture did not create" }
        return DurableStoreRaftStorage.open(store).also { stores[it] = store }
    }
}
