package us.tractat.kuilt.raft

import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey

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

/** @suppress — sample only */
internal suspend fun sampleDurableClientIdentity() {
    // The floor lives in your own store, beside the id. A sample uses the in-memory store.
    val store: DurableStore = InMemoryDurableStore()
    val floor = StoreKey("client/issued-through")
    suspend fun readFloor(): Long = store.read(floor)?.decodeToString()?.toLong() ?: 0L

    // Boot: read the floor and hand it over — `scope.raftNode(cluster, transport, storage, identity = identity)`.
    val identity = ClientIdentity.Durable(ClientId("checkout-client"), issuedThrough = readFloor())

    // A new command: commit its serial as the new floor FIRST, then `node.propose(command, requestId)`.
    val requestId = identity.issuedThrough + 1
    store.write(floor, requestId.toString().encodeToByteArray())

    // A crash from here on loses nothing: the next boot's floor already covers that serial, so its
    // entry coming back is not a collision, and the retry resends the same bytes under the same id.
    val restarted = ClientIdentity.Durable(identity.clientId, issuedThrough = readFloor())
    check(restarted.issuedThrough == requestId)
}
