package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WarpLogRecordExporterTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    // ---- helpers ----

    /** 8 raw bytes for a record id (64-bit). */
    private fun recordId(id: Byte): ByteString = ByteString(ByteArray(8) { id })

    /** 16 raw bytes for a trace id (OTLP: 128-bit). */
    private fun traceId(id: Byte): ByteString = ByteString(ByteArray(16) { id })

    /** 8 raw bytes for a span id (OTLP: 64-bit). */
    private fun spanId(id: Byte): ByteString = ByteString(ByteArray(8) { id })

    private fun record(
        id: Byte,
        body: String = "log message",
        severity: Int? = 9,
    ) = LogRecord(
        recordId = recordId(id),
        body = body,
        severityNumber = severity,
        observedEpochNanos = 1_000L + id,
    )

    private fun exporterFor(
        replica: ReplicaId = replicaA,
        store: DurableStore = InMemoryDurableStore(),
        maxRecords: Int = DEFAULT_MAX_LOG_RECORDS,
        bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
    ) = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = maxRecords,
        bufferPolicy = bufferPolicy,
    )

    // ---- LogRecord validation ----

    @Test
    fun logRecordRejectsShortRecordId() {
        assertFailsWith<IllegalArgumentException> {
            LogRecord(recordId = ByteString(ByteArray(7)))
        }
    }

    @Test
    fun logRecordRejectsShortTraceId() {
        assertFailsWith<IllegalArgumentException> {
            LogRecord(
                recordId = recordId(1),
                traceId = ByteString(ByteArray(15)),
                spanId = spanId(1),
            )
        }
    }

    @Test
    fun logRecordRejectsShortSpanId() {
        assertFailsWith<IllegalArgumentException> {
            LogRecord(
                recordId = recordId(1),
                traceId = traceId(1),
                spanId = ByteString(ByteArray(7)),
            )
        }
    }

    @Test
    fun logRecordRejectsTraceIdWithoutSpanId() {
        assertFailsWith<IllegalArgumentException> {
            LogRecord(
                recordId = recordId(1),
                traceId = traceId(1),
                spanId = null,
            )
        }
    }

    @Test
    fun logRecordRejectsSpanIdWithoutTraceId() {
        assertFailsWith<IllegalArgumentException> {
            LogRecord(
                recordId = recordId(1),
                traceId = null,
                spanId = spanId(1),
            )
        }
    }

    @Test
    fun logRecordAcceptsNullTraceContext() {
        // Untraced log — no exception expected
        LogRecord(recordId = recordId(1), body = "hello")
    }

    @Test
    fun logRecordAcceptsValidTraceContext() {
        // Correlated log — no exception expected
        LogRecord(
            recordId = recordId(1),
            body = "traced log",
            traceId = traceId(1),
            spanId = spanId(1),
        )
    }

    // ---- Basic export ----

    @Test
    fun exportReturnsSuccessOnDurableWrite() = runTest {
        val exporter = exporterFor()
        val result = exporter.export(record(1))
        assertEquals(ExportResult.Success, result)
    }

    @Test
    fun exportedRecordAppearsInSnapshot() = runTest {
        val exporter = exporterFor()
        val r = record(1)
        exporter.export(r)
        assertTrue(r in exporter.snapshot().toList())
    }

    // ---- No double-count under retry ----

    @Test
    fun exportingSameRecordTwiceIsIdempotent() = runTest {
        val exporter = exporterFor()
        val r = record(1)
        exporter.export(r)
        exporter.export(r)
        // Re-export of the same recordId must not duplicate the entry.
        assertEquals(1, exporter.snapshot().toList().size)
    }

    @Test
    fun retryLoopCannotDoubleCount() = runTest {
        val exporter = exporterFor()
        val r = record(1)
        repeat(5) { exporter.export(r) }
        assertEquals(1, exporter.snapshot().toList().size)
    }

    // ---- Ordering ----

    @Test
    fun snapshotPreservesInsertionOrder() = runTest {
        val exporter = exporterFor()
        val r1 = record(1, body = "first")
        val r2 = record(2, body = "second")
        val r3 = record(3, body = "third")
        exporter.export(r1)
        exporter.export(r2)
        exporter.export(r3)
        assertEquals(listOf(r1, r2, r3), exporter.snapshot().toList())
    }

    // ---- Offline-then-sync convergence ----

    @Test
    fun offlineThenSyncConverges() = runTest {
        val storeA = InMemoryDurableStore()
        val storeB = InMemoryDurableStore()
        val exporterA = exporterFor(replica = replicaA, store = storeA)
        val exporterB = exporterFor(replica = replicaB, store = storeB)

        val r1 = record(1, body = "from-A")
        val r2 = record(2, body = "from-B")

        exporterA.export(r1)
        exporterB.export(r2)

        // Sync: exchange snapshots.
        exporterA.merge(exporterB.snapshot())
        exporterB.merge(exporterA.snapshot())

        // Both hold the union.
        val listA = exporterA.snapshot().toList()
        val listB = exporterB.snapshot().toList()
        assertTrue(r1 in listA && r2 in listA)
        assertEquals(listA.toSet(), listB.toSet())
    }

    @Test
    fun mergeIsIdempotent() = runTest {
        val exporterA = exporterFor(replica = replicaA)
        val exporterB = exporterFor(replica = replicaB)
        exporterB.export(record(1))
        val remote = exporterB.snapshot()

        exporterA.merge(remote)
        val after1 = exporterA.snapshot().toList()

        exporterA.merge(remote)
        val after2 = exporterA.snapshot().toList()

        assertEquals(after1, after2)
    }

    @Test
    fun mergePersistsToStore() = runTest {
        val store = InMemoryDurableStore()
        val exporterA = exporterFor(replica = replicaA, store = store)
        val exporterB = exporterFor(replica = replicaB)
        val r = record(1)
        exporterB.export(r)

        exporterA.merge(exporterB.snapshot())

        val recovered = exporterFor(replica = replicaA, store = store)
        recovered.recover()
        assertTrue(r in recovered.snapshot().toList())
    }

    // ---- Durable recovery ----

    @Test
    fun recoveredStateMatchesPersistedState() = runTest {
        val store = InMemoryDurableStore()
        val exporter1 = exporterFor(store = store)
        val r = record(1)
        exporter1.export(r)

        val exporter2 = exporterFor(store = store)
        exporter2.recover()
        assertTrue(r in exporter2.snapshot().toList())
    }

    @Test
    fun recoverOnEmptyStoreStartsFresh() = runTest {
        val exporter = exporterFor()
        exporter.recover()
        assertEquals(0, exporter.snapshot().toList().size)
    }

    @Test
    fun recoverRestoresDeduplicationState() = runTest {
        // After recover(), re-exporting a previously-exported record is still a no-op.
        val store = InMemoryDurableStore()
        val exporter1 = exporterFor(store = store)
        val r = record(1)
        exporter1.export(r)

        val exporter2 = exporterFor(store = store)
        exporter2.recover()
        exporter2.export(r)
        assertEquals(1, exporter2.snapshot().toList().size)
    }

    // ---- Bounded-buffer eviction ----

    @Test
    fun bufferCapEvictsOldestRecordWithDropOldestPolicy() = runTest {
        val exporter = exporterFor(maxRecords = 3, bufferPolicy = BufferPolicy.DROP_OLDEST)

        val r1 = record(1, body = "oldest")
        val r2 = record(2, body = "middle")
        val r3 = record(3, body = "newer")
        val r4 = record(4, body = "newest")
        exporter.export(r1)
        exporter.export(r2)
        exporter.export(r3)
        // At capacity; r4 should evict r1 (oldest by insertion position).
        exporter.export(r4)

        val list = exporter.snapshot().toList()
        assertEquals(3, list.size)
        assertTrue(r1 !in list, "oldest record should be evicted")
        assertTrue(r4 in list, "newest record should be present")
    }

    @Test
    fun bufferCapEvictsNewestRecordWithDropNewestPolicy() = runTest {
        val exporter = exporterFor(maxRecords = 3, bufferPolicy = BufferPolicy.DROP_NEWEST)

        val r1 = record(1, body = "oldest")
        val r2 = record(2, body = "middle")
        val r3 = record(3, body = "newer")
        val r4 = record(4, body = "newest")
        exporter.export(r1)
        exporter.export(r2)
        exporter.export(r3)
        // At capacity; DROP_NEWEST evicts the newest present (r3), then inserts r4.
        exporter.export(r4)

        val list = exporter.snapshot().toList()
        assertEquals(3, list.size)
        assertTrue(r3 !in list, "newest-before-insert should be evicted")
    }

    @Test
    fun evictedRecordLeavesRoomForNewRecord() = runTest {
        val exporter = exporterFor(maxRecords = 2, bufferPolicy = BufferPolicy.DROP_OLDEST)
        exporter.export(record(1))
        exporter.export(record(2))
        val result = exporter.export(record(3))
        assertEquals(ExportResult.Success, result)
        assertEquals(2, exporter.snapshot().toList().size)
    }

    // ---- Store failures ----

    @Test
    fun exportReturnsFailureWhenStoreFails() = runTest {
        val exporter = exporterFor(store = AlwaysFailStore)
        val result = exporter.export(record(1))
        assertIs<ExportResult.Failure>(result)
    }

    private object AlwaysFailStore : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? = null
        override suspend fun write(key: StoreKey, bytes: ByteArray) =
            throw RuntimeException("simulated store failure")
        override suspend fun delete(key: StoreKey) = Unit
    }
}
