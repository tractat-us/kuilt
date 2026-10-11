package us.tractat.kuilt.otel

/**
 * The result of a [WarpOtlpBridge.drain] call.
 *
 * A drain is best-effort and per-signal: each of [OtlpSignal.SPANS], [OtlpSignal.LOGS]
 * and [OtlpSignal.METRICS] is drained independently, and a signal with nothing buffered
 * is not attempted at all. The result is classified by which attempted signal
 * *operations* succeeded or failed, never by how many records moved — an up-to-date
 * signal sends zero records and still succeeded:
 *
 * - [Success] — no attempted signal failed (including when nothing was attempted).
 * - [Partial] — at least one attempted signal succeeded and at least one failed.
 * - [Failure] — every attempted signal failed.
 *
 * A failed signal leaves local data intact: its CRDT is unchanged, and the next drain
 * retries the same delta.
 */
public sealed interface DrainResult {

    /**
     * Every attempted signal drained, or nothing was buffered to attempt.
     *
     * A zero count means that signal had nothing the edge lacked (or nothing buffered) —
     * never that it failed; a failed signal makes the result [Partial] or [Failure].
     *
     * @param spansSent Number of spans transmitted.
     * @param logsSent Number of log records transmitted.
     * @param metricPointsSent Number of metric data points transmitted.
     */
    public data class Success(
        public val spansSent: Int,
        public val logsSent: Int = 0,
        public val metricPointsSent: Int = 0,
    ) : DrainResult

    /**
     * At least one attempted signal drained and at least one failed.
     *
     * The counts report what got through; a failed signal's count is zero. [failures]
     * names each failed signal with the exception its edge call threw — the original
     * instance, not a wrapper.
     *
     * @param spansSent Number of spans transmitted.
     * @param logsSent Number of log records transmitted.
     * @param metricPointsSent Number of metric data points transmitted.
     * @param failures One entry per failed signal, in drain order (spans, logs, metrics).
     *   Never empty, and no signal appears twice.
     */
    public data class Partial(
        public val spansSent: Int,
        public val logsSent: Int,
        public val metricPointsSent: Int,
        public val failures: List<SignalFailure>,
    ) : DrainResult {
        init {
            requireWellFormed(failures)
        }
    }

    /**
     * Every attempted signal failed — typically the edge is unreachable, which is this
     * bridge's routine offline state.
     *
     * @param failures One entry per attempted signal, in drain order (spans, logs,
     *   metrics), each carrying the exception its edge call threw — the original instance,
     *   not a wrapper. Never empty, and no signal appears twice.
     */
    public data class Failure(public val failures: List<SignalFailure>) : DrainResult {
        init {
            requireWellFormed(failures)
        }
    }
}

/** One of the three OTLP signals [WarpOtlpBridge] drains. */
public enum class OtlpSignal {
    /** Spans, via [OtlpEdge.digest] and [OtlpEdge.send]. */
    SPANS,

    /** Log records, via [OtlpEdge.logDigest] and [OtlpEdge.sendLogs]. */
    LOGS,

    /** Metric points, via [OtlpEdge.metricDigest] and [OtlpEdge.sendMetrics]. */
    METRICS,
}

/**
 * One signal's failure within a [WarpOtlpBridge.drain].
 *
 * @param signal The signal whose digest or send threw.
 * @param cause The exception the [OtlpEdge] call threw, unwrapped.
 */
public data class SignalFailure(
    public val signal: OtlpSignal,
    public val cause: Throwable,
)

private fun requireWellFormed(failures: List<SignalFailure>) {
    require(failures.isNotEmpty()) { "a drain failure must name at least one failed signal" }
    require(failures.distinctBy { it.signal }.size == failures.size) {
        "each signal fails at most once per drain: ${failures.map { it.signal }}"
    }
}
