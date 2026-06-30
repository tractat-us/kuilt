package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Install log capture — the single uniform entry point on every platform.
 *
 * Wires this platform's logging output into a shared [LogCapture] core that maps
 * each event to a `LogRecord` and exports it into [exporter]. The capture edge is
 * now identical on every target: `kotlin-logging` (oshai) exposes one settable
 * appender on JVM, Android, iOS, macOS and wasmJs alike, so a single
 * `commonMain` [CapturingAppender] hooks the output everywhere.
 *
 * Installing routes `kotlin-logging` through its direct logger factory (required
 * for the appender to take effect on JVM/Android/Darwin), then replaces the
 * configured appender with a [CapturingAppender] that both feeds the capture core
 * and forwards to a per-platform passthrough appender ([captureDelegate]) so the
 * platform's existing log output is preserved.
 *
 * @param exporter the durable log buffer captured records are written into.
 * @param config which events to keep and how to shape their attributes.
 * @param clock source of event timestamps (required — never the wall clock).
 * @param random source of the per-record id bytes (required — never an unseeded
 *   default).
 * @param scope the [CoroutineScope] the capture edge drains events on. Its
 *   lifetime bounds capture: cancelling it stops capture. Inject a test scope in
 *   tests; an application-owned scope in production.
 * @return the [LogCapture] core that was installed.
 */
public fun installLogCapture(
    exporter: WarpLogRecordExporter,
    config: CaptureConfig,
    clock: Clock,
    random: Random,
    scope: CoroutineScope,
): LogCapture {
    val capture = LogCapture(exporter, config, clock, random)
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    val previous = KotlinLoggingConfiguration.direct.appender
    KotlinLoggingConfiguration.direct.appender = CapturingAppender(capture, captureDelegate(previous), scope)
    return capture
}

/**
 * The passthrough appender [CapturingAppender] forwards each event to, so the
 * platform's existing log output survives capture.
 *
 * The only per-platform piece of the capture edge:
 * - Non-Apple targets (JVM, Android, wasmJs) return [previous] — the console
 *   appender that was already installed.
 * - Apple targets (iOS, macOS) return a `%`-safe `os_log` appender that writes to
 *   the Apple unified logging system.
 */
internal expect fun captureDelegate(previous: Appender): Appender
