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

    /**
     * Stands in for the platform passthrough: keeps the burst off the test console,
     * and records what reached it.
     *
     * The recording is what makes the overflow report *observable*. The report is
     * emitted through `kotlin-logging`, so when this appender is the globally
     * installed one the report re-enters `log()` and is forwarded here — meaning a
     * report that was never emitted (or was emitted and swallowed) is visible as an
     * absence, rather than passing as a green assertion about records that were
     * never going to exist.
     */
    private class RecordingAppender : Appender {
        val logged: MutableList<KLoggingEvent> = mutableListOf()

        override fun log(loggingEvent: KLoggingEvent) {
            logged += loggingEvent
        }
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

    /**
     * Run [body] with the logging config pinned to [DirectLoggerFactory] and the
     * appender under test wired in as the global one, restoring both afterwards.
     *
     * Both halves are load-bearing, not tidiness.
     *
     * **The factory.** Overflowing the queue makes the appender emit its one-shot
     * report through `kotlin-logging`, and `KotlinLogging.logger(name)` resolves the
     * configured factory eagerly. On JVM/Android the auto-detected default is the
     * SLF4J factory, and `slf4j-api` is deliberately absent from this module's test
     * runtime — so a test that leaves the default in place either never exercises
     * the report path at all or depends on an earlier test having switched the
     * factory first. Pinning it here makes every target run the same path and makes
     * each test pass **alone**, which is how this repo tells agents to run them.
     *
     * **The appender.** Installing it globally is what routes the report back into
     * the appender that emitted it, so the re-entrancy guard is under test rather
     * than assumed.
     */
    private fun withGlobalCapture(appender: CapturingAppender, body: () -> Unit) {
        val outerFactory = KotlinLoggingConfiguration.loggerFactory
        KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
        val outerAppender = KotlinLoggingConfiguration.direct.appender
        KotlinLoggingConfiguration.direct.appender = appender
        try {
            body()
        } finally {
            KotlinLoggingConfiguration.direct.appender = outerAppender
            KotlinLoggingConfiguration.loggerFactory = outerFactory
        }
    }

    @Test
    fun aDrainSlowerThanItsProducerCannotGrowTheQueueWithoutBound() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(
            ReplicaId("device-1"),
            SlowStore(InMemoryDurableStore(), writeCost = 1.seconds),
        )
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
        val delegate = RecordingAppender()
        val appender = CapturingAppender(capture, delegate, backgroundScope, capacity = QUEUE)

        withGlobalCapture(appender) {
            // The whole burst lands before the drain gets a single turn: log() is
            // synchronous and never suspends, so this is precisely "producer faster
            // than drain", with no dependence on scheduling luck.
            repeat(BURST) { appender.log(appEvent("event $it")) }

            // Bounded advance — the drain re-arms nothing, but advanceUntilIdle() is
            // banned repo-wide and a bounded window is the honest way to say "let the
            // slow drain finish the events it actually still holds".
            testScheduler.advanceTimeBy(DRAIN_WINDOW)
            testScheduler.runCurrent()
        }

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
        val delegate = RecordingAppender()
        val appender = CapturingAppender(capture, delegate, backgroundScope, capacity = QUEUE)

        withGlobalCapture(appender) {
            repeat(QUEUE) { appender.log(appEvent("event $it")) }
            testScheduler.runCurrent()
        }

        assertAll(
            { assertEquals(QUEUE, exporter.snapshot().toList().size) },
            { assertEquals(0L, appender.health.value.droppedEvents) },
            // No drops means no report: the one-shot warn is not emitted at all.
            { assertEquals(QUEUE, delegate.logged.size) },
            { assertTrue(delegate.logged.none { it.loggerName.startsWith(KUILT_PREFIX) }) },
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
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
        val delegate = RecordingAppender()
        val appender = CapturingAppender(capture, delegate, backgroundScope, capacity = QUEUE)

        withGlobalCapture(appender) {
            repeat(BURST) { appender.log(appEvent("event $it")) }
            testScheduler.runCurrent()
        }

        val records = exporter.snapshot().toList()
        val reports = delegate.logged.filter { it.loggerName.startsWith(KUILT_PREFIX) }
        assertAll(
            // First: the report really happened and really came back through log().
            // Without this the rest would pass just as well if it were never emitted,
            // or emitted and swallowed — an assertion about records that were never
            // going to exist.
            { assertEquals(1, reports.size, "expected exactly one overflow report, got: $reports") },
            { assertEquals(OVERFLOW_LOGGER, reports.single().loggerName) },
            // …and having come back through log(), it was excluded from capture
            // rather than recorded.
            {
                assertTrue(
                    records.none { (it.attributes[LOGGER_NAME_ATTRIBUTE] ?: "").startsWith(KUILT_PREFIX) },
                    "the overflow report must never be captured: $records",
                )
            },
            // Exact, and the point: had the report been queued it would have
            // taken a slot and shifted both of these.
            { assertEquals(QUEUE, records.size) },
            { assertEquals((BURST - QUEUE).toLong(), appender.health.value.droppedEvents) },
        )
    }

    /**
     * Batching amortises the drain; it does not make the queue unbounded. A burst
     * larger than the queue still drops the oldest and still counts every drop — the
     * bound is the queue's, not the drain's, and #2194 must not have quietly moved it.
     *
     * Driven at a batch cap **below** and **above** the queue depth, which is the part
     * [aDrainSlowerThanItsProducerCannotGrowTheQueueWithoutBound] cannot say on its
     * own: the survivor set and the drop count must be *independent* of how much of
     * the queue one turn swallows. A drain allowed to take the whole queue at once
     * must not thereby rescue events the channel already dropped at `log()` time, and
     * a drain capped below the queue must not drop any extra.
     */
    @Test
    fun batchingTheDrainDoesNotWidenTheQueue() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        fun burstThrough(maxBatchSize: Int): Pair<List<String?>, Long> {
            val exporter = WarpLogRecordExporter(
                ReplicaId("device-1"),
                SlowStore(InMemoryDurableStore(), writeCost = 1.seconds),
            )
            val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
            val appender = CapturingAppender(
                capture,
                RecordingAppender(),
                backgroundScope,
                capacity = QUEUE,
                maxBatchSize = maxBatchSize,
            )

            withGlobalCapture(appender) {
                repeat(BURST) { appender.log(appEvent("event $it")) }
                testScheduler.advanceTimeBy(DRAIN_WINDOW)
                testScheduler.runCurrent()
            }
            return exporter.snapshot().toList().map { it.body } to appender.health.value.droppedEvents
        }

        val (belowCapBodies, belowCapDropped) = burstThrough(maxBatchSize = BATCH_BELOW_QUEUE)
        val (aboveCapBodies, aboveCapDropped) = burstThrough(maxBatchSize = QUEUE * BATCH_ABOVE_QUEUE_FACTOR)

        val survivors = List(QUEUE) { "event ${BURST - QUEUE + it}" }
        assertAll(
            { assertEquals(survivors, belowCapBodies, "a batch cap below the queue must not drop anything extra") },
            { assertEquals((BURST - QUEUE).toLong(), belowCapDropped) },
            { assertEquals(survivors, aboveCapBodies, "a batch cap above the queue must not rescue a dropped event") },
            { assertEquals((BURST - QUEUE).toLong(), aboveCapDropped) },
        )
    }

    private companion object {
        /** A queue small enough to overflow cheaply; the production depth is [CAPTURE_QUEUE_CAPACITY]. */
        private const val QUEUE = 8

        /** A batch cap forcing several turns to swallow one full queue. */
        private const val BATCH_BELOW_QUEUE = 2

        /** Multiplied by [QUEUE] for a cap the whole queue fits inside, as production's does. */
        private const val BATCH_ABOVE_QUEUE_FACTOR = 4

        /** Comfortably more than [QUEUE], so the drop path runs many times. */
        private const val BURST = 64

        /** The logger the overflow report is emitted under — inside the exclusion prefix. */
        private const val OVERFLOW_LOGGER = "us.tractat.kuilt.otel.logging.CapturingAppender"

        /** Nothing under kuilt's namespace may be captured back into the buffer. */
        private const val KUILT_PREFIX = "us.tractat.kuilt"

        /** Generous virtual window for [QUEUE] exports at one virtual second per store write. */
        private val DRAIN_WINDOW = 120.seconds
    }
}
