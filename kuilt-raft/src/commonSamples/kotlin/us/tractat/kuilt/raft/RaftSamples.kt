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
    // The floor lives in your own store, beside the id, with the command it was issued for. They share
    // one record because a store commits one key at a time. A sample uses the in-memory store.
    val store: DurableStore = InMemoryDurableStore()
    val lastIssued = StoreKey("client/last-issued")
    suspend fun readFloor(): Long = store.read(lastIssued)?.decodeToString()?.substringBefore(' ')?.toLong() ?: 0L
    suspend fun readCommand(): String? = store.read(lastIssued)?.decodeToString()?.substringAfter(' ')

    // Boot: read the floor and hand it over — `scope.raftNode(cluster, transport, storage, identity = identity)`.
    val identity = ClientIdentity.Durable(ClientId("checkout-client"), issuedThrough = readFloor())

    // A new command, once the last one has returned. Read the floor afresh each time: `identity` is
    // fixed at boot, so taking it from there would reuse a serial from the second command on.
    // Commit the serial and the bytes FIRST, then `node.propose(command, requestId)`.
    val requestId = readFloor() + 1
    store.write(lastIssued, "$requestId charge-card".encodeToByteArray())

    // After a crash the floor covers that serial, so its entry coming back is not a collision. Its
    // outcome is unknown, so retry this serial with these bytes BEFORE issuing another: a higher serial
    // abandons this one, and a retry after that is acknowledged and then dropped.
    val restarted = ClientIdentity.Durable(identity.clientId, issuedThrough = readFloor())
    check(restarted.issuedThrough == requestId)
    check(readCommand() == "charge-card")
}
