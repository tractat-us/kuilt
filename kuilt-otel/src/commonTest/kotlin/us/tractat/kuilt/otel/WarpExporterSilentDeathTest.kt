package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression cover for the **silent death** failure mode (#1860).
 *
 * A device accumulated a multi-megabyte on-device telemetry store and then
 * stopped accepting records entirely — no crash, no exception surfaced, no log
 * line, and the store file's mtime never advanced again across several process
 * launches. Swapping an empty store in restored it immediately; swapping the
 * large one back killed it again.
 *
 * The mechanism pinned here reproduces exactly that signature: only the *decode*
 * inside `recover()` was guarded, so a [DurableStore] whose [DurableStore.read]
 * throws propagates out of `recover()`. The consumer awaits `recover()` before
 * installing log capture, so capture is never installed — zero records, file
 * never touched, nothing logged, deterministically on every launch.
 */
class WarpExporterSilentDeathTest {

    private val replica = ReplicaId("A")

    private fun record(id: Byte) = LogRecord(
        recordId = ByteString(ByteArray(8) { id }),
        body = "log message",
        severityNumber = 9,
        observedEpochNanos = 1_000L + id,
    )

    /**
     * A store whose [read] always throws but whose [write] works.
     *
     * This is the shape the field failure took: the accumulated entry could not
     * be read back, while the device's storage was otherwise perfectly healthy.
     * Writes are retained so a test can prove the exporter is still *usable*
     * after a failed recovery, not merely that recovery failed to throw.
     */
    private class ReadFailsStore : DurableStore {
        val written: MutableMap<StoreKey, ByteArray> = mutableMapOf()
        var reads: Int = 0

        override suspend fun read(key: StoreKey): ByteArray? {
            reads++
            throw IllegalStateException("simulated unreadable store entry for ${key.name}")
        }

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            written[key] = bytes
        }

        override suspend fun delete(key: StoreKey) {
            written.remove(key)
        }
    }

    @Test
    fun logRecoverSurvivesAThrowingStoreRead() = runTest {
        val store = ReadFailsStore()
        val exporter = WarpLogRecordExporter(replica = replica, store = store)

        // Must not propagate: the caller installs log capture after awaiting this.
        exporter.recover()

        assertEquals(1, store.reads, "recover() should still have attempted the read")
    }

    @Test
    fun logExportStillWorksAfterAnUnreadableStore() = runTest {
        val store = ReadFailsStore()
        val exporter = WarpLogRecordExporter(replica = replica, store = store)
        exporter.recover()

        // The device's storage is healthy — only the accumulated entry was
        // unreadable. Capture must degrade to "start fresh", not to "dead".
        val result = exporter.export(record(1))

        assertAll(
            { assertEquals(ExportResult.Success, result) },
            { assertEquals(1, exporter.snapshot().toList().size) },
            { assertTrue(StoreKey("otel.logs.seg.0") in store.written, "the record should have been flushed") },
        )
    }

    @Test
    fun spanRecoverSurvivesAThrowingStoreRead() = runTest {
        val exporter = WarpSpanExporter(replica = replica, store = ReadFailsStore())
        exporter.recover()
    }

    @Test
    fun metricRecoverSurvivesAThrowingStoreRead() = runTest {
        val exporter = WarpMetricExporter(replica = replica, store = ReadFailsStore())
        exporter.recover()
    }

    @Test
    fun causalClockRecoverSurvivesAThrowingStoreRead() = runTest {
        WarpCausalClock(replica).recover(ReadFailsStore())
    }

    /**
     * The whole-surface path, and the reason the sibling exporters are fixed in
     * the same change: [WarpTelemetry.recover] drives the four recoveries
     * **sequentially with logs last**, so an unguarded throw from the causal
     * clock, the spans or the metrics kills log recovery before it is reached —
     * defeating a fix applied only to [WarpLogRecordExporter].
     */
    @Test
    fun telemetryRecoverSurvivesAThrowingStoreRead() = runTest {
        val telemetry = WarpTelemetry(replica = replica, store = ReadFailsStore())

        telemetry.recover()

        assertEquals(ExportResult.Success, telemetry.logs.export(record(1)))
    }
}
