package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that an export-only log exporter stays bounded when a turn's evictions empty the buffer
 * (#3522).
 *
 * At `maxRecords = 1` every export evicts the one visible record, and at any cap a turn as large
 * as the buffer evicts all of it. The arrival used to be inserted after `RgaId.HEAD` then, and
 * HEAD's children sort newest first, so the newest record sorted **ahead of** every tombstone. The
 * window is cut in list order, walking back from the end, so with the only visible records at the
 * front its cut landed at 0: no pass dropped anything, the floor stayed at 0, and the store gained
 * a segment key every few exports for as long as the process ran.
 *
 * Each arm exports [EXPORTS] records and reads the store at three marks. The `cap = 2, batch = 1`
 * arm is the control: its evictions never empty the buffer, so it was flat before the fix too, and
 * a harness that reads every arm as growing is broken rather than the exporter.
 */
class WarpLogRecordExporterSmallCapTest {

    private val replica = ReplicaId("A")

    private fun record(id: Int) = LogRecord(
        recordId = ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() }),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private class Mark(val keys: Int, val floor: Long, val seq: Long)

    private class Run(val marks: List<Mark>, val visible: List<LogRecord>, val dropped: Long)

    private suspend fun run(maxRecords: Int, batch: Int, segmentOps: Int = SEGMENT_OPS): Run {
        val store = RecordingStore()
        val exporter = WarpLogRecordExporter(replica, store, maxRecords = maxRecords, segmentOps = segmentOps)
        val marks = mutableListOf<Mark>()
        (0 until EXPORTS).chunked(batch).forEach { ids ->
            exporter.export(ids.map(::record))
            if ((ids.last() + 1) % MARK_EVERY == 0) {
                val log = exporter.snapshot()
                marks += Mark(
                    keys = store.keys().count { it.startsWith("otel.logs.seg.") },
                    floor = log.compactedBelow[replica],
                    seq = log.seqHighWater(replica),
                )
            }
        }
        return Run(marks, exporter.snapshot().toList(), exporter.health.value.dropped)
    }

    private fun assertBounded(maxRecords: Int, batch: Int, run: Run) {
        val where = "maxRecords=$maxRecords batch=$batch"
        val (first, _, last) = run.marks
        assertAll(
            // The rig: every record past the cap was evicted, and the window holds the newest.
            { assertEquals(3, run.marks.size, "$where: expected a reading at each of 3 marks") },
            { assertEquals((EXPORTS - maxRecords).toLong(), run.dropped, "$where: evictions") },
            {
                assertEquals(
                    (EXPORTS - maxRecords until EXPORTS).map(::record),
                    run.visible,
                    "$where: the window must hold the newest $maxRecords records",
                )
            },
            {
                assertTrue(
                    last.keys <= first.keys,
                    "$where: segment keys grew with records exported: " +
                        run.marks.joinToString(" -> ") { it.keys.toString() },
                )
            },
            {
                assertTrue(
                    first.floor > 0 && last.floor > first.floor,
                    "$where: the floor did not advance: " + run.marks.joinToString(" -> ") { it.floor.toString() },
                )
            },
            {
                assertTrue(
                    last.floor >= last.seq - 2 * maxRecords,
                    "$where: the floor stopped at ${last.floor} of seq ${last.seq}",
                )
            },
        )
    }

    @Test
    fun aCapOfOneStaysBoundedOnTheExportPath() = runTest {
        assertBounded(maxRecords = 1, batch = 1, run = run(maxRecords = 1, batch = 1))
    }

    @Test
    fun aTurnThatEvictsAWholeBufferOfTwoStaysBounded() = runTest {
        assertBounded(maxRecords = 2, batch = 2, run = run(maxRecords = 2, batch = 2, segmentOps = DEFAULT_LOG_SEGMENT_OPS))
    }

    @Test
    fun aCapOfTwoFedOneAtATimeStaysBounded() = runTest {
        assertBounded(maxRecords = 2, batch = 1, run = run(maxRecords = 2, batch = 1))
    }

    private companion object {
        const val EXPORTS = 1_500
        const val MARK_EVERY = 500

        /** Small, so the store rolls a segment every few exports and growth shows in keys. */
        const val SEGMENT_OPS = 8
    }
}
