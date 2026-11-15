package us.tractat.kuilt.otel.otlp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricUpdate
import us.tractat.kuilt.otel.WarpOtlpBridge
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The #3309 routes that do NOT fail, pinned: every non-finite route over OTLP/protobuf, the
 * negative-zero gauge over both wires, and the counter values the recording API refuses outright.
 *
 * A protobuf drain is only evidence once the bad point is read back off the wire, so each test
 * decodes the request the collector received and compares the point's raw bits: a drain that
 * skipped the series, or never reached the encoder, reds here rather than passing by absence.
 */
@OptIn(ExperimentalSerializationApi::class)
class NonFiniteMetricSafeDrainTest {

    private val endpoint = "https://c.example:4318"

    /** Every value the collector received for [key]'s series, across all metric POSTs, in order. */
    private fun RecordingCollector.protobufValues(key: MetricKey): List<Double?> =
        bodiesAt("/v1/metrics").flatMap { bytes ->
            ProtoBuf.decodeFromByteArray(ProtoMetricsRequest.serializer(), bytes)
                .resourceMetrics.flatMap { it.scopeMetrics }.flatMap { it.metrics }
                .filter { it.name == key.name }
                .flatMap { m -> (m.gauge?.dataPoints ?: m.sum?.dataPoints).orEmpty() }
                .map { it.asDouble }
        }

    private suspend fun assertProtobufDrainCarries(route: NonFiniteRoute) {
        val telemetry = telemetryWith(route)
        val collector = RecordingCollector()
        val edge = OtlpHttpEdge(collector.client, endpoint, InMemoryDurableStore(), wire = OtlpWireFormat.PROTOBUF)
        val bridge = WarpOtlpBridge(telemetry, fixedClock)

        val first = bridge.drain(edge)
        val second = bridge.drain(edge)

        val bad = collector.protobufValues(route.series)
        assertAll(
            { assertIs<DrainResult.Success>(first, "$route, first protobuf drain: ${first.describe()}") },
            { assertEquals(2, (first as? DrainResult.Success)?.metricPointsSent, "$route: both series in the first batch") },
            // The digest folded the bad point's hash forward, so the next drain resends nothing.
            { assertEquals(DrainResult.Success(0, 0, 0), second, "$route, second protobuf drain: ${second.describe()}") },
            { assertEquals(listOf(0.5), collector.protobufValues(healthyGauge), "$route: the finite series") },
            {
                assertEquals(
                    listOf(route.expected.toRawBits()),
                    bad.map { it?.toRawBits() },
                    "$route: the bad point reaches the wire bit for bit, once; saw $bad",
                )
            },
        )
    }

    @Test
    fun nanGaugeDrainsOverProtobuf() = runTest { assertProtobufDrainCarries(NonFiniteRoute.NAN_GAUGE) }

    @Test
    fun positiveInfiniteGaugeDrainsOverProtobuf() = runTest {
        assertProtobufDrainCarries(NonFiniteRoute.POSITIVE_INFINITE_GAUGE)
    }

    @Test
    fun negativeInfiniteGaugeDrainsOverProtobuf() = runTest {
        assertProtobufDrainCarries(NonFiniteRoute.NEGATIVE_INFINITE_GAUGE)
    }

    @Test
    fun infiniteCounterDeltaDrainsOverProtobuf() = runTest {
        assertProtobufDrainCarries(NonFiniteRoute.INFINITE_COUNTER_DELTA)
    }

    @Test
    fun finiteDeltasOverflowingOneSlotDrainOverProtobuf() = runTest {
        assertProtobufDrainCarries(NonFiniteRoute.ONE_SLOT_FINITE_DELTAS_OVERFLOW)
    }

    @Test
    fun twoFiniteSlotsSummingPastMaxValueDrainOverProtobuf() = runTest {
        assertProtobufDrainCarries(NonFiniteRoute.TWO_FINITE_SLOTS_SUM_OVERFLOW)
    }

    @Test
    fun decodedInfinitePeerSlotDrainsOverProtobuf() = runTest {
        assertProtobufDrainCarries(NonFiniteRoute.DECODED_INFINITE_PEER_SLOT)
    }

    @Test
    fun negativeZeroGaugeDrainsOverProtobuf() = runTest {
        assertProtobufDrainCarries(NonFiniteRoute.NEGATIVE_ZERO_GAUGE)
    }

    @Test
    fun negativeZeroGaugeDrainsOverJsonWithItsSign() = runTest {
        val route = NonFiniteRoute.NEGATIVE_ZERO_GAUGE
        val telemetry = telemetryWith(route)
        val collector = RecordingCollector()
        val edge = OtlpHttpEdge(collector.client, endpoint, InMemoryDurableStore())

        val result = WarpOtlpBridge(telemetry, fixedClock).drain(edge)

        val bodies = collector.bodiesAt("/v1/metrics").map { it.decodeToString() }
        val badPoints = bodies.flatMap { body ->
            Json.parseToJsonElement(body).jsonObject.getValue("resourceMetrics").jsonArray
                .flatMap { it.jsonObject.getValue("scopeMetrics").jsonArray }
                .flatMap { it.jsonObject.getValue("metrics").jsonArray }
                .map { it.jsonObject }
                .filter { it.getValue("name").jsonPrimitive.content == route.series.name }
                .flatMap { it.getValue("gauge").jsonObject.getValue("dataPoints").jsonArray }
                .map { it.jsonObject.getValue("asDouble").jsonPrimitive }
        }
        assertAll(
            { assertIs<DrainResult.Success>(result, "negative-zero JSON drain: ${result.describe()}") },
            { assertEquals(1, badPoints.size, "one bad.gauge point on the wire; bodies=$bodies") },
            {
                assertEquals(
                    (-0.0).toRawBits(),
                    badPoints.singleOrNull()?.double?.toRawBits(),
                    "the sign of zero survives the JSON wire; literal was ${badPoints.singleOrNull()?.content}",
                )
            },
        )
    }

    /** The counter values the API refuses at record time: they never reach a drain at all. */
    @Test
    fun nonPositiveAndNanCounterValuesAreRefusedAtRecordTime() {
        val key = NonFiniteRoute.INFINITE_COUNTER_DELTA.series
        assertAll(
            { assertFailsWith<IllegalArgumentException> { MetricUpdate.IncrementSumDouble(key, Double.NaN) } },
            { assertFailsWith<IllegalArgumentException> { MetricUpdate.IncrementSumDouble(key, Double.NEGATIVE_INFINITY) } },
            { assertFailsWith<IllegalArgumentException> { MetricUpdate.IncrementSumDouble(key, -0.0) } },
            { assertFailsWith<IllegalArgumentException> { GCounterDouble.of(ReplicaId("peer") to Double.NaN) } },
            {
                assertFailsWith<IllegalArgumentException> {
                    GCounterDouble.of(ReplicaId("peer") to Double.NEGATIVE_INFINITY)
                }
            },
        )
    }
}
