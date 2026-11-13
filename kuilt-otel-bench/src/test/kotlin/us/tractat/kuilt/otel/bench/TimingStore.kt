package us.tractat.kuilt.otel.bench

import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import java.util.concurrent.atomic.AtomicLong

/**
 * A [DurableStore] decorator that counts and times what passes through it, so an arm can split an
 * operation's cost into "the store" and "everything else", and price each durable write in bytes.
 *
 * The counters are atomics rather than plain fields so the type stays correct if an arm ever drives
 * it from more than one thread; the arms here do not.
 */
class TimingStore(private val delegate: DurableStore) : DurableStore {
    private val writeCount = AtomicLong()
    private val writeNanos = AtomicLong()
    private val writeBytes = AtomicLong()
    private val readCount = AtomicLong()
    private val readNanos = AtomicLong()
    private val readBytes = AtomicLong()

    val writes: Long get() = writeCount.get()
    val nanosInWrite: Long get() = writeNanos.get()
    val bytesWritten: Long get() = writeBytes.get()
    val reads: Long get() = readCount.get()
    val nanosInRead: Long get() = readNanos.get()
    val bytesRead: Long get() = readBytes.get()

    override suspend fun read(key: StoreKey): ByteArray? {
        val t0 = System.nanoTime()
        val bytes = delegate.read(key)
        readNanos.addAndGet(System.nanoTime() - t0)
        readCount.incrementAndGet()
        readBytes.addAndGet(bytes?.size?.toLong() ?: 0L)
        return bytes
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        val t0 = System.nanoTime()
        delegate.write(key, bytes)
        writeNanos.addAndGet(System.nanoTime() - t0)
        writeCount.incrementAndGet()
        writeBytes.addAndGet(bytes.size.toLong())
    }

    override suspend fun delete(key: StoreKey): Unit = delegate.delete(key)

    /** Zeroes every counter, so a measured window excludes prefill and warm-up traffic. */
    fun reset() {
        listOf(writeCount, writeNanos, writeBytes, readCount, readNanos, readBytes).forEach { it.set(0L) }
    }
}
