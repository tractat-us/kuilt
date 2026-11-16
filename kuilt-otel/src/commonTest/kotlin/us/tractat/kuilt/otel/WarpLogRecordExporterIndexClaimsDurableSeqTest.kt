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
 * Pins that every index the log exporter writes claims past every seq it has minted, and that a
 * restart which raises the log to that claim keeps its drop floor advancing.
 *
 * The index carries this replica's seq high-water so a restart mints past ids it cannot see: a
 * sealed segment it could not read (#3410), or a record that escaped through [WarpLogRecordExporter.snapshot]
 * or a sink before its write landed (#3452). So the claim runs ahead of what the keys hold, and
 * a restart's log has a hole in its seqs. Until #3447 that hole stopped the floor for good: the
 * window folded drops only along an unbroken run, every later drop was kept as an explicit
 * [RgaOp.Compact], and a segment carrying one was never retired. #3444 answered by keeping the
 * claim down to what the keys hold, which re-minted the escaped ids. The window now crosses the
 * hole ([Rga.dropWindowPastUnheld]), and these arms pin both halves: the claim covers every seq,
 * and the layout after a restart stays as small as a run with no outage.
 *
 * The shapes need only a segment write refused while the index write beside it lands, which is
 * the shape a quota-bound store has: it refuses the large segment and takes the small index.
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

    /** A lease of [SHORT_LEASE], so a run of a few dozen exports renews the claim many times. */
    private fun shortLeaseExporterFor(store: DurableStore) = WarpLogRecordExporter(
        replica,
        store,
        maxRecords,
        BufferPolicy.DROP_OLDEST,
        segmentOps = 8,
        appliedOps = AppliedOpSink.Discarding,
        mintLease = SHORT_LEASE,
    )

    private companion object {
        const val SHORT_LEASE = 8L
    }

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
            // The property: the index claims every seq minted into memory, and the restarted
            // log's floor crosses the hole that leaves, so the layout stays as small as a run with
            // no outage.
            {
                assertTrue(
                    indexAtRestart.seqHighWater >= mintedInMemory,
                    "the index claims seq ${indexAtRestart.seqHighWater}; memory minted $mintedInMemory",
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
     * A whole-log adopt whose segment is refused while the log carries an own record no key holds,
     * then a restart. The restart raises the log past that record's seq, which the claim covers,
     * and the floor crosses the hole the refused writes left.
     *
     * The store refuses only large writes once the adopt comes, the shape a quota-bound store has:
     * the whole log, padded by a peer's long record, is refused and the active segment is taken.
     * [adopt] is the turn that builds the whole-log adopt, a merge or an export deduplicating
     * against the refused merge's record, the two sites that build one.
     */
    private fun aRefusedWholeLogAdoptClaimsEveryMintedSeq(adopt: suspend (WarpLogRecordExporter) -> ExportResult) =
        runTest {
            val backing = RecordingStore()
            val sized = RefuseLargeWritesStore(backing, maxBytes = 2_048)
            val quota = RefuseSegmentWritesStore(sized)
            val writer = exporterFor(quota)
            writer.recover()
            val before = List(25) { writer.export(record(it)) }

            // An own record no key holds, then a refused merge, so the next adopt takes the whole log.
            quota.refuseSegmentWrites()
            val unwritten = writer.export(record(600))
            val (peerLog, _) = Rga.empty<LogRecord>()
                .insertAllAfter(ReplicaId("C"), RgaId.HEAD, listOf(record(901).copy(body = "c".repeat(4_000))))
            val refusedMerge = writer.merge(peerLog)
            quota.acceptSegmentWrites()
            sized.refuseLargeWrites()
            val mintedInMemory = writer.snapshot().seqHighWater(replica)
            val heldBeforeAdopt = ownSeqsHeldWithoutAGap(segmentsOnDisk(backing))

            val refusedAdopt = adopt(writer)
            val indexAtRestart = indexOf(backing)
            val heldAtRestart = ownSeqsHeldWithoutAGap(segmentsOnDisk(backing))

            val restarted = exporterFor(backing)
            restarted.recover()
            val after = List(300) { restarted.export(record(1000 + it)) }
            val log = restarted.snapshot()
            val segments = segmentsOnDisk(backing).map { decodeSegmentForTest(it) }

            assertAll(
                // The rig: an own record in memory alone, a merge left uncarried, and an adopt of the
                // whole log refused for its size.
                { assertTrue(before.all { it == ExportResult.Success }, "the run before the outage: $before") },
                { assertTrue(unwritten != ExportResult.Success, "the own record was written") },
                { assertTrue(refusedMerge != ExportResult.Success, "the first merge was written") },
                { assertTrue(mintedInMemory > heldBeforeAdopt, "the log carried nothing past $heldBeforeAdopt") },
                { assertTrue(refusedAdopt != ExportResult.Success, "the whole-log adopt was written") },
                { assertTrue(sized.refusedWrites() >= 1, "no write was refused for its size") },
                { assertTrue(after.all { it == ExportResult.Success }, "an export after the restart failed") },
                // The property.
                {
                    assertTrue(
                        indexAtRestart.seqHighWater >= mintedInMemory,
                        "the index claims seq ${indexAtRestart.seqHighWater}; memory minted $mintedInMemory",
                    )
                },
                {
                    assertTrue(
                        indexAtRestart.seqHighWater > heldAtRestart,
                        "the rig: the claim runs ahead of the store's 1..$heldAtRestart",
                    )
                },
                {
                    assertTrue(
                        log.compactedBelow[replica] >= log.seqHighWater(replica) - 3 * maxRecords,
                        "the floor stopped at ${log.compactedBelow[replica]} of seq ${log.seqHighWater(replica)}",
                    )
                },
                { assertEquals(0, log.compactedIds.count { it.replicaId == replica }, "an own drop kept a Compact") },
            )
        }

    /**
     * An adopt that leads with the unwritten active segment, in a turn whose window pass then moves
     * that segment, still writes the moved one. The leading write is built before the pass, so it
     * lacks the floor the pass raised; reusing it as the turn's active write would leave that floor
     * in memory alone, and a restart would read the segment back without it.
     */
    @Test
    fun anAdoptThatLedWithTheActiveSegmentStillWritesTheFloorItsWindowPassRaised() = runTest {
        val backing = RecordingStore()
        val quota = RefuseSegmentWritesStore(backing)
        val writer = exporterFor(quota)
        writer.recover()
        repeat(25) { writer.export(record(it)) }
        quota.refuseSegmentWrites()
        val unwritten = writer.export(record(600))
        val (peerLog, _) = Rga.empty<LogRecord>().insertAllAfter(ReplicaId("C"), RgaId.HEAD, listOf(record(901)))
        val refusedMerge = writer.merge(peerLog)
        quota.acceptSegmentWrites()
        val floorBefore = writer.snapshot().compactedBelow[replica]

        val (secondPeerLog, _) = Rga.empty<LogRecord>().insertAllAfter(ReplicaId("D"), RgaId.HEAD, listOf(record(902)))
        val wholeLogAdopt = writer.merge(secondPeerLog)
        val live = writer.snapshot()
        val restarted = exporterFor(backing)
        restarted.recover()
        val recovered = restarted.snapshot()

        assertAll(
            // The rig: the active segment was unwritten, the merge adopted the whole log, and its
            // window pass raised the floor.
            { assertTrue(unwritten != ExportResult.Success, "the own record was written") },
            { assertTrue(refusedMerge != ExportResult.Success, "the first merge was written") },
            { assertEquals(ExportResult.Success, wholeLogAdopt, "the whole-log adopt") },
            { assertTrue(live.compactedBelow[replica] > floorBefore, "the pass left the floor at $floorBefore") },
            // The property.
            {
                assertEquals(
                    live.compactedBelow[replica],
                    recovered.compactedBelow[replica],
                    "the floor the pass raised is not on disk",
                )
            },
            { assertEquals(live.toList(), recovered.toList(), "the restart reads back other records") },
        )
    }

    @Test
    fun aRefusedWholeLogAdoptByAMergeClaimsEveryMintedSeq() =
        aRefusedWholeLogAdoptClaimsEveryMintedSeq { writer ->
            val (peerLog, _) = Rga.empty<LogRecord>().insertAllAfter(ReplicaId("D"), RgaId.HEAD, listOf(record(902)))
            writer.merge(peerLog)
        }

    @Test
    fun aRefusedWholeLogAdoptByADeduplicatingExportClaimsEveryMintedSeq() =
        aRefusedWholeLogAdoptClaimsEveryMintedSeq { writer -> writer.export(record(901)) }

    /**
     * Every segment write carries no own seq past the claim of the last index write that landed
     * before it, so no id reaches a key, a snapshot or a sink ahead of its claim; and every index
     * write covers the ids of every segment it names sealed. Driven through two
     * segment-write outages, one healed in place and one carried across a restart, so the first
     * turns after recovery fail; a merge refused in that outage, so the next merge adopts the whole
     * log with an unwritten record in it; rolls, window passes that retire segments, and a clear.
     *
     * The second half is [WarpLogRecordExporterUnreadSealedSegmentIdsTest]'s invariant, run here
     * through refused writes. Before #3447 the two pulled against each other, since a claim past the
     * keys wedged the floor; the window now crosses that hole, so a claim may only be too low.
     */
    @Test
    fun everySegmentWrittenStaysUnderTheClaimAndEveryIndexCoversWhatItSeals() = runTest {
        val backing = RecordingStore()
        val quota = RefuseSegmentWritesStore(backing)
        val writer = shortLeaseExporterFor(quota)
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
        val restarted = shortLeaseExporterFor(quota)
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
        var claimed = 0L
        var claimedLamport = 0L
        var claimRaises = 0
        var segmentWritesChecked = 0
        operations.forEachIndexed { at, op ->
            when (op.kind) {
                StoreOpKind.READ -> Unit
                StoreOpKind.DELETE -> disk.remove(op.key.name)
                StoreOpKind.WRITE -> {
                    val bytes = requireNotNull(op.bytes)
                    if (op.key.name.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST)) {
                        segmentWritesChecked++
                        val segment = decodeSegmentForTest(bytes)
                        val newest = segment.operations().mapNotNull { segmentOp ->
                            (segmentOp as? RgaOp.Insert)?.id?.takeIf { it.replicaId == replica }?.seq
                        }.maxOrNull() ?: 0L
                        // An adopt carries a peer's copy of this replica's ids, and its own index
                        // write, which names the segment, lands first and claims them.
                        if (newest > claimed) breaches += "operation $at: segment carries seq $newest past the claim $claimed"
                    }
                    if (op.key == INDEX_KEY_FOR_TEST) {
                        val index = decodeIndexForTest(bytes)
                        indexesChecked++
                        retiredSomething = retiredSomething || index.retired.isNotEmpty()
                        // A claim only rises: a later index write restating a lower one would
                        // un-claim ids already minted under it.
                        if (index.seqHighWater < claimed || index.lamportHighWater < claimedLamport) {
                            breaches += "operation $at: index lowers the claim to " +
                                "(${index.seqHighWater}, ${index.lamportHighWater}) from ($claimed, $claimedLamport)"
                        }
                        if (index.seqHighWater > claimed) claimRaises++
                        claimed = index.seqHighWater
                        claimedLamport = index.lamportHighWater
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
            { assertTrue(segmentWritesChecked > 20, "only $segmentWritesChecked segment writes checked") },
            // The rig: the short lease renewed the claim, so the renewal branch ran, many times.
            { assertTrue(claimRaises > 8, "the claim rose only $claimRaises times") },
            { assertTrue(segmentsChecked > 20, "only $segmentsChecked sealed segments checked") },
            { assertTrue(retiredSomething, "no window pass retired a segment") },
            // The property.
            { assertEquals(emptyList(), breaches) },
        )
    }
}
