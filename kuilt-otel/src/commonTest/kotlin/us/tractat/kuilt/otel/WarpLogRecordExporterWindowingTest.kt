package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the **windowing** of [WarpLogRecordExporter]'s in-memory op-log (#2127).
 *
 * The defect these tests exist for: eviction only *tombstones*. `maxRecords` held
 * visibility flat, but the evicted record's `Insert` op — body and all — stayed in the
 * log forever, so the op-log grew with the number of records ever exported. The exporter
 * now batches `Rga.dropWindow` calls, which drop those ops and record the drop as a
 * per-author compaction **floor** — for this replica's *own* dots. A foreign author's cannot
 * fold into that floor and keep a per-element `RgaOp.Compact` pair instead.
 *
 * Suppression is the whole safety story, and it is what most of these tests aim at: once the
 * ops are physically gone there is no tombstone left to refuse a peer that still holds them.
 * Which suppressor does the refusing follows the same split as the cost — the floor for own
 * dots, the retained `RgaOp.Compact`'s compacted-id set for foreign ones — and so only the
 * export path is O(`maxRecords`).
 * [theOpLogIsBoundedOnTheExportPathButNotOnTheMergePath] keeps that split honest.
 */
class WarpLogRecordExporterWindowingTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int, body: String = "body-$id") = LogRecord(
        recordId = recordId(id),
        body = body,
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporterFor(
        replica: ReplicaId = replicaA,
        store: DurableStore = InMemoryDurableStore(),
        maxRecords: Int,
        bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps: Int,
    ) = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = maxRecords,
        bufferPolicy = bufferPolicy,
        segmentOps = segmentOps,
    )

    // ---- The headline claim: the in-memory op-log is O(maxRecords) ----

    @Test
    fun theInMemoryOpLogStaysBoundedAcrossManyMoreRecordsThanTheWindow() = runTest {
        val exporter = exporterFor(maxRecords = 10, segmentOps = 8)

        // The peak matters, not the endpoint: a pass fires on the 500th export, so sampling
        // only at the end would read the trough and pass even if nothing were ever dropped
        // in between. Between passes the log carries the window plus one eviction's Insert
        // and Remove per record, so the true peak is ~3x maxRecords.
        var peakOps = 0
        repeat(500) { i ->
            exporter.export(record(i))
            peakOps = maxOf(peakOps, exporter.snapshot().opCount)
        }

        assertAll(
            {
                assertTrue(
                    peakOps <= 64,
                    "500 records through a 10-record window must not leave a 500-op log; got $peakOps",
                )
            },
            { assertEquals(10, exporter.snapshot().size, "and the window itself is intact") },
        )
    }

    @Test
    fun theOpLogIsBoundedOnTheExportPathButNotOnTheMergePath() = runTest {
        // The class KDoc's bound has two arms and only ONE of them is a bound — which is the
        // whole reason this measures opCount rather than size. `Rga.dropWindow` folds THIS
        // replica's dropped dots into its own compaction floor (O(authors)), but it cannot
        // raise a foreign author's floor entry — that would annihilate dots the author may not
        // have minted yet — so foreign dots keep an explicit `RgaOp.Compact` instead, and
        // nothing ever prunes one. Same 200 records, same 5-record window, both paths, so the
        // two endpoints are directly comparable.
        //
        // opCount UNDERSTATES the merge arm: a `Compact` is one op however many
        // `(RgaId -> RgaId)` pairs it carries, and the residue here is 200 pairs. The SHAPE is
        // what is pinned — the constants deliberately are not.
        val batches = 40
        val perBatch = 5

        val exportOnly = exporterFor(maxRecords = 5, segmentOps = 8)
        repeat(batches * perBatch) { i -> exportOnly.export(record(i)) }

        val merging = exporterFor(maxRecords = 5, segmentOps = 8)
        // A cap above everything it will ever hold, so the peer never windows its own log and
        // every batch really does hand `merging` `perBatch` elements it has not seen.
        val peer = exporterFor(replica = replicaB, maxRecords = batches * perBatch, segmentOps = 64)
        var opsEarly = 0
        repeat(batches) { batch ->
            repeat(perBatch) { i -> peer.export(record(1_000 + batch * perBatch + i)) }
            merging.merge(peer.snapshot())
            if (batch == batches / 4) opsEarly = merging.snapshot().opCount
        }
        val opsLate = merging.snapshot().opCount

        // Re-merging a log that adds no new element must add nothing: the residue tracks
        // foreign elements windowed away, not the number of merge calls.
        repeat(5) { merging.merge(peer.snapshot()) }

        assertAll(
            { assertEquals(5, exportOnly.snapshot().size, "export arm: the window must be intact") },
            { assertEquals(5, merging.snapshot().size, "merge arm: the window must be intact") },
            {
                assertTrue(
                    exportOnly.snapshot().opCount <= 3 * 5,
                    "export path: ${batches * perBatch} records through a 5-record window must leave " +
                        "an O(maxRecords) op-log; got ${exportOnly.snapshot().opCount}",
                )
            },
            {
                assertTrue(
                    opsLate > opsEarly,
                    "merge path: the op-log GROWS with the number of foreign batches windowed away " +
                        "— that is what the class KDoc claims. Got $opsEarly -> $opsLate. If this " +
                        "reddened because the merge path became bounded, correct the KDoc.",
                )
            },
            {
                assertEquals(
                    opsLate,
                    merging.snapshot().opCount,
                    "re-merging the same log grew the op-log; the residue must track foreign elements, " +
                        "not merge calls",
                )
            },
        )
    }

    // ---- Merge safety once the tombstones are gone ----

    @Test
    fun aPeerHoldingWindowedAwayRecordsCannotPushThemBackIn() = runTest {
        val exporter = exporterFor(maxRecords = 5, segmentOps = 4)
        repeat(3) { i -> exporter.export(record(i, body = "early$i")) }
        val peerSnapshot = exporter.snapshot() // a peer that still holds early0..2
        repeat(30) { i -> exporter.export(record(100 + i, body = "later$i")) }

        val windowed = exporter.snapshot()
        val earlyDots = peerSnapshot.causalDots()
        exporter.merge(peerSnapshot)
        val bodies = exporter.snapshot().toList().mapNotNull { it.body }

        assertAll(
            // Without these two the last assertion would be vacuous: an evicted record is
            // suppressed by its own Remove op, and that alone already survives a merge
            // (WarpLogRecordExporterSegmentTest pins it). Windowing takes the Remove away
            // too, so the assertions below say the FLOOR is what refuses the peer.
            {
                assertTrue(
                    earlyDots.none { it in windowed.causalDots() },
                    "precondition: the early ops must be physically gone, leaving no tombstone to lean on",
                )
            },
            {
                assertTrue(
                    earlyDots.all { windowed.causalFloor().contains(it) },
                    "precondition: the floor must cover every windowed-away dot; got ${windowed.causalFloor()}",
                )
            },
            { assertTrue(bodies.none { it.startsWith("early") }, "windowed-away records must not return; got $bodies") },
        )
    }

    @Test
    fun aFullDropNewestBufferDoesNotGrowUnboundedWhenAPeerMergesIn() = runTest {
        // DROP_NEWEST refuses arrivals instead of evicting, so its eviction count is
        // permanently zero and the eviction-count trigger alone would never fire. `merge` is
        // public gossip API and folds a remote log in wholesale, so it is the one path that
        // grows this policy's log — and the only thing that can window it back down is the
        // size arm of the trigger.
        val a = exporterFor(maxRecords = 5, bufferPolicy = BufferPolicy.DROP_NEWEST, segmentOps = 4)
        val b = exporterFor(replica = replicaB, maxRecords = 5, segmentOps = 4)
        repeat(20) { i ->
            a.export(record(i, body = "a$i"))
            b.export(record(1_000 + i, body = "b$i"))
        }

        a.merge(b.snapshot())

        assertAll(
            { assertEquals(5, a.snapshot().size, "a merge must not leave the window over cap") },
            {
                // The name promises the OP-LOG, so measure it and not just visibility. Both
                // logs arrive already windowed at 5, so an un-windowed merge would leave the
                // whole 10-op union standing; the pass leaves the 5-record window plus the one
                // `RgaOp.Compact` it had to mint for b's dropped dots (b authored them, so they
                // cannot fold into a's floor). `<=` rather than `==`: a later change that
                // reclaimed that pair too would be an improvement, not a regression.
                assertTrue(
                    a.snapshot().opCount <= 6,
                    "the merge left an unwindowed op-log; got ${a.snapshot().opCount} ops",
                )
            },
        )
    }

    @Test
    fun aRecordDroppedByAPassAloneCanBeExportedAgain() = runTest {
        // A pass replaces the log wholesale, so the derived state has to be rebuilt from it.
        // The dedup map is the one that fails silently: leave it stale and a record the window
        // dropped is still marked "already exported", so re-exporting it is swallowed and the
        // record is simply lost. Nothing throws and the visible order stays plausible.
        //
        // The record has to be one the *pass* dropped and eviction never touched, or this is
        // vacuous: evictLeading frees the dedup slot itself, so anything that reached the window
        // by being evicted is already covered by that older guard. Exactly maxRecords local
        // exports evict nothing; the merge then doubles the visible count and the pass — not
        // eviction — is what takes the surplus away.
        val a = exporterFor(maxRecords = 5, segmentOps = 4)
        val b = exporterFor(replica = replicaB, maxRecords = 5, segmentOps = 4)
        val mine = (0 until 5).map { record(it, body = "a$it") }
        val theirs = (0 until 5).map { record(100 + it, body = "b$it") }
        mine.forEach { a.export(it) }
        theirs.forEach { b.export(it) }

        a.merge(b.snapshot())

        val survivors = a.snapshot().toList()
        val dropped = (mine + theirs).firstOrNull { it !in survivors }
        assertNotNull(dropped, "precondition: the merge-path pass must have dropped something")
        a.export(dropped)

        assertTrue(
            dropped in a.snapshot().toList(),
            "a record the window dropped must be re-exportable; its dedup slot was not freed",
        )
    }

    // ---- The drop has to survive a restart ----

    @Test
    fun aWindowedLogRecoversToTheSameStateItHeldInMemory() = runTest {
        // The floor lives in the ACTIVE segment; the sealed segments still hold the dropped
        // Inserts. Recovery unions every segment, so it only reproduces the in-memory state
        // if the merged floor re-purges them there — and only if the pass's segment write
        // actually reached the store.
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store, maxRecords = 6, segmentOps = 4)
        repeat(80) { i -> exporter.export(record(i)) }
        val live = exporter.snapshot()

        val recovered = exporterFor(store = store, maxRecords = 6, segmentOps = 4)
        recovered.recover()

        assertAll(
            { assertEquals(live.toList(), recovered.snapshot().toList(), "recovered records differ") },
            { assertEquals(live.causalFloor(), recovered.snapshot().causalFloor(), "the floor did not survive") },
            {
                assertTrue(
                    recovered.snapshot().opCount <= live.opCount,
                    "recovery re-inflated the op-log: ${live.opCount} -> ${recovered.snapshot().opCount}",
                )
            },
        )
    }

    @Test
    fun aWindowPassOnTheMergePathSurvivesARestart() = runTest {
        // merge() writes the remote as a sealed segment and, unlike export(), owes no active-
        // segment write of its own. A pass fired from there has to add one, or its floor is
        // in memory only and the restart re-admits everything the pass dropped.
        val store = InMemoryDurableStore()
        val a = exporterFor(store = store, maxRecords = 5, bufferPolicy = BufferPolicy.DROP_NEWEST, segmentOps = 4)
        val b = exporterFor(replica = replicaB, maxRecords = 5, segmentOps = 4)
        repeat(20) { i ->
            a.export(record(i, body = "a$i"))
            b.export(record(1_000 + i, body = "b$i"))
        }
        a.merge(b.snapshot())
        val live = a.snapshot()

        val recovered = exporterFor(
            store = store,
            maxRecords = 5,
            bufferPolicy = BufferPolicy.DROP_NEWEST,
            segmentOps = 4,
        )
        recovered.recover()

        assertAll(
            { assertEquals(live.toList(), recovered.snapshot().toList(), "recovered records differ") },
            { assertEquals(5, recovered.snapshot().size, "the merge-path pass did not reach disk") },
        )
    }
}
