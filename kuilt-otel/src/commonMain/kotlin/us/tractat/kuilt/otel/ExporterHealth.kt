package us.tractat.kuilt.otel

/**
 * An out-of-band health signal for a durable exporter.
 *
 * ## Why this exists
 *
 * An exporter reports a failed durable write by returning
 * [ExportResult.Failure] — but on the logging path every caller discards it.
 * `LogCapture.capture()` hands the result back to per-platform appenders whose
 * framework signatures return `void`, so the failure has nowhere to go. A
 * component whose whole purpose is post-hoc diagnosis was therefore unable to
 * report its own death: a device silently stopped accepting telemetry and stayed
 * that way for hours, with nothing written and nothing logged (#1860).
 *
 * These counters answer the question that could not be answered at the time:
 * **"has this exporter accepted anything since process start?"** They are
 * cumulative and monotonic (except [consecutiveFailures], which resets), so a
 * monitor can read them at any moment, or collect the owning
 * [kotlinx.coroutines.flow.StateFlow] and alarm on a stall.
 *
 * ## No timestamp, deliberately
 *
 * There is no `lastSuccessAt`/`deadSince` field. Time is an injected dependency
 * in this repo and an exporter holds no `Clock`; adding one to carry a
 * diagnostic field would put a wall-clock read on the export hot path. Counters
 * are sufficient — `accepted == 0` with `failed > 0` *is* "dead since process
 * start", and an observer that needs wall-clock timing can stamp its own
 * observations of the flow.
 *
 * @property accepted Durable writes that **succeeded**. Counts writes, not
 *   `Success` returns: a dedup no-op returns [ExportResult.Success] without
 *   touching the store and is deliberately not counted, so an all-dedup state
 *   cannot masquerade as a healthy climbing count.
 * @property failed Durable writes that threw, cumulative across the process.
 * @property consecutiveFailures Failed writes since the last successful one.
 *   Resets to zero on any success — distinguishes "currently down" from
 *   "recovered after some trouble".
 * @property lastFailure The most recent failure cause, from either a write or a
 *   recovery. Retained after a subsequent success, as forensics.
 * @property recoveryFailed Whether `recover()` could not read or decode the
 *   persisted state, so the exporter started empty and the previously buffered
 *   telemetry is unrecoverable. Sticky for the life of the exporter.
 */
public data class ExporterHealth(
    public val accepted: Long = 0L,
    public val failed: Long = 0L,
    public val consecutiveFailures: Int = 0,
    public val lastFailure: Throwable? = null,
    public val recoveryFailed: Boolean = false,
) {
    /**
     * Whether this exporter has never once written durably yet has failed at
     * least once — the signature of the silent death in #1860.
     */
    public val isDead: Boolean get() = accepted == 0L && (failed > 0L || recoveryFailed)
}
