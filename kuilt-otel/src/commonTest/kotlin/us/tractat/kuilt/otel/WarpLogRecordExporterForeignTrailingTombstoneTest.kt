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
}
