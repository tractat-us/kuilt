package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * An in-process, non-durable [DurableStore] for tests and ephemeral scenarios.
 *
 * **Not crash-safe** — all state is lost on process exit. Use only in tests or
 * where persistence across restarts is explicitly not needed.
 *
 * Thread-safe via a reentrant lock (kuilt policy: explicit primitives, not
 * dispatcher confinement).
 */
public class InMemoryDurableStore : DurableStore {

    private val lock = reentrantLock()
    private val store: MutableMap<StoreKey, ByteArray> = mutableMapOf()

    override suspend fun read(key: StoreKey): ByteArray? = lock.withLock { store[key]?.copyOf() }

    override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = lock.withLock {
        store[key] = bytes.copyOf()
    }

    override suspend fun delete(key: StoreKey): Unit = lock.withLock { store.remove(key) }
}
