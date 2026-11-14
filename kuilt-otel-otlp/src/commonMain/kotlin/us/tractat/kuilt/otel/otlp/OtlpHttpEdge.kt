@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class, ExperimentalVersionOverloading::class)

package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.kotlincrypto.hash.sha2.SHA256
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
 * leaves the digest untouched and the next drain retries. A lost sent-set can cause
 * re-POSTs, so delivery deduplication relies on the backend applying records
 * idempotently by id. This sent-set does not repair distinct stamped span values
 * created by retrying an export after an indeterminate local write.
 *
 * The metric map is read from [store] by the digest and again before each write, and
 * decoded only when its bytes changed since this edge last saw them. With one edge per
 * store and endpoint that is never, so a drain that sends pays two reads, one encode and
 * one write of the map, where it used to pay two decodes on top.
 *
 * The id-set is capped at [maxSentIds] (drop-oldest) so a long-lived producer's
 * sent-set cannot grow without bound; choose a cap that exceeds the device's realistic
 * offline window.
 *
 * @param client caller-owned Ktor [HttpClient] — it owns the engine, timeouts, TLS,
 *   and any auth headers. kuilt does not create or close it, and this module ships no
 *   engine: depend on one (`ktor-client-okhttp`, `ktor-client-java`, `ktor-client-darwin`, …)
 *   and pass it, as in `HttpClient(OkHttp)`.
 * @param endpoint collector base URL, e.g. `https://collector:4318`. Any trailing `/` is
 *   trimmed, so `.../:4318/` and `.../:4318` are one collector. The sent-set [StoreKey]
 *   identifies the endpoint by a 128-bit SHA-256 tag rather than by the URL, so two
 *   collectors never share a sent-set, the key is a fixed width whatever the URL's
 *   length, and a URL carrying credentials never reaches a filename. Length is
 *   unconstrained here.
 * @param store durable persistence for the per-endpoint sent-set. Give each producer its
 *   own store, or its own endpoint: every drain prunes the metric record to the draining
 *   producer's live series, so two producers sharing one store and endpoint would each
 *   prune the other's, then resend their whole metric set on every drain.
 * @param maxSentIds cap on the span/log sent-set size (drop-oldest). The metric
 *   sent-versions map takes no cap: every drain prunes it to the series the producer
 *   still holds ([retainMetrics]), so it is bounded by the producer's `maxMetrics`.
 * @param wire OTLP wire encoding to emit. Defaults to [OtlpWireFormat.JSON].
 * @param resource this producer's resource attributes, sent as the `resource` of every
 *   request on both wires — `service.name` and whatever else tells one producer from
 *   another, so a collector or backend files its records under their own service rather
 *   than one unknown one. Fixed for the edge's life, as a producer's resource is. Empty,
 *   the default, sends no `resource` at all.
 * @param scopeName the instrumentation scope's name, sent as the `scope` of every request
 *   on both wires: the library that recorded the telemetry.
 * @param scopeVersion the instrumentation scope's version. When both [scopeName] and
 *   [scopeVersion] are empty, the default, no `scope` is sent; OTLP reads an empty name as
 *   an unknown scope either way.
 */
