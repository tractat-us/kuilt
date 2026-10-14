package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PersistenceHealthReportingTest {
    @Test
    fun recoveryHealthDoesNotPreemptOrRearmSpanWriteReporting() = runTest {
        val store = HealthStore()
        val key = StoreKey("otel.spans")
        val exporter = WarpSpanExporter(ReplicaId("report"), store)
        capturingLogsOf("us.tractat.kuilt.otel.WarpSpanExporter") { lines ->
            store.refusedReads = setOf(key)
            exporter.recover()
            store.refusedWrites = setOf(key)
            repeat(2) { assertIs<ExportResult.Failure>(exporter.export(healthSpan())) }
            assertAll(
                { assertTrue(exporter.health.value.recoveryFailed) },
                { assertEquals(2L, exporter.health.value.failed) },
                { assertEquals(1, lines.naming("durable write failed").size) },
            )
            store.refusedWrites = emptySet()
            assertEquals(ExportResult.Success, exporter.clear())
            store.refusedWrites = setOf(key)
            assertIs<ExportResult.Failure>(exporter.clear())
            assertEquals(2, lines.naming("durable write failed").size,
                "sticky recovery health must not suppress the next outage")
        }
    }

    @Test
    fun successfulMetricClearDoesNotRearmAStillFailingWrite() = runTest {
        val store = HealthStore()
        val key = MetricKey("sum", MetricKind.SUM)
        val exporter = WarpMetricExporter(ReplicaId("report"), store)
        capturingLogsOf("us.tractat.kuilt.otel.WarpMetricExporter") { lines ->
            store.refusedWrites = setOf(StoreKey("otel.metrics.sums"))
            assertIs<MetricExportResult.Failure>(exporter.incrementSum(key))
            assertEquals(MetricExportResult.Success, exporter.clear())
            assertIs<MetricExportResult.Failure>(exporter.incrementSum(key))
            assertAll(
                { assertEquals(2, store.writeFailures) },
                { assertEquals(1L, exporter.health.value.acknowledged) },
                { assertEquals(2L, exporter.health.value.outstandingFailures) },
                { assertEquals(1, lines.naming("durable write failed").size) },
            )
        }
    }
}
