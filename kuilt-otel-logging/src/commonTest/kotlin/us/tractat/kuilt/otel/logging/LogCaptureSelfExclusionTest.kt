package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The self-capture exclusion must be scoped to the exporter's own
 * `us.tractat.kuilt.otel.*` loggers only — narrow enough to break the export
 * feedback loop, but not so broad it swallows kuilt's *library* diagnostics
 * (`us.tractat.kuilt.session.*`, `...liveness.*`, `...raft.*`, `...nw.*`), which
 * consumers rely on when kuilt is their networking library (#1638).
 */
class LogCaptureSelfExclusionTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    @Test
    fun capturesKuiltLibraryLoggersButExcludesTheExportersOwn() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))

        // A kuilt *library* logger — the exact class that emits real diagnostics a
        // consumer needs. It must be captured.
        val libraryResult = capture.capture(
            NormalizedLogEvent(LogLevel.INFO, "us.tractat.kuilt.session.SeamRoom", "roster changed"),
        )
        // The exporter's own logger — capturing it feeds the export feedback loop.
        // It must be dropped before any record is built.
        val exporterResult = capture.capture(
            NormalizedLogEvent(LogLevel.WARN, "us.tractat.kuilt.otel.WarpLogRecordExporter", "buffer evicted"),
        )

        val records = exporter.snapshot().toList()
        assertAll(
            { assertNotNull(libraryResult, "kuilt library event should be captured") },
            { assertNull(exporterResult, "the exporter's own event should be excluded") },
            { assertEquals(1, records.size) },
            {
                assertEquals(
                    "us.tractat.kuilt.session.SeamRoom",
                    records.single().attributes[LOGGER_NAME_ATTRIBUTE],
                )
            },
            {
                assertTrue(
                    records.none { (it.attributes[LOGGER_NAME_ATTRIBUTE] ?: "").startsWith("us.tractat.kuilt.otel") },
                    "no us.tractat.kuilt.otel record should survive",
                )
            },
        )
    }
}
