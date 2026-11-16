package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Another author's tombstones that a merge sorts **after** every visible record must still leave
 * the log once the window moves past them (#3528).
 *
 * The window is cut in list order and keeps the trailing [WarpLogRecordExporter]'s `maxRecords`
 * visible records, with every tombstone among them. So a tombstone stays only while something
 * visible follows it. New exports are the something: they are appended after the predecessor the
 * exporter keeps, and while that predecessor was the last *visible* record, an export landed inside
 * that record's subtree, ahead of a trailing run of tombstones behind it. The run then sat at the
 * end of the list for good. For this replica's own records #3511 drops them by arrival order;
 * nothing did the same for a foreign author's.
 *
 * **The peer is an [Rga], not a second exporter.** An exporter never tombstones its own newest
 * record — a turn whose eviction takes the whole buffer still appends after it (#3522) — so a
 * subtree its author fully tombstoned has to come from a replica whose list was ordered
 * differently when it evicted. The ops built here are the ones an exporter applies itself
 * (`insertAllAfter`, then `removeFirst`), which is the precedent
 * `WarpLogRecordExporterTailCacheTest.mergedRemoteTombstoneFreesTheDedupSlot` set.
 *
 * Replica names decide the sibling order at the list's head when both roots share a lamport, so
 * [peer] is named to sort *after* [local]. The test asserts that precondition rather than trusting
 * the names.
 */
class WarpLogRecordExporterForeignTrailingTombstoneTest {

    private val local = ReplicaId("local")

    /** Sorts below [local] at an equal lamport, so its root lands after [local]'s subtree. */
    private val peer = ReplicaId("author")

    /** Sorts above [local] at an equal lamport, so its root lands ahead of [local]'s at a tie. */
    private val laterPeer = ReplicaId("zeta")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    @Test
    fun aForeignTrailingTombstoneRunLeavesTheLogOnceTheWindowPassesIt() = runTest {
        val maxRecords = 2
        val exporter = WarpLogRecordExporter(
            replica = local,
            store = InMemoryDurableStore(),
            maxRecords = maxRecords,
            bufferPolicy = BufferPolicy.DROP_OLDEST,
            segmentOps = 8,
        )
        exporter.export(record(1))
        exporter.export(record(2))

        // The peer's whole subtree, written and then tombstoned by its author.
        val (written, _) = Rga.empty<LogRecord>()
            .insertAllAfter(replica = peer, after = RgaId.HEAD, values = listOf(record(101), record(102)))
        val (peerLog, _) = written.removeFirst(2)
        exporter.merge(peerLog)

        val merged = exporter.snapshot()
        val peerIds = merged.sequence.filter { it.replicaId == peer }
        // Precondition: the merge put the peer's run at the end of the list, behind every visible
        // record, and it is all tombstones.
        assertAll(
            { assertEquals(2, peerIds.size, "the merge must deliver both peer records") },
            { assertEquals(peerIds, merged.sequence.takeLast(2), "the peer's run must trail the list") },
            { assertTrue(merged.tombstones.containsAll(peerIds), "the peer's run must be tombstoned") },
        )

        // Every later export evicts one record, and a pass runs once per maxRecords evictions,
        // so this is ten passes.
        repeat(10 * maxRecords) { i -> exporter.export(record(1_000 + i)) }

        val after = exporter.snapshot()
        assertAll(
            // The rig: passes ran, so this replica's own first record was cut from the log.
            {
                assertTrue(
                    after.sequence.none { it.replicaId == local && it.seq == 1L },
                    "the window must have moved past this replica's own oldest record",
                )
            },
            {
                assertEquals(
                    emptyList(),
                    after.sequence.filter { it.replicaId == peer },
                    "the peer's trailing tombstones must leave the log once the window passes them",
                )
            },
        )
    }

    /**
     * This replica's **own** trailing tombstones are not an anchor. They are the #3447 shape:
     * records that arrived before newer own records but sort after them, and `withOlderOwn` drops
     * them through the floor, which keeps no position. A record appended after one of them then
     * re-roots to the list's head when the pass drops it, jumps ahead of older survivors, and is
     * the record DROP_OLDEST evicts next. So the anchor skips them and falls back to the last
     * visible record, as before #3528.
     *
     * The own records arrive through [WarpLogRecordExporter.merge], built on an [Rga] so that the
     * older pair (seqs 1 and 2) carries the lower lamports and sorts after the newer three.
     */
    @Test
    fun anOwnTrailingTombstoneIsNotTheAnchorForTheNextExport() = runTest {
        val exporter = WarpLogRecordExporter(
            replica = local,
            store = InMemoryDurableStore(),
            maxRecords = 3,
            bufferPolicy = BufferPolicy.DROP_OLDEST,
            segmentOps = 8,
        )
        val (older, _) = Rga.empty<LogRecord>()
            .insertAllAfter(replica = local, after = RgaId.HEAD, values = listOf(record(1), record(2)))
        val (olderTombstoned, _) = older.removeFirst(2)
        val (ownLog, _) = olderTombstoned
            .insertAllAfter(replica = local, after = RgaId.HEAD, values = listOf(record(11), record(12), record(13)))
        exporter.merge(ownLog)

        val merged = exporter.snapshot()
        val olderIds = merged.sequence.filter { it.seq <= 2L }
        assertAll(
            { assertEquals(olderIds, merged.sequence.takeLast(2), "the older own pair must trail the list") },
            { assertTrue(merged.tombstones.containsAll(olderIds), "the older own pair must be tombstoned") },
            { assertEquals(listOf("body-11", "body-12", "body-13"), merged.toList().map { it.body }) },
        )

        exporter.export(record(100))
        // One foreign record pushes the buffer past the cap, so the merge runs a pass. It drops
        // the oldest own records, and with them the older pair, through the floor.
        val (foreign, _) = Rga.empty<LogRecord>()
            .insertAllAfter(replica = peer, after = RgaId.HEAD, values = listOf(record(500)))
        exporter.merge(foreign)
        exporter.export(record(101))

        assertEquals(
            listOf("body-100", "body-500", "body-101"),
            exporter.snapshot().toList().map { it.body },
            "DROP_OLDEST must evict the oldest record, 13, and keep the newer 100",
        )
    }

    /**
     * Own then foreign, the foreign run a sibling: `[N1 N2 N3 U1† U2† P1† P2†]`. The first own
     * tombstone stops the run, so the anchor is N3 until a pass drops U1 and U2. Then the next
     * rebuild anchors on P2 and the run goes on a later pass.
     */
    @Test
    fun aRunOfOwnThenForeignTombstonesLeavesTheLog() = runTest {
        val exporter = exporterAtCap3()
        exporter.merge(ownLogWithOlderPairTrailing())
        exporter.merge(tombstonedRoot(peer, listOf(record(201), record(202))))
        val trailing = exporter.snapshot().sequence.takeLast(4)
        assertAll(
            { assertEquals(listOf(local, local, peer, peer), trailing.map { it.replicaId }) },
            { assertEquals(listOf(1L, 2L, 1L, 2L), trailing.map { it.seq }) },
            { assertTrue(exporter.snapshot().tombstones.containsAll(trailing)) },
        )

        exportAndAssertTheRunLeft(exporter)
    }

    /**
     * Foreign then own: `[N1 N2 N3 Z1† Z2† U1† U2†]`. The run starts foreign, so Z2 is the anchor.
     * The export goes behind it and ahead of U1, which the older-own drop takes on the first pass.
     */
    @Test
    fun aRunOfForeignThenOwnTombstonesLeavesTheLog() = runTest {
        val exporter = exporterAtCap3()
        exporter.merge(ownLogWithOlderPairTrailing())
        exporter.merge(tombstonedRoot(laterPeer, listOf(record(201), record(202))))
        val trailing = exporter.snapshot().sequence.takeLast(4)
        assertAll(
            { assertEquals(listOf(laterPeer, laterPeer, local, local), trailing.map { it.replicaId }) },
            { assertTrue(exporter.snapshot().tombstones.containsAll(trailing)) },
        )

        exportAndAssertTheRunLeft(exporter)
    }

    /**
     * A foreign tombstone that **hangs off** an own trailing one: `[N1 N2 N3 U1† U2† P1†]`, with
     * P1 inserted after U2 at a lamport above every N. Anchoring on P1 would put the next export
     * in P1's subtree. The pass drops U2 through the floor, P1 re-roots to the head of the list on
     * its higher lamport, and the export goes with it, ahead of N3, so DROP_OLDEST evicts the newer
     * record. This is why the run is not read past its first own tombstone.
     */
    @Test
    fun aForeignTombstoneBehindAnOwnOneIsNotTheAnchor() = runTest {
        val exporter = exporterAtCap3()
        val own = ownLogWithOlderPairTrailing()
        val (withChild, _) = own.insertAllAfter(replica = peer, after = own.sequence.last(), values = listOf(record(300)))
        val childIndex = withChild.entries().indexOfFirst { (id, _) -> id.replicaId == peer }
        val (peerLog, _) = assertNotNull(withChild.removeAt(childIndex))
        exporter.merge(peerLog)
        val merged = exporter.snapshot()
        val trailing = merged.sequence.takeLast(3)
        assertAll(
            { assertEquals(listOf(local, local, peer), trailing.map { it.replicaId }) },
            { assertTrue(merged.tombstones.containsAll(trailing)) },
            { assertTrue(trailing.last().lamport > merged.sequence.first().lamport, "P1 must outrank N1") },
            { assertEquals(listOf("body-11", "body-12", "body-13"), merged.toList().map { it.body }) },
        )

        exporter.export(record(100))
        val (foreign, _) = Rga.empty<LogRecord>()
            .insertAllAfter(replica = peer, after = RgaId.HEAD, values = listOf(record(500)))
        exporter.merge(foreign)
        exporter.export(record(101))

        assertEquals(
            listOf("body-100", "body-500", "body-101"),
            exporter.snapshot().toList().map { it.body },
            "DROP_OLDEST must evict the oldest record, 13, and keep the newer 100",
        )
    }

    /**
     * The peer's own next window pass, arriving by merge, drops its trailing tombstones through
     * its **floor**, which keeps no position. A record of ours appended after one of them would
     * re-root to the head of the list on its higher lamport, ahead of older records, and be the
     * one DROP_OLDEST evicts. So no export may hang off a foreign tombstone.
     */
    @Test
    fun theAuthorsFloorOverItsTrailingTombstonesKeepsArrivalOrder() = runTest {
        val peerLog = tombstonedRoot(peer, listOf(record(201), record(202)))
        assertRemovalKeepsArrivalOrder(peerLog, removal = assertNotNull(peerLog.dropWindow(peer, peerLog.sequence.toSet())).first)
    }

    /**
     * A third replica's window pass drops the peer's trailing tombstones as foreign dots, through
     * an `RgaOp.Compact`. That keeps each dropped id's predecessor, but the run is rooted at the
     * head of the list, so a record appended after it re-roots there all the same.
     */
    @Test
    fun aThirdReplicasCompactOverTheTrailingTombstonesKeepsArrivalOrder() = runTest {
        val peerLog = tombstonedRoot(peer, listOf(record(201), record(202)))
        val third = ReplicaId("third")
        assertRemovalKeepsArrivalOrder(peerLog, removal = assertNotNull(peerLog.dropWindow(third, peerLog.sequence.toSet())).first)
    }

    /**
     * Export r1 and r2, merge [peerLog] (trailing, fully tombstoned), export r3, merge [removal],
     * export r4 at cap 3: DROP_OLDEST must take r1, the oldest.
     */
    private suspend fun assertRemovalKeepsArrivalOrder(peerLog: Rga<LogRecord>, removal: Rga<LogRecord>) {
        val exporter = exporterAtCap3()
        exporter.export(record(1))
        exporter.export(record(2))
        exporter.merge(peerLog)
        val merged = exporter.snapshot()
        assertAll(
            { assertEquals(listOf(peer, peer), merged.sequence.takeLast(2).map { it.replicaId }, "the run must trail") },
            { assertTrue(merged.tombstones.containsAll(merged.sequence.takeLast(2)), "the run must be tombstoned") },
        )
        exporter.export(record(3))
        exporter.merge(removal)
        assertTrue(
            exporter.snapshot().sequence.none { it.replicaId == peer },
            "precondition: the merged removal took the peer's run out of the log",
        )
        exporter.export(record(4))

        assertEquals(
            listOf("body-2", "body-3", "body-4"),
            exporter.snapshot().toList().map { it.body },
            "DROP_OLDEST must evict r1, the oldest, not r3",
        )
    }

    private fun exporterAtCap3() = WarpLogRecordExporter(
        replica = local,
        store = InMemoryDurableStore(),
        maxRecords = 3,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = 8,
    )

    /**
     * `[N1 N2 N3 U1† U2†]`, all [local]'s. U1 and U2 hold seqs 1 and 2 and the lower lamports, so
     * they sort after the newer N1 to N3 (seqs 3 to 5), as a restart past an unread segment leaves
     * them (#3447).
     */
    private fun ownLogWithOlderPairTrailing(): Rga<LogRecord> {
        val (older, _) = Rga.empty<LogRecord>()
            .insertAllAfter(replica = local, after = RgaId.HEAD, values = listOf(record(1), record(2)))
        val (olderTombstoned, _) = older.removeFirst(2)
        return olderTombstoned
            .insertAllAfter(replica = local, after = RgaId.HEAD, values = listOf(record(11), record(12), record(13)))
            .first
    }

    /** A fresh [author]'s chain of [values] from the head of the list, every one tombstoned. */
    private fun tombstonedRoot(author: ReplicaId, values: List<LogRecord>): Rga<LogRecord> {
        val (written, _) = Rga.empty<LogRecord>().insertAllAfter(replica = author, after = RgaId.HEAD, values = values)
        return written.removeFirst(values.size).first
    }

    /** Ten passes of exports. Then nothing of the run is left, and the survivors are the newest three. */
    private suspend fun exportAndAssertTheRunLeft(exporter: WarpLogRecordExporter) {
        repeat(30) { i -> exporter.export(record(1_000 + i)) }
        val after = exporter.snapshot()
        assertAll(
            { assertEquals(listOf("body-1027", "body-1028", "body-1029"), after.toList().map { it.body }) },
            { assertEquals(emptyList(), after.sequence.filter { it.replicaId != local }, "no peer tombstone may stay") },
            { assertEquals(emptyList(), after.sequence.filter { it.replicaId == local && it.seq <= 2L }, "the older own pair may not stay") },
        )
    }
}
