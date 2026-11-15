package us.tractat.kuilt.otel.otlp

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.WarpOtlpBridge
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * REPRODUCTION for #3309 — **red on purpose; do not merge as is.**
 *
 * Each test leaves a non-finite value as one metric series' latest through the public recording
 * API, beside one span, one log and one finite gauge, then drains twice over OTLP/JSON. The
 * property asserted is the one every candidate fix has to meet, whichever is chosen: the metric
 * leg succeeds, on the first drain and the next, and the finite series in the same batch reaches
 * the collector. What happens to the bad point itself (dropped, skipped, spelled specially) is
 * the fix's decision and deliberately not asserted.
 *
 * The span and log arms are expected green: they show the failure stays inside the metric leg.
 *
 * The rig asserts its own firing in [telemetryWith]: the bad value is in the buffer, bit for bit,
 * before the drain. A fix that refuses a route at ingestion will red that precondition instead,
 * and the route's test then needs restating as "refused at record time" rather than deleting.
 */
class NonFiniteMetricJsonDrainTest {

    private val endpoint = "https://c.example:4318"

    /** An unquoted `NaN` / `Infinity` / `-Infinity` in value position: not a JSON number. */
    private val bareNonFiniteNumber = Regex(""":\s*(NaN|-?Infinity)\s*[,}\]]""")

    private suspend fun assertJsonDrainSurvives(route: NonFiniteRoute) {
        val telemetry = telemetryWith(route)
        val collector = RecordingCollector()
        val edge = OtlpHttpEdge(collector.client, endpoint, InMemoryDurableStore())
        val bridge = WarpOtlpBridge(telemetry, fixedClock)

        val first = bridge.drain(edge)
        val second = bridge.drain(edge)

        val metricBodies = collector.bodiesAt("/v1/metrics").map { it.decodeToString() }
        assertAll(
            { assertEquals(1, collector.posts("/v1/traces"), "the span leg is independent of the metric leg") },
            { assertEquals(1, collector.posts("/v1/logs"), "the log leg is independent of the metric leg") },
            { assertIs<DrainResult.Success>(first, "$route, first JSON drain: ${first.describe()}") },
            { assertIs<DrainResult.Success>(second, "$route, second JSON drain: ${second.describe()}") },
            {
                assertTrue(
                    metricBodies.any { "\"name\":\"${healthyGauge.name}\"" in it },
                    "$route: the finite series in the same batch must reach the collector; " +
                        "metric POSTs the collector saw = ${metricBodies.size}",
                )
            },
            // A JSON number has no NaN or Infinity, so a fix that only turns on kotlinx's
            // allowSpecialFloatingPointValues sends a body no strict parser reads. OTLP/JSON follows
            // the protobuf JSON mapping, which spells them as the strings "NaN", "Infinity" and
            // "-Infinity". Measured: with that one flag set every other arm here goes green.
            {
                val bare = metricBodies.flatMap { body -> bareNonFiniteNumber.findAll(body).map { it.value }.toList() }
                assertTrue(bare.isEmpty(), "$route: a metric body carries a bare non-JSON number: $bare")
            },
        )
    }

    @Test
    fun nanGaugeDrainsOverJson() = runTest { assertJsonDrainSurvives(NonFiniteRoute.NAN_GAUGE) }

    @Test
    fun positiveInfiniteGaugeDrainsOverJson() = runTest {
        assertJsonDrainSurvives(NonFiniteRoute.POSITIVE_INFINITE_GAUGE)
    }

    @Test
    fun negativeInfiniteGaugeDrainsOverJson() = runTest {
        assertJsonDrainSurvives(NonFiniteRoute.NEGATIVE_INFINITE_GAUGE)
    }

    @Test
    fun infiniteCounterDeltaDrainsOverJson() = runTest {
        assertJsonDrainSurvives(NonFiniteRoute.INFINITE_COUNTER_DELTA)
    }

    @Test
    fun finiteDeltasOverflowingOneSlotDrainOverJson() = runTest {
        assertJsonDrainSurvives(NonFiniteRoute.ONE_SLOT_FINITE_DELTAS_OVERFLOW)
    }

    @Test
    fun twoFiniteSlotsSummingPastMaxValueDrainOverJson() = runTest {
        assertJsonDrainSurvives(NonFiniteRoute.TWO_FINITE_SLOTS_SUM_OVERFLOW)
    }

    @Test
    fun decodedInfinitePeerSlotDrainsOverJson() = runTest {
        assertJsonDrainSurvives(NonFiniteRoute.DECODED_INFINITE_PEER_SLOT)
    }
}
