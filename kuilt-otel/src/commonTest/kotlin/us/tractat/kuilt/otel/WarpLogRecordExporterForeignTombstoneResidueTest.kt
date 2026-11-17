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
 * Characterises the residue #3528 accepted: another author's tombstones that a merge sorts
 * **after** every visible record stay in the log through any number of local window passes, and
 * leave it when the author's own window pass over them arrives by [WarpLogRecordExporter.merge].
 *
 * An ordinary local pass is not allowed to cut them. Doing so is a `Compact`; its dependants
 * re-root through the run's predecessor, to HEAD once that is floored. So a peer that still holds
 * those ids visible may append after one, and that record sorts ahead of older records and is the
 * one DROP_OLDEST evicts. Three attempts on #3533 each reordered records
 * that way. These arms pin the behaviour as it stands, so a fourth attempt has to move them on
 * purpose.
 *
 * **The peer is an [Rga], not a second exporter.** An exporter never tombstones its own newest
 * record (#3522), so a run its author fully tombstoned has to come from a replica whose list was
 * ordered differently when it evicted. The ops built here are the ones an exporter applies itself:
 * `insertAllAfter`, then a removal, then `dropWindow` under the author's own replica id for its
 * pass, which raises the author's floor.
 */
class WarpLogRecordExporterForeignTombstoneResidueTest {

    private val local = ReplicaId("local")

    /** Sorts below [local] at an equal lamport, so a root of its at the head lands after [local]'s. */
    private val peer = ReplicaId("author")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporter(maxRecords: Int) = WarpLogRecordExporter(
        replica = local,
        store = InMemoryDurableStore(),
        maxRecords = maxRecords,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = 8,
    )

    @Test
    fun foreignTrailingTombstonesSurviveEveryLocalWindowPass() = runTest {
        val maxRecords = 2
        val exporter = exporter(maxRecords)
        exporter.export(record(1))
        exporter.export(record(2))

        // The author's whole run, written from the head and then tombstoned by the author.
        val (written, _) = Rga.empty<LogRecord>()
            .insertAllAfter(replica = peer, after = RgaId.HEAD, values = listOf(record(101), record(102)))
        val (peerLog, _) = written.removeFirst(2)
        exporter.merge(peerLog)

        val merged = exporter.snapshot()
        val peerIds = merged.sequence.filter { it.replicaId == peer }
        assertAll(
            { assertEquals(2, peerIds.size, "the merge must deliver both of the author's records") },
            { assertEquals(peerIds, merged.sequence.takeLast(2), "the author's run must trail the list") },
            { assertTrue(merged.tombstones.containsAll(peerIds), "the author's run must be tombstoned") },
        )

        // Each export evicts one record, and a pass runs once per maxRecords evictions: ten passes.
        val exports = 10 * maxRecords
        repeat(exports) { i -> exporter.export(record(1_000 + i)) }

        val after = exporter.snapshot()
        val ownSeqs = after.sequence.filter { it.replicaId == local }.map { it.seq }
        assertAll(
            // The rig: the passes ran, and the last of them cut every own record older than the
            // window, so the run below survived all ten rather than none.
            {
                assertTrue(
                    ownSeqs.all { it > exports.toLong() },
                    "every own record older than the window must have left the log; own seqs $ownSeqs",
                )
            },
            { assertEquals(listOf("body-1018", "body-1019"), after.toList().map { it.body }) },
            {
                assertEquals(
                    peerIds,
                    after.sequence.filter { it.replicaId == peer },
                    "the author's trailing tombstones stay until the author's own pass reaches them",
                )
            },
            { assertTrue(after.tombstones.containsAll(peerIds), "and they stay tombstones") },
        )
    }

    /**
     * The author held our records, appended its own after our newest at a **higher** lamport, and
     * tombstoned them. Our next export goes after our newest visible record at a lamport above the
     * author's, so it sorts ahead of the author's run, which then trails. When the author's own
     * pass drops that run through its floor and arrives here by merge, the run leaves the log, and
     * the records keep their arrival order: DROP_OLDEST still takes the oldest.
     */
    @Test
    fun theAuthorsOwnPassArrivingByMergeClearsTheRunAndKeepsArrivalOrder() = runTest {
        val exporter = exporter(maxRecords = 3)
        exporter.export(record(1))
        exporter.export(record(2))

        val held = exporter.snapshot()
        val (appended, inserts) = held.insertAllAfter(
            replica = peer,
            after = held.sequence.last(),
            values = listOf(record(201), record(202)),
        )
        val authorIds = inserts.map { it.id }
        val authorLog = authorIds.fold(appended) { log, id ->
            assertNotNull(log.removeAt(log.entries().indexOfFirst { (entryId, _) -> entryId == id })).first
        }
        exporter.merge(authorLog)
        exporter.export(record(3))

        val before = exporter.snapshot()
        assertAll(
            {
                assertTrue(
                    authorIds.minOf { it.lamport } > held.sequence.maxOf { it.lamport },
                    "the author's clock must be ahead of every record we had when it wrote",
                )
            },
            { assertEquals(authorIds, before.sequence.takeLast(2), "the author's run must trail the list") },
            { assertTrue(before.tombstones.containsAll(authorIds), "the author's run must be tombstoned") },
            { assertEquals(listOf("body-1", "body-2", "body-3"), before.toList().map { it.body }) },
        )

        // The author's own window pass over its run: a floor raised past both, with no position kept.
        val (authorPass, _) = assertNotNull(authorLog.dropWindow(peer, authorIds.toSet()))
        exporter.merge(authorPass)
        exporter.export(record(4))

        val after = exporter.snapshot()
        assertAll(
            {
                assertEquals(
                    emptyList(),
                    after.sequence.filter { it.replicaId == peer },
                    "the author's pass must take its run out of the log",
                )
            },
            {
                assertEquals(
                    listOf("body-2", "body-3", "body-4"),
                    after.toList().map { it.body },
                    "DROP_OLDEST must evict 1, the oldest, and keep arrival order",
                )
            },
        )
    }
}
