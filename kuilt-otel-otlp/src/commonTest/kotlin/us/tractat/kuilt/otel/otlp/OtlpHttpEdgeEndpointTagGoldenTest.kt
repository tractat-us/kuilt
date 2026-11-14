package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.otel.WarpOtlpBridge
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Golden store keys for [OtlpHttpEdge]'s per-endpoint sent-sets.
 *
 * The keys are persisted state. A change to how the endpoint tag is derived, even one that keeps
 * every property [OtlpHttpEdgeSentSetKeyTest] checks, orphans every device's sent-set on upgrade:
 * the next drain reads an empty digest under the new key and re-POSTs the whole buffer. So this
 * pins the exact bytes. A deliberate change updates these values and says so in its PR.
 *
 * Each expected tag was computed outside Kotlin, as the first 16 bytes of SHA-256 over the UTF-8
 * of the endpoint with its trailing slashes trimmed, in lowercase hex:
 * `hashlib.sha256(e.rstrip("/").encode("utf-8")).hexdigest()[:32]`.
 */
class OtlpHttpEdgeEndpointTagGoldenTest {

    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    /** Records the name of every key written, so the test reads keys the edge really used. */
    private class KeyRecordingStore(private val delegate: DurableStore = InMemoryDurableStore()) : DurableStore {
        private val names = mutableSetOf<String>()

        val written: Set<String> get() = names.toSet()

        override suspend fun read(key: StoreKey): ByteArray? = delegate.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            delegate.write(key, bytes)
            names += key.name
        }

        override suspend fun delete(key: StoreKey) = delegate.delete(key)
    }

    /** Drain one span, one log and one metric to [endpoint], and return the sent-set keys written. */
    private suspend fun sentSetKeysFor(endpoint: String): Pair<DrainResult, Set<String>> {
        val telemetry = WarpTelemetry(ReplicaId("golden"), InMemoryDurableStore()).apply {
            recover()
            spans.export(SpanRecord(ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { 1 }), null, "op", SpanKind.INTERNAL, 1L, 2L))
            logs.export(LogRecord(recordId = ByteString(ByteArray(8) { 1 }), body = "hi"))
            metrics.incrementSum(MetricKey("req", MetricKind.SUM), by = 1L)
        }
        val store = KeyRecordingStore()
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val result = WarpOtlpBridge(telemetry, clock).drain(OtlpHttpEdge(client, endpoint, store))
        return result to store.written.filterTo(mutableSetOf()) { it.startsWith("otlp.sent.") }
    }

    private fun keysTagged(tag: String) =
        setOf("otlp.sent.spans@$tag", "otlp.sent.logs@$tag", "otlp.sent.metrics@$tag")

    private suspend fun assertGolden(endpoint: String, tag: String) {
        val (result, keys) = sentSetKeysFor(endpoint)
        assertAll(
            // Precondition: every leg was sent, so each sent-set key had a reason to be written.
            { assertEquals(DrainResult.Success(spansSent = 1, logsSent = 1, metricPointsSent = 1), result) },
            { assertEquals(keysTagged(tag), keys, "the sent-set keys for $endpoint moved") },
        )
    }

    @Test
    fun aPlainLocalCollector() = runTest {
        assertGolden("http://localhost:4318", "e69ee0ee3b1eb9d47ea26d6181cd5a07")
    }

    @Test
    fun aTrailingSlashIsTrimmedBeforeHashing() = runTest {
        assertGolden("https://collector.example.com:4318/", "75a77f9c45c56344fc6b18201684d108")
    }

    @Test
    fun aCredentialBearingEndpoint() = runTest {
        assertGolden("https://user:secret@otel.example/v1/otlp?token=abc", "903cdff9c632114a3b4510def81a09b7")
    }

    @Test
    fun aNonAsciiEndpointIsHashedAsUtf8() = runTest {
        assertGolden("https://otel.example/ü", "1fcc7d3a1c5155c91dd1c1be6e20aa0a")
    }
}
