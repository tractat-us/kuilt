package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
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
 * Pins that a log record which escaped before its write landed never shares its dot with a record
 * minted after a restart (#3452).
 *
 * A turn mints into memory, publishes to its [AppliedOpSink] and then writes. [snapshot] reads the
 * log as it stands. So a record whose segment write is refused can already be held by a peer that
 * gossiped from this replica, or by a sink. If the process then restarts, recovery must mint past
 * that record's seq: before the fix it read back a seq high-water below it, and the next export
 * minted the same `(replica, seq)` for a different record.
 *
 * Each arm refuses the one segment write, checks the record really escaped and really is absent
 * from the store, then restarts and exports once.
 */
class WarpLogRecordExporterEscapedDotTest {

    private val replica = ReplicaId("A")

    private fun recordId(id: Int): ByteString = ByteString(ByteArray(8) { i -> (id.toLong() shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    /** Room for every record, so nothing is evicted and every own id stays visible. */
    private fun exporterFor(store: DurableStore, sink: AppliedOpSink = AppliedOpSink.Discarding) =
        WarpLogRecordExporter(replica, store, maxRecords = 100, segmentOps = 8, appliedOps = sink)

    /** A sink that keeps every insert it was handed. */
    private class Collecting : AppliedOpSink {
        private val lock = reentrantLock()
        private val held = mutableListOf<RgaOp.Insert<LogRecord>>()

        val inserts: List<RgaOp.Insert<LogRecord>> get() = lock.withLock { held.toList() }

        override suspend fun published(ops: List<RgaOp<LogRecord>>) {
            lock.withLock { ops.filterIsInstance<RgaOp.Insert<LogRecord>>().forEach { held += it } }
        }
    }

    /**
     * Every pair of distinct own ids that share a lamport. [RgaId.compareTo] orders on
     * `(lamport, replica)` alone, so two such ids compare equal and the sequence's order breaks.
     */
    private fun sharedLamports(ids: Collection<RgaId>): List<String> =
        ids.filter { it.replicaId == replica }
            .distinct()
            .groupBy { it.lamport }
            .filterValues { it.size > 1 }
            .map { (lamport, same) -> "lamport $lamport: $same" }

    /** Every pair of distinct own records that share a seq. */
    private fun sharedDots(ids: Collection<Pair<RgaId, ByteString>>): List<String> =
        ids.filter { it.first.replicaId == replica }
            .distinct()
            .groupBy { it.first.seq }
            .filterValues { it.size > 1 }
            .map { (seq, pair) -> "seq $seq: $pair" }

    private class Escape(
        val backing: RecordingStore,
        val refusedExport: ExportResult,
        val escaped: RgaId,
        val gossiped: Rga<LogRecord>,
    )

    /** Five records written, then a sixth whose segment write is refused, with what escaped. */
    private suspend fun escapeOne(sink: AppliedOpSink): Escape {
        val backing = RecordingStore()
        val quota = RefuseSegmentWritesStore(backing)
        val writer = exporterFor(quota, sink)
        writer.recover()
        repeat(5) { assertEquals(ExportResult.Success, writer.export(record(it)), "the rig: export $it") }
        quota.refuseSegmentWrites()
        val refused = writer.export(record(100))
        val gossiped = writer.snapshot()
        val escaped = gossiped.entries().single { it.second.recordId == recordId(100) }.first
        return Escape(backing, refused, escaped, gossiped)
    }

    @Test
    fun aRecordGossipedBeforeItsWriteLandedKeepsItsDotAfterARestart() = runTest {
        val escape = escapeOne(AppliedOpSink.Discarding)
        val restarted = exporterFor(escape.backing)
        restarted.recover()
        val heldAfterRestart = restarted.snapshot().toList().map { it.recordId }
        val next = restarted.export(record(200))
        val merged = Rga.empty<LogRecord>().piece(escape.gossiped).piece(restarted.snapshot())
        val ids = merged.entries().map { it.first to it.second.recordId }
        assertAll(
            // The rig: the write was refused, the record was in the snapshot, and no key holds it.
            { assertTrue(escape.refusedExport is ExportResult.Failure, "the sixth export was written") },
            { assertTrue(recordId(100) !in heldAfterRestart, "the store holds the refused record") },
            { assertEquals(ExportResult.Success, next, "the export after the restart") },
            // The property.
            { assertEquals(emptyList(), sharedDots(ids), "two records share a dot") },
            {
                val all = escape.gossiped.entries().map { it.first } + restarted.snapshot().entries().map { it.first }
                assertEquals(emptyList(), sharedLamports(all), "two own ids share a lamport")
            },
            { assertEquals(7, merged.size, "the merged log holds every record once") },
        )
    }

    /**
     * The escaped record comes back through [WarpLogRecordExporter.merge] after the restart. It
     * sorts after everything minted since (a lower lamport than its siblings), so a window in list
     * order would reach the newer records first, and each held own record it kept would block the
     * floor: every own drop a `Compact`, every segment carrying one pinned. The window drops in
     * arrival order (#3447), so the escaped record goes with the newer ones and nothing is pinned.
     */
    @Test
    fun anEscapedRecordMergedBackLeavesNoCompactResidue() = runTest {
        val escape = escapeOne(AppliedOpSink.Discarding)
        val restarted = exporterFor(escape.backing)
        restarted.recover()
        repeat(20) { assertEquals(ExportResult.Success, restarted.export(record(200 + it)), "the rig: export $it") }
        assertEquals(ExportResult.Success, restarted.merge(escape.gossiped), "the merge back")
        val returned = restarted.snapshot().entries().map { it.first }
        repeat(400) { assertEquals(ExportResult.Success, restarted.export(record(1000 + it)), "export ${1000 + it}") }
        val log = restarted.snapshot()
        val segments = escape.backing.keys().filter { it.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }
        val pinned = segments.count {
            decodeSegmentForTest(requireNotNull(escape.backing.read(StoreKey(it)))).compactOpCount > 0
        }
        assertAll(
            // The rig: the merge really brought the escaped record back.
            { assertTrue(escape.escaped in returned, "the merge did not bring the escaped record back") },
            { assertEquals(0, log.compactedIds.count { it.replicaId == replica }, "an own drop kept a Compact") },
            { assertEquals(0, pinned, "a segment is pinned by a Compact") },
            { assertTrue(log.compactedBelow[replica] > escape.escaped.seq, "the floor never passed the escaped record") },
        )
    }

    @Test
    fun aRecordPublishedBeforeItsWriteLandedKeepsItsDotAfterARestart() = runTest {
        val sink = Collecting()
        val escape = escapeOne(sink)
        val restarted = exporterFor(escape.backing, sink)
        restarted.recover()
        val next = restarted.export(record(200))
        val ids = sink.inserts.map { it.id to it.value.recordId }
        assertAll(
            { assertTrue(escape.refusedExport is ExportResult.Failure, "the sixth export was written") },
            { assertTrue(sink.inserts.any { it.id == escape.escaped }, "the sink holds the refused record") },
            { assertEquals(ExportResult.Success, next, "the export after the restart") },
            { assertEquals(7, ids.size, "the sink saw seven inserts") },
            { assertEquals(emptyList(), sharedDots(ids), "two published records share a dot") },
            { assertEquals(emptyList(), sharedLamports(sink.inserts.map { it.id }), "two published ids share a lamport") },
        )
    }

    /** Once [fill] is called, refuses every write, the index included: a full disk. */
    private class FullDisk(private val backing: DurableStore) : DurableStore {
        private val lock = reentrantLock()
        private var full = false

        fun fill(): Unit = lock.withLock { full = true }

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (lock.withLock { full }) throw IllegalStateException("simulated full disk writing ${key.name}")
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    /**
     * **Pins today's residual, so a fix has a red to turn** (#3521). Recovery leaves no claim
     * headroom, so the first turn of every incarnation must claim before its mints are covered.
     * Restart twice inside an outage that refuses the index write too, and each incarnation mints
     * `claim + 1` with nothing behind it: the record the first one published shares its dot with the
     * second's. When this arm goes red, the gap is closed; turn it into the property.
     */
    @Test
    fun aRestartInsideAnOutageThatRefusesTheIndexMintsTheSameDotAgainToday() = runTest {
        val backing = RecordingStore()
        val disk = FullDisk(backing)
        val sink = Collecting()
        val writer = exporterFor(disk, sink)
        writer.recover()
        repeat(5) { assertEquals(ExportResult.Success, writer.export(record(it)), "the rig: export $it") }
        disk.fill()
        val incarnations = listOf(100, 200).map { id ->
            val restarted = exporterFor(disk, sink)
            restarted.recover()
            restarted.export(record(id))
        }
        val ids = sink.inserts.map { it.id to it.value.recordId }
        assertAll(
            { assertTrue(incarnations.all { it is ExportResult.Failure }, "an export inside the outage was written") },
            { assertEquals(7, ids.size, "the sink saw seven inserts") },
            { assertEquals(1, sharedDots(ids).size, "today: the two incarnations share one dot") },
        )
    }
}
