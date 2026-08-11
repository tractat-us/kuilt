package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

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
}
