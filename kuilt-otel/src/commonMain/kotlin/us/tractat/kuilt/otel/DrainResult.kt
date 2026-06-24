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
     * The drain completed successfully.
     *
     * @param spansSent Number of spans transmitted to the edge. Zero means the
     *   edge was already up to date; nothing was sent.
     */
    public data class Success(public val spansSent: Int) : DrainResult

    /**
     * The drain failed — either [OtlpEdge.digest] or [OtlpEdge.send] threw.
     *
     * @param cause The underlying exception.
     */
    public data class Failure(public val cause: Throwable) : DrainResult
}
