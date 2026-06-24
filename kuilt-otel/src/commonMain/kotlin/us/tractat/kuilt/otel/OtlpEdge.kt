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

    /**
     * Returns a compact summary of which spans the edge already holds.
     *
     * The bridge subtracts this set from its local CRDT snapshot to compute the
     * delta: only spans absent from the digest are sent over the wire.
     *
     * Throws on connectivity failure; [WarpOtlpBridge] wraps the call with
     * [runCatchingCancellable] and surfaces the error as [DrainResult.Failure].
     */
    public suspend fun digest(): SpanDigest

    /**
     * Push a batch of spans to the edge.
     *
     * The edge must apply them idempotently (OTLP collectors already guarantee this:
     * duplicate spans are deduplicated by span-id). [WarpOtlpBridge] only sends spans
     * that [digest] says the edge does not have, but idempotency at the edge is the
     * structural backstop that makes a resend under a retry truly harmless.
     *
     * [spans] is non-empty when called by the bridge (an empty delta is never sent).
     *
     * Throws on send failure; [WarpOtlpBridge] wraps the call with
     * [runCatchingCancellable] and surfaces the error as [DrainResult.Failure].
     */
    public suspend fun send(spans: Set<SpanRecord>)
}

/**
 * A compact summary of which spans an [OtlpEdge] already holds, keyed by span id.
 *
 * The bridge computes the delta as `local span ids ∖ digest.spanIds` and sends
 * only the spans in the difference. This is O(N) in span count — cheap for the
 * typical offline buffer of hundreds to low-thousands of spans.
 *
 * ## Granularity
 *
 * The digest is a **flat set of span ids**: it covers what the edge persisted from
 * previous exports, not what it might have GC'd or compacted. An edge that retains
 * a rolling window may over-report (claiming to have spans it already pruned), which
 * causes the bridge to under-send — the span is then permanently missing from the
 * edge until it GCs the stale digest entry. The bridge cannot detect this condition;
 * document it in the edge implementation and choose a retention window that comfortably
 * exceeds the device's maximum offline duration.
 *
 * @param spanIds The raw 8-byte span ids ([SpanRecord.spanId]) currently held by the edge.
 */
public class SpanDigest(public val spanIds: Set<ByteString>)
