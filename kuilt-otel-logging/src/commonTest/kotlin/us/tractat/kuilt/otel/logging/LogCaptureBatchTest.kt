package us.tractat.kuilt.otel.logging

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Mapping a run of events must be one export, and must drop exactly what mapping
 * them one at a time drops — the self-capture exclusion and the level gate are
 * per-event decisions and stay that way (#2194).
 */
class LogCaptureBatchTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    private fun event(message: String, logger: String = "com.example.App", level: LogLevel = LogLevel.INFO) =
        NormalizedLogEvent(level = level, loggerName = logger, message = message, attributes = emptyMap())

    private fun capture() = LogCapture(
        WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore()),
        CaptureConfig(),
        fixedClock,
        Random(1),
    )

    /**
     * Counts [DurableStore.write] calls so "one export" is *asserted*, not merely
     * described. Nested rather than top-level: `:kuilt-otel`'s own `RecordingStore`
     * is not visible from this module's test source set, and a package-level name
     * here would collide with the batching fixtures still to land under #2194.
     *
     * Driven from a single test coroutine only — the plain `var` needs no guard.
     */
    private class WriteCountingStore : DurableStore {
        private val backing = InMemoryDurableStore()
        var writes: Int = 0
            private set

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            writes++
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    @Test
    fun aRunOfEventsBecomesOneExportInOrder() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val batchedStore = WriteCountingStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), batchedStore)
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))

        capture.captureAll(listOf(event("a"), event("b"), event("c")))

        // The same three events one at a time, as the control arm. Asserting the
        // batched run is *cheaper* rather than pinning an exact write count keeps this
        // robust to how many keys one turn touches; what must hold is that a run of
        // events no longer pays the per-turn cost once per event.
        val perEventStore = WriteCountingStore()
        val perEventCapture =
            LogCapture(WarpLogRecordExporter(ReplicaId("device-1"), perEventStore), CaptureConfig(), fixedClock, Random(1))
        listOf(event("a"), event("b"), event("c")).forEach { perEventCapture.capture(it) }

        assertAll(
            { assertEquals(listOf("a", "b", "c"), exporter.snapshot().toList().map { it.body }) },
            {
                assertTrue(
                    batchedStore.writes < perEventStore.writes,
                    "batched run wrote ${batchedStore.writes} times, per-event run ${perEventStore.writes}",
                )
            },
        )
    }

    /**
     * The self-capture exclusion is an invariant, not a filter: an exporter-owned
     * logger inside a batch must be dropped before a record is built, exactly as it is
     * on the single-event path. Capturing one would feed an eviction warn back into
     * export → evict → warn.
     */
    @Test
    fun aRunDropsTheExportersOwnLoggersAndSubMinLevelEvents() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val capture = LogCapture(exporter, CaptureConfig(minLevel = LogLevel.INFO), fixedClock, Random(1))

        capture.captureAll(
            listOf(
                event("kept"),
                event("internal", logger = "us.tractat.kuilt.otel.WarpLogRecordExporter"),
                event("too quiet", level = LogLevel.DEBUG),
            ),
        )

        assertEquals(listOf("kept"), exporter.snapshot().toList().map { it.body })
    }

    @Test
    fun aRunWithNothingToExportIsNull() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        // assertAll's lambdas are NON-suspending, so both runs are driven first and
        // only their results are asserted on inside the blocks.
        val ofNothing = capture().captureAll(emptyList())
        val ofOnlyDropped = capture().captureAll(listOf(event("internal", logger = "us.tractat.kuilt.otel.X")))

        assertAll(
            { assertNull(ofNothing) },
            { assertNull(ofOnlyDropped) },
        )
    }
}
