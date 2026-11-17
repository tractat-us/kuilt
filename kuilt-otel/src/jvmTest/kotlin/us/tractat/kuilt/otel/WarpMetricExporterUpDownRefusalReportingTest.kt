package us.tractat.kuilt.otel

import ch.qos.logback.classic.Level
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A saturated up-down series is refused on every export, so its refusal is logged at WARN the first
 * time and at DEBUG after that (#3307). One key names a series in both the integer and the
 * fractional map, and each is its own series: the fractional one's first refusal is news even after
 * the integer one's. See [capturingLogsOf] for why this runs on the JVM and still says something
 * about `commonMain`.
 */
class WarpMetricExporterUpDownRefusalReportingTest {

    @Test
    fun eachMapsFirstRefusalOfOneKeyIsWarned() = runTest {
        val exporter = WarpMetricExporter(ReplicaId("A"), InMemoryDurableStore()).also { it.recover() }
        val key = MetricKey("in_flight", MetricKind.UP_DOWN_SUM)
        // This replica's level at the top of each map, where any rise is refused.
        exporter.setUpDownSum(key, Long.MAX_VALUE)
        exporter.setUpDownSumDouble(key, Double.MAX_VALUE)

        capturingLogsOf(METRIC_EXPORTER_LOGGER) { events ->
            val results = listOf(
                exporter.addUpDownSum(key, 1L),
                exporter.addUpDownSum(key, 1L),
                exporter.addUpDownSumDouble(key, Double.MAX_VALUE),
                exporter.addUpDownSumDouble(key, Double.MAX_VALUE),
            )
            val lines = events.naming("up-down sum series in_flight")
            assertAll(
                { results.forEach { assertIs<MetricExportResult.Refused>(it, "the rig: every add was refused") } },
                { assertEquals(4, lines.size, "the rig: every refusal was logged") },
                { assertEquals(listOf(Level.WARN, Level.DEBUG, Level.WARN, Level.DEBUG), lines.map { it.level }) },
            )
        }
    }

    private companion object {
        /** The exporter's logger name, spelled out because the production constant is private. */
        const val METRIC_EXPORTER_LOGGER = "us.tractat.kuilt.otel.WarpMetricExporter"
    }
}
