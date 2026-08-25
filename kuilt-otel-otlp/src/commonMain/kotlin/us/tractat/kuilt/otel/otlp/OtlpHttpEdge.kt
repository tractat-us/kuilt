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
import kotlinx.serialization.protobuf.ProtoBuf
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.LogDigest
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricDigest
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.OtlpEdge
import us.tractat.kuilt.otel.SpanDigest
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey

/** Which OTLP/HTTP wire encoding an [OtlpHttpEdge] emits. */
public enum class OtlpWireFormat {
    /** OTLP/JSON (`application/json`) — the default; mature on every target. */
    JSON,

    /** OTLP/protobuf (`application/x-protobuf`) — the canonical, more compact OTLP wire. */
    PROTOBUF,
}

/**
 * A Ktor OTLP/HTTP [OtlpEdge]. POSTs each signal to `/v1/{traces,logs,metrics}` and
 * reconciles by a **producer-local** sent-set persisted in [store] — because OTLP/HTTP
 * is write-only, there is no collector read-back.
 *
 * The wire encoding is selectable via [wire]: **OTLP/JSON** (`application/json`, the
 * default) or **OTLP/protobuf** (`application/x-protobuf`, the canonical, more compact
 * OTLP wire many collectors default to). The digest/bridge reconciliation is identical
 * for both — only the request body bytes and `Content-Type` differ.
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
 * @param endpoint collector base URL, e.g. `https://collector:4318`. It goes into the
 *   sent-set [StoreKey] verbatim (minus any trailing `/`), so two collectors can never
 *   share one sent-set. On a file-backed [store] that name is percent-encoded against a
 *   filename limit of ~255 bytes, and every byte outside `[a-z0-9-]` costs three rather
 *   than one — so the ceiling is on the *encoded* length, not the URL's. Realistic
 *   collector URLs clear it with room to spare: a 104-character internal FQDN with a
 *   path prefix encodes to 152 bytes. An all-lowercase URL first fails around 190
 *   characters; one whose host or path is uppercase- or punctuation-heavy can fail from
 *   about 80. A caller who reaches it gets a loud write error out of the sent-set write,
 *   never silent data loss.
 * @param store durable persistence for the per-endpoint sent-set.
 * @param maxSentIds cap on the span/log sent-set size (drop-oldest). Metrics are
 *   naturally bounded by series count.
 * @param wire OTLP wire encoding to emit. Defaults to [OtlpWireFormat.JSON].
 */
public class OtlpHttpEdge(
    private val client: HttpClient,
    endpoint: String,
    private val store: DurableStore,
    private val maxSentIds: Int = DEFAULT_MAX_SENT_IDS,
    private val wire: OtlpWireFormat = OtlpWireFormat.JSON,
) : OtlpEdge {

    private val base: String = endpoint.trimEnd('/')
    private val json = Json { encodeDefaults = false }

    // Per-endpoint sent-set keys. Two properties, and the key is the *trimmed* base —
    // the same URL the POSTs use — because both turn on it:
    //
    // - `".../:4318/"` and `".../:4318"` are one collector, so they share one sent-set
    //   over a shared store rather than splitting into two (#1053).
    // - Two *different* collectors never share one. The base went in verbatim in #2513,
    //   replacing `base.hashCode()`: a 32-bit non-cryptographic hash has trivially
    //   constructible collisions, and a collision here is silent under-delivery — the
    //   losing endpoint skips records it never sent. `StoreKey` names are encoded
    //   losslessly onto filenames (#2506/#2511), so the URL's `:` and `/` no longer
    //   need hashing away; see the `endpoint` KDoc for the one cost that swaps in.
    private val spanKey = StoreKey("otlp.sent.spans@$base")
    private val logKey = StoreKey("otlp.sent.logs@$base")
    private val metricKey = StoreKey("otlp.sent.metrics@$base")

    // ── Digests (producer-local, read from the persisted sent-set) ─────────────

    override suspend fun digest(): SpanDigest =
        SpanDigest(readIdSet(spanKey).mapTo(mutableSetOf()) { it.hexToByteString() })

    override suspend fun logDigest(): LogDigest =
        LogDigest(readIdSet(logKey).mapTo(mutableSetOf()) { it.hexToByteString() })

    override suspend fun metricDigest(): MetricDigest = MetricDigest(readVersions())

    // ── Sends (POST, then fold into the sent-set on success) ───────────────────

    override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) {
        post(
            "/v1/traces",
            when (wire) {
                OtlpWireFormat.JSON ->
                    json.encodeToString(TracesRequest.serializer(), tracesRequestOf(spans, links))
                OtlpWireFormat.PROTOBUF ->
                    protobuf.encodeToByteArray(ProtoTracesRequest.serializer(), tracesProtoOf(spans, links))
            },
        )
        recordIds(spanKey, spans.map { it.spanId.toHex() })
    }

    override suspend fun sendLogs(logs: Set<LogRecord>) {
        post(
            "/v1/logs",
            when (wire) {
                OtlpWireFormat.JSON ->
                    json.encodeToString(LogsRequest.serializer(), logsRequestOf(logs))
                OtlpWireFormat.PROTOBUF ->
                    protobuf.encodeToByteArray(ProtoLogsRequest.serializer(), logsProtoOf(logs))
            },
        )
        recordIds(logKey, logs.map { it.recordId.toHex() })
    }

    override suspend fun sendMetrics(points: Set<MetricPoint>) {
        post(
            "/v1/metrics",
            when (wire) {
                OtlpWireFormat.JSON ->
                    json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(points))
                OtlpWireFormat.PROTOBUF ->
                    protobuf.encodeToByteArray(ProtoMetricsRequest.serializer(), metricsProtoOf(points))
            },
        )
        recordVersions(points.associate { it.key to it.valueHash() })
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    // body is a JSON String or a protobuf ByteArray; Ktor's default transformers set
    // the entity from either. contentType is keyed to the selected wire.
    private suspend fun post(path: String, body: Any) {
        val response: HttpResponse = client.post(base + path) {
            contentType(
                when (wire) {
                    OtlpWireFormat.JSON -> ContentType.Application.Json
                    OtlpWireFormat.PROTOBUF -> ContentType("application", "x-protobuf")
                },
            )
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

        private val protobuf = ProtoBuf
        private val cbor = Cbor { alwaysUseByteString = true }
        private val idSetSerializer = SetSerializer(String.serializer())
        private val versionsSerializer = MapSerializer(MetricKey.serializer(), Long.serializer())
    }
}
