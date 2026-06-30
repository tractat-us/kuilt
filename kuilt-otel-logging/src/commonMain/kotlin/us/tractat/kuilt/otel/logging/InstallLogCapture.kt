package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.time.Clock

/**
 * A live log-capture installation — the [LogCapture] core plus the way to stop it.
 *
 * Returned by [installLogCapture]. Hold it for as long as capture should run, then
 * [close] it to uninstall. **`close()` is the way to stop capture** — cancelling
 * the install scope alone is not sufficient: it kills the drain coroutine but
 * leaves the capturing appender wired into the global logging config, buffering
 * every subsequent log line into a channel nobody drains (a memory leak).
 */
public class LogCaptureInstallation internal constructor(
    /** The installed capture core (event → `LogRecord` → exporter). */
    public val capture: LogCapture,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val closed = atomic(false)

    /**
     * Uninstall capture: restore the previously-installed appender (and logger
     * factory) and stop the capturing appender so it accepts and buffers no more
     * events. Idempotent — safe to call more than once.
     */
    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) onClose()
    }
}

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
 * **Stop capture by [closing][LogCaptureInstallation.close] the returned handle**,
 * which restores the previous appender/factory and stops the capturing appender.
 * Cancelling [scope] alone is not enough — see [LogCaptureInstallation].
 *
 * @param exporter the durable log buffer captured records are written into.
 * @param config which events to keep and how to shape their attributes.
 * @param clock source of event timestamps (required — never the wall clock).
 * @param random source of the per-record id bytes (required — never an unseeded
 *   default).
 * @param scope the [CoroutineScope] the capture edge drains events on. Inject a
 *   test scope in tests; an application-owned scope in production.
 * @return the [LogCaptureInstallation] handle — its [LogCaptureInstallation.capture]
 *   is the installed core, and [LogCaptureInstallation.close] uninstalls capture.
 */
public fun installLogCapture(
    exporter: WarpLogRecordExporter,
    config: CaptureConfig,
    clock: Clock,
    random: Random,
    scope: CoroutineScope,
): LogCaptureInstallation {
    val capture = LogCapture(exporter, config, clock, random)
    val previousFactory: KLoggerFactory = KotlinLoggingConfiguration.loggerFactory
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    val previousAppender: Appender = KotlinLoggingConfiguration.direct.appender
    val appender = CapturingAppender(capture, captureDelegate(previousAppender), scope)
    KotlinLoggingConfiguration.direct.appender = appender
    return LogCaptureInstallation(capture) {
        KotlinLoggingConfiguration.direct.appender = previousAppender
        KotlinLoggingConfiguration.loggerFactory = previousFactory
        appender.close()
    }
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
