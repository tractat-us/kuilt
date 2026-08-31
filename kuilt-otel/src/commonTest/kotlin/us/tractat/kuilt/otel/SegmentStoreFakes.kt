@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey

/**
 * [DurableStore] fakes shared by the tests that exercise [WarpLogRecordExporter]'s segmented
 * layout — the byte accounting of #1860, and the windowing and segment retirement of #2127 —
 * plus the refusing store every exporter's `clear` failure contract is pinned against
 * (#2249, #2251).
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

/**
 * Delegates to [backing], but while refusal is armed throws on every write whose key satisfies
 * [refuses] — by default, every write.
 *
 * The predicate is what lets one fake serve both shapes a clear has to survive, and the second
 * one is not optional. A store that can only refuse *everything* leaves
 * [WarpTelemetry.clear]'s best-effort fan-out untestable: all three signals fail, so "did it
 * keep going past the first failure?" is answered by nothing. Selective refusal is what makes
 * that question have two different answers.
 *
 * Arming is deliberately separate from construction: a fixture has to load through this store
 * before the refusal starts, or the state under test was never persisted in the first place.
 *
 * [refusedWrites] is a precondition guard, never an assertion — a test whose rig never fired is
 * green by absence, and that is indistinguishable from a green earned by the code under test.
 */
internal class WriteRefusingStore(
    private val backing: DurableStore,
    private val refuses: (StoreKey) -> Boolean = { true },
) : DurableStore {
    private val lock = reentrantLock()
    private var refusing = false
    private var refused = 0

    fun refuseWrites(): Unit = lock.withLock { refusing = true }

    fun allowWrites(): Unit = lock.withLock { refusing = false }

    /** How many writes have been refused so far. */
    fun refusedWrites(): Int = lock.withLock { refused }

    override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        val deny = lock.withLock {
            val deny = refusing && refuses(key)
            if (deny) refused++
            deny
        }
        if (deny) throw IllegalStateException("store refused ${key.name}")
        backing.write(key, bytes)
    }

    override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
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

/**
 * Delegates to [backing], but throws on every delete.
 *
 * Counts what it refused, in the two units that are **not** the same number once the exporter
 * starts retrying: [deleteAttempts] is every call, [deleteTargets] is the distinct keys behind
 * them. A retry path re-attempts a key it already failed, so the gap between the two is what a
 * test asserting "reported once per segment, not once per attempt" measures against.
 */
internal class FailDeleteStore(private val backing: DurableStore) : DurableStore {
    private val lock = reentrantLock()
    private var attempts = 0
    private val targets = mutableSetOf<StoreKey>()

    /** Every [delete] call this store refused. */
    val deleteAttempts: Int get() = lock.withLock { attempts }

    /** The distinct keys those attempts named. */
    fun deleteTargets(): Set<StoreKey> = lock.withLock { targets.toSet() }

    override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
    override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)

    override suspend fun delete(key: StoreKey) {
        lock.withLock {
            attempts++
            targets += key
        }
        throw IllegalStateException("simulated delete failure")
    }
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

/**
 * Delegates to [backing], but once [refuseSegmentWrites] is called it throws on every write to a
 * segment key and keeps accepting every other one.
 *
 * The other fakes here model a **crash** — the process stops, so nothing after the refused write
 * happens at all. This models the shape that does not stop: a store that keeps refusing one class
 * of write while the exporter carries on and builds the *next* batch on whatever state the failed
 * one left behind. `IndexedDbDurableStore` under quota pressure does exactly this — it refuses
 * **large** writes while small ones succeed. The segment blob is ~123 KB at
 * `DEFAULT_LOG_SEGMENT_OPS` and the index is a handful of ints, so the key split here *is* the
 * size split: it refuses exactly the write that carries a retirement's covering state, and accepts
 * exactly the write that would commit the retirement.
 */
internal class RefuseSegmentWritesStore(private val backing: DurableStore) : DurableStore {
    private val lock = reentrantLock()
    private var refusing = false
    private var refused = 0

    fun refuseSegmentWrites(): Unit = lock.withLock { refusing = true }

    /** How many segment writes have been refused — a precondition guard, never an assertion. */
    fun refusedWrites(): Int = lock.withLock { refused }

    override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        val refuse = lock.withLock {
            val refuse = refusing && key.name.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST)
            if (refuse) refused++
            refuse
        }
        if (refuse) throw IllegalStateException("simulated quota refusal of a ${bytes.size}-byte write to $key")
        backing.write(key, bytes)
    }

    override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
}

/** The exporter's index key, duplicated here because the production constant is private. */
internal val INDEX_KEY_FOR_TEST: StoreKey = StoreKey("otel.logs.idx")

/**
 * The prefix every key the log exporter owns shares — index, segments and the legacy single key.
 *
 * A test that wants to fail *the logs signal* refuses on this rather than on [INDEX_KEY_FOR_TEST]:
 * the index write is emitted only when the in-memory index is dirty (`pendingWrites` gates it on
 * `indexPersisted`), so a clear of an exporter that never rolled or retired a segment writes its
 * active segment and no index at all. Keying a refusal to the index alone is silently a no-op
 * there — the store accepts every write, the clear succeeds, and the test passes for the wrong
 * reason.
 */
internal const val LOG_KEY_PREFIX_FOR_TEST: String = "otel.logs"

/** The exporter's segment-key prefix, duplicated here because the production constant is private. */
internal const val SEGMENT_KEY_PREFIX_FOR_TEST: String = "otel.logs.seg."

/** Segment key `n`, duplicated here because the production helper is private. */
internal fun segmentKeyForTest(number: Int): String = "$SEGMENT_KEY_PREFIX_FOR_TEST$number"

private val indexCbor = Cbor { alwaysUseByteString = true }

/** Decode a persisted [LogSegmentIndex] so a test can assert on the layout the exporter wrote. */
internal fun decodeIndexForTest(bytes: ByteArray): LogSegmentIndex =
    indexCbor.decodeFromByteArray(LogSegmentIndex.serializer(), bytes)

/** Decode a persisted segment so a test can see what a key actually carries. */
internal fun decodeSegmentForTest(bytes: ByteArray): Rga<LogRecord> =
    indexCbor.decodeFromByteArray(Rga.wireSerializer(LogRecord.serializer()), bytes)
