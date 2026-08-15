package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLogging
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
 * Regression for #1993 — the third sibling of #1034 (trace) and #1630 (attributes),
 * for the event's own **time**.
 *
 * `LogRecord.timestampEpochNanos` is OTLP's `timeUnixNano`: *when the event
 * occurred*. It was read on the **drain coroutine**, so it was really a second copy
 * of `observedEpochNanos` — every record stamped with flush cadence rather than
 * event timing, and the error invisible in the numbers themselves. Records pulled
 * off a device came out near-uniformly spaced no matter when anything happened.
 *
 * The test proves stamping timing directly: the clock is moved forward **after** the
 * synchronous log edge runs but **before** the drain coroutine advances. The record
 * must carry the edge instant as its event time and the drain instant as its
 * observed time — the two fields OTLP defines separately must actually differ.
 */
class EventTimeResolvesAtEdgeTest {
    /**
     * A clock a test moves by hand, so the emit instant and the drain instant are
     * distinguishable. Not a virtual-time clock: `runTest`'s scheduler decides *when
     * the drain runs*, this decides *what the drain reads*, and the bug is precisely
     * that those two were the same reading.
     */
    private class SettableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    @Test
    fun eventTimeIsTheLogEdgeInstantNotTheDrainInstant() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val clock = SettableClock(EMITTED_AT)

        val installation = installLogCapture(exporter, CaptureConfig(), clock, Random(0), backgroundScope)
        try {
            // The appender's log() runs synchronously here, on this caller — so THIS
            // instant is when the event occurred.
            KotlinLogging.logger("com.example.Edge").info { "the thing happened now" }

            // A minute passes before the drain coroutine gets to it: a slow device, a
            // queue that filled, an app that logged a burst. The event did not happen
            // then, and nothing downstream can tell that it did not.
            clock.instant = DRAINED_AT
            testScheduler.runCurrent()

            val record = exporter.snapshot().toList().single()
            assertAll(
                {
                    assertEquals(
                        EMITTED_AT.toEpochNanos(),
                        record.timestampEpochNanos,
                        "timestampEpochNanos is the event time — stamped at the log edge, never on the drain",
                    )
                },
                {
                    assertEquals(
                        DRAINED_AT.toEpochNanos(),
                        record.observedEpochNanos,
                        "observedEpochNanos is when capture saw the event — the drain instant",
                    )
                },
            )
        } finally {
            installation.close()
        }
    }

    /**
     * A caller driving [LogCapture.capture] straight from its own log site never went
     * through a queueing edge, so there is no earlier emit instant to carry: that call
     * *is* the edge, and both timestamps are correctly the same instant.
     */
    @Test
    fun captureWithoutAnEdgeStampsBothFieldsWithTheCaptureInstant() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val capture = LogCapture(exporter, CaptureConfig(), SettableClock(EMITTED_AT), Random(0))

        capture.capture(NormalizedLogEvent(LogLevel.INFO, "com.example.Direct", "logged from the call site"))

        val record = exporter.snapshot().toList().single()
        assertAll(
            { assertEquals(EMITTED_AT.toEpochNanos(), record.timestampEpochNanos) },
            { assertEquals(EMITTED_AT.toEpochNanos(), record.observedEpochNanos) },
        )
    }

    private companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** When the line was logged. */
        private val EMITTED_AT = Instant.fromEpochSeconds(epochSeconds = 1_700_000_000L, nanosecondAdjustment = 500L)

        /** A minute later — when the drain coroutine finally exported it. */
        private val DRAINED_AT = Instant.fromEpochSeconds(epochSeconds = 1_700_000_060L, nanosecondAdjustment = 250L)

        private fun Instant.toEpochNanos(): Long = epochSeconds * NANOS_PER_SECOND + nanosecondsOfSecond
    }
}
