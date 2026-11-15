package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricExportResult
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricWriteResult
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.store.InMemoryDurableStore
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

// Shared rig for #3309: every way a consumer can leave a non-finite value as a metric series'
// latest, through the public recording API, and a collector that records what each drain sent.

internal val fixedClock: Clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

internal val badGauge = MetricKey("bad.gauge", MetricKind.GAUGE)
internal val badSum = MetricKey("bad.sum", MetricKind.SUM)
internal val healthyGauge = MetricKey("healthy.gauge", MetricKind.GAUGE)

/**
 * One public-API route to a non-finite (or signed-zero) value in the metric buffer.
 *
 * [plant] records it into a fresh telemetry; [bufferedValue] reads the series back the way the
 * bridge renders it, so a test can assert the value is really there before it drains.
 */
internal enum class NonFiniteRoute(val series: MetricKey, val expected: Double) {
    /** `setGauge(NaN)`: `MetricUpdate.SetGauge` and `LWWRegister` both admit it. */
    NAN_GAUGE(badGauge, Double.NaN) {
        override suspend fun plant(t: WarpTelemetry) = t.metrics.setGauge(badGauge, Double.NaN, 1L).requireRecorded()
    },

    /** `setGauge(+Infinity)`. */
    POSITIVE_INFINITE_GAUGE(badGauge, Double.POSITIVE_INFINITY) {
        override suspend fun plant(t: WarpTelemetry) =
            t.metrics.setGauge(badGauge, Double.POSITIVE_INFINITY, 1L).requireRecorded()
    },

    /** `setGauge(-Infinity)`: a gauge has no sign rule, so this is admitted where a counter's is not. */
    NEGATIVE_INFINITE_GAUGE(badGauge, Double.NEGATIVE_INFINITY) {
        override suspend fun plant(t: WarpTelemetry) =
            t.metrics.setGauge(badGauge, Double.NEGATIVE_INFINITY, 1L).requireRecorded()
    },

    /** `incrementSumDouble(+Infinity)`: passes `by > 0.0`. */
    INFINITE_COUNTER_DELTA(badSum, Double.POSITIVE_INFINITY) {
        override suspend fun plant(t: WarpTelemetry) =
            t.metrics.incrementSumDouble(badSum, Double.POSITIVE_INFINITY).requireRecorded()
    },

    /** Two finite local deltas of `1e308`: this replica's own slot overflows to `+Infinity`. */
    ONE_SLOT_FINITE_DELTAS_OVERFLOW(badSum, Double.POSITIVE_INFINITY) {
        override suspend fun plant(t: WarpTelemetry) {
            t.metrics.incrementSumDouble(badSum, 1e308).requireRecorded()
            t.metrics.incrementSumDouble(badSum, 1e308).requireRecorded()
        }
    },

    /**
     * Two replicas' finite slots of `1e308` each, merged: every slot is finite, and
     * `GCounterDouble.value`'s plain `sumOf` reads `+Infinity`.
     */
    TWO_FINITE_SLOTS_SUM_OVERFLOW(badSum, Double.POSITIVE_INFINITY) {
        override suspend fun plant(t: WarpTelemetry) {
            t.metrics.incrementSumDouble(badSum, 1e308).requireRecorded()
            val peer = WarpTelemetry(ReplicaId("peer-3309"), InMemoryDurableStore()).also { it.recover() }
            peer.metrics.incrementSumDouble(badSum, 1e308).requireRecorded()
            val remote = peer.metrics.doubleSumSnapshot(badSum)
            check(remote.value.isFinite()) { "rig: the peer's own snapshot must be finite, was ${remote.value}" }
            t.metrics.mergeSumDouble(badSum, remote).requireWritten()
        }
    },

