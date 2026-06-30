package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Exercises the uniform `commonMain` capture edge feeding the shared [LogCapture]
 * core. A real `kotlin-logging` call flows through the installed [CapturingAppender]
 * into the durable buffer — on every target the same way.
 */
class CaptureEdgeTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    @Test
    fun loggingFlowsThroughCaptureEdgeIntoBuffer() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
        val installation = installLogCapture(exporter, CaptureConfig(), fixedClock, Random(1), backgroundScope)
        try {
            KotlinLogging.logger("com.example.Edge").warn { "captured via the uniform edge" }

            // Drain the capture channel. The appender's trySend and the drain
            // coroutine both run at the current virtual instant, so runCurrent()
            // processes the queued event. (advanceUntilIdle() deliberately ignores
            // backgroundScope tasks, so it would never run the drain.)
            testScheduler.runCurrent()

            val record = exporter.snapshot().toList().single()
            assertAll(
                { assertEquals("captured via the uniform edge", record.body) },
                { assertEquals("WARN", record.severityText) },
                { assertEquals(13, record.severityNumber) },
                { assertEquals("com.example.Edge", record.attributes[LOGGER_NAME_ATTRIBUTE]) },
                { assertEquals(8, record.recordId.size) },
            )
        } finally {
            installation.close()
        }
    }

    @Test
    fun selfCaptureStaysBoundedAndExcludesKuiltLoggers() = runTest {
        val store = InMemoryDurableStore()
        // maxRecords = 1 forces an eviction on the second app record. The eviction
        // logs an internal `us.tractat.kuilt.otel` warning; absent the self-capture
        // exclusion in LogCapture that warn would be captured and re-exported,
        // triggering another eviction → another warn → a self-sustaining loop that
        // crowds out the real app records (and would spin runCurrent() forever).
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store, maxRecords = 1)
        val installation = installLogCapture(exporter, CaptureConfig(), fixedClock, Random(1), backgroundScope)
        try {
            val logger = KotlinLogging.logger("com.example.App")
            logger.warn { "app log 1" }
            logger.warn { "app log 2" }
            testScheduler.runCurrent()

            val records = exporter.snapshot().toList()
            assertAll(
                // Bounded — no runaway self-capture loop past the buffer cap.
                { assertEquals(1, records.size) },
                // Only application records survive — never a kuilt-internal line.
                { assertTrue(records.all { it.attributes[LOGGER_NAME_ATTRIBUTE] == "com.example.App" }) },
                {
                    assertTrue(
                        records.none { (it.attributes[LOGGER_NAME_ATTRIBUTE] ?: "").startsWith("us.tractat.kuilt") },
                    )
                },
            )
        } finally {
            installation.close()
        }
    }

    @Test
    fun closeRestoresPreviousAppenderAndStopsCapture() = runTest {
        // Baseline the factory to DirectLoggerFactory — the native default — so
        // logging after close uses a deterministic, non-SLF4J path on every target
        // (the JVM's auto-detected SLF4J factory isn't on this module's test
        // classpath). The baseline *appender* stays distinct from the capturing one,
        // so the appender-restore remains observable.
        val outerFactory = KotlinLoggingConfiguration.loggerFactory
        KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
        val baselineAppender = KotlinLoggingConfiguration.direct.appender
        try {
            val store = InMemoryDurableStore()
            val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
            val installation = installLogCapture(exporter, CaptureConfig(), fixedClock, Random(1), backgroundScope)

            installation.close()

            // Logging after close is not captured — the leak fix: the appender is
            // uninstalled (previous restored) and stops accepting events.
            KotlinLogging.logger("com.example.AfterClose").warn { "should not be captured" }
            testScheduler.runCurrent()

            assertAll(
                { assertSame(baselineAppender, KotlinLoggingConfiguration.direct.appender) },
                { assertSame(DirectLoggerFactory, KotlinLoggingConfiguration.loggerFactory) },
                { assertTrue(exporter.snapshot().toList().isEmpty()) },
            )
        } finally {
            KotlinLoggingConfiguration.loggerFactory = outerFactory
        }
    }
}
