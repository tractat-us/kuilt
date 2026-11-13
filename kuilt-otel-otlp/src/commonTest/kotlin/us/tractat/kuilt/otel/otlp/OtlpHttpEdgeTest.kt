package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtlpHttpEdgeTest {
    private fun tId(b: Byte) = ByteString(ByteArray(16) { b })
    private fun sId(b: Byte) = ByteString(ByteArray(8) { b })
    private fun span(b: Byte) = SpanRecord(tId(b), sId(b), null, "op", SpanKind.INTERNAL, 1L, 2L)

    @Test
    fun sendPostsToTracesPathAsJson() = runTest {
        var path = ""
        var contentType = ""
        var body = ""
        val engine = MockEngine { req ->
            path = req.url.encodedPath
            contentType = req.body.contentType?.toString() ?: ""
            body = (req.body as io.ktor.http.content.TextContent).text
            respond("{}", HttpStatusCode.OK)
        }
        val edge = OtlpHttpEdge(HttpClient(engine), "https://collector.example:4318", InMemoryDurableStore())
        edge.send(setOf(span(1)))
        assertEquals("/v1/traces", path)
        assertTrue(contentType.contains("application/json"), contentType)
        assertTrue(body.contains("resourceSpans"), body)
    }

    @Test
    fun protobufWirePostsAsXProtobufBytes() = runTest {
        var contentType = ""
        var bodyBytes = ByteArray(0)
        val engine = MockEngine { req ->
            contentType = req.body.contentType?.toString() ?: ""
            bodyBytes = (req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
            respond("", HttpStatusCode.OK)
        }
        val edge = OtlpHttpEdge(
            HttpClient(engine), "https://collector.example:4318", InMemoryDurableStore(),
            wire = OtlpWireFormat.PROTOBUF,
        )
        edge.send(setOf(span(1)))
        assertTrue(contentType.contains("application/x-protobuf"), contentType)
        // Binary, not JSON: the first byte is the resource_spans tag 0x0a, not '{' (0x7b).
        assertEquals(0x0a.toByte(), bodyBytes.first())
        // The 8-byte span id (all 0x01) appears verbatim as raw bytes, never as hex text.
        assertTrue(bodyBytes.toList().windowed(8).any { w -> w.all { it == 0x01.toByte() } }, "raw span-id bytes")
        // Digest reconciliation is wire-agnostic — the send still records the span id.
        assertTrue(edge.digest().spanIds.contains(sId(1)))
    }

    @Test
    fun digestReflectsPriorSends() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
        val store = InMemoryDurableStore()
        val edge = OtlpHttpEdge(HttpClient(engine), "https://c.example:4318", store)
        edge.send(setOf(span(1)))
        assertTrue(edge.digest().spanIds.contains(sId(1)))

        // A fresh edge over the same store recovers the sent-set.
        val edge2 = OtlpHttpEdge(HttpClient(engine), "https://c.example:4318", store)
        assertTrue(edge2.digest().spanIds.contains(sId(1)))
    }

    @Test
    fun failedSendDoesNotRecordSentSet() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val store = InMemoryDurableStore()
        val edge = OtlpHttpEdge(HttpClient(engine), "https://c.example:4318", store)
        runCatchingCancellable { edge.send(setOf(span(1))) } // non-2xx throws
        assertTrue(edge.digest().spanIds.isEmpty(), "a 5xx must leave the sent-set untouched so drain retries")
    }

    // ── Resource and instrumentation scope reach the wire (#3153) ────────────────

    /** What one request said about its producer: resource attributes and scope name/version. */
    private data class SentEnvelope(val resource: Map<String, String>?, val scope: Pair<String, String>?)

    /**
     * Drives one edge through all three signals and reads each request body back off the
     * mock engine: JSON by its wire key names, protobuf through the request DTOs (whose
     * field numbers the golden vectors in OtlpProtoEncodingTest pin).
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun sentEnvelopes(wire: OtlpWireFormat, edgeOf: (HttpClient) -> OtlpHttpEdge): Map<String, SentEnvelope> {
        val bodies = mutableMapOf<String, ByteArray>()
        val engine = MockEngine { req ->
            bodies[req.url.encodedPath] = when (val b = req.body) {
                is TextContent -> b.text.encodeToByteArray()
                is OutgoingContent.ByteArrayContent -> b.bytes()
                else -> error("unexpected body ${b::class}")
            }
            respond("{}", HttpStatusCode.OK)
        }
        val edge = edgeOf(HttpClient(engine))
        edge.send(setOf(span(1)))
        edge.sendLogs(setOf(LogRecord(recordId = sId(2), body = "hello")))
        edge.sendMetrics(setOf(MetricPoint.Gauge(MetricKey("cpu", MetricKind.GAUGE), value = 0.5, timeEpochNanos = 5L)))

        fun json(path: String, resourceKey: String, scopeKey: String): SentEnvelope {
            val first = Json.parseToJsonElement(bodies.getValue(path).decodeToString())
                .jsonObject.getValue(resourceKey).jsonArray.single().jsonObject
            return SentEnvelope(
                resource = first["resource"]?.jsonObject?.getValue("attributes")?.jsonArray?.associate {
                    it.jsonObject.getValue("key").jsonPrimitive.content to
                        it.jsonObject.getValue("value").jsonObject.getValue("stringValue").jsonPrimitive.content
                },
                scope = first.getValue(scopeKey).jsonArray.single().jsonObject["scope"]?.jsonObject?.let {
                    (it["name"]?.jsonPrimitive?.content ?: "") to (it["version"]?.jsonPrimitive?.content ?: "")
                },
            )
        }
        fun ProtoResource?.attrs() = this?.attributes?.associate { it.key to it.value.stringValue }
        fun ProtoScope?.pair() = this?.let { it.name to it.version }

        return when (wire) {
            OtlpWireFormat.JSON -> mapOf(
                "traces" to json("/v1/traces", "resourceSpans", "scopeSpans"),
                "logs" to json("/v1/logs", "resourceLogs", "scopeLogs"),
                "metrics" to json("/v1/metrics", "resourceMetrics", "scopeMetrics"),
            )
            OtlpWireFormat.PROTOBUF -> {
                val t = ProtoBuf.decodeFromByteArray(ProtoTracesRequest.serializer(), bodies.getValue("/v1/traces"))
                    .resourceSpans.single()
                val l = ProtoBuf.decodeFromByteArray(ProtoLogsRequest.serializer(), bodies.getValue("/v1/logs"))
                    .resourceLogs.single()
                val m = ProtoBuf.decodeFromByteArray(ProtoMetricsRequest.serializer(), bodies.getValue("/v1/metrics"))
                    .resourceMetrics.single()
                mapOf(
                    "traces" to SentEnvelope(t.resource.attrs(), t.scopeSpans.single().scope.pair()),
                    "logs" to SentEnvelope(l.resource.attrs(), l.scopeLogs.single().scope.pair()),
                    "metrics" to SentEnvelope(m.resource.attrs(), m.scopeMetrics.single().scope.pair()),
                )
            }
        }
    }

    @Test
    fun edgeSendsItsResourceAndScopeOnBothWiresForEverySignal() = runTest {
        val resource = mapOf("service.name" to "svc", "host.name" to "box-1")
        val expected = SentEnvelope(resource, "us.tractat.kuilt.otel" to "0.7.5")
        val sent = OtlpWireFormat.entries.associateWith { wire ->
            sentEnvelopes(wire) { client ->
                OtlpHttpEdge(
                    client, "https://collector.example:4318", InMemoryDurableStore(),
                    wire = wire, resource = resource, scopeName = "us.tractat.kuilt.otel", scopeVersion = "0.7.5",
                )
            }
        }
        assertAll(
            *sent.flatMap { (wire, bySignal) ->
                listOf("traces", "logs", "metrics").map { signal ->
                    { assertEquals(expected, bySignal[signal], "$wire $signal") }
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun defaultEdgeSendsNoResourceAndNoScopeOnEitherWire() = runTest {
        // The before-state, and the compatibility promise: an edge built without the new
        // parameters puts neither field on the wire.
        val absent = SentEnvelope(resource = null, scope = null)
        val sent = OtlpWireFormat.entries.associateWith { wire ->
            sentEnvelopes(wire) { client ->
                OtlpHttpEdge(client, "https://collector.example:4318", InMemoryDurableStore(), wire = wire)
            }
        }
        assertAll(
            *sent.flatMap { (wire, bySignal) ->
                listOf("traces", "logs", "metrics").map { signal ->
                    { assertEquals(absent, bySignal[signal], "$wire $signal") }
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun resourceIsCopiedAtConstruction() = runTest {
        val resource = mutableMapOf("service.name" to "svc")
        val sent = sentEnvelopes(OtlpWireFormat.JSON) { client ->
            OtlpHttpEdge(client, "https://collector.example:4318", InMemoryDurableStore(), resource = resource)
                .also { resource["service.name"] = "changed-after" }
        }
        assertEquals(mapOf("service.name" to "svc"), sent.getValue("traces").resource)
    }
}
