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
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.otel.WarpOtlpBridge
import us.tractat.kuilt.otel.WarpTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
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

            assertEquals(1, bodies["traces"]?.size)
            assertEquals(1, bodies["logs"]?.size)
            assertEquals(1, bodies["metrics"]?.size)
            assertTrue(bodies["traces"]!!.first().contains("resourceSpans"))
            assertTrue(bodies["logs"]!!.first().contains("resourceLogs"))
            assertTrue(bodies["metrics"]!!.first().contains("resourceMetrics"))
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
}
