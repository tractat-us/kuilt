package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.cinterop.ExperimentalForeignApi
import kuilt.oslog.kuilt_oslog_create
import kuilt.oslog.kuilt_oslog_debug
import kuilt.oslog.kuilt_oslog_default
import kuilt.oslog.kuilt_oslog_default_level
import kuilt.oslog.kuilt_oslog_error
import kuilt.oslog.kuilt_oslog_info

/**
 * An [Appender] that writes to the Apple unified logging system (`os_log`),
 * `%`-safe by construction.
 *
 * Each event is rendered to a string by the configured
 * [io.github.oshai.kotlinlogging.Formatter], then handed to `os_log` as the
 * single `%{public}s` **argument** — never as the format string. A raw `%` in the
 * message (e.g. `url=%20`, `100% done`) is therefore data, not a printf
 * specifier: it cannot trigger the format-string crash class and renders
 * literally. `%{public}` keeps the line visible in Console.app / `log` rather
 * than redacted as `<private>`.
 *
 * The log handle honours [KotlinLoggingConfiguration.subsystem] /
 * [KotlinLoggingConfiguration.category] when a subsystem is configured (so
 * Console output can be filtered), falling back to the default log otherwise. The
 * handle is created once and lives for the appender's lifetime.
 */
@OptIn(ExperimentalForeignApi::class)
internal class OSLogAppender : Appender {

    // The cinterop shim maps `const char*` parameters to Kotlin `String?` and the
    // opaque `void*` handle to `COpaquePointer?`, so no manual C-string marshalling
    // is needed.
    private val log = run {
        val subsystem = KotlinLoggingConfiguration.subsystem.value
        if (subsystem != null) {
            kuilt_oslog_create(subsystem, KotlinLoggingConfiguration.category.value ?: "")
        } else {
            kuilt_oslog_default()
        }
    }

    override fun log(loggingEvent: KLoggingEvent) {
        val formatted = KotlinLoggingConfiguration.direct.formatter.formatMessage(loggingEvent)
        when (loggingEvent.level) {
            Level.TRACE, Level.DEBUG -> kuilt_oslog_debug(log, formatted)
            Level.INFO -> kuilt_oslog_info(log, formatted)
            Level.WARN -> kuilt_oslog_default_level(log, formatted)
            Level.ERROR -> kuilt_oslog_error(log, formatted)
            Level.OFF -> Unit
        }
    }
}
