package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins [WarpLogRecordExporter.clear] (#2208): a supported reset a live exporter keeps
 * exporting into, with the persisted segments actually deleted.
 *
 * The property that matters is durable, not in-memory — "the buffer looks empty" was
 * always achievable and is not what the consumer could not get.
 */
class WarpLogRecordExporterClearTest {

    private val replicaA = ReplicaId("A")

    // Little-endian over a Long: Int.shr masks its operand to 5 bits, so `id shr 32` is `id shr 0`
    // and an Int-based version would silently mirror bytes 0-3 into 4-7 rather than encode 64 bits.
    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id.toLong() shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "log message body number ${id.toString().padStart(6, '0')}",
        severityNumber = 9,
        severityText = "INFO",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporterFor(
        store: DurableStore,
        maxRecords: Int = DEFAULT_MAX_LOG_RECORDS,
        segmentOps: Int = DEFAULT_LOG_SEGMENT_OPS,
    ) = WarpLogRecordExporter(
        replica = replicaA,
        store = store,
        maxRecords = maxRecords,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = segmentOps,
    )

    @Test
    fun clearEmptiesTheBufferAndTheStoreAFreshExporterRecoversFrom() = runTest {
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store, segmentOps = 2)
        repeat(10) { i -> exporter.export(record(i)) }

        assertEquals(ExportResult.Success, exporter.clear())

        val recovered = exporterFor(store = store, segmentOps = 2)
        recovered.recover()
        assertAll(
            { assertEquals(emptyList(), exporter.snapshot().toList(), "the live buffer is empty") },
            { assertEquals(emptyList(), recovered.snapshot().toList(), "a fresh exporter recovers empty") },
        )
    }

    @Test
    fun clearingAnAlreadyEmptyExporterSucceedsAndLeavesARecoverableStore() = runTest {
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store)

        assertEquals(ExportResult.Success, exporter.clear())

        // Asserts a recoverable empty store, NOT "wrote nothing". A clear writes its index and
        // active segment unconditionally — see clearTurn — and a test named for the absent
        // write would pin the optimisation that breaks the retry.
        val recovered = exporterFor(store = store)
        recovered.recover()
        assertAll(
            { assertEquals(emptyList(), exporter.snapshot().toList()) },
            { assertEquals(emptyList(), recovered.snapshot().toList()) },
        )
    }

    @Test
    fun theSameInstanceKeepsExportingAfterAClearAndARestartSeesOnlyTheNewRecords() = runTest {
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store, segmentOps = 2)
        repeat(10) { i -> exporter.export(record(i)) }
        exporter.clear()

        repeat(5) { i -> exporter.export(record(100 + i)) }

        val recovered = exporterFor(store = store, segmentOps = 2)
        recovered.recover()
        assertEquals(
            (100 until 105).map { recordId(it) },
            recovered.snapshot().toList().map { it.recordId },
            "re-initialisation: only what was exported after the clear survives a restart",
        )
    }

    /**
     * The reclamation half of the motivation, which the three tests above do not cover: a clear
     * that emptied memory and the index while leaving every segment key on disk passes all of
     * them. [RecordingStore.keys] is what reports the live key set — `InMemoryDurableStore`
     * cannot, and it should stay that way; the absence of key enumeration on [DurableStore] is a
     * documented consumer-facing constraint (#2208), not an oversight to close here.
     */
    @Test
    fun clearDeletesTheSealedSegmentKeysAndLeavesOnlyTheIndexAndOneActiveSegment() = runTest {
        val store = RecordingStore()
        val exporter = exporterFor(store = store, segmentOps = 2)
        repeat(20) { i -> exporter.export(record(i)) }

        val segmentsBefore = store.keys().filter { it.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }
        assertTrue(
            segmentsBefore.size >= 5,
            "the fixture must actually roll segments or this test proves nothing; got $segmentsBefore",
        )

        assertEquals(ExportResult.Success, exporter.clear())

        val segmentsAfter = store.keys().filter { it.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }
        assertAll(
            { assertEquals(1, segmentsAfter.size, "exactly one active segment survives; got $segmentsAfter") },
            { assertTrue(INDEX_KEY_FOR_TEST.name in store.keys(), "the index survives") },
        )
    }

    @Test
    fun aPeerHoldingThePreClearOpsCannotPushThemBackThroughMerge() = runTest {
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store)
        exporter.export(record(1))
        exporter.export(record(2))
        // A peer that gossiped with us before the clear still holds the raw Inserts.
        val peerCopy = exporter.snapshot()

        assertEquals(ExportResult.Success, exporter.clear())
        assertEquals(ExportResult.Success, exporter.merge(peerCopy))

        assertEquals(
            emptyList(),
            exporter.snapshot().toList(),
            "the floor must suppress the cleared dots, so a merge re-purges rather than resurrects",
        )
    }

    @Test
    fun aRecordExportedAfterAClearDoesNotReuseTheIdOfOneExportedBefore() = runTest {
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store)
        exporter.export(record(1))
        val idBefore = exporter.snapshot().entries().single().first

        exporter.clear()
        exporter.export(record(2))
        val idAfter = exporter.snapshot().entries().single().first

        // Rga.empty() would re-mint (lamport = 1, A, seq = 1) for both, and a later merge
        // would then resolve two different records onto one id by map-put order.
        assertNotEquals(
            idBefore,
            idAfter,
            "a cleared exporter must not re-mint an RgaId it has already used",
        )
    }

    private class WriteRefusingStore(private val backing: DurableStore) : DurableStore {
        var refuse: Boolean = false
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (refuse) throw IllegalStateException("store refused $key")
            backing.write(key, bytes)
        }
        override suspend fun delete(key: StoreKey) = backing.delete(key)
    }

    @Test
    fun aRefusedClearReportsFailureAndARetryConverges() = runTest {
        val store = WriteRefusingStore(InMemoryDurableStore())
        val exporter = exporterFor(store = store, segmentOps = 2)
        repeat(10) { i -> exporter.export(record(i)) }

        // Captured rather than hard-coded: `accepted` counts the ten successful exports above,
        // and what this test asserts is that a clear does not MOVE it, not what it equals.
        val acceptedBefore = exporter.health.value.accepted
        val failedBefore = exporter.health.value.failed

        store.refuse = true
        val refused = exporter.clear()
        val failedAfterRefusal = exporter.health.value.failed

        // A failed clear leaves the buffer empty while the store still holds the records —
        // the documented divergence. A caller must read this as "count unknown", not zero.
        val snapshotAfterRefusal = exporter.snapshot().toList()

        store.refuse = false
        val retried = exporter.clear()

        // THE assertion of this test. A retry that returns Success having written nothing
        // passes every other line here — the live buffer was already empty from the failed
        // attempt — while the store still holds every sealed segment and a restart brings
        // all ten records back. Only recovering a fresh exporter can tell the two apart.
        val recovered = exporterFor(store = store, segmentOps = 2)
        recovered.recover()

        assertAll(
            { assertTrue(refused is ExportResult.Failure, "a refused durable write fails the clear") },
            { assertEquals(failedBefore + 1, failedAfterRefusal, "the store rejected a write, so `failed` moves") },
            { assertEquals(emptyList(), snapshotAfterRefusal, "the in-memory drop is not undone on failure") },
            { assertEquals(ExportResult.Success, retried, "a retry converges") },
            { assertEquals(emptyList(), recovered.snapshot().toList(), "the retry actually reached the store") },
            { assertEquals(acceptedBefore, exporter.health.value.accepted, "no clear moves `accepted`") },
            { assertEquals(emptyList(), exporter.snapshot().toList()) },
        )
    }
}
