package us.tractat.kuilt.otel

/**
 * A durable, typed-key store that persists serialized CRDT state across process restarts.
 *
 * The store is **key-addressed**: each CRDT in [WarpTelemetry] lives under its own [StoreKey].
 * Reads and writes are opaque byte arrays — the store knows nothing about CRDT internals;
 * serialization lives in the callers.
 *
 * ## Durability contract
 *
 * [write] returns only after the bytes are durably committed. A crash after [write] returns
 * implies the bytes survive a restart and are returned by the next [read]. This is the
 * foundation of the key inversion in `WarpTelemetry`: `export()` returns success the moment
 * [write] returns — not on delivery to a backend.
 *
 * ## Platform implementations
 *
 * | Platform | Suggested WAL | Status |
 * |---|---|---|
 * | JVM / Android | `FileChannel.force(true)` + atomic rename | Ships in this module (#800) |
 * | iOS / macOS | NSFileManager atomic-write | Follow-up issue filed (#802) |
 * | wasmJs | IndexedDB `IDBObjectStore` | Follow-up issue filed (#801) |
 *
 * Tests inject [InMemoryDurableStore]; production code wires a platform WAL.
 */
public interface DurableStore {
    /**
     * Read the bytes stored under [key], or `null` if the key has never been written.
     *
     * Called on startup to recover CRDT state from the previous session.
     */
    public suspend fun read(key: StoreKey): ByteArray?

    /**
     * Durably write [bytes] under [key], overwriting any previous value.
     *
     * Returns after the write is fsync'd (or equivalent). Never returns before
     * the bytes are committed — the caller relies on this for crash-safe export.
     */
    public suspend fun write(key: StoreKey, bytes: ByteArray)

    /**
     * Remove the entry for [key]. No-op if the key is absent.
     *
     * Intended for tests and cleanup; production code rarely needs this.
     */
    public suspend fun delete(key: StoreKey)
}
