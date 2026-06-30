package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.ExportResult
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.time.Clock

/**
 * The shared, platform-independent capture core.
 *
 * Maps a [NormalizedLogEvent] to an OTLP-shaped [LogRecord] and exports it into
 * the durable buffer. Every per-platform capture edge funnels through this one
 * type, so the mapping — level, body, attributes, identity, timestamps — is
 * identical on every target.
 *
 * ## Injected dependencies
 *
 * Both time and randomness are dependencies, never reached for directly:
 * - [clock] supplies the event timestamps. A test injects a virtual clock; a
 *   production install passes `kotlin.time.Clock.System`.
 * - [random] supplies the fresh 8-byte `recordId` per record. A test injects a
 *   seeded [Random]; a production install passes `Random.Default`.
 *
 * @param exporter the durable log buffer this capture writes into.
 * @param config which events to keep and how to shape their attributes.
 * @param clock source of the event timestamps (required — never the wall clock).
 * @param random source of the per-record id bytes (required — never an unseeded
 *   default).
 */
public class LogCapture(
    private val exporter: WarpLogRecordExporter,
    private val config: CaptureConfig,
    private val clock: Clock,
    private val random: Random,
) {
    /**
     * Map [event] to a [LogRecord] and export it.
     *
     * Returns the exporter's [ExportResult], or `null` if [event] was below
     * [CaptureConfig.minLevel] and therefore dropped before any record was built.
     */
    public suspend fun capture(event: NormalizedLogEvent): ExportResult? {
        if (event.level.ordinal < config.minLevel.ordinal) return null
        val now = clock.now()
        val epochNanos = now.epochSeconds * NANOS_PER_SECOND + now.nanosecondsOfSecond
        val record = LogRecord(
            recordId = ByteString(random.nextBytes(RECORD_ID_BYTES)),
            severityNumber = event.level.severityNumber,
            severityText = event.level.severityText,
            body = event.message,
            attributes = config.attributeMapper(event),
            timestampEpochNanos = epochNanos,
            observedEpochNanos = epochNanos,
        )
        return exporter.export(record)
    }

    private companion object {
        private const val RECORD_ID_BYTES = 8
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
