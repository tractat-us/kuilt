package us.tractat.kuilt.otel

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Retirement must not spend suppression supplied only by an unacknowledged merge. */
class WarpLogRecordExporterDurableSuppressionTest {
    private enum class Outcome { REFUSED, CANCELLED_BEFORE_WRITE, CANCELLED_AFTER_WRITE, ACKNOWLEDGED, RECOVERED }

    @Test
    fun refusedCompactCannotAuthorizeRetirement() = scenario(Outcome.REFUSED, floor = false)

    @Test
    fun cancelledCompactBeforeWriteCannotAuthorizeRetirement() = scenario(Outcome.CANCELLED_BEFORE_WRITE, floor = false)

    @Test
    fun cancelledCompactAfterWriteCannotAuthorizeRetirement() = scenario(Outcome.CANCELLED_AFTER_WRITE, floor = false)

    @Test
    fun acknowledgedCompactAllowsReclamation() = scenario(Outcome.ACKNOWLEDGED, floor = false)

    @Test
    fun recoveredCompactAllowsReclamation() = scenario(Outcome.RECOVERED, floor = false)

    @Test
    fun refusedFloorCanBeCoveredByLaterWindowWrite() = scenario(Outcome.REFUSED, floor = true)

    @Test
    fun cancelledFloorBeforeWriteCanBeCoveredByLaterWindowWrite() = scenario(Outcome.CANCELLED_BEFORE_WRITE, floor = true)

    @Test
    fun cancelledFloorAfterWriteCanBeCoveredByLaterWindowWrite() = scenario(Outcome.CANCELLED_AFTER_WRITE, floor = true)

    @Test
    fun acknowledgedFloorAllowsReclamation() = scenario(Outcome.ACKNOWLEDGED, floor = true)

    @Test
    fun recoveredFloorAllowsReclamation() = scenario(Outcome.RECOVERED, floor = true)

    private fun scenario(outcome: Outcome, floor: Boolean) = runTest {
        val store = RecordingStore()
        val refusing = WriteRefusingStore(store) { it.name.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }
        val parking = CancelDuringSegmentWriteStore(store, landFirst = outcome == Outcome.CANCELLED_AFTER_WRITE)
        val storage = if (outcome == Outcome.REFUSED) refusing else parking
        var exporter = exporter(storage)
        val foreign = ReplicaId("foreign")
        val (stale, insert) = Rga.empty<LogRecord>().insertAfter(foreign, RgaId.HEAD, record(1))
        val removed = requireNotNull(stale.removeAt(0)).first
        assertEquals(ExportResult.Success, exporter.merge(removed))
        val original = decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST))).sealedSegments.single()
        val compacted = requireNotNull(removed.dropWindow(if (floor) foreign else ReplicaId("peer"), setOf(insert.id))).first
        assertTrue(
            if (floor) compacted.compactedBelow.contains(insert.id.dot) else insert.id in compacted.compactedIds,
            "precondition: remote must carry the selected suppressor",
        )

        when (outcome) {
            Outcome.REFUSED -> {
                refusing.refuseWrites()
                assertTrue(exporter.merge(compacted) is ExportResult.Failure)
                assertEquals(1, refusing.refusedWrites(), "the adopted segment write must be refused")
                refusing.allowWrites()
            }
            Outcome.CANCELLED_BEFORE_WRITE, Outcome.CANCELLED_AFTER_WRITE -> {
                parking.arm()
                val merge = launch { exporter.merge(compacted) }
                parking.parked.await()
                merge.cancel()
                merge.join()
                assertAll(
                    { assertTrue(merge.isCancelled) },
                    { assertEquals(1, parking.cancelledWrites(), "cancellation must reach the adopted write") },
                    { assertEquals(if (outcome == Outcome.CANCELLED_AFTER_WRITE) 1 else 0, parking.landedWrites()) },
                )
            }
            Outcome.ACKNOWLEDGED, Outcome.RECOVERED -> {
                assertEquals(ExportResult.Success, exporter.merge(compacted))
                if (outcome == Outcome.RECOVERED) {
                    exporter = exporter(storage)
                    exporter.recover()
                }
            }
        }
        // Local churn runs window passes after the failed turn, with healthy writes. None of
        // those passes sees the foreign id: the live merge has already purged it from log.
        repeat(12) { assertEquals(ExportResult.Success, exporter.export(record(100 + it))) }
        val originalRetained = segmentKeyForTest(original) in store.keys()
        val restarted = exporter(store, maxRecords = 100)
        restarted.recover()
        assertEquals(ExportResult.Success, restarted.merge(stale))
        val resurrected = restarted.snapshot().toList().any { it.recordId == record(1).recordId }
        // A window delta carries the WHOLE floor, so even an unacknowledged remote floor
        // becomes durable through the later active write. It does not carry inherited Compacts.
        val acknowledged = outcome == Outcome.ACKNOWLEDGED || outcome == Outcome.RECOVERED
        assertAll(
            { assertEquals(false, resurrected, "stale foreign record resurrected after $outcome (floor=$floor)") },
            {
                assertEquals(
                    !acknowledged && !floor, originalRetained,
                    "only acknowledged suppression may reclaim the original tombstone segment: $outcome (floor=$floor)",
                )
            },
        )
    }

    private fun exporter(store: DurableStore, maxRecords: Int = 2) = WarpLogRecordExporter(
        replica = ReplicaId("local"), store = store, maxRecords = maxRecords, segmentOps = 2,
    )

    private fun record(id: Int) = LogRecord(
        recordId = ByteString(ByteArray(8) { i -> (id.toLong() shr (8 * i)).toByte() }),
        body = "record-$id",
        observedEpochNanos = 1L,
    )
}
