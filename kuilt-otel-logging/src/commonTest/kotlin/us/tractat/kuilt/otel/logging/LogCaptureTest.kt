package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.ExportResult
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class LogCaptureTest {

    private val fixedInstant = Instant.fromEpochSeconds(epochSeconds = 1_700_000_000L, nanosecondAdjustment = 500)
    private val fixedClock = object : Clock {
        override fun now(): Instant = fixedInstant
    }
    private val expectedNanos = 1_700_000_000L * 1_000_000_000L + 500L

    private fun exporter(store: InMemoryDurableStore) =
        WarpLogRecordExporter(ReplicaId("producer-1"), store)

    @Test
    fun mapsEventToLogRecordShape() = runTest {
        val store = InMemoryDurableStore()
        val exp = exporter(store)
        val capture = LogCapture(exp, CaptureConfig(), fixedClock, Random(42))

        val result = capture.capture(
            NormalizedLogEvent(
                level = LogLevel.WARN,
                loggerName = "com.example.Service",
                message = "disk almost full",
                attributes = mapOf("disk" to "/dev/sda"),
            ),
        )

        assertEquals(ExportResult.Success, result)
        val record = exp.snapshot().toList().single()
        assertAll(
            { assertEquals(13, record.severityNumber) },
            { assertEquals("WARN", record.severityText) },
            { assertEquals("disk almost full", record.body) },
            { assertEquals(8, record.recordId.size) },
            { assertEquals("com.example.Service", record.attributes[LOGGER_NAME_ATTRIBUTE]) },
            { assertEquals("/dev/sda", record.attributes["disk"]) },
            { assertEquals(expectedNanos, record.timestampEpochNanos) },
            { assertEquals(expectedNanos, record.observedEpochNanos) },
            { assertNull(record.traceId) },
            { assertNull(record.spanId) },
        )
    }

    @Test
    fun dropsEventsBelowMinLevel() = runTest {
        val store = InMemoryDurableStore()
        val exp = exporter(store)
        val capture = LogCapture(exp, CaptureConfig(minLevel = LogLevel.WARN), fixedClock, Random(0))

        val result = capture.capture(
            NormalizedLogEvent(LogLevel.INFO, "com.example.Service", "below the floor"),
        )

        assertNull(result)
        assertTrue(exp.snapshot().toList().isEmpty())
    }

    @Test
    fun capturesEventsAtOrAboveMinLevel() = runTest {
        val store = InMemoryDurableStore()
        val exp = exporter(store)
        val capture = LogCapture(exp, CaptureConfig(minLevel = LogLevel.WARN), fixedClock, Random(0))

        assertEquals(ExportResult.Success, capture.capture(NormalizedLogEvent(LogLevel.WARN, "lg", "at floor")))
        assertEquals(ExportResult.Success, capture.capture(NormalizedLogEvent(LogLevel.ERROR, "lg", "above floor")))
        assertEquals(2, exp.snapshot().toList().size)
    }

    @Test
    fun recordIdComesFromInjectedRandom() = runTest {
        val expectedId = Random(7).nextBytes(8)
        val store = InMemoryDurableStore()
        val exp = exporter(store)
        val capture = LogCapture(exp, CaptureConfig(), fixedClock, Random(7))

        capture.capture(NormalizedLogEvent(LogLevel.INFO, "lg", "m"))

        val record = exp.snapshot().toList().single()
        assertContentEquals(expectedId, record.recordId.toByteArray())
    }

    @Test
    fun customAttributeMapperReplacesAttributes() = runTest {
        val store = InMemoryDurableStore()
        val exp = exporter(store)
        val config = CaptureConfig(attributeMapper = { mapOf("fixed" to "value") })
        val capture = LogCapture(exp, config, fixedClock, Random(1))

        capture.capture(NormalizedLogEvent(LogLevel.INFO, "lg", "m", attributes = mapOf("dropped" to "x")))

        val record = exp.snapshot().toList().single()
        assertAll(
            { assertEquals(mapOf("fixed" to "value"), record.attributes) },
            { assertNull(record.attributes["dropped"]) },
        )
    }
}
