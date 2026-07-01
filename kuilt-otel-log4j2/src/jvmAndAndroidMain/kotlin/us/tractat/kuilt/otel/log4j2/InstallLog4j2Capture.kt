package us.tractat.kuilt.otel.log4j2

import kotlinx.coroutines.CoroutineScope
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.logging.CaptureConfig
import us.tractat.kuilt.otel.logging.LogCapture
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Install the optional log4j2 capture add-on — the JVM/Android entry point that
 * additionally captures raw-SLF4J/log4j2 output into [exporter]. The log4j2 sibling
 * of [us.tractat.kuilt.otel.logback.installLogbackCapture] (see that module if your
 * backend is logback instead).
 *
 * Mirrors [us.tractat.kuilt.otel.logging.installLogCapture]'s shape: it builds a
 * [LogCapture] core, constructs a [KuiltLog4j2Appender] over it, starts the appender
 * and attaches it to [loggerContext]'s root logger. The attach is **additive** — it
 * does not replace the uniform `kotlin-logging` capture edge or any console appender
 * the app already configured; both keep running. An app that wants the JVM "bonus
 * scope" (catch everything on the log4j2 side — frameworks, transitive deps) installs
 * this alongside the uniform edge.
 *
 * @param exporter the durable log buffer captured records are written into — the
 *   same exporter the uniform edge uses, so records land in one buffer.
 * @param config which events to keep and how to shape their attributes.
 * @param clock source of event timestamps (required — never the wall clock).
 * @param random source of the per-record id bytes (required — never an unseeded
 *   default).
 * @param scope the [CoroutineScope] the capture edge drains events on. A backstop
 *   on the appender's lifetime, not the primary teardown: cancelling it while the
 *   appender is still attached leaks (see [KuiltLog4j2Appender]). Uninstall via the
 *   returned appender instead. Inject a test scope in tests; an application-owned
 *   scope in production.
 * @param loggerContext the log4j2 [LoggerContext] to attach to. Defaults to the
 *   process-wide context log4j2 is bound to — the one raw `LogManager.getLogger`
 *   calls resolve against.
 * @return the started [KuiltLog4j2Appender]. **Uninstall it** by removing it from the
 *   root logger config ([uninstallLog4j2Capture]) and calling
 *   [KuiltLog4j2Appender.stop] — `stop()` halts the log path *and* closes the drain,
 *   fully releasing capture. (Cancelling [scope] alone is not sufficient — see
 *   [KuiltLog4j2Appender].)
 */
public fun installLog4j2Capture(
    exporter: WarpLogRecordExporter,
    config: CaptureConfig,
    clock: Clock,
    random: Random,
    scope: CoroutineScope,
    loggerContext: LoggerContext = defaultLoggerContext(),
): KuiltLog4j2Appender {
    val capture = LogCapture(exporter, config, clock, random)
    val appender = KuiltLog4j2Appender(APPENDER_NAME, capture, scope).apply { start() }
    val configuration = loggerContext.configuration
    configuration.addAppender(appender)
    configuration.rootLogger.addAppender(appender, null, null)
    loggerContext.updateLoggers()
    return appender
}

/**
 * Detach [appender] from [loggerContext]'s root logger — the log4j2-native
 * counterpart to logback's `root.detachAppender`. Does **not** stop the appender:
 * call [KuiltLog4j2Appender.stop] afterwards to close the drain and release capture.
 */
public fun uninstallLog4j2Capture(
    appender: KuiltLog4j2Appender,
    loggerContext: LoggerContext = defaultLoggerContext(),
) {
    val configuration = loggerContext.configuration
    configuration.rootLogger.removeAppender(appender.name)
    loggerContext.updateLoggers()
}

/** The [KuiltLog4j2Appender.getName] of the appender [installLog4j2Capture] attaches. */
public const val APPENDER_NAME: String = "kuilt-capture"

/**
 * The process-wide log4j2 [LoggerContext] log4j2 is bound to — the one raw
 * `LogManager.getLogger` calls resolve against. Binds the platform-typed context to
 * a non-null local before the cast so it reads as a plain non-nullable cast.
 */
private fun defaultLoggerContext(): LoggerContext {
    val spiContext: org.apache.logging.log4j.spi.LoggerContext = LogManager.getContext(false)
    return spiContext as LoggerContext
}
