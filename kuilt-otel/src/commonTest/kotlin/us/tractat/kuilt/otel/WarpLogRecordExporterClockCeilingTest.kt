package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An export turn the Lamport clock ceiling refuses changes nothing (#3431).
 *
 * Since #3429 [Rga.insertAllAfter] throws an `ArithmeticException` rather than carry the clock past
 * [Long.MAX_VALUE]. A log reaches the ceiling through [WarpLogRecordExporter.merge], whose join
 * takes the larger clock, so one peer op near `MAX` is enough. On a full `DROP_OLDEST` buffer the
 * turn owes evictions as well as inserts, and the evictions used to be committed first: the turn
 * dropped records in memory, counted them on `health`, put their tombstones in the active segment
 * and published none of it, all for a turn that returned `Failure` and admitted nothing.
 *
 * Every arm asserts its rig: the merged clock really is near the ceiling, the buffer really is
 * full, and the refused turn really did reach the insert (its cause is the ceiling's
 * `ArithmeticException`, not some other refusal).
 */
class WarpLogRecordExporterClockCeilingTest {

    private val local = ReplicaId("local")
    private val peer = ReplicaId("peer")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(recordId = recordId(id), body = "record-$id")

    private class Recorder : AppliedOpSink {
        val ops = mutableListOf<RgaOp<LogRecord>>()
        override suspend fun published(ops: List<RgaOp<LogRecord>>) {
            this.ops += ops
        }
    }

    private fun exporter(
        store: RecordingStore,
        policy: BufferPolicy = BufferPolicy.DROP_OLDEST,
        sink: AppliedOpSink = AppliedOpSink.Discarding,
    ) = WarpLogRecordExporter(replica = local, store = store, maxRecords = CAP, bufferPolicy = policy, appliedOps = sink)

    /** One peer insert whose id carries [lamport]: the join takes it as the merged clock. */
    private fun peerLogAt(lamport: Long): Rga<LogRecord> =
        Rga.empty<LogRecord>().apply(
            RgaOp.Insert(id = RgaId(lamport = lamport, replicaId = peer, seq = 1L), value = record(PEER_RECORD), after = RgaId.HEAD),
        )

    private fun RecordingStore.mutations(): Int = operations().count { it.kind != StoreOpKind.READ }

    private fun Rga<LogRecord>.bodies(): List<String?> = toList().map { it.body }

    private suspend fun bodiesAfterRestart(store: RecordingStore): List<String?> =
        exporter(store).also { it.recover() }.snapshot().bodies()

    /**
     * A full buffer whose clock sits one below the ceiling: [CAP] − 1 local records plus the
     * peer's. A two-record batch then owes two evictions and crosses the ceiling on its second
     * insert.
     */
    private suspend fun fullBufferNearTheCeiling(
        store: RecordingStore,
        sink: AppliedOpSink = AppliedOpSink.Discarding,
    ): WarpLogRecordExporter {
        val exporter = exporter(store, sink = sink)
        assertEquals(ExportResult.Success, exporter.export((0 until CAP - 1).map(::record)), "the rig's local records land")
        assertEquals(ExportResult.Success, exporter.merge(peerLogAt(Long.MAX_VALUE - 1L)), "the rig's merge lands")
        assertAll(
            { assertEquals(Long.MAX_VALUE - 1L, exporter.snapshot().lamport, "the rig's merged clock is one below the ceiling") },
            { assertEquals(CAP, exporter.snapshot().toList().size, "the rig's buffer is full") },
            { assertEquals(0L, exporter.health.value.dropped, "the rig has dropped nothing yet") },
        )
        return exporter
    }

    @Test
    fun aTurnRefusedAtTheClockCeilingMutatesNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = RecordingStore()
        val recorder = Recorder()
        val exporter = fullBufferNearTheCeiling(store, recorder)
        val before = exporter.snapshot()
        val healthBefore = exporter.health.value
        val mutationsBefore = store.mutations()
        val publishedBefore = recorder.ops.size

        val refused = exporter.export(listOf(record(10), record(11)))
        val afterTurn = exporter.snapshot()
        val healthAfter = exporter.health.value
        val mutationsAfterTurn = store.mutations()
        val publishedAfterTurn = recorder.ops.size
        // A merge that adds nothing writes only what the active segment still owes. A refused turn
        // owes it nothing, so this writes nothing; tombstones left there would be written now.
        val noOpMerge = exporter.merge(peerLogAt(Long.MAX_VALUE - 1L))
        val mutationsAfterMerge = store.mutations()
        val restarted = bodiesAfterRestart(store)

        assertAll(
            { assertIs<ArithmeticException>(assertIs<ExportResult.Failure>(refused).cause, "the turn reached the ceiling") },
            { assertEquals(before, afterTurn, "the log is unchanged") },
            { assertEquals(before.bodies(), afterTurn.bodies(), "no record was evicted") },
            { assertEquals(0L, healthAfter.dropped, "no drop was counted") },
            { assertEquals(healthBefore.failed + 1, healthAfter.failed, "the refusal is one failure") },
            { assertEquals(mutationsBefore, mutationsAfterTurn, "the refused turn wrote and deleted nothing") },
            { assertEquals(publishedBefore, publishedAfterTurn, "the refused turn published nothing") },
            { assertEquals(ExportResult.Success, noOpMerge, "the no-op merge lands") },
            { assertEquals(mutationsBefore, mutationsAfterMerge, "the refused turn left the active segment owing nothing") },
            { assertEquals(before.bodies(), restarted, "a restart reads what memory holds") },
        )
    }

    /**
     * The turn after a refused one evicts what it owes and no more. Before the fix the refused
     * turn's two evictions had already happened, so this turn's one made three, two of them never
     * published.
     */
    @Test
    fun theTurnAfterARefusedOneDropsOnlyWhatItEvicts() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = RecordingStore()
        val recorder = Recorder()
        val exporter = fullBufferNearTheCeiling(store, recorder)
        val before = exporter.snapshot().bodies()

        val refused = exporter.export(listOf(record(10), record(11)))
        val landed = exporter.export(record(12))
        val after = exporter.snapshot()
        val left = before.toSet() - after.bodies().toSet()
        val tombstoned = recorder.ops.filterIsInstance<RgaOp.Remove<LogRecord>>()
        val restarted = bodiesAfterRestart(store)

        assertAll(
            { assertIs<ArithmeticException>(assertIs<ExportResult.Failure>(refused).cause, "the rig's first turn reached the ceiling") },
            { assertEquals(ExportResult.Success, landed, "one insert still fits below the ceiling") },
            { assertEquals(Long.MAX_VALUE, after.lamport, "and it took the clock to the ceiling") },
            { assertEquals(before.drop(1) + "record-12", after.bodies(), "only the oldest record was evicted") },
            { assertEquals(1L, exporter.health.value.dropped, "one drop counted") },
            { assertEquals(left.size, tombstoned.size, "every record that left was published as a tombstone") },
            { assertEquals(after.bodies(), restarted, "a restart reads what memory holds") },
        )
    }

    /**
     * At the ceiling every later turn that inserts is refused, and each refusal mutates nothing.
     * That is the fail-closed outcome this exporter can give alone: what a peer's clock near the
     * ceiling should do to a replica that merges it is #3432's question, not this exporter's.
     */
    @Test
    fun aLogAtTheCeilingRefusesEveryLaterInsertAndMutatesNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = RecordingStore()
        val exporter = exporter(store)
        exporter.export((0 until CAP - 1).map(::record))
        exporter.merge(peerLogAt(Long.MAX_VALUE))
        val before = exporter.snapshot()
        val mutationsBefore = store.mutations()

        val refusals = (0 until 3).map { i -> exporter.export(record(20 + i)) }

        assertAll(
            { assertEquals(Long.MAX_VALUE, before.lamport, "the rig's merged clock is at the ceiling") },
            { assertEquals(CAP, before.toList().size, "the rig's buffer is full") },
            {
                assertTrue(
                    refusals.all { (it as? ExportResult.Failure)?.cause is ArithmeticException },
                    "every turn is refused at the ceiling: $refusals",
                )
            },
            { assertEquals(before, exporter.snapshot(), "the log is unchanged") },
            { assertEquals(0L, exporter.health.value.dropped, "no drop was counted") },
            { assertEquals(mutationsBefore, store.mutations(), "nothing was written") },
        )
    }

    /**
     * Under `DROP_NEWEST` the turn counts its cap refusals while it admits, before the insert. A
     * refused turn must not count them: the caller sees one `Failure`, and a retry would count
     * the same records again.
     */
    @Test
    fun aDropNewestTurnRefusedAtTheCeilingCountsNoRefusal() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = RecordingStore()
        val exporter = exporter(store, policy = BufferPolicy.DROP_NEWEST)
        exporter.export((0 until CAP - 2).map(::record))
        exporter.merge(peerLogAt(Long.MAX_VALUE))
        val before = exporter.snapshot()

        // One admitted into the last free slot, then two refused by the cap.
        val refused = exporter.export(listOf(record(10), record(11), record(12)))

        assertAll(
            { assertEquals(CAP - 1, before.toList().size, "the rig's buffer has one free slot") },
            { assertIs<ArithmeticException>(assertIs<ExportResult.Failure>(refused).cause, "the turn reached the ceiling") },
            { assertEquals(0L, exporter.health.value.refused, "no refusal was counted") },
            { assertEquals(before, exporter.snapshot(), "the log is unchanged") },
        )
    }

    private companion object {
        private const val CAP = 4
        private const val PEER_RECORD = 100
    }
}
