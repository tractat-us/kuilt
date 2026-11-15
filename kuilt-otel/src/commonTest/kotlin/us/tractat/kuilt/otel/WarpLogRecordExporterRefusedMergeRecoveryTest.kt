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

    private fun exporterFor(store: DurableStore, maxRecords: Int = 1_000, segmentOps: Int = 4) =
        WarpLogRecordExporter(replica = replicaA, store = store, maxRecords = maxRecords, segmentOps = segmentOps)

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
        // Re-admitting the record, as dropping its id from `seenIds` would, keeps two copies.
        val copiesInMemory = exporter.snapshot().toList().count { it.recordId == recordId(500) }
        val copiesAfterRestart = exporterFor(backing).also { it.recover() }
            .snapshot().toList().count { it.recordId == recordId(500) }

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
            { assertEquals(1, copiesInMemory, "the log holds $copiesInMemory records with one id") },
            { assertEquals(1, copiesAfterRestart, "the store holds $copiesAfterRestart records with one id") },
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
        // deduped record that only the refused merge's in-memory join holds. The budget fits both
        // records in ONE turn: at `segmentOps = 4` the three records before leave no room,
        // `fitsInTurn` splits the batch, and this arm is the dedup-only arm again.
        val backing = RecordingStore()
        val store = segmentRefusing(backing)
        val exporter = exporterFor(store, segmentOps = 16)
        repeat(3) { exporter.export(record(it)) }
        val remote = foreignLog(ReplicaId("B"), firstId = 500, label = "foreign")

        store.refuseWrites()
        val refused = exporter.merge(remote)
        val refusals = store.refusedWrites()
        store.allowWrites()
        val durableBefore = bodiesAfterRestart(backing)
        val sealedBefore = indexOf(backing).sealedSegments.toSet()
        val operationsBefore = backing.operations().size

        val exported = exporter.export(listOf(record(9, "local9"), record(501, "foreign1")))
        val turnWrites = backing.operations().drop(operationsBefore).filter { it.kind == StoreOpKind.WRITE }
        val adopted = indexOf(backing).sealedSegments.toSet() - sealedBefore
        val durable = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(1, refusals, "the rig refused $refusals writes, not exactly the adopted segment's") },
            { assertTrue(refused is ExportResult.Failure, "the refused merge reported $refused") },
            { assertTrue("foreign1" !in durableBefore, "the refused merge's record was durable already: $durableBefore") },
            // One turn: the adopt's index and segment, then the active segment. Two turns would
            // write the active segment twice.
            { assertEquals(3, turnWrites.size, "the batch wrote ${turnWrites.map { it.key.name }}, not one adopting turn") },
            { assertEquals(1, adopted.size, "the turn adopted $adopted, not exactly one segment") },
            { assertEquals(ExportResult.Success, exported) },
            { assertTrue("local9" in durable, "the admitted record was lost: $durable") },
            { assertTrue("foreign1" in durable, "export reported $exported for a record no key holds: $durable") },
        )
    }

    @Test
    fun aWindowPassInsideAnAdoptingExportTurnKeepsTheAdoptedSegmentNamed() = runTest {
        // The turn adopts the whole log, admits a record, and owes a window pass, so its
        // active-segment write runs with `retire = true` beside the adopt. Every index write it
        // emits after the adopt's own has to project the adopted number as sealed.
        val backing = RecordingStore()
        val store = segmentRefusing(backing)
        val exporter = exporterFor(store, maxRecords = 2)
        // Three records at a cap of two: one eviction, one short of a pass.
        repeat(3) { exporter.export(record(it)) }
        // B removes one of A's records and adds one of its own, so the merge leaves the visible
        // count at the cap and owes no pass of its own.
        val base = exporter.snapshot()
        val (withForeign, foreignInsert) =
            base.insertAfter(replica = ReplicaId("B"), after = base.entries().last().first, value = record(500, "foreign0"))
        val remote = requireNotNull(withForeign.removeAt(0)).first

        store.refuseWrites()
        val refused = exporter.merge(remote)
        val refusals = store.refusedWrites()
        store.allowWrites()
        val floorBefore = exporter.snapshot().compactedBelow[replicaA]
        val durableBefore = bodiesAfterRestart(backing)
        val sealedBefore = indexOf(backing).sealedSegments.toSet()

        val exported = exporter.export(listOf(record(500, "foreign0"), record(3, "local3")))
        val floorAfter = exporter.snapshot().compactedBelow[replicaA]
        val adopted = indexOf(backing).sealedSegments.toSet() - sealedBefore
        val durable = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(1, refusals, "the rig refused $refusals writes, not exactly the adopted segment's") },
            { assertTrue(refused is ExportResult.Failure, "the refused merge reported $refused") },
            { assertTrue(exporter.snapshot().entries().any { it.first == foreignInsert.id }, "the merge did not keep B's record") },
            { assertTrue("foreign0" !in durableBefore, "the refused merge's record was durable already: $durableBefore") },
            { assertEquals(0L, floorBefore, "a window pass ran before the export turn") },
            { assertTrue(floorAfter > floorBefore, "no window pass ran in the export turn: floor $floorAfter") },
            { assertEquals(1, adopted.size, "the turn adopted $adopted, not exactly one segment") },
            { assertEquals(ExportResult.Success, exported) },
            { assertTrue("foreign0" in durable, "export reported $exported for a record no key holds: $durable") },
            { assertTrue("local3" in durable, "the admitted record was lost: $durable") },
        )
    }

    @Test
    fun anExportWithNoDedupHitLeavesTheRefusedMergeToTheNextMerge() = runTest {
        // Only a dedup hit claims a merged record is durable, so only it pays for the whole-log
        // adopt. An export with none writes its active segment alone and leaves the merged records
        // to the next merge, which adopts the whole log.
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
