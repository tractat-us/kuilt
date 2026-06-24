package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import us.tractat.kuilt.core.runCatchingCancellable

private val logger = KotlinLogging.logger {}

/**
 * Drains converged CRDTs to an OTLP-capable edge, reconciling by digest.
 *
 * On each [drain] call, the bridge:
 * 1. Takes a snapshot of the local CRDT (via [WarpSpanExporter.snapshot]).
 * 2. If the local set is empty, returns immediately — no edge round-trip.
 * 3. Fetches a compact [SpanDigest] from the edge (what it already has).
 * 4. Computes the delta: spans present locally but absent from the digest.
 * 5. Sends only the delta to the edge via [OtlpEdge.send].
 *
 * ## Why reconcile-by-digest?
 *
 * A naive replay would retransmit the entire buffer on every reconnect. A
 * digest-gated delta means:
 * - **No double-count.** The same span sent twice is a no-op at an OTLP
 *   collector (deduped by span-id). The digest gates before the send, so the
 *   common case (edge already has the span) never touches the wire at all.
 * - **No delta-temporality retry bug.** The CRDT's idempotent merge means the
 *   local buffer can be retried freely; the digest ensure the edge only ingests
 *   each span once.
 * - **Offline-then-reconnect.** A device buffering spans for hours only sends
 *   what the edge missed — not a full queue replay.
 *
 * ## Honest limits
 *
 * - **Digest granularity.** The digest is a flat set of span ids. An edge that
 *   GCs or compacts old spans may over-report its digest (claiming to have spans
 *   it pruned), causing the bridge to silently under-deliver. Choose an edge
 *   retention window that comfortably exceeds the device's maximum offline
 *   duration. See [SpanDigest].
 * - **Late traces.** Spans that straddle an offline and an online producer only
 *   assemble at the collector when the offline half syncs. OTLP collectors
 *   accept late spans within a configurable assembly window.
 * - **A3/A4 deferred.** Only span export (A2) is connected here. Metric and log
 *   exporters follow in subsequent PRs; the [OtlpEdge] interface is designed to
 *   accept additional signal types without breaking changes.
 *
 * @param exporter The [WarpSpanExporter] that holds the local CRDT span buffer.
 *
 * @sample us.tractat.kuilt.otel.sampleWarpOtlpBridge
 */
public class WarpOtlpBridge(
    private val exporter: WarpSpanExporter,
) {

    /**
     * Drain all spans missing from the edge.
     *
     * Safe to call on every reconnect — the digest comparison ensures only new
     * spans move over the wire. Idempotent: calling [drain] twice in a row
     * results in exactly one batch sent (on the first call); the second call finds
     * nothing new.
     *
     * [OtlpEdge.digest] and [OtlpEdge.send] errors are caught and returned as
     * [DrainResult.Failure] rather than thrown — drain is best-effort; a failure
     * leaves the local CRDT intact for the next attempt.
     *
     * @param edge The OTLP-capable backend to drain into.
     * @return [DrainResult.Success] with the number of spans sent, or
     *   [DrainResult.Failure] if the edge was unreachable.
     */
    public suspend fun drain(edge: OtlpEdge): DrainResult {
        val local = exporter.snapshot().elements
        if (local.isEmpty()) return DrainResult.Success(spansSent = 0)

        val digestResult = runCatchingCancellable { edge.digest() }
        if (digestResult.isFailure) {
            val cause = digestResult.exceptionOrNull()!!
            logger.debug(cause) { "WarpOtlpBridge: digest fetch failed; skipping drain" }
            return DrainResult.Failure(cause)
        }
        val digest = digestResult.getOrThrow()

        val delta = computeDelta(local, digest)
        if (delta.isEmpty()) return DrainResult.Success(spansSent = 0)

        val sendResult = runCatchingCancellable { edge.send(delta) }
        return sendResult.fold(
            onSuccess = {
                logger.debug { "WarpOtlpBridge: sent ${delta.size} span(s) to edge" }
                DrainResult.Success(spansSent = delta.size)
            },
            onFailure = { cause ->
                logger.debug(cause) { "WarpOtlpBridge: send failed for ${delta.size} span(s)" }
                DrainResult.Failure(cause)
            },
        )
    }

    /** Returns the subset of [local] whose span ids are absent from [digest]. */
    private fun computeDelta(local: Set<SpanRecord>, digest: SpanDigest): Set<SpanRecord> =
        local.filterTo(mutableSetOf()) { it.spanId !in digest.spanIds }
}