    /**
     * A peer slot of `+Infinity` decoded off the wire and merged: `GCounterDouble`'s `init` only
     * requires `count >= 0.0`, and the generated decoder runs that same `init`.
     */
    @OptIn(ExperimentalSerializationApi::class)
    DECODED_INFINITE_PEER_SLOT(badSum, Double.POSITIVE_INFINITY) {
        override suspend fun plant(t: WarpTelemetry) {
            val wire = Cbor.encodeToByteArray(
                GCounterDouble.serializer(),
                GCounterDouble.of(ReplicaId("peer-3309") to Double.POSITIVE_INFINITY),
            )
            val decoded = Cbor.decodeFromByteArray(GCounterDouble.serializer(), wire)
            t.metrics.mergeSumDouble(badSum, decoded).requireWritten()
        }
    },

    /** `setGauge(-0.0)`: finite, so the control route; JSON writes it as `-0.0`. */
    NEGATIVE_ZERO_GAUGE(badGauge, -0.0) {
        override suspend fun plant(t: WarpTelemetry) = t.metrics.setGauge(badGauge, -0.0, 1L).requireRecorded()
    },
    ;

    abstract suspend fun plant(t: WarpTelemetry)

    fun bufferedValue(t: WarpTelemetry): Double? = when (series.kind) {
        MetricKind.GAUGE -> t.metrics.gaugeValue(series)
        else -> t.metrics.doubleSumValue(series)
    }
}

private fun MetricExportResult.requireRecorded() =
    check(this == MetricExportResult.Success) { "rig: the recording was not applied: $this" }

private fun MetricWriteResult.requireWritten() =
    check(this == MetricWriteResult.Success) { "rig: the merge was not applied: $this" }

/**
 * A fresh telemetry holding one span, one log, one finite gauge and the [route]'s bad series, so
 * one drain shows what the bad point costs the other series and the other signals.
 */
internal suspend fun telemetryWith(route: NonFiniteRoute): WarpTelemetry {
    val t = WarpTelemetry(ReplicaId("device-3309"), InMemoryDurableStore()).also { it.recover() }
    t.spans.export(SpanRecord(ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { 1 }), null, "op", SpanKind.INTERNAL, 1L, 2L))
    t.logs.export(LogRecord(recordId = ByteString(ByteArray(8) { 2 }), body = "hi"))
    t.metrics.setGauge(healthyGauge, 0.5, 1L).requireRecorded()
    route.plant(t)
    // The rig: the bad value is in the buffer, bit for bit, before anything drains.
    assertEquals(
        route.expected.toRawBits(),
        route.bufferedValue(t)?.toRawBits(),
        "rig: $route must leave ${route.expected} as ${route.series.name}'s latest, was ${route.bufferedValue(t)}",
    )
    return t
}

/** A 200-answering collector that keeps every request body by path. */
internal class RecordingCollector {
    val bodies: MutableList<Pair<String, ByteArray>> = mutableListOf()

    val client: HttpClient = HttpClient(
        MockEngine { req ->
            bodies += req.url.encodedPath to when (val b = req.body) {
                is TextContent -> b.text.encodeToByteArray()
                is OutgoingContent.ByteArrayContent -> b.bytes()
                else -> error("unexpected body ${b::class}")
            }
            respond("{}", HttpStatusCode.OK)
        },
    )

    fun posts(path: String): Int = bodies.count { it.first == path }

    fun bodiesAt(path: String): List<ByteArray> = bodies.filter { it.first == path }.map { it.second }
}

/** One line naming the outcome, and for a failed leg the exception's type and message. */
internal fun DrainResult.describe(): String = when (this) {
    is DrainResult.Success -> "Success(spans=$spansSent, logs=$logsSent, metrics=$metricPointsSent)"
    is DrainResult.Partial -> "Partial(spans=$spansSent, logs=$logsSent, metrics=$metricPointsSent, failures=${failures.describe()})"
    is DrainResult.Failure -> "Failure(${failures.describe()})"
}

private fun List<us.tractat.kuilt.otel.SignalFailure>.describe(): String =
    joinToString(prefix = "[", postfix = "]") { "${it.signal}: ${it.cause::class.simpleName}: ${it.cause.message}" }
