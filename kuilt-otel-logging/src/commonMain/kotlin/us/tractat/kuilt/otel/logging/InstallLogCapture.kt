package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Install log capture — the single uniform entry point on every platform.
 *
 * Wires this platform's logging output into a shared [LogCapture] core that maps
 * each event to a `LogRecord` and exports it into [exporter]. The platform
 * capture edge selected behind this call is invisible to the caller: identical
 * signature, identical `LogRecord` model, identical buffer on every target.
 *
 * The actual edge differs by platform — an oshai `Appender` on Native/JS/wasm; a
 * no-op on the JVM/Android for now (the SLF4J sink is a later milestone) — but
 * that selection is an internal detail.
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
    installPlatformLogCapture(capture, scope)
    return capture
}

/**
 * Wire the platform's logging output into [capture], draining on [scope].
 *
 * `actual` per platform: an oshai `Appender` on Native/JS/wasm; a no-op on
 * JVM/Android (M1).
 */
internal expect fun installPlatformLogCapture(capture: LogCapture, scope: CoroutineScope)
