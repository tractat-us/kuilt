package us.tractat.kuilt.store

/**
 * A place to put a handful of named blobs so they are still there after the app restarts.
 *
 * The store is **key-addressed**: a caller picks a [StoreKey] per thing it wants to keep, and
 * reads and writes whole byte arrays under it. The store never looks inside those bytes — what
 * they mean, and how they are serialized, belongs entirely to the caller.
 *
 * It is a small surface on purpose. There is no iteration, no query, no transaction across keys:
 * the number of keys is expected to be small and application-controlled, and everything past
 * "keep these bytes safe" is somebody else's problem.
 *
 * ## Durability contract
 *
 * [write] returns only after the bytes are durably committed. A crash after [write] returns
 * implies the bytes survive a restart and are returned by the next [read]. That is the whole
 * point of the interface: a caller can treat "the write returned" as "the data is safe", and so
 * report success at that moment rather than waiting on anything further downstream.
 *
 * What "durably committed" costs differs by platform — an `fsync` before an atomic rename, an
 * IndexedDB transaction reaching `complete` — and each implementation documents the exact point
 * it treats as the commit, including where its guarantee stops short.
 *
 * ## Implementations
 *
 * | Platform | Implementation | Mechanism |
 * |---|---|---|
 * | any | [InMemoryDurableStore] | none — **not** crash-safe; for tests and ephemeral use |
 * | JVM / Android | `FileChannelDurableStore` | `FileChannel.force(true)` + atomic rename |
 * | iOS / macOS | `NSFileManagerDurableStore` | `NSData.writeToFile` + POSIX `rename(2)` |
 * | wasmJs | `IndexedDbDurableStore` | IndexedDB transaction `complete` |
 *
 * The in-memory store satisfies the read/write/delete behaviour but keeps nothing across a
 * process exit — it is the right choice in a test and the wrong one anywhere a restart matters.
 */
public interface DurableStore {
    /**
     * Read the bytes stored under [key], or `null` if the key has never been written.
     *
     * Called on startup to recover state written by the previous session.
     */
    public suspend fun read(key: StoreKey): ByteArray?

    /**
     * Durably write [bytes] under [key], overwriting any previous value.
     *
     * Returns after the write is fsync'd (or equivalent). Never returns before
     * the bytes are committed — the caller relies on this for crash safety.
     */
    public suspend fun write(key: StoreKey, bytes: ByteArray)

    /**
     * Remove the entry for [key]. No-op if the key is absent.
     *
     * Intended for tests and cleanup; production code rarely needs this.
     */
    public suspend fun delete(key: StoreKey)
}
