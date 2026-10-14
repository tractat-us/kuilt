package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IndeterminateWriteAuditTest {
    @Test
    fun committedSentSetSurvivesFailureAfterSuccessfulPost() = runTest {
        for (cancel in listOf(false, true)) {
            var posts = 0
            val client = HttpClient(MockEngine {
                posts++
                respond("{}", HttpStatusCode.OK)
            })
            try {
                val backing = InMemoryDurableStore()
                var injections = 0
                val store = object : DurableStore by backing {
                    override suspend fun write(key: StoreKey, bytes: ByteArray) {
                        backing.write(key, bytes)
                        injections++
                        if (cancel) throw CancellationException("committed before cancellation")
                        throw Exception("committed before failure")
                    }
                }
                val span = SpanRecord(
                    ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { 1 }), null,
                    "audit", SpanKind.INTERNAL, 1L, 2L,
                )
                val edge = OtlpHttpEdge(client, "https://collector.example", store)
                assertFailsWith<Exception> { edge.send(setOf(span)) }
                val recovered = OtlpHttpEdge(client, "https://collector.example", backing)
                val sent = recovered.digest().spanIds
                assertAll(
                    { assertEquals(1, injections, "sent-set commit-before-failure injection must fire") },
                    { assertEquals(1, posts, "HTTP delivery must precede sent-set persistence") },
                    { assertEquals(setOf(span.spanId), sent, "a failed send call may already be in the digest") },
                )
            } finally {
                client.close()
            }
        }
    }
}