public class OtlpHttpEdge(
    private val client: HttpClient,
    endpoint: String,
    private val store: DurableStore,
    private val maxSentIds: Int = DEFAULT_MAX_SENT_IDS,
    private val wire: OtlpWireFormat = OtlpWireFormat.JSON,
    @IntroducedAt("0.7.5") resource: Map<String, String> = emptyMap(),
    @IntroducedAt("0.7.5") scopeName: String = "",
    @IntroducedAt("0.7.5") scopeVersion: String = "",
) : OtlpEdge {

    private val base: String = endpoint.trimEnd('/')

    // Copied, so a caller mutating the map it passed cannot change what later requests say.
    private val envelope = OtlpEnvelope(resource.toMap(), scopeName, scopeVersion)
    private val json = Json { encodeDefaults = false }

    // Per-endpoint sent-set keys, tagged by a 128-bit SHA-256 of the *trimmed* base —
    // the same URL the POSTs use. Three properties ride on that one expression:
    //
    // - `".../:4318/"` and `".../:4318"` are one collector, so they hash alike and share
    //   one sent-set over a shared store rather than splitting into two (#1053).
    // - Two *different* collectors never share one. This replaced `base.hashCode()` in
    //   #2513: a 32-bit non-cryptographic hash has trivially constructible collisions,
    //   and a collision here is silent under-delivery — the losing endpoint skips records
    //   it never sent. 128 bits of SHA-256 puts a collision out of reach.
    // - The key's length is **bounded and independent of the endpoint**. That is not
    //   cosmetic. A file-backed store percent-encodes the key onto a filename, so an
    //   endpoint-shaped key is endpoint-length, and past the filesystem's limit the
    //   failure is not loud: `File.exists()` answers *false* for a too-long name rather
    //   than throwing, so the read degrades to an empty digest while the write throws.
    //   Every drain would then re-POST the whole buffer forever. Worse, the three prefixes
    //   differ in length, so there is a band where the span key fits and the metric key
    //   does not — spans persist, metrics do not, and every drain is a
    //   `DrainResult.Partial` whose metric failure reads like a routine offline one. A
    //   fixed-width tag removes the whole class by construction.
    //
    // The tag is also why a credential-bearing endpoint (`https://user:pass@host`, or a
    // token in the path) never reaches a filename, where directory listings, backup
    // manifests and support bundles would carry it.
    private val spanKey = StoreKey("otlp.sent.spans@${endpointTag(base)}")
    private val logKey = StoreKey("otlp.sent.logs@${endpointTag(base)}")
    private val metricKey = StoreKey("otlp.sent.metrics@${endpointTag(base)}")

    // ── Digests (producer-local, read from the persisted sent-set) ─────────────

    override suspend fun digest(): SpanDigest =
        SpanDigest(readIdSet(spanKey).mapTo(mutableSetOf()) { it.hexToByteString() })

    override suspend fun logDigest(): LogDigest =
        LogDigest(readIdSet(logKey).mapTo(mutableSetOf()) { it.hexToByteString() })

    override suspend fun metricDigest(): MetricDigest =
        MetricDigest(sentVersionsLock.withLock { refreshSentVersions() }.versions)

    // ── Sends (POST, then fold into the sent-set on success) ───────────────────

    override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) {
        post(
            "/v1/traces",
            when (wire) {
                OtlpWireFormat.JSON ->
                    json.encodeToString(TracesRequest.serializer(), tracesRequestOf(spans, links, envelope))
                OtlpWireFormat.PROTOBUF ->
                    protobuf.encodeToByteArray(ProtoTracesRequest.serializer(), tracesProtoOf(spans, links, envelope))
            },
        )
        recordIds(spanKey, spans.map { it.spanId.toHex() })
    }

    override suspend fun sendLogs(logs: Set<LogRecord>) {
        post(
            "/v1/logs",
            when (wire) {
                OtlpWireFormat.JSON ->
                    json.encodeToString(LogsRequest.serializer(), logsRequestOf(logs, envelope))
                OtlpWireFormat.PROTOBUF ->
                    protobuf.encodeToByteArray(ProtoLogsRequest.serializer(), logsProtoOf(logs, envelope))
            },
        )
        recordIds(logKey, logs.map { it.recordId.toHex() })
    }

    override suspend fun sendMetrics(points: Set<MetricPoint>) {
        post(
            "/v1/metrics",
            when (wire) {
                OtlpWireFormat.JSON ->
                    json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(points, envelope))
                OtlpWireFormat.PROTOBUF ->
                    protobuf.encodeToByteArray(ProtoMetricsRequest.serializer(), metricsProtoOf(points, envelope))
            },
        )
        val updates = points.associate { it.key to it.valueHash() }
        sentVersionsLock.withLock { commitSentVersions(refreshSentVersions().versions + updates) }
    }

    override suspend fun retainMetrics(live: Set<MetricKey>) {
        sentVersionsLock.withLock {
            // Decide from the cache, so a drain with nothing to prune reads nothing more; re-read
            // only once there is a write to build. A key another edge added since the cache was
            // loaded is pruned by the next drain instead.
            if (sentVersions?.versions?.keys?.all { it in live } == true) return
            val current = refreshSentVersions().versions
            if (current.keys.all { it in live }) return
            commitSentVersions(current.filterKeys { it in live })
        }
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

    // ── Metric sent-versions: one decoded copy, checked against disk (#3292) ───
    //
    // At 10k series the map is about 900 KiB of CBOR, and a drain used to read and decode it twice
    // (once for the digest, once more to fold the sent points in) and then encode it again. Now it
    // is read by the digest and again just before each write, and decoded only when the bytes
    // differ from the ones the cached map came from, which with one edge is never.
    //
    // Why re-read before every write rather than trust the cache: this instance is not the only
    // writer of its key. Two edges on one store and one endpoint share one sent-set by design
    // (#1053; a trailing slash is the same collector), so another instance's write can land at any
    // point, including inside this one's POST. A write built on the digest-time copy would put that
    // instance's entries back to their old values: silent under-delivery for a gauge that returns to
    // an earlier value, since the record says it was sent while the collector holds the newer one.
    // The read before the write narrows the window back to read-then-write, which is what it was
    // before this cache. It does not close it: a write that lands between this read and this write
    // is still lost. The byte comparison is what keeps each read from costing a decode.
    //
    // The cache is replaced after the store's write returns, and `sendMetrics` reaches that write
    // only after a 2xx, so a failed POST or a refused write leaves it as it was. It is not a promise
    // that the cache equals disk: another instance can write after it, and a store can land a write
    // and then throw. Both are why every write starts from a fresh read. The lock makes each
    // read-fold-write one step on this edge, so two concurrent sends cannot each fold onto the same
    // base and lose the other's points.

    /** The decoded sent-versions map, beside the exact bytes on disk it came from (null: no key). */
    private class SentVersions(val bytes: ByteArray?, val versions: Map<MetricKey, Long>)

    private val sentVersionsLock = Mutex()

    // Guarded by sentVersionsLock.
    private var sentVersions: SentVersions? = null

    /** Reads the key once; decodes only when its bytes are not the ones the cache came from. */
    private suspend fun refreshSentVersions(): SentVersions {
        val bytes = store.read(metricKey)
        val cached = sentVersions
        if (cached != null && cached.bytes.contentEquals(bytes)) return cached
        return SentVersions(bytes, decodeVersions(bytes)).also { sentVersions = it }
    }

    private suspend fun commitSentVersions(versions: Map<MetricKey, Long>) {
        val bytes = cbor.encodeToByteArray(versionsSerializer, versions)
        store.write(metricKey, bytes)
        sentVersions = SentVersions(bytes, versions)
    }

    private fun decodeVersions(bytes: ByteArray?): Map<MetricKey, Long> {
        if (bytes == null) return emptyMap()
        return runCatchingCancellable {
            cbor.decodeFromByteArray(versionsSerializer, bytes)
        }.getOrDefault(emptyMap())
    }

    public companion object {
        /** Default cap on the span/log producer-local sent-set (drop-oldest). */
        public const val DEFAULT_MAX_SENT_IDS: Int = 50_000

        /**
         * Bytes of SHA-256 kept for the endpoint tag. 128 bits makes a collision
         * unreachable for a keyspace of collector URLs, and fixes the tag at 32 hex
         * characters — all of them inside the store's `[a-z0-9-]` safe set, so the
         * encoded filename is the same length whatever the endpoint is.
         */
        private const val ENDPOINT_TAG_BYTES = 16

        /** The per-endpoint sent-set tag: 128 bits of SHA-256 over [base], lowercase hex. */
        private fun endpointTag(base: String): String =
            ByteString(*SHA256().digest(base.encodeToByteArray()).copyOf(ENDPOINT_TAG_BYTES)).toHex()

        private val protobuf = ProtoBuf
        private val cbor = Cbor { alwaysUseByteString = true }
        private val idSetSerializer = SetSerializer(String.serializer())
        private val versionsSerializer = MapSerializer(MetricKey.serializer(), Long.serializer())
    }
}
