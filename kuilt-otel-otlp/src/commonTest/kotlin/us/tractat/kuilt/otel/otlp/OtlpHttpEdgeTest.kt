package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
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
}
