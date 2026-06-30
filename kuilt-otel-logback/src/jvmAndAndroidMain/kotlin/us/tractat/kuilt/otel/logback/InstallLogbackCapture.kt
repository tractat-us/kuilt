package us.tractat.kuilt.otel.logback

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.CoroutineScope
import org.slf4j.ILoggerFactory
import org.slf4j.LoggerFactory
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.logging.CaptureConfig
import us.tractat.kuilt.otel.logging.LogCapture
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Install the optional logback capture add-on — the JVM/Android entry point that
 * additionally captures raw-SLF4J output into [exporter].
 *
 * Mirrors [us.tractat.kuilt.otel.logging.installLogCapture]'s shape: it builds a
 * [LogCapture] core, constructs a [KuiltLogbackAppender] over it, starts the
 * appender and attaches it to [loggerContext]'s root logger. The attach is
 * **additive** — it does not replace the uniform `kotlin-logging` capture edge or
 * any console appender the app already configured; both keep running. An app that
 * wants the JVM "bonus scope" (catch everything on the SLF4J side — frameworks,
 * transitive deps) installs this alongside the uniform edge.
 *
 * @param exporter the durable log buffer captured records are written into — the
 *   same exporter the uniform edge uses, so records land in one buffer.
 * @param config which events to keep and how to shape their attributes.
 * @param clock source of event timestamps (required — never the wall clock).
 * @param random source of the per-record id bytes (required — never an unseeded
 *   default).
 * @param scope the [CoroutineScope] the capture edge drains events on. A backstop
 *   on the appender's lifetime, not the primary teardown: cancelling it while the
 *   appender is still attached leaks (see [KuiltLogbackAppender]). Uninstall via
 *   the returned appender instead. Inject a test scope in tests; an
 *   application-owned scope in production.
 * @param loggerContext the logback [LoggerContext] to attach to. Defaults to the
 *   process-wide context SLF4J is bound to — the one raw `LoggerFactory.getLogger`
 *   calls resolve against.
 * @return the started [KuiltLogbackAppender]. **Uninstall it** by detaching it
 *   (`root.detachAppender(it)`) and calling [KuiltLogbackAppender.stop] — `stop()`
 *   halts the log path *and* closes the drain, fully releasing capture. (Cancelling
 *   [scope] alone is not sufficient — see [KuiltLogbackAppender].)
 */
public fun installLogbackCapture(
    exporter: WarpLogRecordExporter,
    config: CaptureConfig,
    clock: Clock,
    random: Random,
    scope: CoroutineScope,
    loggerContext: LoggerContext = defaultLoggerContext(),
): KuiltLogbackAppender {
    val capture = LogCapture(exporter, config, clock, random)
    val appender = KuiltLogbackAppender(capture, scope).apply {
        context = loggerContext
        name = APPENDER_NAME
        start()
    }
    loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
    return appender
}

/** The [KuiltLogbackAppender.getName] of the appender [installLogbackCapture] attaches. */
public const val APPENDER_NAME: String = "kuilt-capture"

/**
 * The process-wide logback [LoggerContext] SLF4J is bound to — the one raw
 * `LoggerFactory.getLogger` calls resolve against. Binds the platform-typed
 * factory to a non-null local before the cast so it reads as a plain
 * non-nullable cast.
 */
private fun defaultLoggerContext(): LoggerContext {
    val factory: ILoggerFactory = LoggerFactory.getILoggerFactory()
    return factory as LoggerContext
}
