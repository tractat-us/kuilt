package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.OtlpSignal
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.otel.WarpOtlpBridge
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Real-network round-trip: drain a WarpTelemetry through OtlpHttpEdge into an embedded
 * Ktor server. runBlocking is a deliberate real-threading harness (a genuine socket
 * round-trip, fixed clock — no virtual time); production dispatchers are not used.
 */
class OtlpHttpEdgeIntegrationTest {
    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }
    private fun b16(v: Byte) = ByteString(ByteArray(16) { v })
    private fun b8(v: Byte) = ByteString(ByteArray(8) { v })

    @Test
    fun drainRoundTripsAllSignalsThenIsIdempotent() = runBlocking {
        val bodies = mutableMapOf<String, MutableList<String>>()
        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/v1/{signal}") {
                    val sig = call.parameters["signal"] ?: "?"
                    bodies.getOrPut(sig) { mutableListOf() }.add(call.receiveText())
                    call.respondText("{}")
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(OkHttp)
        try {
            val store = InMemoryDurableStore()
            val telemetry = WarpTelemetry(ReplicaId("p"), store)
            telemetry.spans.export(SpanRecord(b16(1), b8(1), null, "op", SpanKind.INTERNAL, 1L, 2L))
            telemetry.logs.export(LogRecord(recordId = b8(2), body = "hi"))
            telemetry.metrics.incrementSum(MetricKey("req", MetricKind.SUM), by = 1L)

            val edge = OtlpHttpEdge(client, "http://localhost:$port", store)
            val bridge = WarpOtlpBridge(telemetry, clock)
            bridge.drain(edge)
            bridge.drain(edge) // idempotent — nothing new on the second pass

            val traces = assertNotNull(bodies["traces"], "the traces endpoint was never posted to")
            val logs = assertNotNull(bodies["logs"], "the logs endpoint was never posted to")
            val metrics = assertNotNull(bodies["metrics"], "the metrics endpoint was never posted to")
            assertEquals(1, traces.size)
            assertEquals(1, logs.size)
            assertEquals(1, metrics.size)
            assertTrue(traces.first().contains("resourceSpans"))
            assertTrue(logs.first().contains("resourceLogs"))
            assertTrue(metrics.first().contains("resourceMetrics"))
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }

    @Test
    fun failedPostLeavesSentSetForRetry() = runBlocking {
        var attempts = 0
        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/v1/traces") {
                    attempts++
                    call.receiveText()
                    if (attempts == 1) {
                        call.respondText("boom", status = io.ktor.http.HttpStatusCode.InternalServerError)
                    } else {
                        call.respondText("{}")
                    }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(OkHttp)
        try {
            val store = InMemoryDurableStore()
            val telemetry = WarpTelemetry(ReplicaId("p"), store)
            telemetry.spans.export(SpanRecord(b16(1), b8(1), null, "op", SpanKind.INTERNAL, 1L, 2L))
            val edge = OtlpHttpEdge(client, "http://localhost:$port", store)
            val bridge = WarpOtlpBridge(telemetry, clock)

            bridge.drain(edge) // first POST 500s → sent-set untouched
            assertTrue(edge.digest().spanIds.isEmpty())
            bridge.drain(edge) // retry succeeds → now recorded
            assertTrue(edge.digest().spanIds.contains(b8(1)))
            assertEquals(2, attempts)
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }

    /**
     * The metric arm of [failedPostLeavesSentSetForRetry] (#3290). Metrics are where a wrong
     * sent-set update shows up as a *counting* error rather than a duplicate id: marking a
     * refused series as sent loses its advance for good, and re-sending a series the collector
     * already holds is a second data point for a total it already counted.
     *
     * Three series, so each failure has somewhere to land:
     * - `req` is delivered at 2, advances to 5, and that advance is what the collector refuses;
     * - `err` first appears in the refused POST, so it has never been delivered at all;
     * - `idle` is delivered at 7 and never moves, so it must never be posted again.
     *
     * The collector model reads only the POSTs it **accepted**. Every Sum is cumulative, so the
     * collector's value for a series is its latest accepted point, and "nothing double-counted"
     * means each series' accepted points are exactly its successive distinct totals.
     */
    @Test
    fun failedMetricPostRetriesOnNextDrainWithNothingDoubleCounted() = runBlocking {
        val posts = AtomicInteger(0)
        val refusedBodies = CopyOnWriteArrayList<String>()
        val acceptedBodies = CopyOnWriteArrayList<String>()
        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/v1/metrics") {
                    val n = posts.incrementAndGet()
                    val body = call.receiveText()
                    if (n == 2) {
                        refusedBodies.add(body)
                        call.respondText("unavailable", status = io.ktor.http.HttpStatusCode.ServiceUnavailable)
                    } else {
                        acceptedBodies.add(body)
                        call.respondText("{}")
                    }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(OkHttp)
        try {
            val store = InMemoryDurableStore()
            val telemetry = WarpTelemetry(ReplicaId("p"), store)
            val edge = OtlpHttpEdge(client, "http://localhost:$port", store)
            val bridge = WarpOtlpBridge(telemetry, clock)
            val req = MetricKey("req", MetricKind.SUM)
            val err = MetricKey("err", MetricKind.SUM)
            val idle = MetricKey("idle", MetricKind.SUM)

            // Drain 1 — accepted: req=2 and idle=7 reach the collector.
            telemetry.metrics.incrementSum(req, by = 2L)
            telemetry.metrics.incrementSum(idle, by = 7L)
            val first = bridge.drain(edge)
            val sentBefore = edge.metricDigest().versions

            // Drain 2 — refused: req has advanced to 5, and err appears at 4.
            telemetry.metrics.incrementSum(req, by = 3L)
            telemetry.metrics.incrementSum(err, by = 4L)
            val refused = bridge.drain(edge)
            val sentAfterRefusal = edge.metricDigest().versions

            // Drain 3 — accepted: the retry. Drain 4 — nothing new, so no POST at all.
            val retry = bridge.drain(edge)
            val sentAfterRetry = edge.metricDigest().versions
            val quiet = bridge.drain(edge)

            val accepted = acceptedBodies.map(::sumPoints)
            // The collector's view: a cumulative Sum's value is its latest accepted point.
            val collector = accepted.flatten().toMap()

            assertAll(
                // The rig fired: exactly one POST was refused, and it carried the advance.
                { assertEquals(1, refusedBodies.size, "the collector never refused a POST") },
                { assertEquals(listOf("err" to 4L, "req" to 5L), sumPoints(refusedBodies.single())) },
                { assertEquals(DrainResult.Success(spansSent = 0, logsSent = 0, metricPointsSent = 2), first) },
                {
                    val failure = assertIs<DrainResult.Failure>(refused).failures.single()
                    assertEquals(OtlpSignal.METRICS, failure.signal)
                },
                // The sent-set before the refusal, and untouched by it.
                { assertEquals(mapOf(req to 2L, idle to 7L), sentBefore) },
                { assertEquals(sentBefore, sentAfterRefusal, "a refused POST moved the sent-set") },
                // The retry carries what was refused, and the sent-set catches up.
                { assertEquals(DrainResult.Success(spansSent = 0, logsSent = 0, metricPointsSent = 2), retry) },
                { assertEquals(mapOf(req to 5L, err to 4L, idle to 7L), sentAfterRetry) },
                { assertEquals(DrainResult.Success(spansSent = 0, logsSent = 0, metricPointsSent = 0), quiet) },
                { assertEquals(3, posts.get(), "POST count across four drains") },
                // Nothing lost, nothing counted twice: each series' accepted points are its distinct totals.
                {
                    assertEquals(
                        listOf(listOf("idle" to 7L, "req" to 2L), listOf("err" to 4L, "req" to 5L)),
                        accepted,
                    )
                },
                { assertEquals(mapOf("idle" to 7L, "req" to 5L, "err" to 4L), collector) },
            )
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }

    /**
     * The `(name, cumulative value)` of every Sum data point in an OTLP/JSON metrics body, sorted
     * by name. Walks the envelope rather than decoding the edge's own request type, so a resource
     * or scope field added to the envelope does not disturb it. Every Sum must be cumulative and
     * monotonic — the property that makes re-sending a total safe rather than a second count.
     */
    private fun sumPoints(body: String): List<Pair<String, Long>> =
        Json.parseToJsonElement(body).jsonObject.getValue("resourceMetrics").jsonArray
            .flatMap { it.jsonObject.getValue("scopeMetrics").jsonArray }
            .flatMap { it.jsonObject.getValue("metrics").jsonArray }
            .flatMap { metric ->
                val m = metric.jsonObject
                val sum = m.getValue("sum").jsonObject
                // 2 is OTLP's AGGREGATION_TEMPORALITY_CUMULATIVE, spelled from the spec rather
                // than from the encoder's own constant so the two cannot drift together.
                assertEquals(2, sum.getValue("aggregationTemporality").jsonPrimitive.int)
                assertTrue(sum.getValue("isMonotonic").jsonPrimitive.boolean)
                val name = m.getValue("name").jsonPrimitive.content
                sum.getValue("dataPoints").jsonArray.map { point ->
                    name to point.jsonObject.getValue("asInt").jsonPrimitive.content.toLong()
                }
            }
            .sortedBy { it.first }
}
