@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.serialization.cbor.Cbor

/**
 * [DurableStore] fakes shared by the tests that exercise [WarpLogRecordExporter]'s segmented
 * layout — the byte accounting of #1860, and the windowing and segment retirement of #2127.
 *
 * They live here rather than nested inside one test class because two suites need the same
 * failure injection, and a second hand-rolled copy is how two suites quietly stop testing the
 * same store.
 */

/** What a [RecordingStore] saw, in call order. */
internal enum class StoreOpKind { READ, WRITE, DELETE }

/** One observed [DurableStore] call. [bytes] is the payload of a [StoreOpKind.WRITE]. */
internal class StoreOperation(val kind: StoreOpKind, val key: StoreKey, val bytes: ByteArray?)

/**
 * A [DurableStore] that records every call and can report what is currently resident.
 *
 * Backed by an in-memory map, guarded by an explicit lock (kuilt policy: primitives, never
 * dispatcher confinement).
 */
internal class RecordingStore : DurableStore {
    private val lock = reentrantLock()
    private val backing = mutableMapOf<StoreKey, ByteArray>()
    private val payloadSizes = mutableListOf<Int>()
    private val operations = mutableListOf<StoreOperation>()
    private var readCount = 0

    override suspend fun read(key: StoreKey): ByteArray? = lock.withLock {
        readCount++
        operations += StoreOperation(StoreOpKind.READ, key, null)
        backing[key]?.copyOf()
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = lock.withLock {
        backing[key] = bytes.copyOf()
        payloadSizes += bytes.size
        operations += StoreOperation(StoreOpKind.WRITE, key, bytes.copyOf())
    }

    override suspend fun delete(key: StoreKey): Unit = lock.withLock {
        backing.remove(key)
        operations += StoreOperation(StoreOpKind.DELETE, key, null)
    }

    /** Bytes written per [write] call, in call order. */
    fun writes(): List<Int> = lock.withLock { payloadSizes.toList() }

    /** Every call this store has seen, in order. */
    fun operations(): List<StoreOperation> = lock.withLock { operations.toList() }

    /** How many keys have been opened — the quantity segment retirement has to keep flat. */
    fun reads(): Int = lock.withLock { readCount }

    fun resetReadCount(): Unit = lock.withLock { readCount = 0 }

    /** Total bytes currently resident across every live key. */
    fun residentBytes(): Int = lock.withLock { backing.values.sumOf { it.size } }

    fun keys(): Set<String> = lock.withLock { backing.keys.map { it.name }.toSet() }

    fun putRaw(key: StoreKey, bytes: ByteArray): Unit = lock.withLock { backing[key] = bytes.copyOf() }

    fun resetWriteLog(): Unit = lock.withLock { payloadSizes.clear() }
}

/** Delegates to [backing], but throws on reading [poisoned]. */
internal class FailReadOfStore(private val backing: DurableStore, private val poisoned: StoreKey) : DurableStore {
    override suspend fun read(key: StoreKey): ByteArray? {
        if (key == poisoned) throw IllegalStateException("simulated transient read failure on $key")
        return backing.read(key)
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)
    override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
}

/** Delegates to [backing], but throws on every delete. */
internal class FailDeleteStore(private val backing: DurableStore) : DurableStore {
    override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
    override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)
    override suspend fun delete(key: StoreKey): Unit = throw IllegalStateException("simulated delete failure")
}

/** Fails the [failOn]-th [write] call, then keeps failing until [failing] is cleared. */
internal class FailNthWriteStore(private val failOn: Int) : DurableStore {
    private val lock = reentrantLock()
    private val backing = mutableMapOf<StoreKey, ByteArray>()
    private var writes = 0
    var failing: Boolean = true

    override suspend fun read(key: StoreKey): ByteArray? = lock.withLock { backing[key]?.copyOf() }

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        lock.withLock {
            writes++
            if (failing && writes >= failOn) throw IllegalStateException("simulated crash on write $writes")
            backing[key] = bytes.copyOf()
        }
    }

    override suspend fun delete(key: StoreKey): Unit = lock.withLock { backing.remove(key) }

    fun putRaw(key: StoreKey, bytes: ByteArray): Unit = lock.withLock { backing[key] = bytes.copyOf() }
}

/**
 * Delegates to [backing], but throws on the index write that would **commit a retirement** —
 * the first write of a [LogSegmentIndex] naming anything in [LogSegmentIndex.retired].
 *
 * That write is the commit point, so this is the crash a `retired` ledger has to survive from
 * the other side: everything the batch wrote before it landed, nothing after it did.
 */
internal class FailRetirementLedgerStore(private val backing: DurableStore) : DurableStore {
    private val lock = reentrantLock()

    /** Whether the ledger write has been refused yet — the point at which the process "dies". */
    var tripped: Boolean = false
        private set

    override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        if (namesARetirement(key, bytes)) {
            lock.withLock { tripped = true }
            throw IllegalStateException("simulated crash on the retirement ledger write")
        }
        backing.write(key, bytes)
    }

    override suspend fun delete(key: StoreKey): Unit = backing.delete(key)

    private fun namesARetirement(key: StoreKey, bytes: ByteArray): Boolean =
        key == INDEX_KEY_FOR_TEST && decodeIndexForTest(bytes).retired.isNotEmpty()
}

/** The exporter's index key, duplicated here because the production constant is private. */
internal val INDEX_KEY_FOR_TEST: StoreKey = StoreKey("otel.logs.idx")

/** Segment key `n`, duplicated here because the production helper is private. */
internal fun segmentKeyForTest(number: Int): String = "otel.logs.seg.$number"

private val indexCbor = Cbor { alwaysUseByteString = true }

/** Decode a persisted [LogSegmentIndex] so a test can assert on the layout the exporter wrote. */
internal fun decodeIndexForTest(bytes: ByteArray): LogSegmentIndex =
    indexCbor.decodeFromByteArray(LogSegmentIndex.serializer(), bytes)
