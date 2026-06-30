package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope

/**
 * JVM/Android capture edge — a no-op in M1.
 *
 * On the JVM `kotlin-logging` logs *through* SLF4J, so capture must sit at the
 * SLF4J layer rather than at the oshai `Appender` (which oshai ignores here). That
 * SLF4J sink is a separate milestone. The uniform [installLogCapture] entry point
 * still exists on these targets — it simply wires nothing yet.
 */
@Suppress("UnusedParameter")
internal actual fun installPlatformLogCapture(capture: LogCapture, scope: CoroutineScope) {
    // Intentionally empty — see KDoc.
}
