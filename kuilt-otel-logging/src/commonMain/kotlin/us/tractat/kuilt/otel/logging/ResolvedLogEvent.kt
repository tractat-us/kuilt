package us.tractat.kuilt.otel.logging

/**
 * An opaque snapshot of a log event ready for queued capture.
 *
 * Only [LogCapture.resolveAtEdge] produces these events. The sealed type has no
 * consumer-constructible implementation and exposes no copy operation. A nullable
 * trace means resolution found no active trace; it never means resolution was skipped.
 * Attributes and event time are always resolved.
 *
 * This type proves resolution occurred, not which thread performed it. Call
 * [LogCapture.resolveAtEdge] on the emitting thread before enqueueing, then pass
 * the result to [LogCapture.capture] or [LogCapture.captureAll] on the drain.
 */
public sealed class ResolvedLogEvent protected constructor() {
    internal abstract val event: NormalizedLogEvent
    internal abstract val activeTrace: ActiveTrace?
    internal abstract val resolvedAttributes: Map<String, String>
    internal abstract val emittedEpochNanos: Long
}
