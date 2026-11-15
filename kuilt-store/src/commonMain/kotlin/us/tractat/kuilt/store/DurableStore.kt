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
 * | JVM / Android | `FileChannelDurableStore` | `FileChannel.force(true)` + atomic rename + directory force (not on Windows) |
 * | iOS / macOS | `NSFileManagerDurableStore` | `fcntl(F_FULLFSYNC)` + POSIX `rename(2)` |
 * | wasmJs | `IndexedDbDurableStore` | IndexedDB transaction `complete` |
 *
 * The in-memory store satisfies the read/write/delete behaviour but keeps nothing across a
 * process exit — it is the right choice in a test and the wrong one anywhere a restart matters.
 *
 * ## Failures
 *
 * Every method reports a failure by **throwing an [Exception]** — never by returning normally, and
 * never as a [Throwable] that is not an [Exception], so a caller's `catch (e: Exception)` sees every
 * failure a store reports. The type is the backend's own (an `IOException` on JVM and Android, an
 * [IllegalStateException] naming the `errno` on Apple, an IndexedDB error on wasmJs); what the
 * contract fixes is that it is an [Exception], and that a failure is never dressed as an answer.
 *
 * The answer it is most easily dressed as is **absence**. [read] returns `null` for a key with no
 * entry and for nothing else: a store that cannot tell whether an entry exists — the file is there
 * but may not be opened, the directory may not be searched, the connection is closing — throws.
 * A caller treats `null` as "nothing was ever stored here" and writes a fresh value, so a read error
 * reported as `null` is not a lost read but a destroyed value.
 */
public interface DurableStore {
    /**
     * Read the bytes stored under [key], or `null` if the key has no entry — never written, or
     * deleted.
     *
     * Called on startup to recover state written by the previous session.
     *
     * `null` means absence and nothing else. A read that **fails** throws an [Exception] instead —
     * see *Failures* above for why answering `null` there would destroy the value.
     */
    public suspend fun read(key: StoreKey): ByteArray?

    /**
     * Durably write [bytes] under [key], overwriting any previous value.
     *
     * Returns after the write is fsync'd (or equivalent). Never returns before
     * the bytes are committed — the caller relies on this for crash safety.
     *
     * A thrown exception or cancellation has an **indeterminate commit outcome**: the bytes
     * may already have been committed, or an outstanding write may commit after the caller
     * has stopped waiting. Neither failure nor cancellation promises to leave the old value
     * intact. Cancellation does not, by itself, abort the underlying storage operation.
     *
     * Callers must account for this when rolling back memory, retrying, or recovering. An
     * immediate read of the old value is not proof that an outstanding write cannot land later;
     * this interface provides no operation for awaiting or aborting such a write.
     */
    public suspend fun write(key: StoreKey, bytes: ByteArray)

    /**
     * Remove the entry for [key]. No-op if the key is absent.
     *
     * A delete the medium refuses throws an [Exception]; it does not return as the absent case
     * does, or the caller would believe the entry gone while it is still there.
     *
     * Intended for tests and cleanup; production code rarely needs this.
     */
    public suspend fun delete(key: StoreKey)
}
