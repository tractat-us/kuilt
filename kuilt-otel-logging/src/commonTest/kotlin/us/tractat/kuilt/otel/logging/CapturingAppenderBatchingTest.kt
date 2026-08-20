package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The drain must export what is **already queued** as one turn, not one turn per
 * event (#2194).
 *
 * Capturing one log line cost two durable file writes; a burst of N lines cost ~N,
 * which is why `CapturingAppender` had to warn that "the exporter is draining slower
 * than this application logs". These pin the amortisation, and pin that it is
 * opportunistic — a lone line on an idle app is still exported immediately, so no
 * durability window is introduced.
 *
 * ## Why every amortisation test here carries a control arm
 *
 * The batching claim is invisible to any assertion about *output*: the records, their
 * order and their count are identical whether the drain batches or loops one export
 * per event. So each test below runs the same burst through **both** shapes and
 * asserts the batched arm is strictly cheaper, rather than comparing the batched arm
 * against a hand-picked number that describes today's implementation and pins nothing.
 */
class CapturingAppenderBatchingTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    /** Counts what reaches the store — the direct measurement #2194 is about. */
    private class CountingStore(private val delegate: DurableStore = InMemoryDurableStore()) : DurableStore {
        var writes: Int = 0
            private set

        override suspend fun read(key: StoreKey): ByteArray? = delegate.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            writes++
            delegate.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = delegate.delete(key)
    }

    /** Stands in for the platform passthrough, keeping the burst off the test console. */
    private class RecordingAppender : Appender {
        val logged: MutableList<KLoggingEvent> = mutableListOf()

        override fun log(loggingEvent: KLoggingEvent) {
            logged += loggingEvent
        }
    }

    private fun appEvent(message: String) = KLoggingEvent(
        level = Level.INFO,
        marker = null,
        loggerName = LOGGER_NAME,
        message = message,
        timestamp = 0L,
    )

    /**
     * The queued form of [appEvent] — what `CapturingAppender.normalize()` produces —
     * so a control arm can drive `LogCapture.capture` with the same events the drain
     * would have handed it.
     */
    private fun queuedEvent(message: String) = NormalizedLogEvent(
        level = LogLevel.INFO,
        loggerName = LOGGER_NAME,
        message = message,
        attributes = emptyMap(),
    )

    private fun exporterOver(store: DurableStore, segmentOps: Int = DEFAULT_SEGMENT_OPS) =
        WarpLogRecordExporter(ReplicaId("device-1"), store, segmentOps = segmentOps)

    private fun captureInto(exporter: WarpLogRecordExporter) =
        LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))

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

    /**
     * The headline, and the only end-to-end pin of #2194's claim. `log()` never
     * suspends, so the whole burst is queued before the drain gets a turn — which is
     * precisely the overload condition the batch exists for, reached without any
     * dependence on scheduling luck.
     *
     * The control arm is the drain body this replaces, verbatim:
     * `for (event in events) capture.capture(event)`, over the same events, into an
     * identically configured exporter. Both arms produce the same records in the same
     * order, so the write count is the *only* thing that can tell them apart — which
     * is exactly why an assertion on the batched arm alone would prove nothing.
     */
    @Test
    fun aBurstIsDrainedAsOneTurnRatherThanOnePerEvent() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val perEventStore = CountingStore()
        val perEventExporter = exporterOver(perEventStore, segmentOps = SEGMENT_OPS)
        val perEventCapture = captureInto(perEventExporter)
        List(BURST) { queuedEvent("event $it") }.forEach { event -> perEventCapture.capture(event) }

        val batchedStore = CountingStore()
        val exporter = exporterOver(batchedStore, segmentOps = SEGMENT_OPS)
        val appender = CapturingAppender(captureInto(exporter), RecordingAppender(), backgroundScope, capacity = QUEUE)

        withGlobalCapture(appender) {
            repeat(BURST) { appender.log(appEvent("event $it")) }
            testScheduler.runCurrent()
        }

        val bodies = exporter.snapshot().toList().map { it.body }
        assertAll(
            { assertEquals(List(BURST) { "event $it" }, bodies, "every event must still be exported, in order") },
            {
                assertEquals(
                    perEventExporter.snapshot().toList().map { it.body },
                    bodies,
                    "both arms must produce the same records — which is why the write count is the discriminator",
                )
            },
            { assertEquals(0L, appender.health.value.droppedEvents, "the queue is deep enough for this burst") },
            {
                assertTrue(
                    batchedStore.writes * MIN_AMORTISATION <= perEventStore.writes,
                    "a burst of $BURST drained as turns must cost at least ${MIN_AMORTISATION}x fewer store " +
                        "writes than the same burst exported one event at a time; " +
                        "batched=${batchedStore.writes} perEvent=${perEventStore.writes}",
                )
            },
            {
                // Guards the control arm itself: an arm that had somehow become cheap
                // would make the ratio above pass for the wrong reason.
                assertTrue(
                    perEventStore.writes >= BURST,
                    "the per-event arm must pay at least one write per event, or it measures nothing; " +
                        "got ${perEventStore.writes}",
                )
            },
        )
    }

    /**
     * The other half of "opportunistic": with nothing else queued, one line is one
     * export, *durably*, before any time is advanced. No timer holds it back, so the
     * durability contract is unchanged — which is the whole reason this is not OTel's
     * `BatchLogRecordProcessor`.
     *
     * The store write is asserted, not just the snapshot: the snapshot is in-memory
     * state, and a design that buffered the line for a later flush would satisfy it.
     */
    @Test
    fun aLoneEventIsExportedWithoutWaitingForCompany() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = CountingStore()
        val exporter = exporterOver(store)
        val appender = CapturingAppender(captureInto(exporter), RecordingAppender(), backgroundScope, capacity = QUEUE)

        withGlobalCapture(appender) {
            appender.log(appEvent("alone"))
            testScheduler.runCurrent()
        }

        assertAll(
            { assertEquals(listOf("alone"), exporter.snapshot().toList().map { it.body }) },
            { assertTrue(store.writes > 0, "a lone line must reach the store immediately, with no flush window") },
        )
    }

    /**
     * A batch is capped, so one turn's memory and one segment write stay bounded.
     *
     * The cap is likewise invisible in the output — every event arrives either way —
     * so the uncapped drain over the same burst is the control arm. A capped drain
     * must pay *more* store writes, because it takes more turns to swallow the same
     * queue; an ignored `maxBatchSize` would make the two arms identical.
     */
    @Test
    fun aBurstBiggerThanTheCapIsDrainedInSeveralTurns() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val uncappedStore = CountingStore()
        val uncappedExporter = exporterOver(uncappedStore)
        val uncapped =
            CapturingAppender(captureInto(uncappedExporter), RecordingAppender(), backgroundScope, capacity = QUEUE)

        withGlobalCapture(uncapped) {
            repeat(BURST) { uncapped.log(appEvent("event $it")) }
            testScheduler.runCurrent()
        }

        val cappedStore = CountingStore()
        val cappedExporter = exporterOver(cappedStore)
        val capped = CapturingAppender(
            captureInto(cappedExporter),
            RecordingAppender(),
            backgroundScope,
            capacity = QUEUE,
            maxBatchSize = SMALL_BATCH,
        )

        withGlobalCapture(capped) {
            repeat(BURST) { capped.log(appEvent("event $it")) }
            testScheduler.runCurrent()
        }

        assertAll(
            { assertEquals(List(BURST) { "event $it" }, cappedExporter.snapshot().toList().map { it.body }) },
            {
                assertTrue(
                    cappedStore.writes > uncappedStore.writes,
                    "a maxBatchSize of $SMALL_BATCH must split a burst of $BURST into more turns than an " +
                        "uncapped drain; capped=${cappedStore.writes} uncapped=${uncappedStore.writes}",
                )
            },
            {
                assertTrue(
                    cappedStore.writes >= (BURST + SMALL_BATCH - 1) / SMALL_BATCH,
                    "a capped drain owes at least one segment write per turn; got ${cappedStore.writes}",
                )
            },
        )
    }

    @Test
    fun everyEventInABurstSurvivesInOrder() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = exporterOver(InMemoryDurableStore())
        val appender = CapturingAppender(captureInto(exporter), RecordingAppender(), backgroundScope, capacity = QUEUE)

        withGlobalCapture(appender) {
            repeat(BURST) { appender.log(appEvent("event $it")) }
            testScheduler.runCurrent()
        }

        assertEquals(List(BURST) { "event $it" }, exporter.snapshot().toList().map { it.body })
    }

    private companion object {
        private const val LOGGER_NAME = "com.example.App"

        /** Deep enough that the burst below never overflows — drops are the other suite's subject. */
        private const val QUEUE = 128

        /** Small enough for wasmJs (#2183); large enough that per-event writes are obvious. */
        private const val BURST = 40

        /** Small enough that a [BURST]-record turn is split, so the turn-splitting path runs. */
        private const val SEGMENT_OPS = 32

        /** Big enough that [BURST] never rolls a segment, so only the batch cap moves the write count. */
        private const val DEFAULT_SEGMENT_OPS = 256

        /** Forces several turns for [BURST]. */
        private const val SMALL_BATCH = 7

        /**
         * The floor the batched path must beat. Deliberately far below the ratio a
         * production `segmentOps` gives: this asserts the *shape* (per-turn, not
         * per-event) on a deliberately tiny segment, not a tuning number.
         */
        private const val MIN_AMORTISATION = 4
    }
}
