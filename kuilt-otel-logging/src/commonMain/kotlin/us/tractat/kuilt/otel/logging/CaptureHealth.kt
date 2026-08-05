package us.tractat.kuilt.otel.logging

/**
 * How deep the capture queue is — the buffer between the application's
 * synchronous logging call and the drain coroutine that exports each event.
 *
 * The queue exists because the app's `log()` call cannot suspend, so the drain
 * runs behind it. When the drain falls behind, this is the number of events held
 * before the oldest start being dropped (and counted on
 * [CaptureHealth.droppedEvents]).
 *
 * ## Why this number
 *
 * It is chosen from three independent bounds that happen to agree:
 *
 * - **Burst.** An app's noisiest moments — startup, a reconnect storm, an
 *   exception cascade — are a few hundred lines over a second or two. A depth
 *   below that would drop events while the drain is merely momentarily behind,
 *   which is the case the queue is *for*.
 * - **Heap.** A queued [NormalizedLogEvent] is a level, two strings and a small
 *   attribute map — order a kilobyte with its strings. This depth therefore bounds
 *   the capture queue's contribution to the host application's heap at roughly a
 *   megabyte, which is the largest fixed overhead worth imposing on a mobile host
 *   for a diagnostics subsystem.
 * - **Staleness.** At the field's measured worst case — a Debug build on an A12
 *   against a large store, 692–1153 ms per export (#1860) — a full queue is
 *   already 12–20 minutes of backlog. Records that old have lost their relation to
 *   whatever is being diagnosed, so a deeper queue buys staleness rather than
 *   fidelity.
 *
 * ## Why it is not tunable
 *
 * A knob here would invite raising the depth in response to sustained loss, and
 * sustained loss is not a depth problem: the drain is slower than the producer, so
 * a bigger queue only defers the same drops while holding more heap. The response
 * that works is a cheaper export path. [CaptureHealth.droppedEvents] makes the
 * loss visible so that conversation can start; the queue depth stays fixed so it
 * cannot be used to hide it. (A depth parameter can be added later without
 * breaking anything; a shipped one cannot be taken away.)
 */
public const val CAPTURE_QUEUE_CAPACITY: Int = 1024

/**
 * An out-of-band health signal for the log-capture edge.
 *
 * ## Why this exists
 *
 * The capture queue is bounded, so an application that logs faster than the
 * exporter drains loses events instead of growing the heap without limit (#2124).
 * That trade is only acceptable if the loss is *visible*: bounded-and-silent would
 * swap a failure you can see (memory climbing) for one you cannot, which is the
 * exact inversion #1860 was about.
 *
 * Read it from [LogCaptureInstallation.health] — in-process, never through the
 * logging pipeline, because the logging pipeline is what is failing.
 *
 * ## Why it is not `ExporterHealth`
 *
 * `us.tractat.kuilt.otel.ExporterHealth` answers the neighbouring question ("is
 * the durable write path alive?") and this deliberately mirrors its shape — a data
 * class of cumulative counters behind a `StateFlow`, no timestamps, no clock on a
 * hot path. It is a separate type because the drop happens *above* the exporter,
 * in the queue that feeds it: an event dropped here never reached the exporter, so
 * folding it into the exporter's counters would attribute a queue overflow to a
 * component that never saw the event. The two are read together and mean different
 * things — `droppedEvents` climbing with a healthy `ExporterHealth` says the export
 * path works and is merely too slow for this log volume.
 *
 * @property droppedEvents Events discarded because the capture queue was full when
 *   they were logged, cumulative across the process. **Exact, not sampled**: the
 *   channel's own overflow path reports each evicted element once, so every
 *   increment is one application log line that will never be exported. It counts
 *   *only* overflow — events the capture policy declines (below
 *   [CaptureConfig.minLevel], or one of the exporter's own loggers) never enter the
 *   queue, and events logged after capture is closed are not overflow.
 */
public data class CaptureHealth(
    public val droppedEvents: Long = 0L,
)
