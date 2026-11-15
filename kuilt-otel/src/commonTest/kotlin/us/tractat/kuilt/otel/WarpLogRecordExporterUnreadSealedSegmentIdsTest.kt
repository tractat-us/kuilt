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
 * Pins that a sealed segment recovery could not read never has its ids minted again (#3410).
 *
 * [WarpLogRecordExporter] keeps a refused sealed segment named, so a later start can read it. But
 * the next `RgaId` comes from the log recovery *did* read. When the unread segment holds this
 * replica's newest ids, the first export after that start mints one of them again, and the next
 * clean start's union keeps one record of each pair. The newest ids sit in a sealed segment at
 * every roll boundary: right after a roll the active key has never been written.
 *
 * Each arm asserts its rig fired: the read was really refused, the index really named the segment
 * sealed, and the active key really was unwritten. Both refusal shapes a store produces are run: a
 * plain throw, and a cancellation the store mints from its own `withTimeout` at a live caller.
 */
class WarpLogRecordExporterUnreadSealedSegmentIdsTest {

    private val replica = ReplicaId("A")

    private fun recordId(id: Int): ByteString = ByteString(ByteArray(8) { i -> (id.toLong() shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    /** Room for every record, so nothing is evicted; eight ops to a segment, so 8 exports roll. */
    private fun exporterFor(store: DurableStore) = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = 100,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = 8,
    )

    private suspend fun bodiesAfterRestart(store: DurableStore): List<String?> =
        exporterFor(store).also { it.recover() }.snapshot().toList().map { it.body }

    /**
     * The seqs of this replica's ids a clean start holds. A raise that kept the lamport but lost
     * the seq mints a fresh id on a used dot, so every record survives and the dot does not.
     */
    private suspend fun ownSeqsAfterRestart(store: DurableStore): List<Long> =
        exporterFor(store).also { it.recover() }.snapshot().entries()
            .map { it.first }.filter { it.replicaId == replica }.map { it.seq }

    private suspend fun indexOf(store: RecordingStore): LogSegmentIndex =
        decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST)) { "no index written" })

    /** How the store refuses the one segment read. */
    enum class Refusal { THROW, MINT }

    /**
     * A store over [backing] that refuses exactly one read of segment [number] in the shape [how],
     * and a probe returning how many times it did.
     */
    private fun refusingOneRead(backing: DurableStore, number: Long, how: Refusal): Pair<DurableStore, () -> Int> {
        val key = StoreKey(segmentKeyForTest(number))
        return when (how) {
            Refusal.THROW -> {
                val store = ReadRefusingStore(backing, key)
                store to { store.refusals }
            }
            Refusal.MINT -> {
                val store = CancellationScriptedStore(backing)
                store.arm(StoreCall.READ, StoreAct.MINT) { it == key }
                store to { store.minted }
            }
        }
    }

    /**
     * Export [count] records one at a time into a fresh store, then start a second exporter whose
     * read of sealed segment [unread] is refused, and export [after] through it, one at a time.
     * Returns the backing store, what the rig saw, and each later export's result.
     */
    private suspend fun runRepro(count: Int, unread: Long, after: List<Int>, how: Refusal): Repro {
        val backing = RecordingStore()
        val writer = exporterFor(backing)
        repeat(count) { writer.export(record(it)) }
        val index = indexOf(backing)
        val activeKeyWritten = segmentKeyForTest(index.active) in backing.keys()

        val (refusing, refusals) = refusingOneRead(backing, unread, how)
        val restarted = exporterFor(refusing)
        restarted.recover()
        val refusedOnRecover = refusals()
        val results = after.map { restarted.export(record(it)) }
        return Repro(backing, index, activeKeyWritten, refusedOnRecover, results)
    }

    private class Repro(
        val backing: RecordingStore,
        val indexAtRestart: LogSegmentIndex,
        val activeKeyWritten: Boolean,
        val refusedOnRecover: Int,
        val results: List<ExportResult>,
    )

    // ── The first repro: one roll, segment 0 unread ─────────────────────────────────────────

    @Test
    fun aRefusedSealedSegmentAtTheFirstRollKeepsEveryRecordOnAThrow() = runTest { firstRollKeepsEveryRecord(Refusal.THROW) }

    @Test
    fun aRefusedSealedSegmentAtTheFirstRollKeepsEveryRecordOnAMintedCancellation() =
        runTest { firstRollKeepsEveryRecord(Refusal.MINT) }

    private suspend fun firstRollKeepsEveryRecord(how: Refusal) {
        val repro = runRepro(count = 8, unread = 0L, after = listOf(1000), how = how)
        val expected = ((0..7) + 1000).map { "body-$it" }.toSet()
        val recovered = bodiesAfterRestart(repro.backing)
        val seqs = ownSeqsAfterRestart(repro.backing)
        assertAll(
            // The rig: the eighth export rolled, so segment 0 is sealed and holds ids 1..8, and the
            // active key the roll opened was never written.
            { assertEquals(listOf(0L), repro.indexAtRestart.sealedSegments, "the index names segment 0 sealed") },
            { assertEquals(1L, repro.indexAtRestart.active, "the roll opened segment 1") },
            { assertEquals(false, repro.activeKeyWritten, "the active key is unwritten at the roll boundary") },
            { assertEquals(1, repro.refusedOnRecover, "recover's read of segment 0 was refused") },
            { assertEquals(listOf<ExportResult>(ExportResult.Success), repro.results, "the export after it succeeded") },
            // The property: nine records went in, nine come back.
            { assertEquals(expected, recovered.toSet(), "a clean start holds every record") },
            { assertEquals(9, recovered.size, "and holds each once") },
            { assertEquals(seqs.distinct(), seqs, "no seq is minted twice") },
        )
    }

    // ── The second repro: the second roll, segment 1 unread ────────────────────────────────

    @Test
    fun aRefusedSealedSegmentAtALaterRollKeepsEveryRecordOnAThrow() = runTest { laterRollKeepsEveryRecord(Refusal.THROW) }

    @Test
    fun aRefusedSealedSegmentAtALaterRollKeepsEveryRecordOnAMintedCancellation() =
        runTest { laterRollKeepsEveryRecord(Refusal.MINT) }

    private suspend fun laterRollKeepsEveryRecord(how: Refusal) {
        val repro = runRepro(count = 16, unread = 1L, after = listOf(100, 101), how = how)
        val expected = ((0..15) + 100 + 101).map { "body-$it" }.toSet()
        val recovered = bodiesAfterRestart(repro.backing)
        val seqs = ownSeqsAfterRestart(repro.backing)
        assertAll(
            { assertEquals(listOf(0L, 1L), repro.indexAtRestart.sealedSegments, "the index names segments 0 and 1 sealed") },
            { assertEquals(2L, repro.indexAtRestart.active, "the second roll opened segment 2") },
            { assertEquals(false, repro.activeKeyWritten, "the active key is unwritten at the roll boundary") },
            { assertEquals(1, repro.refusedOnRecover, "recover's read of segment 1 was refused") },
            {
                assertEquals(
                    listOf<ExportResult>(ExportResult.Success, ExportResult.Success),
                    repro.results,
                    "both exports after it succeeded",
                )
            },
            { assertEquals(expected, recovered.toSet(), "a clean start holds every record, body-8 and body-9 included") },
            { assertEquals(18, recovered.size, "and holds each once") },
            { assertEquals(seqs.distinct(), seqs, "no seq is minted twice") },
        )
    }

    // ── The invariant both repros rest on ──────────────────────────────────────────────────

    /**
     * Every index the exporter writes covers this replica's ids in every segment it names as
     * sealed, **from the write that first names it**: a merge's adopt names its segment before
     * writing it, and a roll names the segment it seals after writing it. A sealed key is never
     * written again and a number is never reused, so the last bytes written under a key are that
     * segment's sealed content.
     *
     * Driven through rolls, evictions, window passes that retire segments, two merges, and a clear.
     */
    @Test
    fun everyIndexWrittenCoversTheIdsOfEverySegmentItNamesSealed() = runTest {
        val backing = RecordingStore()
        val writer = WarpLogRecordExporter(replica, backing, maxRecords = 10, segmentOps = 8)
        repeat(30) { writer.export(record(it)) }
        val (peerLog, _) = Rga.empty<LogRecord>().insertAllAfter(ReplicaId("B"), RgaId.HEAD, listOf(record(900)))
        assertEquals(ExportResult.Success, writer.merge(peerLog))
        repeat(30) { writer.export(record(100 + it)) }
        assertEquals(ExportResult.Success, writer.merge(writer.snapshot().piece(peerLog)))
        assertEquals(ExportResult.Success, writer.clear())
        repeat(20) { writer.export(record(200 + it)) }

        val writes = backing.operations().filter { it.kind == StoreOpKind.WRITE }
        val sealedContent = writes.associate { it.key.name to requireNotNull(it.bytes) }
        var segmentsChecked = 0
        var idsChecked = 0
        val breaches = mutableListOf<String>()
        writes.filter { it.key == INDEX_KEY_FOR_TEST }.forEachIndexed { at, write ->
            val index = decodeIndexForTest(requireNotNull(write.bytes))
            for (number in index.sealedSegments) {
                val bytes = sealedContent[segmentKeyForTest(number)] ?: continue
                val segment = decodeSegmentForTest(bytes)
                val own = segment.operations().flatMap { op ->
                    when (op) {
                        is RgaOp.Insert -> sequenceOf(op.id)
                        is RgaOp.Remove -> emptySequence()
                        is RgaOp.Compact -> op.positions.keys.asSequence()
                    }
                }.filter { it.replicaId == replica }.toList()
                segmentsChecked++
                idsChecked += own.size
                own.filter { it.lamport > index.lamportHighWater || it.seq > index.seqHighWater }.forEach { id ->
                    breaches += "index write $at ($index) does not cover $id in segment $number"
                }
                if (segment.compactedBelow[replica] > index.seqHighWater) {
                    breaches += "index write $at ($index) is below segment $number's floor ${segment.compactedBelow}"
                }
            }
        }
        val retiredSomething = writes.any { it.key == INDEX_KEY_FOR_TEST && decodeIndexForTest(requireNotNull(it.bytes)).retired.isNotEmpty() }
        assertAll(
            // The rig: the run sealed and retired segments, and the walk read ids out of them.
            { assertTrue(segmentsChecked > 20, "only $segmentsChecked sealed segments checked") },
            { assertTrue(idsChecked > 50, "only $idsChecked ids checked") },
            { assertTrue(retiredSomething, "no window pass retired a segment") },
            { assertEquals(emptyList(), breaches) },
        )
    }

    // ── A clear keeps the high-water ────────────────────────────────────────────────────────

    /**
     * A clear forgets every record, and the floor it writes keeps this replica's **seqs** used. It
     * does not keep the Lamport clock: a floor records seqs only, and a clear leaves no op of this
     * replica's carrying a lamport. A merge can lift the clock above the seq, and then a restart
     * after the clear mints a lamport this replica already used, for a different id. Two ids from
     * one author never share a lamport; [RgaId.compareTo] returns `0` for such a pair on any peer
     * still holding the old one.
     *
     * The peer's log carries an [RgaOp.Compact], so its adopted segment is never retired and the
     * clear has nothing to retire. Its own index write is then the only one that could carry the
     * clock.
     */
    @Test
    fun aClearKeepsTheClockAboveEveryIdItForgot() = runTest {
        val backing = RecordingStore()
        val writer = exporterFor(backing)
        val peer = ReplicaId("B")
        val (twoRecords, peerIds) = Rga.empty<LogRecord>().insertAllAfter(peer, RgaId.HEAD, listOf(record(500), record(501)))
        val (peerLog, _) = requireNotNull(twoRecords.dropWindow(peer, setOf(peerIds.last().id)))
        assertEquals(ExportResult.Success, writer.merge(peerLog))
        repeat(3) { writer.export(record(it)) }
        val ownBeforeClear = writer.snapshot().entries().map { it.first }.filter { it.replicaId == replica }
        val maxLamport = ownBeforeClear.maxOf { it.lamport }
        val maxSeq = ownBeforeClear.maxOf { it.seq }

        assertEquals(ExportResult.Success, writer.clear())
        val indexAfterClear = indexOf(backing)

        val restarted = exporterFor(backing)
        restarted.recover()
        assertEquals(ExportResult.Success, restarted.export(record(1000)))
        val minted = restarted.snapshot().entries().single { it.second.recordId == recordId(1000) }.first

        assertAll(
            // The rig: the merge lifted the clock above the seq, the peer's segment carries a
            // Compact so the clear retired nothing, and the clear really forgot the records.
            { assertTrue(peerLog.compactOpCount > 0, "the peer's log carries a Compact") },
            { assertTrue(maxLamport > maxSeq, "the merge lifted the clock above the seq: $ownBeforeClear") },
            { assertTrue(indexAfterClear.sealedSegments.isNotEmpty(), "the adopted segment is still sealed") },
            { assertTrue(indexAfterClear.retired.isEmpty(), "the clear retired nothing") },
            { assertEquals(listOf("body-1000"), restarted.snapshot().toList().map { it.body }, "only the new record is held") },
            // The property: the new id is past every id the clear forgot, on both counters.
            { assertTrue(minted.lamport > maxLamport, "minted $minted reuses a lamport at or below $maxLamport") },
            { assertTrue(minted.seq > maxSeq, "minted $minted reuses a seq at or below $maxSeq") },
        )
    }
}
