package us.tractat.kuilt.otel

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

/**
 * Pins that one refused [WarpLogRecordExporter.merge] costs a bounded number of writes (#3349).
 *
 * A refused merge has already joined its remote into the in-memory log, so until some merge
 * persists those ops the exporter must keep adopting. Before this fix the flag that tracks it was
 * cleared only by a merge that had found it clear, so after one failure **every** later merge
 * adopted and wrote its whole remote, no-op or not, until restart: a segment number and a whole
 * peer log per gossip round.
 *
 * Every arm also restarts from the store, because "stops writing" alone would pass an exporter
 * that stopped before the refused merge's records were durable.
 */
class WarpLogRecordExporterRefusedMergeRecoveryTest {

    private val replicaA = ReplicaId("A")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int, body: String = "body-$id") = LogRecord(
        recordId = recordId(id),
        body = body,
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporterFor(store: DurableStore) =
        WarpLogRecordExporter(replica = replicaA, store = store, maxRecords = 1_000, segmentOps = 4)

    private fun foreignLog(author: ReplicaId, firstId: Int, label: String, count: Int = 6): Rga<LogRecord> {
        var rga = Rga.empty<LogRecord>()
        var tail = RgaId.HEAD
        repeat(count) { i ->
            val (next, op) = rga.insertAfter(replica = author, after = tail, value = record(firstId + i, "$label$i"))
            rga = next
            tail = op.id
        }
        return rga
    }

    private suspend fun indexOf(store: RecordingStore): LogSegmentIndex =
        decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST)))

    private suspend fun bodiesAfterRestart(store: DurableStore): Set<String?> =
        exporterFor(store).also { it.recover() }.snapshot().toList().map { it.body }.toSet()

    private fun segmentRefusing(backing: DurableStore) =
        WriteRefusingStore(backing) { key -> key.name.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }

    @Test
    fun gossipAfterOneRefusedMergeStopsWritingOnceTheRetryLands() = runTest {
        val backing = RecordingStore()
        val store = segmentRefusing(backing)
        val exporter = exporterFor(store)
        repeat(3) { exporter.export(record(it)) }
        val remote = foreignLog(ReplicaId("B"), firstId = 500, label = "foreign")

        store.refuseWrites()
        val refused = exporter.merge(remote)
        val refusals = store.refusedWrites()
        store.allowWrites()
        val retried = exporter.merge(remote)
        val afterRetry = indexOf(backing)
        val writesAfterRetry = backing.writes().size

        val gossip = (0 until 10).flatMap { listOf(exporter.merge(remote), exporter.merge(exporter.snapshot())) }
        val afterGossip = indexOf(backing)

        val bodies = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(1, refusals, "the rig refused $refusals writes, not exactly the adopted segment's") },
            { assertTrue(refused is ExportResult.Failure, "the refused merge reported $refused") },
            { assertEquals(ExportResult.Success, retried) },
            { assertTrue(gossip.all { it == ExportResult.Success }, "a gossip merge failed: $gossip") },
            { assertEquals(afterRetry.next, afterGossip.next, "no-op gossip kept spending segment numbers") },
            { assertEquals(afterRetry.sealedSegments, afterGossip.sealedSegments, "no-op gossip kept adopting") },
            { assertEquals(writesAfterRetry, backing.writes().size, "no-op gossip kept writing") },
            { assertTrue((0 until 6).all { "foreign$it" in bodies }, "the refused merge's records were lost: $bodies") },
        )
    }

    @Test
    fun aDifferentMergeAfterARefusalCarriesItAndEndsTheAdopting() = runTest {
        // The merge that clears the flag is not the refused one, so it must persist what the
        // refused one left in memory. Adopting only its own remote and then clearing would lose it.
        val backing = RecordingStore()
        val store = segmentRefusing(backing)
        val exporter = exporterFor(store)
        repeat(3) { exporter.export(record(it)) }
        val first = foreignLog(ReplicaId("B"), firstId = 500, label = "first")
        val second = foreignLog(ReplicaId("C"), firstId = 700, label = "second")

        store.refuseWrites()
        val refused = exporter.merge(first)
        val refusals = store.refusedWrites()
        store.allowWrites()
        val between = exporter.merge(second)
        val afterBetween = indexOf(backing)
        val writesAfterBetween = backing.writes().size

        val gossip = (0 until 10).flatMap { listOf(exporter.merge(first), exporter.merge(second)) }
        val afterGossip = indexOf(backing)

        val bodies = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(1, refusals, "the rig refused $refusals writes, not exactly the adopted segment's") },
            { assertTrue(refused is ExportResult.Failure, "the refused merge reported $refused") },
            { assertEquals(ExportResult.Success, between) },
            { assertTrue(gossip.all { it == ExportResult.Success }, "a gossip merge failed: $gossip") },
            { assertEquals(afterBetween.next, afterGossip.next, "no-op gossip kept spending segment numbers") },
            { assertEquals(writesAfterBetween, backing.writes().size, "no-op gossip kept writing") },
            { assertTrue((0 until 6).all { "second$it" in bodies }, "the merge in between was lost: $bodies") },
            { assertTrue((0 until 6).all { "first$it" in bodies }, "the refused merge's records were lost: $bodies") },
        )
    }

    @Test
    fun aLocalExportOfARecordARefusedMergeBroughtInIsDurableBeforeItReportsSuccess() = runTest {
        // #3384 case 2. The refused merge left the remote's records in `log`, so their ids are in
        // `seenIds`, and a local export of one of them is a dedup hit on a clean active segment.
        val backing = RecordingStore()
        val store = segmentRefusing(backing)
        val exporter = exporterFor(store)
        repeat(3) { exporter.export(record(it)) }
        val remote = foreignLog(ReplicaId("B"), firstId = 500, label = "foreign")

        store.refuseWrites()
        val refused = exporter.merge(remote)
        val refusals = store.refusedWrites()
        store.allowWrites()
        val heldInMemory = exporter.snapshot().toList().map { it.body }
        val durableBefore = bodiesAfterRestart(backing)

        val exported = exporter.export(record(500, "foreign0"))
        val durableAfter = bodiesAfterRestart(backing)
        val writesAfterExport = backing.writes().size
        val gossip = exporter.merge(remote)

        assertAll(
            { assertEquals(1, refusals, "the rig refused $refusals writes, not exactly the adopted segment's") },
            { assertTrue(refused is ExportResult.Failure, "the refused merge reported $refused") },
            { assertTrue("foreign0" in heldInMemory, "the export was not a dedup hit: $heldInMemory") },
            { assertTrue("foreign0" !in durableBefore, "the refused merge's record was durable already: $durableBefore") },
            { assertEquals(ExportResult.Success, exported) },
            { assertTrue("foreign0" in durableAfter, "export reported $exported for a record no key holds: $durableAfter") },
            { assertTrue((0 until 6).all { "foreign$it" in durableAfter }, "the refused merge's records were lost: $durableAfter") },
            { assertEquals(ExportResult.Success, gossip) },
            { assertEquals(writesAfterExport, backing.writes().size, "the export's adopt left the merge flag set") },
        )
    }

    @Test
    fun aDedupHitOverARefusedMergeNeverReportsSuccessWhileTheStoreStillRefuses() = runTest {
        val backing = RecordingStore()
        val store = segmentRefusing(backing)
        val exporter = exporterFor(store)
        repeat(3) { exporter.export(record(it)) }
        val remote = foreignLog(ReplicaId("B"), firstId = 500, label = "foreign")

        store.refuseWrites()
        val refused = exporter.merge(remote)
        val refusalsAfterMerge = store.refusedWrites()
        val exported = exporter.export(record(500, "foreign0"))
        val refusalsAfterExport = store.refusedWrites()

        assertAll(
            { assertEquals(1, refusalsAfterMerge, "the rig refused $refusalsAfterMerge writes, not exactly the adopt's") },
            { assertTrue(refused is ExportResult.Failure, "the refused merge reported $refused") },
            { assertTrue(exported is ExportResult.Failure, "export reported $exported while every segment write was refused") },
            { assertTrue(refusalsAfterExport > refusalsAfterMerge, "the export never attempted a write") },
        )
    }

    @Test
    fun aBatchThatAdmitsOneRecordAndDedupsAnotherStillCarriesTheDedupedOne() = runTest {
        // The active-segment write lands, so the turn reports Success. It must not do so for a
        // deduped record that only the refused merge's in-memory join holds.
        val backing = RecordingStore()
        val store = segmentRefusing(backing)
        val exporter = exporterFor(store)
        repeat(3) { exporter.export(record(it)) }
        val remote = foreignLog(ReplicaId("B"), firstId = 500, label = "foreign")

        store.refuseWrites()
        val refused = exporter.merge(remote)
        val refusals = store.refusedWrites()
        store.allowWrites()
        val durableBefore = bodiesAfterRestart(backing)

        val exported = exporter.export(listOf(record(9, "local9"), record(501, "foreign1")))
        val durable = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(1, refusals, "the rig refused $refusals writes, not exactly the adopted segment's") },
            { assertTrue(refused is ExportResult.Failure, "the refused merge reported $refused") },
            { assertTrue("foreign1" !in durableBefore, "the refused merge's record was durable already: $durableBefore") },
            { assertEquals(ExportResult.Success, exported) },
            { assertTrue("local9" in durable, "the admitted record was lost: $durable") },
            { assertTrue("foreign1" in durable, "export reported $exported for a record no key holds: $durable") },
        )
    }

    @Test
    fun anExportWithNoDedupHitLeavesTheRefusedMergeToTheNextMerge() = runTest {
        // Only a dedup hit claims a merged record is durable, so only it pays for the whole-log
        // adopt. A store that refuses large writes and takes small ones keeps taking new records.
        val backing = RecordingStore()
        val store = segmentRefusing(backing)
        val exporter = exporterFor(store)
        repeat(3) { exporter.export(record(it)) }
        val remote = foreignLog(ReplicaId("B"), firstId = 500, label = "foreign")

        store.refuseWrites()
        val refused = exporter.merge(remote)
        val refusals = store.refusedWrites()
        store.allowWrites()

        val exported = exporter.export(record(9, "local9"))
        val afterExport = bodiesAfterRestart(backing)
        val merged = exporter.merge(remote)
        val afterMerge = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(1, refusals, "the rig refused $refusals writes, not exactly the adopted segment's") },
            { assertTrue(refused is ExportResult.Failure, "the refused merge reported $refused") },
            { assertEquals(ExportResult.Success, exported) },
            { assertTrue("local9" in afterExport, "the exported record was lost: $afterExport") },
            { assertTrue("foreign0" !in afterExport, "an export with no dedup hit adopted the whole log: $afterExport") },
            { assertEquals(ExportResult.Success, merged) },
            { assertTrue((0 until 6).all { "foreign$it" in afterMerge }, "the refused merge's records were lost: $afterMerge") },
        )
    }
}
