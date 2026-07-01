@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.DurableStore
import us.tractat.kuilt.otel.LogDigest
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricDigest
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.OtlpEdge
import us.tractat.kuilt.otel.SpanDigest
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.otel.StoreKey

/**
 * A Ktor OTLP/HTTP **JSON** [OtlpEdge]. POSTs each signal to `/v1/{traces,logs,metrics}`
 * as `application/json` and reconciles by a **producer-local** sent-set persisted in
 * [store] — because OTLP/HTTP is write-only, there is no collector read-back.
 *
 * The digest is what *this* producer has already successfully delivered to *this*
 * endpoint: span and log ids in a bounded id-set, metric series as `MetricKey → value
 * hash`. The set is folded forward only **after** a 2xx response, so a failed POST
 * leaves the digest untouched and the next drain retries. The collector deduplicates
 * re-sent spans/logs by id, so a lost sent-set costs bandwidth, never correctness.
 *
 * The id-set is capped at [maxSentIds] (drop-oldest) so a long-lived producer's
 * sent-set cannot grow without bound; choose a cap that exceeds the device's realistic
 * offline window.
 *
 * @param client caller-owned Ktor [HttpClient] — it owns the engine, timeouts, TLS,
 *   and any auth headers. kuilt does not create or close it.
 * @param endpoint collector base URL, e.g. `https://collector:4318`.
 * @param store durable persistence for the per-endpoint sent-set.
 * @param maxSentIds cap on the span/log sent-set size (drop-oldest). Metrics are
 *   naturally bounded by series count.
 */
public class OtlpHttpEdge(
    private val client: HttpClient,
    endpoint: String,
    private val store: DurableStore,
    private val maxSentIds: Int = DEFAULT_MAX_SENT_IDS,
) : OtlpEdge {

    private val base: String = endpoint.trimEnd('/')
    private val json = Json { encodeDefaults = false }

    // Per-endpoint sent-set keys.
    private val spanKey = StoreKey("otlp.sent.spans@${endpoint.hashCode()}")
    private val logKey = StoreKey("otlp.sent.logs@${endpoint.hashCode()}")
    private val metricKey = StoreKey("otlp.sent.metrics@${endpoint.hashCode()}")

    // ── Digests (producer-local, read from the persisted sent-set) ─────────────

    override suspend fun digest(): SpanDigest =
        SpanDigest(readIdSet(spanKey).mapTo(mutableSetOf()) { it.hexToByteString() })

    override suspend fun logDigest(): LogDigest =
        LogDigest(readIdSet(logKey).mapTo(mutableSetOf()) { it.hexToByteString() })

    override suspend fun metricDigest(): MetricDigest = MetricDigest(readVersions())

    // ── Sends (POST, then fold into the sent-set on success) ───────────────────

    override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) {
        postJson("/v1/traces", json.encodeToString(TracesRequest.serializer(), tracesRequestOf(spans, links)))
        recordIds(spanKey, spans.map { it.spanId.toHex() })
    }

    override suspend fun sendLogs(logs: Set<LogRecord>) {
        postJson("/v1/logs", json.encodeToString(LogsRequest.serializer(), logsRequestOf(logs)))
        recordIds(logKey, logs.map { it.recordId.toHex() })
    }

    override suspend fun sendMetrics(points: Set<MetricPoint>) {
        postJson("/v1/metrics", json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(points)))
        recordVersions(points.associate { it.key to it.valueHash() })
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private suspend fun postJson(path: String, body: String) {
        val response: HttpResponse = client.post(base + path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            error("OTLP POST $path failed: ${response.status}")
        }
    }

    // ── Producer-local sent-set persistence (CBOR) ─────────────────────────────

    private suspend fun readIdSet(key: StoreKey): Set<String> {
        val bytes = store.read(key) ?: return emptySet()
        return runCatchingCancellable {
            cbor.decodeFromByteArray(idSetSerializer, bytes)
        }.getOrDefault(emptySet())
    }

    private suspend fun recordIds(key: StoreKey, hexIds: List<String>) {
        // Preserve insertion order for drop-oldest; new ids appended after existing.
        val merged = LinkedHashSet(readIdSet(key))
        merged.addAll(hexIds)
        val capped: Set<String> =
            if (merged.size <= maxSentIds) merged
            else merged.toList().takeLast(maxSentIds).toCollection(LinkedHashSet())
        store.write(key, cbor.encodeToByteArray(idSetSerializer, capped))
    }

    private suspend fun readVersions(): Map<MetricKey, Long> {
        val bytes = store.read(metricKey) ?: return emptyMap()
        return runCatchingCancellable {
            cbor.decodeFromByteArray(versionsSerializer, bytes)
        }.getOrDefault(emptyMap())
    }

    private suspend fun recordVersions(updates: Map<MetricKey, Long>) {
        val merged = readVersions() + updates
        store.write(metricKey, cbor.encodeToByteArray(versionsSerializer, merged))
    }

    public companion object {
        /** Default cap on the span/log producer-local sent-set (drop-oldest). */
        public const val DEFAULT_MAX_SENT_IDS: Int = 50_000

        private val cbor = Cbor { alwaysUseByteString = true }
        private val idSetSerializer = SetSerializer(String.serializer())
        private val versionsSerializer = MapSerializer(MetricKey.serializer(), Long.serializer())
    }
}
