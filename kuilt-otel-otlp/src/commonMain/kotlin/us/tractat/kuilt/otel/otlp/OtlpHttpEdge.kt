@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class, ExperimentalVersionOverloading::class)

package us.tractat.kuilt.otel.otlp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.toHexString
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
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.OtlpEdge
import us.tractat.kuilt.otel.SpanDigest
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.otlp.OtlpHttpEdge")

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
 * ## One signal, several requests
 *
 * A signal whose request would be bigger than [maxRequestBytes] is split into several,
 * each under the cap, sent one after another. Records that share an id, such as two stamped
 * copies of one span, are grouped and always ride in one request, placed where that id first
 * appears in the batch; otherwise the batch's order holds. A span travels with the links it
 * owns, and every request carries the resource and scope.
 * A request's records count as delivered only once that request is acknowledged, and
 * the sent-set is written **once per signal**, after the last request, from a fresh
 * read. So a refused request, a transport failure or a cancellation part-way still
 * records the requests that were acknowledged, and the next drain sends only the rest.
 * The send then throws, so the bridge reports that signal failed with a count of 0,
 * although the collector holds part of it (see [OtlpEdge]). A process killed before
 * that write resends the signal's whole batch on the next drain, the bound an unsplit
 * request already had.
 *
 * Too big is decided by this edge's own measurement and by a `413` answer, never by a
 * `400`: a collector may answer an oversized body with either, and answers a malformed
 * one with `400` too. A `413` on a request of several records halves it and sends the
 * halves; the smaller size is not remembered. A record bigger than the cap on its own
 * is sent alone, after every other request is acknowledged, because the cap is a guess
 * and the collector may still take it. A `413` on that record records it as delivered,
 * with a WARN naming it and its size: the same bytes would be refused again, and keeping
 * it would fail the signal on every drain until the buffer evicts it. A `413` on a lone
 * record **inside** the cap fails the signal instead: something in front of the collector
 * limits bodies below [maxRequestBytes], and folding would report records sent that the
 * collector never took. Unlike other refusals it does not stop the sends: the same record is
 * refused on every drain, so the rest are still offered and recorded, and the signal fails
 * after them. Lower [maxRequestBytes] to that limit.
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
 * @param maxRequestBytes the largest body, in bytes before any compression the client
 *   applies, that one request may carry; a batch over it is split. Defaults to
 *   [DEFAULT_MAX_REQUEST_BYTES], 1 MiB, the limit a reverse proxy commonly enforces in
 *   front of a collector, which itself defaults to 20 MiB. It must hold one minimal record
 *   inside this edge's resource and scope, or construction throws
 *   [IllegalArgumentException]: an envelope that fills the cap would otherwise send every
 *   record alone.
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
    @IntroducedAt("0.7.6") private val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
) : OtlpEdge {

    private val base: String = endpoint.trimEnd('/')

    // Copied, so a caller mutating the map it passed cannot change what later requests say.
    private val envelope = OtlpEnvelope(resource.toMap(), scopeName, scopeVersion)
    private val json = Json { encodeDefaults = false }

    init {
        // A minimal record of each signal, inside this edge's own envelope.
        val minimal = listOf(
            encodeTraces(listOf(MINIMAL_SPAN), emptyList()),
            encodeLogs(listOf(MINIMAL_LOG)),
            encodeMetrics(listOf(MINIMAL_METRIC)),
        ).maxOf { it.bytes }
        require(maxRequestBytes >= minimal) {
            "maxRequestBytes = $maxRequestBytes cannot hold one minimal record under this edge's resource and " +
                "scope, which needs $minimal bytes"
        }
    }

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
    //   failure is not loud: both the read and the write throw (`ENAMETOOLONG`), so every
    //   digest for that signal fails, and `WarpOtlpBridge.drain` reports it as that
    //   signal's failure on every call — which reads like a routine offline collector.
    //   Worse, the three prefixes differ in length, so there is a band where the span key
    //   fits and the metric key does not — spans persist, metrics never do, and every
    //   drain is a `DrainResult.Partial` whose metric failure reads like a routine offline
    //   one. A fixed-width tag removes the whole class by construction.
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
        val linksByFrom = links.groupBy { it.fromSpanId }
        deliver(
            path = "/v1/traces",
            items = spans.toList(),
            idOf = { it.spanId },
            // A span's links travel with it. Two stamped copies of one span id can share a chunk,
            // so the ids are made distinct first, or each copy would carry the links twice.
            encode = { chunk -> encodeTraces(chunk, chunk.map { it.spanId }.distinct().flatMap { linksByFrom[it].orEmpty() }) },
            describe = { "span ${it.spanId.toHexString()}" },
        ) { delivered -> recordIds(spanKey, delivered.map { it.spanId.toHexString() }) }
    }

    override suspend fun sendLogs(logs: Set<LogRecord>) {
        deliver(
            path = "/v1/logs",
            items = logs.toList(),
            idOf = { it.recordId },
            encode = ::encodeLogs,
            describe = { "log record ${it.recordId.toHexString()}" },
        ) { delivered -> recordIds(logKey, delivered.map { it.recordId.toHexString() }) }
    }

    override suspend fun sendMetrics(points: Set<MetricPoint>) {
        deliver(
            path = "/v1/metrics",
            items = points.toList(),
            idOf = { it.key },
            encode = ::encodeMetrics,
            describe = { "metric series ${it.key.name}" },
        ) { delivered ->
            val updates = delivered.associate { it.key to it.valueHash() }
            sentVersionsLock.withLock { commitSentVersions(refreshSentVersions().versions + updates) }
        }
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

    // ── Encoding ───────────────────────────────────────────────────────────────

    /** A request body ready to POST, a JSON `String` or a protobuf `ByteArray`, and its size in bytes. */
    private class Encoded(val body: Any, val bytes: Int)

    private fun encodeTraces(spans: List<SpanRecord>, links: List<SpanLink>): Encoded {
        val set = spans.toCollection(LinkedHashSet())
        return when (wire) {
            OtlpWireFormat.JSON ->
                json.encodeToString(TracesRequest.serializer(), tracesRequestOf(set, links, envelope)).encoded()
            OtlpWireFormat.PROTOBUF ->
                protobuf.encodeToByteArray(ProtoTracesRequest.serializer(), tracesProtoOf(set, links, envelope)).encoded()
        }
    }

    private fun encodeLogs(logs: List<LogRecord>): Encoded {
        val set = logs.toCollection(LinkedHashSet())
        return when (wire) {
            OtlpWireFormat.JSON -> json.encodeToString(LogsRequest.serializer(), logsRequestOf(set, envelope)).encoded()
            OtlpWireFormat.PROTOBUF ->
                protobuf.encodeToByteArray(ProtoLogsRequest.serializer(), logsProtoOf(set, envelope)).encoded()
        }
    }

    private fun encodeMetrics(points: List<MetricPoint>): Encoded {
        val set = points.toCollection(LinkedHashSet())
        return when (wire) {
            OtlpWireFormat.JSON -> json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(set, envelope)).encoded()
            OtlpWireFormat.PROTOBUF ->
                protobuf.encodeToByteArray(ProtoMetricsRequest.serializer(), metricsProtoOf(set, envelope)).encoded()
        }
    }

    private fun String.encoded() = Encoded(this, utf8Size())

    private fun ByteArray.encoded() = Encoded(this, size)

    // ── Sending one signal, split by size (#3286) ──────────────────────────────

    /**
     * POSTs [items] in as many requests as [maxRequestBytes] needs, keeping every record that shares
     * an [idOf] in one request, then hands every record an
     * acknowledged request carried to [record], once. [record] runs whatever ended the sends: the
     * last acknowledgement, a refused request, a transport failure, or this coroutine's own
     * cancellation. It runs shielded, so what the collector acknowledged is written down even while
     * the caller is being cancelled, and the failure, if any, is rethrown after it.
     *
     * [record] builds its write from a fresh read of the store, after the last request, so another
     * edge's write that lands between two requests is kept rather than overwritten.
     */
    private suspend fun <T> deliver(
        path: String,
        items: List<T>,
        idOf: (T) -> Any,
        encode: (List<T>) -> Encoded,
        describe: (T) -> String,
        record: suspend (List<T>) -> Unit,
    ) {
        val delivered = ArrayList<T>(items.size)
        // Caught to be rethrown below, after the write: whatever ended the sends, own cancellation
        // included, the acknowledged requests are recorded first.
        val failure: Throwable? = try {
            Chunker(path, encode, describe, delivered).send(items.groupBy(idOf).values.toList())
            null
        } catch (failure: Throwable) {
            failure
        }
        // An empty batch still writes on success, as an unsplit send always did.
        if (failure == null || delivered.isNotEmpty()) {
            withContext(NonCancellable) {
                if (failure == null) {
                    record(delivered)
                } else {
                    // Shielded, so a CancellationException reaching here was minted by the store; the
                    // failure that ended the sends is the one the caller needs to see.
                    try {
                        record(delivered)
                    } catch (recordFailure: Throwable) {
                        failure.addSuppressed(recordFailure)
                    }
                }
            }
        }
        if (failure != null) throw failure
    }

    /**
     * One signal's sends: appends each acknowledged request's records to [delivered], in order.
     *
     * It packs, halves and refuses whole groups, each the records that share one id, in the order
     * each id first appears. Delivery is recorded by id, so two copies of one id split across two
     * requests could see the first acknowledged and the second refused, and the refused copy would
     * then count as sent and never be retried. A span's links already travel with it the same way.
     * A group of copies over the cap is sent, and on a `413` folded, as a whole.
     *
     * Which arm a refused lone group takes is decided once, where [send] measures it: a group over
     * the cap goes to [sendOversized], the only place that folds, and every other group to
     * [sendChunk], which never does. A `413` there is remembered and the sends carry on, since the
     * same bytes are refused on every drain and would otherwise hold back everything behind them;
     * [send] throws it once the rest has been offered.
     */
    private inner class Chunker<T>(
        private val path: String,
        private val encode: (List<T>) -> Encoded,
        private val describe: (T) -> String,
        private val delivered: MutableList<T>,
    ) {
        private var refused: IllegalStateException? = null

        suspend fun send(groups: List<List<T>>) {
            // The fast path: a batch under the cap is one encode and one request, as before #3286.
            val whole = encode(groups.flatten())
            if (whole.bytes <= maxRequestBytes) {
                sendChunk(groups, whole)
            } else {
                // Measure each group inside an otherwise empty request, so its cost includes what the
                // wire adds around it, then pack greedily. Every chunk is encoded for real before it
                // is sent, and halved if the estimate fell short.
                val emptyBytes = encode(emptyList()).bytes
                val fitting = ArrayList<Pair<List<T>, Int>>(groups.size)
                val oversized = ArrayList<Pair<List<T>, Encoded>>()
                for (group in groups) {
                    val alone = encode(group)
                    if (alone.bytes > maxRequestBytes) oversized += group to alone else fitting += group to alone.bytes - emptyBytes
                }
                for (chunk in pack(fitting, emptyBytes)) sendChunk(chunk, encode(chunk.flatten()))
                // Last, so a record the collector may refuse holds back nothing else.
                for ((group, alone) in oversized) sendOversized(group, alone)
            }
            refused?.let { throw it }
        }

        private fun pack(fitting: List<Pair<List<T>, Int>>, emptyBytes: Int): List<List<List<T>>> {
            val budget = maxRequestBytes - PACKING_SLACK_BYTES
            val chunks = ArrayList<List<List<T>>>()
            var current = ArrayList<List<T>>()
            var bytes = emptyBytes
            for ((group, cost) in fitting) {
                // One byte more per group: JSON's comma between array elements. A group measured
                // alone already carries the commas between its own copies.
                if (current.isNotEmpty() && bytes + cost + 1 > budget) {
                    chunks += current
                    current = ArrayList()
                    bytes = emptyBytes
                }
                current += group
                bytes += cost + 1
            }
            if (current.isNotEmpty()) chunks += current
            return chunks
        }

        private suspend fun sendChunk(chunk: List<List<T>>, body: Encoded) {
            if (chunk.size > 1 && body.bytes > maxRequestBytes) return sendHalves(chunk)
            val status = post(path, body)
            when {
                status.isSuccess() -> chunk.forEach { delivered += it }
                status != HttpStatusCode.PayloadTooLarge -> error("OTLP POST $path failed: $status")
                chunk.size > 1 -> sendHalves(chunk)
                // A lone group [send] measured inside the cap, still too large: something in front of
                // the collector caps bodies below maxRequestBytes. Folding it would record records as
                // sent that the collector never took, so the signal fails, every drain, visibly.
                else -> if (refused == null) {
                    refused = IllegalStateException(
                        "OTLP POST $path failed: $status on ${describe(chunk.single().first())} (${body.bytes} bytes), " +
                            "inside maxRequestBytes = $maxRequestBytes; the collector or a proxy in front of it limits " +
                            "bodies below that",
                    )
                }
            }
        }

        // The one place a 413 is folded: a group [send] measured over the cap on its own.
        private suspend fun sendOversized(group: List<T>, body: Encoded) {
            val status = post(path, body)
            when {
                status.isSuccess() -> delivered += group
                status != HttpStatusCode.PayloadTooLarge -> error("OTLP POST $path failed: $status")
                else -> {
                    logger.warn {
                        "OTLP POST $path: the collector refused ${describe(group.first())} (${body.bytes} bytes) " +
                            "as too large on its own; recording it as sent, since the same bytes would be refused on every drain"
                    }
                    delivered += group
                }
            }
        }

        private suspend fun sendHalves(chunk: List<List<T>>) {
            val mid = chunk.size / 2
            for (half in listOf(chunk.subList(0, mid), chunk.subList(mid, chunk.size))) sendChunk(half, encode(half.flatten()))
        }
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    // body is a JSON String or a protobuf ByteArray; Ktor's default transformers set
    // the entity from either. contentType is keyed to the selected wire.
    private suspend fun post(path: String, body: Encoded): HttpStatusCode =
        client.post(base + path) {
            contentType(
                when (wire) {
                    OtlpWireFormat.JSON -> ContentType.Application.Json
                    OtlpWireFormat.PROTOBUF -> ContentType("application", "x-protobuf")
                },
            )
            setBody(body.body)
        }.status

    // ── Producer-local sent-set persistence (CBOR) ─────────────────────────────

    private suspend fun readIdSet(key: StoreKey): Set<String> {
        val bytes = store.read(key) ?: return emptySet()
        return runCatchingCancellable {
            cbor.decodeFromByteArray(idSetSerializer, bytes)
        }.getOrDefault(emptySet())
    }

    // Makes each id-set read-merge-write one step on this edge, so two concurrent sends cannot each
    // merge onto the same read and lose the other's ids. Another edge's write between this read and
    // this write is still lost, as for the metric map below.
    private val idSetLock = Mutex()

    private suspend fun recordIds(key: StoreKey, hexIds: List<String>) = idSetLock.withLock {
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

        /** Default cap on one request's body, in bytes: 1 MiB. See `maxRequestBytes`. */
        public const val DEFAULT_MAX_REQUEST_BYTES: Int = 1 shl 20

        /**
         * Headroom a packed chunk leaves under the cap. A record measured alone pays a one-byte
         * length prefix on each enclosing protobuf message; a full chunk's prefixes are a few bytes
         * longer in all. Without it a packed chunk could overshoot by those bytes and be halved.
         */
        private const val PACKING_SLACK_BYTES = 16

        private val MINIMAL_SPAN =
            SpanRecord(ByteString(ByteArray(16)), ByteString(ByteArray(8)), null, "", SpanKind.INTERNAL, 0L, 0L)
        private val MINIMAL_LOG = LogRecord(recordId = ByteString(ByteArray(8)))
        private val MINIMAL_METRIC = MetricPoint.Sum(MetricKey("m", MetricKind.SUM), 0L, 0L, 0L)

        /**
         * Bytes of SHA-256 kept for the endpoint tag. 128 bits makes a collision
         * unreachable for a keyspace of collector URLs, and fixes the tag at 32 hex
         * characters — all of them inside the store's `[a-z0-9-]` safe set, so the
         * encoded filename is the same length whatever the endpoint is.
         */
        private const val ENDPOINT_TAG_BYTES = 16

        /** The per-endpoint sent-set tag: 128 bits of SHA-256 over [base], lowercase hex. */
        private fun endpointTag(base: String): String =
            ByteString(*SHA256().digest(base.encodeToByteArray()).copyOf(ENDPOINT_TAG_BYTES)).toHexString()

        private val protobuf = ProtoBuf
        private val cbor = Cbor { alwaysUseByteString = true }
        private val idSetSerializer = SetSerializer(String.serializer())
        private val versionsSerializer = MapSerializer(MetricKey.serializer(), Long.serializer())
    }
}

/**
 * This string's length in UTF-8 bytes, without encoding it. An unpaired surrogate counts as three,
 * the most any encoder writes for one, so the count never undershoots the body on the wire.
 */
internal fun String.utf8Size(): Int {
    var bytes = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> {
                i++
                4
            }
            else -> 3
        }
        i++
    }
    return bytes
}
