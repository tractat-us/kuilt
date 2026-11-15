package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that no index the log exporter writes claims a seq the store does not hold (#3410's
 * second half).
 *
 * The index carries this replica's seq high-water so a restart mints past a sealed segment it
 * could not read. It has to stop there. Recovery raises the log to whatever the index claims, and
 * [Rga.dropWindow] folds this replica's drops into its floor only along an unbroken run of seqs.
 * A seq the index claims and no key holds is a hole in that run, so the floor stops below it for
 * good: every later drop is recorded as an explicit [RgaOp.Compact], and a segment carrying one is
 * never retired.
 *
 * The over-claim needs only a segment write refused while the index write beside it lands, which
 * is the shape a quota-bound store has: it refuses the large segment and takes the small index.
 * Each turn after the first refusal leads with an index write, and that write used to carry the
 * seqs the same turn had just minted into memory.
 */
class WarpLogRecordExporterIndexClaimsDurableSeqTest {

    private val replica = ReplicaId("A")
    private val maxRecords = 10

    private fun record(id: Int) = LogRecord(
        recordId = ByteString(ByteArray(8) { i -> (id.toLong() shr (8 * i)).toByte() }),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    /** Eight ops to a segment, so a roll every few exports; a small window, so passes are frequent. */
    private fun exporterFor(store: DurableStore) =
        WarpLogRecordExporter(replica, store, maxRecords = maxRecords, segmentOps = 8)

    private suspend fun indexOf(store: RecordingStore): LogSegmentIndex =
        decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST)) { "no index written" })

    private fun indexWrites(store: RecordingStore): Int =
        store.operations().count { it.kind == StoreOpKind.WRITE && it.key == INDEX_KEY_FOR_TEST }

    /**
     * The highest `s` such that every one of this replica's seqs `1..s` is held by some segment key
     * in [segments]: as an `Insert`, as a [RgaOp.Compact]'s id, or under a floor. The run a floor can
     * fold along, which is what an index's claim must not pass.
     */
    private fun ownSeqsHeldWithoutAGap(segments: Collection<ByteArray>): Long {
        var floor = 0L
        val held = HashSet<Long>()
        for (bytes in segments) {
            val segment = decodeSegmentForTest(bytes)
            floor = maxOf(floor, segment.compactedBelow[replica])
            segment.operations().forEach { op ->
                when (op) {
                    is RgaOp.Insert -> if (op.id.replicaId == replica) held += op.id.seq
                    is RgaOp.Compact -> op.positions.keys.forEach { id -> if (id.replicaId == replica) held += id.seq }
                    is RgaOp.Remove -> Unit
                }
            }
        }
        var run = floor
        while ((run + 1) in held) run++
        return run
    }

    private suspend fun segmentsOnDisk(store: RecordingStore): List<ByteArray> =
        store.keys().filter { it.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }.map { requireNotNull(store.read(StoreKey(it))) }

    /**
     * Segment writes are refused while index writes land, the process restarts, and 300 exports
     * follow. The floor has to keep up with them and the segment count has to stay where an
     * uninterrupted run keeps it.
     */
    @Test
    fun aRefusedSegmentWriteBesideALandedIndexLeavesTheFloorAdvancingAfterARestart() = runTest {
        val backing = RecordingStore()
        val quota = RefuseSegmentWritesStore(backing)
        val writer = exporterFor(quota)
        writer.recover()
        val before = List(25) { writer.export(record(it)) }

        quota.refuseSegmentWrites()
        val indexWritesBefore = indexWrites(backing)
        val outage = List(3) { writer.export(record(500 + it)) }
        val indexWritesInOutage = indexWrites(backing) - indexWritesBefore
        val mintedInMemory = writer.snapshot().seqHighWater(replica)
        val heldAtRestart = ownSeqsHeldWithoutAGap(segmentsOnDisk(backing))
        val indexAtRestart = indexOf(backing)

        val restarted = exporterFor(backing)
        restarted.recover()
        val after = List(300) { restarted.export(record(1000 + it)) }
        val log = restarted.snapshot()
        val segments = segmentsOnDisk(backing).map { decodeSegmentForTest(it) }

        assertAll(
            // The rig: the outage refused segment writes, landed index writes beside them, and
            // minted seqs into memory that no key holds.
            { assertTrue(before.all { it == ExportResult.Success }, "the run before the outage: $before") },
            { assertTrue(outage.none { it == ExportResult.Success }, "the outage refused every export: $outage") },
            { assertTrue(quota.refusedWrites() >= 3, "only ${quota.refusedWrites()} segment writes refused") },
            { assertTrue(indexWritesInOutage > 0, "no index write landed during the outage") },
            { assertTrue(mintedInMemory > heldAtRestart, "the outage minted nothing past $heldAtRestart") },
            { assertTrue(after.all { it == ExportResult.Success }, "an export after the restart failed") },
            // The property: the index claims only what the store holds, so the restarted log has
            // no hole to stop its floor, and the layout stays as small as a run with no outage.
            {
                assertTrue(
                    indexAtRestart.seqHighWater <= heldAtRestart,
                    "the index claims seq ${indexAtRestart.seqHighWater}; the store holds 1..$heldAtRestart",
                )
            },
            {
                assertTrue(
                    log.compactedBelow[replica] >= log.seqHighWater(replica) - 3 * maxRecords,
                    "the floor stopped at ${log.compactedBelow[replica]} of seq ${log.seqHighWater(replica)}",
                )
            },
            { assertEquals(0, log.compactedIds.count { it.replicaId == replica }, "an own drop kept a Compact") },
            { assertEquals(0, segments.count { it.compactOpCount > 0 }, "a segment is pinned by a Compact") },
            { assertTrue(segments.size <= 4, "${segments.size} segment keys after 300 exports") },
        )
    }

    /**
     * Every index write, at the moment it lands, claims no seq past the run the segment keys then
     * on disk hold, and covers the ids of every segment it names sealed. Driven through two
     * segment-write outages, one healed in place and one carried across a restart, so the first
     * turns after recovery fail; a merge refused in that outage, so the next merge adopts the whole
     * log with an unwritten record in it; rolls, window passes that retire segments, and a clear.
     *
     * The second half is [WarpLogRecordExporterUnreadSealedSegmentIdsTest]'s invariant, run here
     * through refused writes. The two pull against each other: a claim too low reuses an unread
     * segment's ids, a claim too high wedges the floor.
     */
    @Test
    fun everyIndexWrittenClaimsOnlySeqsTheStoreHoldsAndCoversWhatItSeals() = runTest {
        val backing = RecordingStore()
        val quota = RefuseSegmentWritesStore(backing)
        val writer = exporterFor(quota)
        writer.recover()
        repeat(25) { writer.export(record(it)) }
        quota.refuseSegmentWrites()
        val firstOutageStart = indexWrites(backing)
        repeat(3) { writer.export(record(300 + it)) }
        val firstOutageIndexWrites = indexWrites(backing) - firstOutageStart
        quota.acceptSegmentWrites()
        repeat(10) { writer.export(record(400 + it)) }
        val (peerLog, _) = Rga.empty<LogRecord>().insertAllAfter(ReplicaId("B"), RgaId.HEAD, listOf(record(900)))
        val merged = writer.merge(peerLog)
        quota.refuseSegmentWrites()
        repeat(3) { writer.export(record(500 + it)) }

        // Restarted with the outage still on, so the first turns after recovery fail: their index
        // writes restate what recovery found, and nothing a segment write raised yet.
        val restarted = exporterFor(quota)
        restarted.recover()
        val unwritten = restarted.export(record(600))
        val (secondPeerLog, _) = Rga.empty<LogRecord>().insertAllAfter(ReplicaId("C"), RgaId.HEAD, listOf(record(901)))
        val refusedAdopt = restarted.merge(secondPeerLog)
        val refused = quota.refusedWrites()
        quota.acceptSegmentWrites()
        // The refused merge leaves its ops in memory alone, so this one adopts the whole log, and
        // with it the record the first export after the restart could not write.
        val (thirdPeerLog, _) = Rga.empty<LogRecord>().insertAllAfter(ReplicaId("D"), RgaId.HEAD, listOf(record(902)))
        val wholeLogAdopt = restarted.merge(thirdPeerLog)
        repeat(30) { restarted.export(record(610 + it)) }
        val mergedAgain = restarted.merge(restarted.snapshot().piece(peerLog))
        val cleared = restarted.clear()
        repeat(20) { restarted.export(record(700 + it)) }

        val operations = backing.operations()
        val lastWritten = operations.filter { it.kind == StoreOpKind.WRITE }.associate { it.key.name to requireNotNull(it.bytes) }
        val disk = mutableMapOf<String, ByteArray>()
        val breaches = mutableListOf<String>()
        var indexesChecked = 0
        var segmentsChecked = 0
        var retiredSomething = false
        operations.forEachIndexed { at, op ->
            when (op.kind) {
                StoreOpKind.READ -> Unit
                StoreOpKind.DELETE -> disk.remove(op.key.name)
                StoreOpKind.WRITE -> {
                    val bytes = requireNotNull(op.bytes)
                    if (op.key == INDEX_KEY_FOR_TEST) {
                        val index = decodeIndexForTest(bytes)
                        indexesChecked++
                        retiredSomething = retiredSomething || index.retired.isNotEmpty()
                        // What the store holds now, plus what a segment this index names sealed and
                        // the store does not hold yet will hold: a merge's adopt names its segment
                        // before writing it, so that write may claim the segment's seqs.
                        val segmentsHere = disk.filterKeys { it.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }.values +
                            index.sealedSegments.map { segmentKeyForTest(it) }.filter { it !in disk }.mapNotNull { lastWritten[it] }
                        val held = ownSeqsHeldWithoutAGap(segmentsHere)
                        if (index.seqHighWater > held) {
                            breaches += "operation $at: index claims seq ${index.seqHighWater}, the store holds 1..$held"
                        }
                        for (number in index.sealedSegments) {
                            val sealed = lastWritten[segmentKeyForTest(number)] ?: continue
                            segmentsChecked++
                            decodeSegmentForTest(sealed).operations().flatMap { sealedOp ->
                                when (sealedOp) {
                                    is RgaOp.Insert -> listOf(sealedOp.id)
                                    is RgaOp.Remove -> emptyList()
                                    is RgaOp.Compact -> sealedOp.positions.keys.toList()
                                }
                            }.filter { it.replicaId == replica && it.seq > index.seqHighWater }.forEach { id ->
                                breaches += "operation $at: index ($index) does not cover $id in segment $number"
                            }
                        }
                    }
                    disk[op.key.name] = bytes
                }
            }
        }
        assertAll(
            // The rig: both outages refused segment writes while index writes landed, and the walk
            // checked enough of the run to mean something.
            { assertTrue(refused >= 8, "only $refused segment writes refused") },
            { assertTrue(firstOutageIndexWrites > 0, "no index write landed during the first outage") },
            { assertEquals(ExportResult.Success, merged, "the first merge") },
            { assertTrue(unwritten != ExportResult.Success, "the first export after the restart was written") },
            { assertTrue(refusedAdopt != ExportResult.Success, "the adopt during the outage was written") },
            { assertEquals(ExportResult.Success, wholeLogAdopt, "the whole-log adopt") },
            { assertEquals(ExportResult.Success, mergedAgain, "the second merge") },
            { assertEquals(ExportResult.Success, cleared, "the clear") },
            { assertTrue(indexesChecked > 20, "only $indexesChecked index writes checked") },
            { assertTrue(segmentsChecked > 20, "only $segmentsChecked sealed segments checked") },
            { assertTrue(retiredSomething, "no window pass retired a segment") },
            // The property.
            { assertEquals(emptyList(), breaches) },
        )
    }
}
