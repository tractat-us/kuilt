package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Eviction and refusal must stay **counted** even though they stopped being **logged** per
 * record.
 *
 * The per-eviction `warn` was written when eviction was exceptional. At the production
 * cap the buffer is full permanently, so every record evicts one and the line became a
 * per-record narration of normal operation — measured inside the ~58% of the export path
 * that is neither CRDT copying nor sequence recomputation. Removing it is only safe if
 * the loss stays visible somewhere cheaper, which is what these pin.
 */
class WarpLogRecordExporterDropAccountingTest {

    private fun record(n: Int) = LogRecord(
        recordId = ByteString(ByteArray(RECORD_ID_BYTES) { n.toByte() }),
        severityNumber = 9,
        severityText = "INFO",
        body = "event $n",
        attributes = emptyMap(),
        timestampEpochNanos = n.toLong(),
        observedEpochNanos = n.toLong(),
    )

    private fun records(count: Int) = List(count) { record(it) }

    @Test
    fun everyEvictionIsCountedUnderDropOldest() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore(), maxRecords = CAP)
        exporter.export(records(CAP + OVERFLOW))

        assertAll(
            { assertEquals(CAP, exporter.snapshot().toList().size) },
            {
                assertEquals(
                    OVERFLOW.toLong(),
                    exporter.health.value.dropped,
                    "every evicted record must be counted",
                )
            },
            { assertEquals(0L, exporter.health.value.refused, "DROP_OLDEST never refuses") },
        )
    }

    @Test
    fun everyRefusalIsCountedUnderDropNewest() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(
            ReplicaId("device-1"),
            InMemoryDurableStore(),
            maxRecords = CAP,
            bufferPolicy = BufferPolicy.DROP_NEWEST,
        )
        exporter.export(records(CAP + OVERFLOW))

        assertAll(
            { assertEquals(OVERFLOW.toLong(), exporter.health.value.refused) },
            { assertEquals(0L, exporter.health.value.dropped, "DROP_NEWEST never evicts") },
        )
    }

    @Test
    fun aBufferThatNeverFillsCountsNeither() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore(), maxRecords = CAP)
        exporter.export(records(CAP))

        assertAll(
            { assertEquals(0L, exporter.health.value.dropped) },
            { assertEquals(0L, exporter.health.value.refused) },
            { assertEquals(0, exporter.dropSummariesEmitted, "nothing dropped, so nothing to announce") },
        )
    }

    /**
     * The **first** drop must be announced, not the [DROP_REPORT_INTERVAL]-th.
     *
     * This is the whole reason `lastDropReport` seeds at `-1` rather than `0`: at `0` the first
     * bucket compares equal to the seed, so an exporter that drops fewer than one interval — 5
     * here, against an interval of 10,000 — would emit **nothing, ever**, leaving an operator who
     * never polls `health` with no evidence of loss. That is the silent-loss inversion (#1860) the
     * summary line exists to prevent.
     *
     * Asserting on the seed would not catch it (`lastDropReport == 0` after one drop under *both*
     * seeds), and the line itself is unobservable from this module — so this counts emissions.
     * Mutate the seed to `0L` and this is the test that reddens.
     */
    @Test
    fun theFirstDropIsAnnouncedEvenWellBelowTheReportInterval() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore(), maxRecords = CAP)
        exporter.export(records(CAP + OVERFLOW))

        assertAll(
            { assertEquals(OVERFLOW.toLong(), exporter.health.value.dropped, "fixture must drop") },
            {
                assertEquals(
                    1,
                    exporter.dropSummariesEmitted,
                    "the first drop announces itself, and the next $OVERFLOW share its bucket",
                )
            },
        )
    }

    private companion object {
        private const val RECORD_ID_BYTES = 8
        private const val CAP = 8
        private const val OVERFLOW = 5
    }
}
