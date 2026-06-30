package us.tractat.kuilt.otel.logging

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
        val previousFactory = KotlinLoggingConfiguration.loggerFactory
        val previousAppender = KotlinLoggingConfiguration.direct.appender
        try {
            val store = InMemoryDurableStore()
            val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
            installLogCapture(exporter, CaptureConfig(), fixedClock, Random(1), backgroundScope)

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
            KotlinLoggingConfiguration.direct.appender = previousAppender
            KotlinLoggingConfiguration.loggerFactory = previousFactory
        }
    }
}
