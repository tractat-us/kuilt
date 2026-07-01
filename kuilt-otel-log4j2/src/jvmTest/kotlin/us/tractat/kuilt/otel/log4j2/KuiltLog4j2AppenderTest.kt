package us.tractat.kuilt.otel.log4j2

import kotlinx.coroutines.test.runTest
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.ThreadContext
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.Configurator
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.logging.CaptureConfig
import us.tractat.kuilt.otel.logging.EXCEPTION_MESSAGE_ATTRIBUTE
import us.tractat.kuilt.otel.logging.LOGGER_NAME_ATTRIBUTE
import us.tractat.kuilt.otel.logging.LogLevel
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Exercises the optional log4j2 appender. A **raw log4j2** logger — not
 * `kotlin-logging` — flows through the installed [KuiltLog4j2Appender] into the same
 * `WarpLogRecordExporter` buffer the uniform edge uses, proving the add-on catches
 * the log4j2-side output the uniform `DirectLoggerFactory` edge cannot see.
 */
class KuiltLog4j2AppenderTest {

    private val context: LoggerContext = run {
        val spiContext: org.apache.logging.log4j.spi.LoggerContext = LogManager.getContext(false)
        spiContext as LoggerContext
    }
    private var appender: KuiltLog4j2Appender? = null

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    @AfterTest
    fun tearDown() {
        appender?.let {
            uninstallLog4j2Capture(it, context)
            it.stop()
        }
        ThreadContext.clearAll()
        // Restore log4j2's default root level so tests don't cross-contaminate.
        Configurator.setRootLevel(Level.ERROR)
    }

    @Test
    fun rawLog4j2OutputIsCapturedIntoTheBuffer() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
        // Default log4j2 root level is ERROR — lower it so WARN dispatches at all.
        Configurator.setRootLevel(Level.TRACE)
        appender = installLog4j2Capture(
            exporter = exporter,
            config = CaptureConfig(),
            clock = fixedClock,
            random = Random(1),
            scope = backgroundScope,
            loggerContext = context,
        )

        // A RAW log4j2 logger — not kotlin-logging. Thread-context (MDC) + a thrown
        // both ride along.
        val log4j2 = LogManager.getLogger("com.example.Framework")
        ThreadContext.put("tenant", "acme")
        log4j2.warn("framework warning", IllegalStateException("boom"))
        ThreadContext.remove("tenant")

        // The appender's trySend and the drain coroutine both run at the current
        // virtual instant, so runCurrent() processes the queued event. (Bounded
        // drain — never advanceUntilIdle(), which would ignore backgroundScope.)
        testScheduler.runCurrent()

        val record = exporter.snapshot().toList().single()
        assertAll(
            { assertEquals("framework warning", record.body) },
            { assertEquals(LogLevel.WARN.severityText, record.severityText) },
            { assertEquals(LogLevel.WARN.severityNumber, record.severityNumber) },
            { assertEquals("com.example.Framework", record.attributes[LOGGER_NAME_ATTRIBUTE]) },
            { assertEquals("acme", record.attributes["tenant"]) },
            { assertEquals("boom", record.attributes[EXCEPTION_MESSAGE_ATTRIBUTE]) },
            { assertEquals(8, record.recordId.size) },
        )
    }

    @Test
    fun fatalIsFoldedIntoError() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
        Configurator.setRootLevel(Level.TRACE)
        appender = installLog4j2Capture(
            exporter = exporter,
            config = CaptureConfig(),
            clock = fixedClock,
            random = Random(1),
            scope = backgroundScope,
            loggerContext = context,
        )

        LogManager.getLogger("com.example.Framework").fatal("the roof is on fire")
        testScheduler.runCurrent()

        val record = exporter.snapshot().toList().single()
        assertAll(
            { assertEquals("the roof is on fire", record.body) },
            { assertEquals(LogLevel.ERROR.severityText, record.severityText) },
            { assertEquals(LogLevel.ERROR.severityNumber, record.severityNumber) },
        )
    }

    @Test
    fun stopEndsCaptureAndClosesTheDrain() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
        Configurator.setRootLevel(Level.TRACE)
        val installed = installLog4j2Capture(
            exporter = exporter,
            config = CaptureConfig(),
            clock = fixedClock,
            random = Random(1),
            scope = backgroundScope,
            loggerContext = context,
        )
        appender = installed

        val log4j2 = LogManager.getLogger("com.example.Framework")
        log4j2.warn("before stop")
        testScheduler.runCurrent()

        // Tear down the standard log4j2 way: remove from the root logger + stop.
        // stop() closes the channel so the drain coroutine completes (no leak), and
        // halts the log path.
        uninstallLog4j2Capture(installed, context)
        installed.stop()
        appender = null

        log4j2.warn("after stop")
        testScheduler.runCurrent()

        // Only the pre-stop line was captured; the post-stop line is not.
        assertEquals(listOf("before stop"), exporter.snapshot().toList().map { it.body })
    }

    @Test
    fun belowMinLevelEventsAreDropped() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
        Configurator.setRootLevel(Level.TRACE)
        appender = installLog4j2Capture(
            exporter = exporter,
            config = CaptureConfig(minLevel = LogLevel.WARN),
            clock = fixedClock,
            random = Random(1),
            scope = backgroundScope,
            loggerContext = context,
        )

        val log4j2 = LogManager.getLogger("com.example.Framework")
        log4j2.info("an info line below the WARN floor")
        log4j2.warn("a warning at the floor")

        testScheduler.runCurrent()

        val records = exporter.snapshot().toList()
        assertAll(
            { assertEquals(1, records.size) },
            { assertEquals("a warning at the floor", records.single().body) },
        )
    }
}
