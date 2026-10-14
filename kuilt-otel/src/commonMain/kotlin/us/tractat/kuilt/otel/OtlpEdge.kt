package us.tractat.kuilt.otel

import kotlinx.io.bytestring.ByteString

/**
 * An OTLP-capable edge that [WarpOtlpBridge] drains converged CRDTs into.
 *
 * The contract is intentionally minimal so the bridge stays decoupled from any
 * specific transport: the edge exposes what it already has ([digest]) and accepts
 * what it is missing ([send]).
 *
 * **Keep `:kuilt-core` types off this surface.** The bridge consumes `Seam` and
 * `Loom` as an implementation detail; neither leaks through here. Consumers depend
 * on `:kuilt-otel`; they should not need to depend on `:kuilt-core` just to wire up
 * a backend.
 *
 * ## Implementations
 *
 * - A Ktor HTTP client that POSTs to `POST /v1/traces` (OTLP/HTTP protobuf).
 * - A test double ([us.tractat.kuilt.otel] package's `RecordingEdge` in tests).
 *
 * @sample us.tractat.kuilt.otel.sampleOtlpEdge
 */
public interface OtlpEdge {

    // ── Spans ──────────────────────────────────────────────────────────────────

    /**
     * Returns a compact summary of which spans this producer has already delivered to
     * the edge (producer-local — see [SpanDigest]).
     *
     * The bridge subtracts this set from its local CRDT snapshot to compute the
     * delta: only spans absent from the digest are sent over the wire.
     *
     * Throws on connectivity failure; [WarpOtlpBridge] wraps the call with
     * [runCatchingCancellable] and reports the exception, tagged [OtlpSignal.SPANS], in a
     * [DrainResult.Partial] or [DrainResult.Failure].
     */
    public suspend fun digest(): SpanDigest

    /**
     * Push a batch of spans to the edge, with their inferred causal [links].
     *
     * [links] carries the [SpanLink]s the bridge inferred for the spans in this batch
     * (happens-before edges derived from causal stamps); the edge attaches each to its
     * owning span's OTLP `Span.links`. Defaults to empty so a link-unaware caller — or
     * a span with no links — is unaffected.
     *
     * The edge must apply spans idempotently by span id. [WarpOtlpBridge] filters
     * ids already in [digest]; a lost digest or failed acknowledgement may cause
     * another send, so the backend must provide that delivery deduplication.
     * This requirement does not deduplicate the local span buffer: an unstamped
     * export retried after an indeterminate write can leave distinct stamped values
     * with the same id (see [SpanRecord]).
     *
     * [spans] is non-empty when called by the bridge (an empty delta is never sent).
     *
     * Throws on send failure; [WarpOtlpBridge] wraps the call with
     * [runCatchingCancellable] and reports the exception, tagged [OtlpSignal.SPANS], in a
     * [DrainResult.Partial] or [DrainResult.Failure].
     */
    public suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink> = emptyList())

    // ── Logs (default no-op keeps span-only impls valid) ───────────────────────

    /** Which log records this producer has already delivered (producer-local; see [LogDigest]). */
    public suspend fun logDigest(): LogDigest = LogDigest(emptySet())

    /**
     * Push a batch of log records to the edge. Delivery deduplication relies on the
     * collector applying records idempotently by record id; [WarpOtlpBridge] only
     * sends records absent from [logDigest].
     */
    public suspend fun sendLogs(logs: Set<LogRecord>) {}

    // ── Metrics ────────────────────────────────────────────────────────────────

    /** The value-version this producer last delivered per series (producer-local; see [MetricDigest]). */
    public suspend fun metricDigest(): MetricDigest = MetricDigest(emptyMap())

    /**
     * Push a batch of metric data points to the edge. [WarpOtlpBridge] only sends
     * points whose value-hash differs from [metricDigest].
     */
    public suspend fun sendMetrics(points: Set<MetricPoint>) {}
}

/**
 * A compact summary of which spans this producer has already delivered to an
 * [OtlpEdge], keyed by span id.
 *
 * The bridge computes the delta as `local span ids ∖ digest.spanIds` and sends
 * only the spans in the difference. This is O(N) in span count — cheap for the
 * typical offline buffer of hundreds to low-thousands of spans.
 *
 * ## Producer-local, not a collector query
 *
 * OTLP/HTTP is **write-only** — a collector exposes no "what do you already have"
 * read-back. So the digest is **not** the collector's holdings; it is what *this*
 * producer has already successfully POSTed to *this* endpoint, persisted locally by
 * the edge. A lost or stale digest can cause re-POSTs; handling those without
 * duplicate delivery relies on the edge's idempotency requirement in [OtlpEdge.send].
 * This id-based filter does not repair distinct stamped values already in the local
 * ORSet after an indeterminate export write and retry.
 *
 * The retention bound is therefore on the producer's own sent-set (choose a cap that
 * exceeds the device's realistic offline window), not on the collector's holdings.
 *
 * @param spanIds The raw 8-byte span ids ([SpanRecord.spanId]) this producer has
 *   already delivered to the edge.
 */
public class SpanDigest(public val spanIds: Set<ByteString>)
