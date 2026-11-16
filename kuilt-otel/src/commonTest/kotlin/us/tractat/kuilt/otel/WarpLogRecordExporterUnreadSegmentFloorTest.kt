package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that a sealed segment recovery could not read does not wedge this replica's drop floor
 * (#3447).
 *
 * Recovery raises the log's seq past an unread segment's ids, so the loaded log holds none of the
 * seqs in it. A floor walk that cannot step over a seq the log does not hold stops below the first
 * of them, every later own drop becomes an explicit `RgaOp.Compact`, and every segment carrying
 * one is pinned on disk for good. Measured on the #3410 repro before the fix: 35 segment keys, 30
 * of them pinned, about 50 KB resident, against 3 keys and about 2.3 KB with no refusal.
 *
 * The shape is the issue's: 16 exports with eight ops to a segment and room for ten records, a
 * restart whose read of segment 1 is refused once, 300 exports, then a clean restart and 300 more.
 * The no-refusal run is the control, and the refused run must end in the same layout.
 */
class WarpLogRecordExporterUnreadSegmentFloorTest {

    private companion object {
        const val MAX_RECORDS = 10
        const val SEGMENT_OPS = 8
    }

    private val replica = ReplicaId("A")

    private fun recordId(id: Int): ByteString = ByteString(ByteArray(8) { i -> (id.toLong() shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporterFor(store: DurableStore) = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = MAX_RECORDS,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = SEGMENT_OPS,
    )

    /** What the store and the log look like at the end of a run. */
    private data class Layout(
        val segmentKeys: Int,
        val compactCarrying: Int,
        val residentBytes: Int,
        val compactedIds: Int,
    )

    private class Run(
        val refusals: Int,
        val floorBeforeCleanStart: Long,
        val seqBeforeCleanStart: Long,
        val compactedBeforeCleanStart: Int,
        val visible: List<String?>,
        val layout: Layout,
        val failures: Int,
    )

    private suspend fun layoutOf(store: RecordingStore, log: Rga<LogRecord>): Layout {
        val segments = store.keys().filter { it.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }
        val compactCarrying = segments.count { key ->
            decodeSegmentForTest(requireNotNull(store.read(StoreKey(key)))).compactOpCount > 0
        }
        return Layout(segments.size, compactCarrying, store.residentBytes(), log.compactedIds.size)
    }

    private suspend fun run(refuseOnce: Boolean, between: Int = 300, after: Int = 300): Run {
        val backing = RecordingStore()
        val writer = exporterFor(backing)
        var failures = 0
        repeat(16) { if (writer.export(record(it)) != ExportResult.Success) failures++ }

        val refusing = ReadRefusingStore(backing, StoreKey(segmentKeyForTest(1L)), refusingAtStart = refuseOnce)
        val restarted = exporterFor(refusing)
        restarted.recover()
        refusing.heal()
        repeat(between) { if (restarted.export(record(100 + it)) != ExportResult.Success) failures++ }
        val before = restarted.snapshot()

        val clean = exporterFor(backing)
        clean.recover()
        repeat(after) { if (clean.export(record(1000 + it)) != ExportResult.Success) failures++ }
        val after = clean.snapshot()
        return Run(
            refusals = refusing.refusals,
            floorBeforeCleanStart = before.compactedBelow[replica],
            seqBeforeCleanStart = before.seqHighWater(replica),
            compactedBeforeCleanStart = before.compactedIds.size,
            visible = after.toList().map { it.body },
            layout = layoutOf(backing, after),
            failures = failures,
        )
    }

    @Test
    fun aRefusedReadEndsInTheLayoutOfARunWithNoRefusal() = runTest {
        val control = run(refuseOnce = false)
        val refused = run(refuseOnce = true)
        assertAll(
            // The rigs: the refused run really was refused, once, and the control never was.
            { assertEquals(1, refused.refusals, "segment 1's read was refused once") },
            { assertEquals(0, control.refusals, "the control was never refused") },
            { assertEquals(0, refused.failures + control.failures, "every export succeeded") },
            // The control's own shape, so a change that bloats both runs alike cannot pass.
            { assertEquals(0, control.layout.compactCarrying, "the control pins nothing") },
            { assertEquals(0, control.layout.compactedIds, "the control holds no Compact id") },
            // The property: the floor walked past the unread segment's seqs before it came back,
            { assertEquals(control.seqBeforeCleanStart, refused.seqBeforeCleanStart, "both runs minted the same seqs") },
            // Not equal to the control's: the refused run starts with fewer records visible, so
            // its window passes fall on other exports. Before the fix it stopped at 8.
            {
                assertTrue(
                    refused.floorBeforeCleanStart >= refused.seqBeforeCleanStart - 2 * MAX_RECORDS,
                    "the floor stopped at ${refused.floorBeforeCleanStart} of seq ${refused.seqBeforeCleanStart}",
                )
            },
            { assertEquals(0, refused.compactedBeforeCleanStart, "no own drop became a Compact") },
            // and the store ends where the control's does.
            { assertEquals(control.layout, refused.layout, "the refused run ends in the control's layout") },
            { assertEquals(control.visible, refused.visible, "and holds the same records") },
        )
    }

    /**
     * The unread segment comes back **before** the window has turned over: [between] exports, fewer
     * than [MAX_RECORDS], between the refused start and the clean one. At the default buffer of
     * 10,000 records this is the common case. The returned records sort after everything minted
     * since the refused start, since they are siblings with a lower lamport, so the window reaches
     * newer records first. Measured before the fix at 5 exports: 34 keys, 30 pinned, 286 Compact
     * ids, and the count still growing, 34/64/94 keys at 300/600/900 exports; at 1 export, 5 keys
     * with nothing pinned, which is the pass phase and stays flat.
     */
    @Test
    fun aSegmentReadBackBeforeTheWindowTurnsEndsInTheControlsLayout() = runTest {
        for (between in listOf(1, 5, 9)) {
            val control = run(refuseOnce = false, between = between, after = 600)
            val refused = run(refuseOnce = true, between = between, after = 600)
            val longer = run(refuseOnce = true, between = between, after = 900)
            assertAll(
                { assertEquals(1, refused.refusals, "the rig at $between: segment 1's read was refused once") },
                { assertEquals(0, refused.failures + control.failures, "every export succeeded at $between") },
                { assertEquals(0, control.layout.compactCarrying, "the control pins nothing at $between") },
                // Not the control's layout byte for byte: the refused run's window passes land on
                // other exports, so up to a pass's worth of tombstones, 2 * MAX_RECORDS ops, may
                // still be waiting in sealed segments the control has already retired.
                { assertEquals(0, refused.layout.compactCarrying, "a segment is pinned at $between") },
                { assertEquals(0, refused.layout.compactedIds, "an own drop became a Compact at $between") },
                {
                    assertTrue(
                        refused.layout.segmentKeys <= control.layout.segmentKeys + 2 * MAX_RECORDS / SEGMENT_OPS + 1,
                        "${refused.layout.segmentKeys} keys against the control's ${control.layout.segmentKeys} at $between",
                    )
                },
                { assertEquals(control.visible, refused.visible, "and holds the same records at $between") },
                {
                    assertEquals(
                        refused.layout.segmentKeys,
                        longer.layout.segmentKeys,
                        "the key count kept growing at $between",
                    )
                },
            )
        }
    }

    /**
     * A peer's `Compact` over this replica's dots, above the hole a refused segment left. Peer B
     * merges the degraded A, windows A's records away with exports of its own, and A merges B
     * back, so A holds compacted own seqs on both sides of the hole. The compacted seqs must
     * neither license A's floor across the hole nor block it: A's own drops keep folding, nothing
     * is pinned, and the store stops growing while the key stays refused. Measured on the round-2
     * walk, which let a compacted seq block: 33/63/93 keys and 31/61/91 pinned at 300/600/900.
     */
    @Test
    fun aPeersCompactAboveTheHoleNeitherLicensesNorBlocksTheFloor() = runTest {
        val backing = RecordingStore()
        val writer = exporterFor(backing)
        repeat(16) { assertEquals(ExportResult.Success, writer.export(record(it))) }
        val refusing = ReadRefusingStore(backing, StoreKey(segmentKeyForTest(1L)))
        val restarted = exporterFor(refusing)
        restarted.recover()
        val peer = WarpLogRecordExporter(ReplicaId("B"), RecordingStore(), maxRecords = MAX_RECORDS, segmentOps = SEGMENT_OPS)
        assertEquals(ExportResult.Success, peer.merge(restarted.snapshot()))
        repeat(25) { assertEquals(ExportResult.Success, peer.export(record(50_000 + it))) }
        val peerLog = peer.snapshot()
        assertEquals(ExportResult.Success, restarted.merge(peerLog))
        repeat(300) { assertEquals(ExportResult.Success, restarted.export(record(1000 + it))) }
        val atThreeHundred = layoutOf(backing, restarted.snapshot())
        repeat(300) { assertEquals(ExportResult.Success, restarted.export(record(2000 + it))) }
        val atSixHundred = layoutOf(backing, restarted.snapshot())
        val log = restarted.snapshot()
        assertAll(
            // The rig: the read was refused and the peer's log carries Compacts over A's seqs.
            { assertTrue(refusing.refusals >= 1, "segment 1's read was refused") },
            { assertTrue(peerLog.compactedIds.any { it.replicaId == replica }, "the peer compacted none of A's seqs") },
            // The property.
            { assertEquals(0, atSixHundred.compactCarrying - atThreeHundred.compactCarrying, "pinned segments kept growing") },
            { assertEquals(atThreeHundred.segmentKeys, atSixHundred.segmentKeys, "the key count kept growing") },
            {
                assertTrue(
                    log.compactedBelow[replica] >= log.seqHighWater(replica) - 2 * MAX_RECORDS,
                    "the floor stopped at ${log.compactedBelow[replica]} of seq ${log.seqHighWater(replica)}",
                )
            },
        )
    }

    /**
     * What the arrival-order widening rests on, checked after every export: the own records the
     * log keeps, visible or tombstoned, are the newest by seq, so their seqs form one unbroken run
     * and nothing dropped is newer than anything kept; and the visible ones number at most
     * [MAX_RECORDS], exactly `min(MAX_RECORDS, exported)` on a log with no hole. Run on a log with
     * no hole, where list order is already arrival order, and after a segment read back early,
     * where it is not.
     */
    @Test
    fun theOwnRecordsKeptAreAlwaysTheNewestBySeq() = runTest {
        val breaches = mutableListOf<String>()
        var checks = 0
        fun check(label: String, log: Rga<LogRecord>, exported: Int?) {
            checks++
            val kept = log.sequence.filter { it.replicaId == replica }.map { it.seq }.sorted()
            if (kept.isNotEmpty() && kept.last() - kept.first() + 1 != kept.size.toLong()) {
                breaches += "$label: kept own seqs are not one run: $kept"
            }
            val visible = log.entries().count { it.first.replicaId == replica }
            if (visible > MAX_RECORDS) breaches += "$label: $visible visible"
            if (exported != null && visible != minOf(MAX_RECORDS, exported)) {
                breaches += "$label: $visible visible after $exported exports"
            }
        }
        val plain = exporterFor(RecordingStore())
        repeat(300) {
            plain.export(record(it))
            check("no hole, export $it", plain.snapshot(), it + 1)
        }
        val backing = RecordingStore()
        val writer = exporterFor(backing)
        repeat(16) { writer.export(record(it)) }
        val refusing = ReadRefusingStore(backing, StoreKey(segmentKeyForTest(1L)))
        val restarted = exporterFor(refusing)
        restarted.recover()
        refusing.heal()
        repeat(5) { restarted.export(record(100 + it)) }
        val clean = exporterFor(backing)
        clean.recover()
        val reordered = clean.snapshot().sequence.filter { it.replicaId == replica }.map { it.seq }
        repeat(300) {
            clean.export(record(1000 + it))
            check("read back early, export $it", clean.snapshot(), null)
        }
        assertAll(
            // The rig: the read-back log really was out of seq order, and the checks ran.
            { assertTrue(reordered != reordered.sorted(), "the read-back log was in seq order: $reordered") },
            { assertTrue(checks > 400, "only $checks checks") },
            { assertEquals(emptyList(), breaches.take(5)) },
        )
    }

    /**
     * A sealed key the store refuses for good: the floor still walks past its seqs, so nothing
     * own is pinned, and the layout stops growing. The key itself stays named, since nothing was
     * read out of it.
     */
    @Test
    fun aSegmentRefusedForGoodStopsGrowingTheStore() = runTest {
        val backing = RecordingStore()
        val writer = exporterFor(backing)
        repeat(16) { assertEquals(ExportResult.Success, writer.export(record(it))) }
        val refusing = ReadRefusingStore(backing, StoreKey(segmentKeyForTest(1L)))
        val restarted = exporterFor(refusing)
        restarted.recover()
        repeat(300) { assertEquals(ExportResult.Success, restarted.export(record(100 + it))) }
        val atThreeHundred = layoutOf(backing, restarted.snapshot())
        repeat(300) { assertEquals(ExportResult.Success, restarted.export(record(1000 + it))) }
        val atSixHundred = layoutOf(backing, restarted.snapshot())
        assertAll(
            { assertTrue(refusing.refusals >= 1, "segment 1's read was refused") },
            { assertTrue(segmentKeyForTest(1L) in backing.keys(), "the refused segment stays on disk") },
            { assertEquals(0, atSixHundred.compactCarrying, "no segment carries a Compact") },
            { assertEquals(0, atSixHundred.compactedIds, "no own drop became a Compact") },
            // Before the fix: 33 keys at 300 exports and 63 at 600, 59 of them pinned.
            { assertEquals(atThreeHundred.segmentKeys, atSixHundred.segmentKeys, "the key count stopped growing") },
            { assertEquals(atThreeHundred.compactCarrying, atSixHundred.compactCarrying) },
        )
    }
}
