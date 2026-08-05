package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.DurableStore
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.StoreKey
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The capture queue must stay bounded when the drain is slower than the producer,
 * and the loss must be counted rather than silent (#2124).
 *
 * The producer here is the application's own logging call — `log()` is synchronous
 * and never suspends — so an unbounded queue is unbounded heap growth *in the host
 * application*, during normal operation. The fix is bounded + drop-oldest + a
 * counter; these tests pin all three, plus the invariant that the overflow report
 * can never re-enter capture.
 */
class CapturingAppenderBoundedQueueTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    /** Swallows the passthrough so a burst does not flood the test's console output. */
    private object NoOpAppender : Appender {
        override fun log(loggingEvent: KLoggingEvent) = Unit
    }

    /**
     * A [DurableStore] that takes [writeCost] of *virtual* time per write — the
     * deliberately slow drain. At the field's measured Debug/A12 export cost the
     * real exporter is at or past break-even at one log line per second (#1860),
     * which is exactly this shape.
     */
    private class SlowStore(
        private val delegate: DurableStore,
        private val writeCost: kotlin.time.Duration,
    ) : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? = delegate.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            delay(writeCost)
            delegate.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = delegate.delete(key)
    }

    private fun appEvent(message: String) = KLoggingEvent(
        level = Level.INFO,
        marker = null,
        loggerName = "com.example.App",
        message = message,
        timestamp = 0L,
    )

    @Test
    fun aDrainSlowerThanItsProducerCannotGrowTheQueueWithoutBound() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(
            ReplicaId("device-1"),
            SlowStore(InMemoryDurableStore(), writeCost = 1.seconds),
        )
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
        val appender = CapturingAppender(capture, NoOpAppender, backgroundScope, capacity = QUEUE)

        // The whole burst lands before the drain gets a single turn: log() is
        // synchronous and never suspends, so this is precisely "producer faster
        // than drain", with no dependence on scheduling luck.
        repeat(BURST) { appender.log(appEvent("event $it")) }

        // Bounded advance — the drain re-arms nothing, but advanceUntilIdle() is
        // banned repo-wide and a bounded window is the honest way to say "let the
        // slow drain finish the events it actually still holds".
        testScheduler.advanceTimeBy(DRAIN_WINDOW)
        testScheduler.runCurrent()

        val bodies = exporter.snapshot().toList().map { it.body }
        assertAll(
            { assertEquals(QUEUE, bodies.size, "the queue must hold at most its capacity, not the whole burst") },
            // Drop-OLDEST, not drop-latest: the survivors are the newest events,
            // which are the ones a post-hoc diagnosis actually wants.
            { assertEquals(List(QUEUE) { "event ${BURST - QUEUE + it}" }, bodies) },
            {
                assertEquals(
                    (BURST - QUEUE).toLong(),
                    appender.health.value.droppedEvents,
                    "every dropped event must be counted — silent loss is the inversion #1860 was about",
                )
            },
        )
    }

    @Test
    fun anUnfilledQueueDropsNothingAndCountsNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
        val appender = CapturingAppender(capture, NoOpAppender, backgroundScope, capacity = QUEUE)

        repeat(QUEUE) { appender.log(appEvent("event $it")) }
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(QUEUE, exporter.snapshot().toList().size) },
            { assertEquals(0L, appender.health.value.droppedEvents) },
        )
    }

    /**
     * The overflow report must never re-enter capture.
     *
     * Reporting the drop through the app's own logging facade means the report is
     * emitted *while the queue is overflowing* — the exact condition that would make
     * a feedback loop self-sustaining. The report is emitted under a
     * `us.tractat.kuilt.otel.*` logger, which [LogCapture]'s self-capture exclusion
     * drops before anything is queued, so it can neither be recorded nor consume a
     * queue slot. This wires the appender into the global logging config exactly the
     * way `installLogCapture` does, so anything it logs comes straight back to it.
     */
    @Test
    fun theOverflowReportCannotReEnterCapture() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val outerFactory = KotlinLoggingConfiguration.loggerFactory
        KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
        val outerAppender = KotlinLoggingConfiguration.direct.appender
        try {
            val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
            val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
            val appender = CapturingAppender(capture, NoOpAppender, backgroundScope, capacity = QUEUE)
            KotlinLoggingConfiguration.direct.appender = appender

            repeat(BURST) { appender.log(appEvent("event $it")) }
            testScheduler.runCurrent()

            val records = exporter.snapshot().toList()
            assertAll(
                {
                    assertTrue(
                        records.none { (it.attributes[LOGGER_NAME_ATTRIBUTE] ?: "").startsWith("us.tractat.kuilt") },
                        "the overflow report must never be captured: $records",
                    )
                },
                // Exact, and the point: had the report been queued it would have
                // taken a slot and shifted both of these.
                { assertEquals(QUEUE, records.size) },
                { assertEquals((BURST - QUEUE).toLong(), appender.health.value.droppedEvents) },
            )
        } finally {
            KotlinLoggingConfiguration.direct.appender = outerAppender
            KotlinLoggingConfiguration.loggerFactory = outerFactory
        }
    }

    private companion object {
        /** A queue small enough to overflow cheaply; the production depth is [CAPTURE_QUEUE_CAPACITY]. */
        private const val QUEUE = 8

        /** Comfortably more than [QUEUE], so the drop path runs many times. */
        private const val BURST = 64

        /** Generous virtual window for [QUEUE] exports at one virtual second per store write. */
        private val DRAIN_WINDOW = 120.seconds
    }
}
