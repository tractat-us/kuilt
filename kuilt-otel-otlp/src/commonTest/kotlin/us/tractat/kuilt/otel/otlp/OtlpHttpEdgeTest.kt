package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
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
        runCatching { edge.send(setOf(span(1))) } // non-2xx throws
        assertTrue(edge.digest().spanIds.isEmpty(), "a 5xx must leave the sent-set untouched so drain retries")
    }
}
