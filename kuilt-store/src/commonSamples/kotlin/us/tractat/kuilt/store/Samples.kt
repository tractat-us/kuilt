package us.tractat.kuilt.store

/** @suppress — sample only */
internal suspend fun sampleDurableStore() {
    // Every platform has its own crash-safe implementation; a test uses the in-memory one.
    val store: DurableStore = InMemoryDurableStore()
    val key = StoreKey("draft")

    // `write` returns only once the bytes are committed — that is the whole contract.
    store.write(key, byteArrayOf(1, 2, 3))

    // A later session (a fresh store over the same backing directory or database)
    // reads back exactly what was committed; an unwritten key reads back null.
    val recovered: ByteArray? = store.read(key)
    check(recovered.contentEquals(byteArrayOf(1, 2, 3)))
    check(store.read(StoreKey("never-written")) == null)

    store.delete(key)
    check(store.read(key) == null)
}
