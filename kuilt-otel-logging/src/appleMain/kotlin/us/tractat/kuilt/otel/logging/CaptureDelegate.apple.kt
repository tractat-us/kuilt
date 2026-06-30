package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender

/**
 * Apple passthrough — route the platform's log output to a `%`-safe [OSLogAppender]
 * (Apple unified logging / `os_log`) rather than the default console appender.
 *
 * The previously-installed appender is intentionally discarded: on Apple targets
 * the unified log *is* the console surface, so [OSLogAppender] replaces — not
 * wraps — it.
 */
@Suppress("UnusedParameter")
internal actual fun captureDelegate(previous: Appender): Appender = OSLogAppender()
