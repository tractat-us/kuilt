package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WarpSpanExporterTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    // ---- helpers ----

    private fun span(
        id: String,
        name: String = "op",
        startNanos: Long = 1_000L,
        endNanos: Long = 2_000L,
    ) = SpanRecord(
        traceId = "trace-$id",
        spanId = id,
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
        store.write("key", byteArrayOf(1, 2, 3))
        val got = store.read("key")!!
        assertEquals(3, got.size)
        assertEquals(1, got[0])
    }

    @Test
    fun inMemoryStoreReturnsNullForAbsentKey() = runTest {
        assertEquals(null, InMemoryDurableStore().read("missing"))
    }

    @Test
    fun inMemoryStoreDeleteRemovesKey() = runTest {
        val store = InMemoryDurableStore()
        store.write("k", byteArrayOf(42))
        store.delete("k")
        assertEquals(null, store.read("k"))
    }

    @Test
    fun inMemoryStoreWriteIsDefensivelyCopied() = runTest {
        val store = InMemoryDurableStore()
        val bytes = byteArrayOf(1, 2, 3)
        store.write("k", bytes)
        bytes[0] = 99
        assertEquals(1, store.read("k")!![0])
    }

    // ---- A2: WarpSpanExporter idempotency ----

    @Test
    fun exportReturnsSuccessOnDurableWrite() = runTest {
        val exporter = exporterFor()
        val result = exporter.export(span("s1"))
        assertEquals(ExportResult.Success, result)
    }

    @Test
    fun exportingSameSpanTwiceIsIdempotent() = runTest {
        val exporter = exporterFor()
        val s = span("s1")
        exporter.export(s)
        exporter.export(s)
        // ORSet deduplicates by element: same span → size remains 1
        assertEquals(1, exporter.snapshot().elements.size)
    }

    @Test
    fun exportedSpanAppearsInSnapshot() = runTest {
        val exporter = exporterFor()
        val s = span("s1")
        exporter.export(s)
        assertTrue(s in exporter.snapshot().elements)
    }

    // ---- No double-count under retry (the key invariant) ----

    @Test
    fun retryCannotDoubleCount() = runTest {
        // Simulate a retry loop: the same span is sent N times.
        val exporter = exporterFor()
        val s = span("retry-me")
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

        val spanA = span("a1", name = "A-op")
        val spanB = span("b1", name = "B-op")

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
        exporterB.export(span("b1"))
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
        val s = span("b1")
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
        val s = span("s1")
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

        exporter.export(span("s1", startNanos = 100))
        exporter.export(span("s2", startNanos = 200))
        exporter.export(span("s3", startNanos = 300))
        // Buffer is at capacity; exporting s4 must evict the oldest (s1).
        exporter.export(span("s4", startNanos = 400))

        val elements = exporter.snapshot().elements
        assertEquals(3, elements.size)
        assertTrue(elements.none { it.spanId == "s1" }, "oldest span should be evicted")
        assertTrue(elements.any { it.spanId == "s4" }, "newest span should be present")
    }

    @Test
    fun bufferCapEvictsNewestSpanWithDropNewestPolicy() = runTest {
        val exporter = exporterFor(maxSpans = 3, bufferPolicy = BufferPolicy.DROP_NEWEST)

        exporter.export(span("s1", startNanos = 100))
        exporter.export(span("s2", startNanos = 200))
        exporter.export(span("s3", startNanos = 300))
        // Buffer is at capacity; exporting s4 must evict the newest (s3, then s4 is inserted).
        // With DROP_NEWEST the newest buffered span is evicted to make room for the incoming one.
        exporter.export(span("s4", startNanos = 400))

        // After eviction of s3 and insertion of s4, set is {s1, s2, s4}.
        val elements = exporter.snapshot().elements
        assertEquals(3, elements.size)
        assertTrue(elements.none { it.spanId == "s3" }, "newest-before-insert should be evicted")
    }

    @Test
    fun evictedSpanLeavesRoomForNewSpan() = runTest {
        val exporter = exporterFor(maxSpans = 2, bufferPolicy = BufferPolicy.DROP_OLDEST)
        exporter.export(span("s1", startNanos = 100))
        exporter.export(span("s2", startNanos = 200))
        val result = exporter.export(span("s3", startNanos = 300))
        assertEquals(ExportResult.Success, result)
        assertEquals(2, exporter.snapshot().elements.size)
    }

    // ---- Store failures ----

    @Test
    fun exportReturnsFailureWhenStoreFails() = runTest {
        val exporter = exporterFor(store = AlwaysFailStore)
        val result = exporter.export(span("s1"))
        assertIs<ExportResult.Failure>(result)
    }

    private object AlwaysFailStore : DurableStore {
        override suspend fun read(key: String): ByteArray? = null
        override suspend fun write(key: String, bytes: ByteArray) =
            throw RuntimeException("simulated store failure")
        override suspend fun delete(key: String) = Unit
    }
}
