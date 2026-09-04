package us.tractat.kuilt.otel

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Capture what a `commonMain` logger actually emits, so a claim about **log volume** can be asserted
 * against emitted lines rather than against a counter production never reads.
 *
 * ## Why this is shared rather than copied a third time
 *
 * Three exporters in this module have now had the same defect — a retried failure reported once per
 * attempt, for one unchanging condition (#2237 on [WarpLogRecordExporter], #2593 on
 * [WarpSpanExporter] and [WarpMetricExporter]). The test that catches it is the same test each time,
 * and its load-bearing part is this: attach a `ListAppender` to the logger under test and count.
 * The alternative — an `internal` counter on the exporter — is an *instrument*, not an outcome, and
 * keeps reporting `1` after somebody deletes the log call it exists to witness.
 *
 * All three failure-reporting suites call this one; `WarpLogRecordExporterFailureReportingTest`
 * carried the original private copy and was migrated onto it here, so the third instance did not
 * become a third copy.
 *
 * `:kuilt-otel-tap`'s `JoinCodeNotLoggedTest` established the pattern; `logback` is on this module's
 * JVM test **compile** classpath for it.
 *
 * ## Why `jvmTest` proves something about `commonMain`
 *
 * Nothing captured here is JVM-specific: the code under test is `commonMain`, and the decision of
 * whether to emit is taken in common code. The JVM is only where a `kotlin-logging` backend that can
 * be tapped happens to exist. A green here is a green everywhere.
 */
internal suspend fun <T> capturingLogsOf(
    loggerName: String,
    block: suspend (List<ILoggingEvent>) -> T,
): T {
    // Through a declared non-null slf4j type first: `getLogger` returns a platform type, and casting
    // one straight to logback's `Logger` is a nullable-to-non-nullable cast detekt rejects.
    val slf4j: org.slf4j.Logger = LoggerFactory.getLogger(loggerName)
    val logger = slf4j as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    val previousLevel = logger.level
    // Level TRACE deliberately: the property under test is about the *volume* a broken store
    // provokes, so a line demoted to `debug` still counts against it.
    logger.level = Level.TRACE
    logger.addAppender(appender)
    try {
        return block(appender.list)
    } finally {
        // Restored in `finally` so the swap cannot leak into whatever runs next in this JVM.
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }
}

/** The captured events whose rendered message contains [fragment]. */
internal fun List<ILoggingEvent>.naming(fragment: String): List<ILoggingEvent> =
    filter { fragment in it.formattedMessage }
