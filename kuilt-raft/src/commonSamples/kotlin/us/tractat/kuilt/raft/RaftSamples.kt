package us.tractat.kuilt.raft

import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore

/** @suppress — sample only */
internal suspend fun sampleDurableRaftStorage() {
    // One store per node. In production this is the platform's crash-safe implementation —
    // FileChannelDurableStore(nodeDirectory), NSFileManagerDurableStore(nodeDirectory), or
    // IndexedDbDurableStore.open(nodeDatabase). A sample uses the in-memory one.
    val store: DurableStore = InMemoryDurableStore()

    val storage = DurableStoreRaftStorage.open(store)

    // Every mutator commits to the medium before it updates its own memory, so a term that has
    // been saved is a term that survives — pass this to `scope.raftNode(cluster, transport, storage)`.
    storage.saveTermAndVotedFor(term = 4L, votedFor = NodeId("node-a"))
    storage.appendEntries(listOf(LogEntry(index = 1L, term = 4L, command = byteArrayOf(7, 8, 9))))

    // A restart: a second handle onto the same store, decoding what the first one wrote.
    val restarted = DurableStoreRaftStorage.open(store)
    check(restarted.term() == 4L)
    check(restarted.votedFor() == NodeId("node-a"))
    check(restarted.entries().single().command.contentEquals(byteArrayOf(7, 8, 9)))
}
