package us.tractat.kuilt.otel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins that [WarpLogRecordExporter] never reports `Success` for a record no key holds (#3350).
 *
 * A turn admits a record into the in-memory log and the active segment **before** its durable
 * write. When that turn ends without the write landing — the store refuses it, or the caller is
 * cancelled while the [AppliedOpSink] is still being told — the record is in memory alone. The
 * next turn that touches the same record is a dedup hit (a retried export) or a merge that adds
 * nothing (a peer echoing it back), and both used to return `Success` without writing anything.
 * A restart then loses a record the exporter had acknowledged.
 *
 * Every arm restarts from the store, because the in-memory log holds the record either way: only
 * the store can tell "durable" from "remembered".
 */
class WarpLogRecordExporterExportDurabilityTest {

    private val replica = ReplicaId("A")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporterFor(store: DurableStore, sink: AppliedOpSink = AppliedOpSink.Discarding) =
        WarpLogRecordExporter(replica = replica, store = store, maxRecords = 1_000, segmentOps = 64, appliedOps = sink)

    private suspend fun bodiesAfterRestart(store: DurableStore): List<String> =
        exporterFor(store).also { it.recover() }.snapshot().toList().map { it.body }

    /**
     * A sink that parks the first publication it is armed for until its caller is cancelled, and
     * counts both halves, so an arm can assert that the cancellation it relies on really landed
     * inside `publishApplied` rather than inferring it from the outcome.
     */
    private class ParkingSink : AppliedOpSink {
        var armed = false
        var parked = 0
        var cancelled = 0

        override suspend fun published(ops: List<RgaOp<LogRecord>>) {
            if (!armed) return
            armed = false
            parked++
            try {
                CompletableDeferred<Unit>().await()
            } catch (cause: CancellationException) {
                cancelled++
                throw cause
            }
        }
    }

    /** Export [id] in a child job, park it in [sink], cancel it there, and wait for it to finish. */
    private suspend fun TestScope.exportCancelledInPublish(exporter: WarpLogRecordExporter, sink: ParkingSink, id: Int) {
        sink.armed = true
        var returned: ExportResult? = null
        val job = launch { returned = exporter.export(record(id)) }
        runCurrent()
        job.cancelAndJoin()
        assertNull(returned, "the cancelled export returned instead of being cancelled: $returned")
    }

    // ---- Path A: a refused export, then a retry ----

    @Test
    fun aRetryOfARefusedExportIsDurableBeforeItReportsSuccess() = runTest {
        val backing = RecordingStore()
        val store = WriteRefusingStore(backing)
        val exporter = exporterFor(store)
        val first = exporter.export(record(0))

        store.refuseWrites()
        val refused = exporter.export(record(1))
        val refusals = store.refusedWrites()
        store.allowWrites()
        val retried = exporter.export(record(1))

        val bodies = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(ExportResult.Success, first) },
            { assertEquals(1, refusals, "the rig refused $refusals writes, not exactly the one it exists to refuse") },
            { assertTrue(refused is ExportResult.Failure, "the refused export reported $refused") },
            { assertEquals(ExportResult.Success, retried) },
            { assertEquals(listOf("body-0", "body-1"), bodies, "the retry reported Success over a lost record") },
        )
    }

    // ---- Path B: a cancellation while the sink is being told ----

    @Test
    fun aRetryOfAnExportCancelledInItsSinkIsDurableBeforeItReportsSuccess() = runTest {
        val backing = RecordingStore()
        val sink = ParkingSink()
        val exporter = exporterFor(backing, sink)
        val first = exporter.export(record(0))

        exportCancelledInPublish(exporter, sink, id = 1)
        val retried = exporter.export(record(1))

        val bodies = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(ExportResult.Success, first) },
            { assertEquals(1, sink.parked, "the rig never parked an export in its sink") },
            { assertEquals(1, sink.cancelled, "the cancellation never landed inside the sink") },
            { assertEquals(ExportResult.Success, retried) },
            { assertEquals(listOf("body-0", "body-1"), bodies, "the retry reported Success over a lost record") },
        )
    }

    @Test
    fun aNoOpMergeAfterAnExportCancelledInItsSinkStillWritesTheRecord() = runTest {
        // A peer echoes back the record this exporter never wrote. The merge adds nothing to the
        // log, so it spends no segment; it must still put the record on disk before Success.
        val backing = RecordingStore()
        val sink = ParkingSink()
        val exporter = exporterFor(backing, sink)
        val first = exporter.export(record(0))

        exportCancelledInPublish(exporter, sink, id = 1)
        val echo = exporter.merge(exporter.snapshot())

        val bodies = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(ExportResult.Success, first) },
            { assertEquals(1, sink.parked, "the rig never parked an export in its sink") },
            { assertEquals(1, sink.cancelled, "the cancellation never landed inside the sink") },
            { assertEquals(ExportResult.Success, echo) },
            { assertEquals(listOf("body-0", "body-1"), bodies, "the echoed merge reported Success over a lost record") },
        )
    }

    // ---- A cancellation the sink mints is the sink's failure, not the caller's ----

    @Test
    fun aCancellationTheSinkMintsDoesNotFailTheExport() = runTest {
        // `AppliedOpSink`'s contract: a sink that throws does not fail the export. A sink whose own
        // `withTimeout` expired throws a CancellationException at a caller whose job is alive; that
        // must not skip the durable write, and must not escape an export that never throws.
        val backing = RecordingStore()
        var minted = 0
        val sink = AppliedOpSink {
            minted++
            throw CancellationException("minted by the sink, not by the caller")
        }
        val exporter = exporterFor(backing, sink)

        val result = try {
            exporter.export(record(0))
        } catch (escaped: CancellationException) {
            // Our own job is alive, so this falls through and records what escaped.
            currentCoroutineContext().ensureActive()
            ExportResult.Failure(escaped)
        }

        val bodies = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(1, minted, "the rig minted $minted cancellations, not one") },
            { assertEquals(ExportResult.Success, result, "a sink's own cancellation escaped the export") },
            { assertEquals(listOf("body-0"), bodies, "a sink's own cancellation skipped the durable write") },
        )
    }

    // ---- The control: nothing pending, nothing written ----

    @Test
    fun aRetryOfAnExportThatLandedWritesNothing() = runTest {
        // Without this, "write whenever a dedup hit happens" would pass every arm above.
        val store = RecordingStore()
        val exporter = exporterFor(store)
        val first = exporter.export(record(0))
        val writesAfterFirst = store.writes().size

        val retried = exporter.export(record(0))
        val echo = exporter.merge(exporter.snapshot())

        assertAll(
            { assertEquals(ExportResult.Success, first) },
            { assertTrue(writesAfterFirst > 0, "the first export never wrote, so this control measures nothing") },
            { assertEquals(ExportResult.Success, retried) },
            { assertEquals(ExportResult.Success, echo) },
            { assertEquals(writesAfterFirst, store.writes().size, "a retry of a durable record wrote again") },
        )
    }
}
