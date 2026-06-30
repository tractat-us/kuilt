package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.Level
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test

/**
 * The os_log appender must be immune to the printf-format-string crash class: a
 * raw `%` in a message must be written verbatim, never interpreted as a format
 * specifier. `OSLogAppender` passes the message as the `%{public}s` argument, so
 * emitting a `%`-bearing line must complete normally (no trap / crash) on a real
 * Apple target. We can't read os_log output back from a unit test, so the
 * load-bearing assertion is "it does not crash".
 */
class OSLogAppenderTest {

    private fun event(level: Level, message: String): KLoggingEvent = KLoggingEvent(
        level = level,
        marker = null,
        loggerName = "com.example.OsLog",
        message = message,
        cause = null,
        payload = null,
        timestamp = 0L,
    )

    @Test
    fun percentBearingMessagesDoNotCrash() {
        val appender = OSLogAppender()
        assertAll(
            { appender.log(event(Level.TRACE, "trace 100% done")) },
            { appender.log(event(Level.DEBUG, "debug url=%20&q=%s")) },
            { appender.log(event(Level.INFO, "info %d %s %@ %%")) },
            { appender.log(event(Level.WARN, "warn 50% off")) },
            { appender.log(event(Level.ERROR, "error %n%n raw percents")) },
        )
    }
}
