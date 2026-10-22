package us.tractat.kuilt.otel.logback

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.slf4j.ILoggerFactory
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.logging.ActiveTrace
import us.tractat.kuilt.otel.logging.CaptureConfig
import us.tractat.kuilt.otel.logging.LOGGER_NAME_ATTRIBUTE
import us.tractat.kuilt.otel.logging.LogLevel
import us.tractat.kuilt.otel.logging.TraceContextProvider
import us.tractat.kuilt.otel.logging.withLogContext
import us.tractat.kuilt.store.InMemoryDurableStore
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

    private val context: LoggerContext = run {
        val factory: ILoggerFactory = LoggerFactory.getILoggerFactory()
        factory as LoggerContext
    }
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
    fun stopEndsCaptureAndClosesTheDrain() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
        val installed = installLogbackCapture(
            exporter = exporter,
            config = CaptureConfig(),
            clock = fixedClock,
            random = Random(1),
            scope = backgroundScope,
            loggerContext = context,
        )
        appender = installed

        val slf4j = LoggerFactory.getLogger("com.example.Framework")
        slf4j.warn("before stop")
        testScheduler.runCurrent()

        // Tear down the standard logback way: detach + stop. stop() closes the
        // channel so the drain coroutine completes (no leak), and halts the log path.
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(installed)
        installed.stop()
        appender = null

        slf4j.warn("after stop")
        testScheduler.runCurrent()

        // Only the pre-stop line was captured; the post-stop line is not.
        assertEquals(listOf("before stop"), exporter.snapshot().toList().map { it.body })
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

    @Test
    fun pausedDrainPreservesEmissionContext() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("edge"), InMemoryDurableStore())
        val emittedTrace = ActiveTrace(ByteString(ByteArray(16) { 3 }), ByteString(ByteArray(8) { 4 }), true)
        var trace: ActiveTrace? = emittedTrace
        var instant = Instant.fromEpochSeconds(10)
        var tenant = "emitted"
        var mappings = 0
        val clock = object : Clock { override fun now(): Instant = instant }
        val config = CaptureConfig(attributeMapper = {
            mappings++
            mapOf("tenant" to tenant)
        })
        val installed = installLogbackCapture(
            exporter, config, clock, Random(0), backgroundScope, context, TraceContextProvider { trace },
        )
        appender = installed
        try {
            withLogContext("scope" to "emitted") {
                LoggerFactory.getLogger("com.example.Edge").warn("traced")
            }
            trace = null
            LoggerFactory.getLogger("com.example.Edge").warn("untraced")
            val mappingsAtEdge = mappings
            assertEquals(0, exporter.snapshot().toList().size, "the drain must still be paused")
            trace = ActiveTrace(ByteString(ByteArray(16) { 5 }), ByteString(ByteArray(8) { 6 }), true)
            instant = Instant.fromEpochSeconds(20)
            tenant = "drained"
            withLogContext("scope" to "drained") { testScheduler.runCurrent() }
            val records = exporter.snapshot().toList()
            assertEquals(2, records.size)
            val traced = records[0]
            val untraced = records[1]
            assertAll(
                { assertEquals(emittedTrace.traceId, traced.traceId) },
                { assertEquals(emittedTrace.spanId, traced.spanId) },
                { assertEquals(null, untraced.traceId, "resolved absence must remain untraced") },
                { assertEquals(null, untraced.spanId) },
                { assertEquals("emitted", traced.attributes["scope"]) },
                { assertEquals(null, untraced.attributes["scope"]) },
                { assertEquals(listOf("emitted", "emitted"), records.map { it.attributes["tenant"] }) },
                { assertEquals(listOf(10_000_000_000L, 10_000_000_000L), records.map { it.timestampEpochNanos }) },
                { assertEquals(listOf(20_000_000_000L, 20_000_000_000L), records.map { it.observedEpochNanos }) },
                { assertEquals(2, mappingsAtEdge, "both events must resolve before the drain runs") },
                { assertEquals(2, mappings, "draining must not call the mapper again") },
            )
        } finally {
            installed.stop()
        }
    }
}
