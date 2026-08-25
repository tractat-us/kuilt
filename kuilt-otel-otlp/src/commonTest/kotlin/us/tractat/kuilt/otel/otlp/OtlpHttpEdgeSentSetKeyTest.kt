package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.otel.WarpOtlpBridge
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The sent-set key must separate two collectors that are genuinely different (#2513),
 * and must keep joining two spellings of one collector (#1053).
 *
 * The first property was un-pinned while the key was `base.hashCode()`: a 32-bit
 * non-cryptographic hash has trivially constructible collisions, so two endpoints could
 * silently share one producer-local sent-set. That is *under-delivery* — the losing
 * endpoint's collector never receives records the winning endpoint recorded as sent —
 * which is why these tests drain through [WarpOtlpBridge] rather than compare digests:
 * a digest that lies is only a defect because a drain believes it.
 */
class OtlpHttpEdgeSentSetKeyTest {

    private fun tId(b: Byte) = ByteString(ByteArray(16) { b })
    private fun sId(b: Byte) = ByteString(ByteArray(8) { b })
    private fun span(b: Byte) = SpanRecord(tId(b), sId(b), null, "op", SpanKind.INTERNAL, 1L, 2L)

    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    /** One span, exported once, ready to be drained at any number of edges. */
    private suspend fun telemetryHoldingOneSpan(): WarpTelemetry =
        WarpTelemetry(ReplicaId("device-2513"), InMemoryDurableStore())
            .also { it.recover(); it.spans.export(span(1)) }

    private class CountingCollector {
        var posts: Int = 0
            private set

        val client: HttpClient = HttpClient(MockEngine { posts++; respond("{}", HttpStatusCode.OK) })
    }

    /**
     * `"Aa"` and `"BB"` share a `String.hashCode()`, and so do `X + "Aa"` and `X + "BB"`
     * for any prefix `X` — so these two URLs are two different collectors that a 32-bit
     * hash cannot tell apart. The collision is asserted as a *precondition*: if the
     * fixture ever stops colliding, this test proves nothing and must say so rather than
     * pass quietly.
     */
    @Test
    fun collidingEndpointUrlsDoNotShareASentSet() = runTest {
        val endpointA = "https://c.example:4318/Aa"
        val endpointB = "https://c.example:4318/BB"
        assertEquals(
            endpointA.hashCode(),
            endpointB.hashCode(),
            "fixture precondition: these two endpoints must be a genuine String.hashCode() collision",
        )

        // One store, two edges — the shape a process draining to two collectors has.
        val shared = InMemoryDurableStore()
        val collectorA = CountingCollector()
        val collectorB = CountingCollector()
        val edgeA = OtlpHttpEdge(collectorA.client, endpointA, shared)
        val edgeB = OtlpHttpEdge(collectorB.client, endpointB, shared)

        val bridge = WarpOtlpBridge(telemetryHoldingOneSpan(), clock)
        val toA = bridge.drain(edgeA)
        val toB = bridge.drain(edgeB)

        assertAll(
            { assertEquals(DrainResult.Success(spansSent = 1), toA, "the span must reach collector A") },
            { assertEquals(1, collectorA.posts, "collector A must be POSTed to") },
            {
                assertEquals(
                    DrainResult.Success(spansSent = 1),
                    toB,
                    "collector B has been sent nothing, so the span must still be owed to it",
                )
            },
            { assertEquals(1, collectorB.posts, "collector B must be POSTed to, not skipped on A's sent-set") },
        )
    }

    /**
     * The other half of the same property: a trailing slash is not a different collector
     * (#1053). The key derives from the *trimmed* base — the same string the POSTs use —
     * so two spellings of one endpoint keep sharing one sent-set and the second drain
     * sends nothing.
     */
    @Test
    fun trailingSlashIsTheSameEndpointAndSharesOneSentSet() = runTest {
        val shared = InMemoryDurableStore()
        val collector = CountingCollector()
        val edge = OtlpHttpEdge(collector.client, "https://c.example:4318", shared)
        val edgeWithSlash = OtlpHttpEdge(collector.client, "https://c.example:4318/", shared)

        val bridge = WarpOtlpBridge(telemetryHoldingOneSpan(), clock)
        val first = bridge.drain(edge)
        val second = bridge.drain(edgeWithSlash)

        assertAll(
            { assertEquals(DrainResult.Success(spansSent = 1), first, "the first drain sends the span") },
            {
                assertEquals(
                    DrainResult.Success(spansSent = 0),
                    second,
                    "`.../:4318/` is the same collector as `.../:4318`, which already has the span",
                )
            },
            { assertEquals(1, collector.posts, "one collector, one POST — a trailing slash must not resend") },
        )
    }

    /** A sanity arm for the fixture the two tests share: the span really is exported. */
    @Test
    fun oneEdgeDrainsTheExportedSpan() = runTest {
        val collector = CountingCollector()
        val edge = OtlpHttpEdge(collector.client, "https://c.example:4318", InMemoryDurableStore())
        val result = WarpOtlpBridge(telemetryHoldingOneSpan(), clock).drain(edge)
        assertTrue(result is DrainResult.Success && result.spansSent == 1, "drain result was $result")
    }
}
