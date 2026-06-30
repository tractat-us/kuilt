package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.CoroutineScope

/**
 * Non-JVM capture edge — registers a [CapturingAppender] with oshai.
 *
 * Off the JVM, `kotlin-logging` dispatches every event to a single configurable
 * [io.github.oshai.kotlinlogging.Appender]. We wrap (and delegate to) whatever
 * appender was already installed so existing console output is preserved, and
 * additionally feed each event into the shared [capture] core.
 */
internal actual fun installPlatformLogCapture(capture: LogCapture, scope: CoroutineScope) {
    val previous = KotlinLoggingConfiguration.appender
    KotlinLoggingConfiguration.appender = CapturingAppender(capture, previous, scope)
}
