package us.tractat.kuilt.otel.logback

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.logging.CaptureConfig
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
 * Exercises the optional logback appender. A **raw SLF4J** logger — not
 * `kotlin-logging` — flows through the installed [KuiltLogbackAppender] into the
 * same `WarpLogRecordExporter` buffer the uniform edge uses, proving the add-on
 * catches the SLF4J-side output the uniform `DirectLoggerFactory` edge cannot see.
 */
class KuiltLogbackAppenderTest {

    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private var appender: KuiltLogbackAppender? = null

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    @AfterTest
    fun tearDown() {
        appender?.let {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(it)
            it.stop()
        }
        MDC.clear()
    }

    @Test
    fun rawSlf4jOutputIsCapturedIntoTheBuffer() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
        appender = installLogbackCapture(
            exporter = exporter,
            config = CaptureConfig(),
            clock = fixedClock,
            random = Random(1),
            scope = backgroundScope,
            loggerContext = context,
        )

        // A RAW SLF4J logger — not kotlin-logging. MDC context + a SLF4J 2.0
        // structured key/value pair both ride along.
        val slf4j = LoggerFactory.getLogger("com.example.Framework")
        MDC.put("tenant", "acme")
        slf4j.atWarn().addKeyValue("requestId", "r-7").log("framework warning")
        MDC.remove("tenant")

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
            { assertEquals("r-7", record.attributes["requestId"]) },
            { assertEquals(8, record.recordId.size) },
        )
    }

    @Test
    fun belowMinLevelEventsAreDropped() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
        appender = installLogbackCapture(
            exporter = exporter,
            config = CaptureConfig(minLevel = LogLevel.WARN),
            clock = fixedClock,
            random = Random(1),
            scope = backgroundScope,
            loggerContext = context,
        )

        val slf4j = LoggerFactory.getLogger("com.example.Framework")
        slf4j.info("an info line below the WARN floor")
        slf4j.warn("a warning at the floor")

        testScheduler.runCurrent()

        val records = exporter.snapshot().toList()
        assertAll(
            { assertEquals(1, records.size) },
            { assertEquals("a warning at the floor", records.single().body) },
        )
    }
}
