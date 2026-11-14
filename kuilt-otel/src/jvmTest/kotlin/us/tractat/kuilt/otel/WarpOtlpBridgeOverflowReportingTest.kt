package us.tractat.kuilt.otel

import ch.qos.logback.classic.Level
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A sum series past [Long.MAX_VALUE] is skipped on every drain, and is reported at WARN on the first
 * one only (#3318). Under `installLogCapture` a WARN per drain would itself be a durable log record
 * the next drain ships, a trickle that lasts as long as the series. See [capturingLogsOf] for why
 * this runs on the JVM and still says something about `commonMain`.
 */
class WarpOtlpBridgeOverflowReportingTest {

    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    private object NoSpansEdge : OtlpEdge {
        override suspend fun digest(): SpanDigest = SpanDigest(emptySet())
        override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) {}
    }

    @Test
    fun anOverflowingSumSeriesIsWarnedOnceAcrossTwoDrains() = runTest {
        val telemetry = WarpTelemetry(ReplicaId("device"), InMemoryDurableStore())
        val big = MetricKey("big", MetricKind.SUM)
        telemetry.metrics.incrementSum(big, by = Long.MAX_VALUE - 1L)
        telemetry.metrics.mergeSum(big, GCounter.of(ReplicaId("other") to 2L))
        val bridge = WarpOtlpBridge(telemetry, clock)

        capturingLogsOf(BRIDGE_LOGGER) { events ->
            bridge.drain(NoSpansEdge)
            bridge.drain(NoSpansEdge)

            val lines = events.naming("sum series big")
            assertAll(
                { assertEquals(2, lines.size, "the rig: both drains met the series and logged it") },
                { assertEquals(listOf(Level.WARN, Level.DEBUG), lines.map { it.level }) },
            )
        }
    }

    private companion object {
        /** The bridge's logger name, spelled out because the production constant is private. */
        const val BRIDGE_LOGGER = "us.tractat.kuilt.otel.WarpOtlpBridge"
    }
}
