package us.tractat.kuilt.otel

/**
 * Controls what happens when the in-memory CRDT buffer reaches [maxSpans].
 *
 * ## Honest limits
 *
 * An offline-forever device cannot buffer forever. The degradation is intentionally
 * **asymmetric and logged**:
 * - Metrics compress losslessly (a counter is O(1) regardless of how many increments
 *   happen offline). Buffer caps only apply to spans and logs.
 * - When a cap is hit, the [BufferPolicy] decides which spans to drop, and the
 *   exporter *always logs what it dropped* — never silently truncates.
 *
 * The [DROP_OLDEST] strategy is usually right: oldest spans are already "done" and
 * are the least likely to complete a trace being actively sampled right now. A
 * span dropped here is logged with its [SpanRecord.traceId] and [SpanRecord.spanId]
 * so an operator can correlate against their backend's orphan-span index.
 */
public enum class BufferPolicy {
    /** Drop the oldest span when the buffer is full. **Logs each drop.** */
    DROP_OLDEST,

    /** Drop the newest span when the buffer is full. **Logs each drop.** */
    DROP_NEWEST,
}

/** Maximum number of [SpanRecord]s buffered in memory before eviction. */
public const val DEFAULT_MAX_SPANS: Int = 10_000
