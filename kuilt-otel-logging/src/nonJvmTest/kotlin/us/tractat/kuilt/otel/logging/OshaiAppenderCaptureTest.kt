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
 * Exercises the non-JVM oshai capture edge feeding the same shared [LogCapture]
 * core. A real `kotlin-logging` call flows through the registered appender into
 * the durable buffer.
 */
class OshaiAppenderCaptureTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    @Test
    fun oshaiEventFeedsSharedCaptureCore() = runTest {
        val previousAppender = KotlinLoggingConfiguration.appender
        try {
            val store = InMemoryDurableStore()
            val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)
            installLogCapture(exporter, CaptureConfig(), fixedClock, Random(1), backgroundScope)

            KotlinLogging.logger("com.example.Edge").warn { "captured via oshai" }

            // Drain the capture channel. The drain is a single non-timer hand-off
            // coroutine that parks on an empty channel, so advancing to idle
            // terminates after the one queued event is processed.
            testScheduler.advanceUntilIdle()

            val record = exporter.snapshot().toList().single()
            assertAll(
                { assertEquals("captured via oshai", record.body) },
                { assertEquals("WARN", record.severityText) },
                { assertEquals(13, record.severityNumber) },
                { assertEquals("com.example.Edge", record.attributes[LOGGER_NAME_ATTRIBUTE]) },
                { assertEquals(8, record.recordId.size) },
            )
        } finally {
            KotlinLoggingConfiguration.appender = previousAppender
        }
    }
}
