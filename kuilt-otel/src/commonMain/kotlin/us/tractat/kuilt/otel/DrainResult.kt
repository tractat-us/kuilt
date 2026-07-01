package us.tractat.kuilt.otel

/**
 * The result of a [WarpOtlpBridge.drain] call.
 *
 * A drain is best-effort: a [Failure] means the edge was unreachable or rejected
 * the batch, not that local data was lost. The CRDT is unchanged on failure; the
 * next drain will retry the same delta.
 */
public sealed interface DrainResult {

    /**
     * The drain completed (fully or partially) — at least one signal got through, or
     * there was nothing to send.
     *
     * Each signal is drained independently; a per-signal failure contributes 0 and is
     * logged, without aborting the others. A drain is a [Failure] only when every
     * *attempted* signal failed.
     *
     * @param spansSent Number of spans transmitted. Zero means the edge was already
     *   up to date (or the span leg failed — see class doc).
     * @param logsSent Number of log records transmitted.
     * @param metricPointsSent Number of metric data points transmitted.
     */
    public data class Success(
        public val spansSent: Int,
        public val logsSent: Int = 0,
        public val metricPointsSent: Int = 0,
    ) : DrainResult

    /**
     * The drain failed — either [OtlpEdge.digest] or [OtlpEdge.send] threw.
     *
     * @param cause The underlying exception.
     */
    public data class Failure(public val cause: Throwable) : DrainResult
}
