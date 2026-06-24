package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WarpSpanExporterTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    // ---- helpers ----

    /** 16 raw bytes for a trace id (OTLP: 128-bit). */
    private fun traceId(id: Byte): ByteString = ByteString(ByteArray(16) { id })

    /** 8 raw bytes for a span id (OTLP: 64-bit). */
    private fun spanId(id: Byte): ByteString = ByteString(ByteArray(8) { id })

    private fun span(
        id: Byte,
        name: String = "op",
        startNanos: Long = 1_000L,
        endNanos: Long = 2_000L,
    ) = SpanRecord(
        traceId = traceId(id),
        spanId = spanId(id),
        parentSpanId = null,
        name = name,
        kind = SpanKind.INTERNAL,
        startEpochNanos = startNanos,
        endEpochNanos = endNanos,
    )

    private fun exporterFor(
        replica: ReplicaId = replicaA,
        store: DurableStore = InMemoryDurableStore(),
        maxSpans: Int = DEFAULT_MAX_SPANS,
        bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
    ) = WarpSpanExporter(
        replica = replica,
        store = store,
        maxSpans = maxSpans,
        bufferPolicy = bufferPolicy,
    )

    // ---- A1: DurableStore ----

    @Test
    fun inMemoryStoreRoundTrips() = runTest {
        val store = InMemoryDurableStore()
        val key = StoreKey("key")
        store.write(key, byteArrayOf(1, 2, 3))
        val got = store.read(key)!!
        assertEquals(3, got.size)
        assertEquals(1, got[0])
    }

    @Test
    fun inMemoryStoreReturnsNullForAbsentKey() = runTest {
        assertEquals(null, InMemoryDurableStore().read(StoreKey("missing")))
    }

    @Test
    fun inMemoryStoreDeleteRemovesKey() = runTest {
        val store = InMemoryDurableStore()
        val key = StoreKey("k")
        store.write(key, byteArrayOf(42))
        store.delete(key)
        assertEquals(null, store.read(key))
    }

    @Test
    fun inMemoryStoreWriteIsDefensivelyCopied() = runTest {
        val store = InMemoryDurableStore()
        val key = StoreKey("k")
        val bytes = byteArrayOf(1, 2, 3)
        store.write(key, bytes)
        bytes[0] = 99
        assertEquals(1, store.read(key)!![0])
    }

    // ---- SpanRecord validation ----

    @Test
    fun spanRecordRejectsShortTraceId() {
        assertFailsWith<IllegalArgumentException> {
            SpanRecord(
                traceId = ByteString(ByteArray(15)), // too short
                spanId = spanId(1),
                parentSpanId = null,
                name = "op",
                kind = SpanKind.INTERNAL,
                startEpochNanos = 1L,
                endEpochNanos = 2L,
            )
        }
    }

    @Test
    fun spanRecordRejectsShortSpanId() {
        assertFailsWith<IllegalArgumentException> {
            SpanRecord(
                traceId = traceId(1),
                spanId = ByteString(ByteArray(7)), // too short
                parentSpanId = null,
                name = "op",
                kind = SpanKind.INTERNAL,
                startEpochNanos = 1L,
                endEpochNanos = 2L,
            )
        }
    }

    @Test
    fun spanRecordRejectsWrongParentSpanIdLength() {
        assertFailsWith<IllegalArgumentException> {
            SpanRecord(
                traceId = traceId(1),
                spanId = spanId(1),
                parentSpanId = ByteString(ByteArray(4)), // must be 8 or null
                name = "op",
                kind = SpanKind.INTERNAL,
                startEpochNanos = 1L,
                endEpochNanos = 2L,
            )
        }
    }

    @Test
    fun spanRecordAcceptsNullParentSpanId() {
        // Root span — no exception expected
        SpanRecord(
            traceId = traceId(1),
            spanId = spanId(1),
            parentSpanId = null,
            name = "root",
            kind = SpanKind.INTERNAL,
            startEpochNanos = 1L,
            endEpochNanos = 2L,
        )
    }

    @Test
    fun spanRecordAcceptsValidParentSpanId() {
        // Child span with 8-byte parent — no exception expected
        SpanRecord(
            traceId = traceId(1),
            spanId = spanId(2),
            parentSpanId = spanId(1),
            name = "child",
            kind = SpanKind.CLIENT,
            startEpochNanos = 1L,
            endEpochNanos = 2L,
        )
    }

    // ---- A2: WarpSpanExporter idempotency ----

    @Test
    fun exportReturnsSuccessOnDurableWrite() = runTest {
        val exporter = exporterFor()
        val result = exporter.export(span(1))
        assertEquals(ExportResult.Success, result)
    }

    @Test
    fun exportingSameSpanTwiceIsIdempotent() = runTest {
        val exporter = exporterFor()
        val s = span(1)
        exporter.export(s)
        exporter.export(s)
        // ORSet deduplicates by element: same span → size remains 1
        assertEquals(1, exporter.snapshot().elements.size)
    }

    @Test
    fun exportedSpanAppearsInSnapshot() = runTest {
        val exporter = exporterFor()
        val s = span(1)
        exporter.export(s)
        assertTrue(s in exporter.snapshot().elements)
    }

    // ---- No double-count under retry (the key invariant) ----

    @Test
    fun retryCannotDoubleCount() = runTest {
        // Simulate a retry loop: the same span is sent N times.
        val exporter = exporterFor()
        val s = span(1)
        repeat(5) { exporter.export(s) }
        assertEquals(1, exporter.snapshot().elements.size)
    }

    // ---- Offline-then-sync convergence ----

    @Test
    fun offlineThenSyncConverges() = runTest {
        // Two replicas diverge offline, then reconcile via merge().
        val storeA = InMemoryDurableStore()
        val storeB = InMemoryDurableStore()
        val exporterA = exporterFor(replica = replicaA, store = storeA)
        val exporterB = exporterFor(replica = replicaB, store = storeB)

        val spanA = span(1, name = "A-op")
        val spanB = span(2, name = "B-op")

        // Both export while offline — independently.
        exporterA.export(spanA)
        exporterB.export(spanB)

        // They come back online and exchange snapshots.
        exporterA.merge(exporterB.snapshot())
        exporterB.merge(exporterA.snapshot())

        // Both now hold the union.
        val elementsA = exporterA.snapshot().elements
        val elementsB = exporterB.snapshot().elements
        assertEquals(setOf(spanA, spanB), elementsA)
        assertEquals(elementsA, elementsB)
    }

    @Test
    fun mergeIsIdempotent() = runTest {
        // Merging the same remote snapshot twice changes nothing.
        val exporterA = exporterFor(replica = replicaA)
        val exporterB = exporterFor(replica = replicaB)
        exporterB.export(span(1))
        val remote = exporterB.snapshot()

        exporterA.merge(remote)
        val after1 = exporterA.snapshot().elements

        exporterA.merge(remote)
        val after2 = exporterA.snapshot().elements

        assertEquals(after1, after2)
    }

    @Test
    fun mergePersiststToStore() = runTest {
        // After merging, a fresh exporter recovering from the same store sees the merged state.
        val store = InMemoryDurableStore()
        val exporterA = exporterFor(replica = replicaA, store = store)
        val exporterB = exporterFor(replica = replicaB)
        val s = span(1)
        exporterB.export(s)

        exporterA.merge(exporterB.snapshot())

        // Recover into a brand-new exporter sharing the same store.
        val recovered = exporterFor(replica = replicaA, store = store)
        recovered.recover()
        assertTrue(s in recovered.snapshot().elements)
    }

    // ---- Durable recovery ----

    @Test
    fun recoveredStateMatchesPersistedState() = runTest {
        val store = InMemoryDurableStore()
        val exporter1 = exporterFor(store = store)
        val s = span(1)
        exporter1.export(s)

        // New exporter, same store — simulates a process restart.
        val exporter2 = exporterFor(store = store)
        exporter2.recover()
        assertTrue(s in exporter2.snapshot().elements)
    }

    @Test
    fun recoverOnEmptyStoreStartsFresh() = runTest {
        val exporter = exporterFor()
        exporter.recover()
        assertEquals(0, exporter.snapshot().elements.size)
    }

    // ---- Bounded-buffer eviction ----

    @Test
    fun bufferCapEvictsOldestSpanWithDropOldestPolicy() = runTest {
        val exporter = exporterFor(maxSpans = 3, bufferPolicy = BufferPolicy.DROP_OLDEST)

        val s1 = span(1, startNanos = 100)
        val s2 = span(2, startNanos = 200)
        val s3 = span(3, startNanos = 300)
        val s4 = span(4, startNanos = 400)
        exporter.export(s1)
        exporter.export(s2)
        exporter.export(s3)
        // Buffer is at capacity; exporting s4 must evict the oldest (s1).
        exporter.export(s4)

        val elements = exporter.snapshot().elements
        assertEquals(3, elements.size)
        assertTrue(s1 !in elements, "oldest span should be evicted")
        assertTrue(s4 in elements, "newest span should be present")
    }

    @Test
    fun bufferCapEvictsNewestSpanWithDropNewestPolicy() = runTest {
        val exporter = exporterFor(maxSpans = 3, bufferPolicy = BufferPolicy.DROP_NEWEST)

        val s1 = span(1, startNanos = 100)
        val s2 = span(2, startNanos = 200)
        val s3 = span(3, startNanos = 300)
        val s4 = span(4, startNanos = 400)
        exporter.export(s1)
        exporter.export(s2)
        exporter.export(s3)
        // Buffer is at capacity; exporting s4 must evict the newest (s3), then s4 is inserted.
        exporter.export(s4)

        // After eviction of s3 and insertion of s4, set is {s1, s2, s4}.
        val elements = exporter.snapshot().elements
        assertEquals(3, elements.size)
        assertTrue(s3 !in elements, "newest-before-insert should be evicted")
    }

    @Test
    fun evictedSpanLeavesRoomForNewSpan() = runTest {
        val exporter = exporterFor(maxSpans = 2, bufferPolicy = BufferPolicy.DROP_OLDEST)
        exporter.export(span(1, startNanos = 100))
        exporter.export(span(2, startNanos = 200))
        val result = exporter.export(span(3, startNanos = 300))
        assertEquals(ExportResult.Success, result)
        assertEquals(2, exporter.snapshot().elements.size)
    }

    // ---- Store failures ----

    @Test
    fun exportReturnsFailureWhenStoreFails() = runTest {
        val exporter = exporterFor(store = AlwaysFailStore)
        val result = exporter.export(span(1))
        assertIs<ExportResult.Failure>(result)
    }

    private object AlwaysFailStore : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? = null
        override suspend fun write(key: StoreKey, bytes: ByteArray) =
            throw RuntimeException("simulated store failure")
        override suspend fun delete(key: StoreKey) = Unit
    }
}
