@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the **segmented op-log** persistence format of [WarpLogRecordExporter] (#1860).
 *
 * The defect these tests exist for: the previous format re-encoded and rewrote the
 * *entire* op-log on *every* record, so a device paid Θ(N²) bytes to accumulate N
 * records, and `maxRecords` bounded only *visibility* — never the file, because an
 * evicted record's `Insert` op (carrying its full body) was tombstoned but never
 * dropped from the blob.
 */
class WarpLogRecordExporterSegmentTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    // ---- helpers ----

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    /**
     * Every record encodes to the **same** number of bytes: a fixed-width id in the body
     * and a constant timestamp. The byte-accounting tests below compare write sizes across
     * windows, and a body that grows a character when the id gains a digit would show up
     * as growth-with-N that has nothing to do with the layout under test.
     */
    private fun record(id: Int, body: String = "log message body number ${id.toString().padStart(6, '0')}") =
        LogRecord(
            recordId = recordId(id),
            body = body,
            severityNumber = 9,
            severityText = "INFO",
            observedEpochNanos = 1_700_000_000_000_000_000L,
        )

    private fun exporterFor(
        replica: ReplicaId = replicaA,
        store: DurableStore,
        maxRecords: Int = DEFAULT_MAX_LOG_RECORDS,
        bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps: Int = DEFAULT_LOG_SEGMENT_OPS,
    ) = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = maxRecords,
        bufferPolicy = bufferPolicy,
        segmentOps = segmentOps,
    )

    /**
     * A [DurableStore] that records the size of every [write] payload and can report
     * the total bytes currently resident. Backed by an in-memory map, guarded by an
     * explicit lock (kuilt policy: primitives, never dispatcher confinement).
     */
    private class RecordingStore : DurableStore {
        private val lock = reentrantLock()
        private val backing = mutableMapOf<StoreKey, ByteArray>()
        private val payloadSizes = mutableListOf<Int>()

        override suspend fun read(key: StoreKey): ByteArray? = lock.withLock { backing[key]?.copyOf() }

        override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = lock.withLock {
            backing[key] = bytes.copyOf()
            payloadSizes += bytes.size
        }

        override suspend fun delete(key: StoreKey): Unit = lock.withLock { backing.remove(key) }

        /** Bytes written per [write] call, in call order. */
        fun writes(): List<Int> = lock.withLock { payloadSizes.toList() }

        /** Total bytes currently resident across every live key. */
        fun residentBytes(): Int = lock.withLock { backing.values.sumOf { it.size } }

        fun keys(): Set<String> = lock.withLock { backing.keys.map { it.name }.toSet() }

        fun putRaw(key: StoreKey, bytes: ByteArray): Unit = lock.withLock { backing[key] = bytes.copyOf() }

        fun resetWriteLog(): Unit = lock.withLock { payloadSizes.clear() }
    }

    /** The pre-#1860 single-blob format: one CBOR-encoded [Rga] under `otel.logs`. */
    private object Legacy {
        val key: StoreKey = StoreKey("otel.logs")
        private val cbor = Cbor { alwaysUseByteString = true }
        private val serializer = Rga.wireSerializer(LogRecord.serializer())

        fun blobOf(records: List<LogRecord>, replica: ReplicaId): ByteArray {
            var rga = Rga.empty<LogRecord>()
            var tail = RgaId.HEAD
            for (r in records) {
                val (next, op) = rga.insertAfter(replica = replica, after = tail, value = r)
                rga = next
                tail = op.id
            }
            return cbor.encodeToByteArray(serializer, rga)
        }
    }

    // ---- The headline claim: the per-export write is bounded, not merely smaller ----

    @Test
    fun perExportWriteIsBoundedByTheSegmentNotByTheLogSize() = runTest {
        val store = RecordingStore()
        val exporter = exporterFor(store = store, segmentOps = 16)

        repeat(100) { exporter.export(record(it)) }
        val firstWindow = store.writes()
        store.resetWriteLog()
        repeat(100) { exporter.export(record(100 + it)) }
        val secondWindow = store.writes()

        // A segment holds at most 16 records; 8 KiB is a generous ceiling that the pre-#1860
        // whole-blob rewrite blows past well before 200 records. The `<=` below is exact
        // rather than slack-tolerant because 200 exports keeps every Lamport counter under
        // 256 — inside one CBOR width class — so no encoding-width drift can leak in.
        val ceiling = 8 * 1024
        assertAll(
            { assertTrue(firstWindow.max() < ceiling, "first-window max ${firstWindow.max()} >= $ceiling") },
            { assertTrue(secondWindow.max() < ceiling, "second-window max ${secondWindow.max()} >= $ceiling") },
            {
                assertTrue(
                    secondWindow.max() <= firstWindow.max(),
                    "per-export write grew with N: ${firstWindow.max()} -> ${secondWindow.max()}",
                )
            },
        )
    }

    // ---- The second, separately-proven defect: maxRecords never bounded the file ----

    @Test
    fun totalPersistedBytesPlateauUnderTheBufferCap() = runTest {
        val store = RecordingStore()
        val exporter = exporterFor(store = store, maxRecords = 20, segmentOps = 8)

        // Sample after every export and compare window *maxima*, so the sawtooth from
        // segments filling and being reclaimed cannot decide the verdict — only a real
        // upward trend can.
        val resident = mutableListOf<Int>()
        repeat(300) {
            exporter.export(record(it))
            resident += store.residentBytes()
        }
        val earlier = resident.subList(100, 200).max()
        val later = resident.subList(200, 300).max()

        // The residue is not zero and cannot be: Lamport counters and segment numbers rise
        // monotonically, so their CBOR encodings widen — O(log N) drift, a few bytes per
        // hundred exports. The defect this pins was ~385 bytes *per export*, forever, so a
        // ceiling of 10 B/export over the window separates the two by two orders of magnitude.
        val driftAllowance = 10 * 100

        assertAll(
            { assertEquals(20, exporter.snapshot().toList().size, "visible count must stay at the cap") },
            {
                assertTrue(
                    later - earlier < driftAllowance,
                    "persisted bytes still growing with N under the cap: $earlier -> $later",
                )
            },
            {
                assertTrue(
                    later < 16 * 1024,
                    "20 capped records should not need $later bytes on disk",
                )
            },
        )
    }

    // ---- Round-trip through the segments ----

    @Test
    fun recoverFromSegmentsReproducesOrderAndDedup() = runTest {
        val store = RecordingStore()
        val exporter = exporterFor(store = store, segmentOps = 16)
        val exported = (0 until 100).map { record(it) }
        exported.forEach { exporter.export(it) }

        val recovered = exporterFor(store = store, segmentOps = 16)
        recovered.recover()
        val before = recovered.snapshot().toList()
        recovered.export(exported[42])

        assertAll(
            { assertEquals(exported, before, "recovered order must match the exported order") },
            { assertEquals(before, recovered.snapshot().toList(), "re-export of a known id must be a no-op") },
        )
    }

    @Test
    fun recoverAfterEvictionDoesNotResurrectEvictedRecords() = runTest {
        val store = RecordingStore()
        val exporter = exporterFor(store = store, maxRecords = 10, segmentOps = 8)
        repeat(60) { exporter.export(record(it)) }
        val live = exporter.snapshot().toList()

        val recovered = exporterFor(store = store, maxRecords = 10, segmentOps = 8)
        recovered.recover()

        assertEquals(live, recovered.snapshot().toList())
    }

    @Test
    fun reclamationNeverDropsATombstoneWhoseRecordSurvivesElsewhere() = runTest {
        // The resurrection hazard, reachable only under DROP_NEWEST. That policy pins the
        // OLDEST segment forever — its first records are never the newest, so they are never
        // evicted and it never becomes fully superseded — while every later segment does
        // become superseded. Those later segments carry the `Remove` ops for `Insert`s still
        // living in the pinned one, so dropping one on "all my own records are evicted"
        // alone un-tombstones an evicted record. In memory nothing shows: `log` still holds
        // every op. It only surfaces on the next start, which is why this recovers.
        val store = RecordingStore()
        fun exporter() = exporterFor(
            store = store,
            maxRecords = 3,
            bufferPolicy = BufferPolicy.DROP_NEWEST,
            segmentOps = 4,
        )
        val live = exporter().let { e ->
            repeat(40) { e.export(record(it)) }
            e.snapshot().toList()
        }

        val recovered = exporter()
        recovered.recover()

        assertAll(
            { assertEquals(3, live.size, "precondition: the cap holds in memory") },
            { assertEquals(live, recovered.snapshot().toList(), "recovery resurrected an evicted record") },
        )
    }

    // ---- Migration off the legacy single blob ----

    @Test
    fun recoverMigratesTheLegacyBlobIntoSegments() = runTest {
        val store = RecordingStore()
        val legacy = (0 until 40).map { record(it) }
        store.putRaw(Legacy.key, Legacy.blobOf(legacy, replicaA))

        val exporter = exporterFor(store = store, segmentOps = 8)
        exporter.recover()
        val index = store.read(StoreKey("otel.logs.idx"))

        assertAll(
            { assertEquals(legacy, exporter.snapshot().toList(), "migration must preserve every record, in order") },
            { assertTrue(Legacy.key.name !in store.keys(), "legacy key must be gone after migration") },
            { assertTrue(store.keys().any { it.startsWith("otel.logs.seg.") }, "segments must exist") },
            { assertNotNull(index, "the segment index must exist") },
        )
    }

    @Test
    fun exportAfterMigrationWritesOnlyTheActiveSegment() = runTest {
        val store = RecordingStore()
        store.putRaw(Legacy.key, Legacy.blobOf((0 until 200).map { record(it) }, replicaA))

        val exporter = exporterFor(store = store, segmentOps = 8)
        exporter.recover()
        store.resetWriteLog()
        exporter.export(record(1000))

        assertTrue(
            store.writes().max() < 4 * 1024,
            "a post-migration export rewrote ${store.writes().max()} bytes — the legacy blob is being rewritten",
        )
    }

    @Test
    fun recoverCompletesAMigrationInterruptedBeforeTheIndexWrite() = runTest {
        // Crash point 1: the sealed segment was written, the index was not. Only the
        // legacy key is authoritative, so the whole migration must simply re-run.
        val store = RecordingStore()
        val legacy = (0 until 30).map { record(it) }
        val blob = Legacy.blobOf(legacy, replicaA)
        store.putRaw(Legacy.key, blob)
        store.putRaw(StoreKey("otel.logs.seg.0"), blob)

        val exporter = exporterFor(store = store, segmentOps = 8)
        exporter.recover()

        assertAll(
            { assertEquals(legacy, exporter.snapshot().toList(), "no loss, no duplication") },
            { assertTrue(Legacy.key.name !in store.keys(), "the re-run must finish by dropping the legacy key") },
        )
    }

    @Test
    fun recoverCompletesAMigrationInterruptedBeforeTheLegacyDelete() = runTest {
        // Crash point 2: segment + index committed, the legacy delete did not run.
        // The index is the commit point, so the leftover legacy key is garbage.
        val store = RecordingStore()
        val legacy = (0 until 30).map { record(it) }
        store.putRaw(Legacy.key, Legacy.blobOf(legacy, replicaA))

        val first = exporterFor(store = store, segmentOps = 8)
        first.recover()
        // Re-plant the legacy key to simulate a delete that never landed.
        store.putRaw(Legacy.key, Legacy.blobOf(legacy, replicaA))

        val second = exporterFor(store = store, segmentOps = 8)
        second.recover()

        assertAll(
            { assertEquals(legacy, second.snapshot().toList(), "no loss, no duplication") },
            { assertTrue(Legacy.key.name !in store.keys(), "the leftover legacy key must be dropped") },
        )
    }

    @Test
    fun aMigrationThatDiesPartWayLeavesTheLegacyBlobIntact() = runTest {
        // The two tests above pin the *states* a crash can leave behind; this pins the write
        // ORDER that makes those the only reachable states. Deleting the legacy key before
        // the index is on disk would open a state — no legacy, no index — that is total loss,
        // and no state-based test can see it because that state is simply never constructed.
        val legacy = (0 until 30).map { record(it) }
        val blob = Legacy.blobOf(legacy, replicaA)

        for (failingWrite in 1..2) {
            val store = FailNthWriteStore(failingWrite)
            store.putRaw(Legacy.key, blob)
            exporterFor(store = store, segmentOps = 8).recover()

            // Whatever landed, the legacy key is still authoritative, so a healthy
            // start re-runs the migration and loses nothing.
            store.failing = false
            val survivingLegacy = store.read(Legacy.key)
            val retried = exporterFor(store = store, segmentOps = 8)
            retried.recover()
            assertAll(
                { assertNotNull(survivingLegacy, "write #$failingWrite: legacy blob was dropped early") },
                { assertEquals(legacy, retried.snapshot().toList(), "write #$failingWrite: records lost") },
            )
        }
    }

    /** Fails the [failOn]-th [write] call, then keeps failing until [failing] is cleared. */
    private class FailNthWriteStore(private val failOn: Int) : DurableStore {
        private val lock = reentrantLock()
        private val backing = mutableMapOf<StoreKey, ByteArray>()
        private var writes = 0
        var failing: Boolean = true

        override suspend fun read(key: StoreKey): ByteArray? = lock.withLock { backing[key]?.copyOf() }

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            lock.withLock {
                writes++
                if (failing && writes >= failOn) throw IllegalStateException("simulated crash on write $writes")
                backing[key] = bytes.copyOf()
            }
        }

        override suspend fun delete(key: StoreKey): Unit = lock.withLock { backing.remove(key) }

        fun putRaw(key: StoreKey, bytes: ByteArray): Unit = lock.withLock { backing[key] = bytes.copyOf() }
    }

    @Test
    fun recoverOnAnEmptyStoreStartsFreshAndWritesNothing() = runTest {
        val store = RecordingStore()
        val exporter = exporterFor(store = store, segmentOps = 8)
        exporter.recover()
        val index = store.read(StoreKey("otel.logs.idx"))

        assertAll(
            { assertEquals(0, exporter.snapshot().toList().size) },
            { assertEquals(emptyList(), store.writes(), "recover on a fresh store must not write") },
            { assertNull(index) },
        )
    }

    @Test
    fun recoverToleratesASegmentTheIndexNamesButTheStoreLacks() = runTest {
        // The index is written before the segment it allocates, so a crash in that
        // window leaves the index naming a segment that was never written.
        val store = RecordingStore()
        val exporter = exporterFor(store = store, segmentOps = 8)
        repeat(20) { exporter.export(record(it)) }
        val expected = exporter.snapshot().toList()

        val activeKey = store.keys().filter { it.startsWith("otel.logs.seg.") }.maxBy { it.substringAfterLast('.').toInt() }
        store.delete(StoreKey(activeKey))

        val recovered = exporterFor(store = store, segmentOps = 8)
        recovered.recover()

        assertTrue(
            recovered.snapshot().toList().size in 1..expected.size,
            "a missing segment must degrade, not throw",
        )
    }

    // ---- Merge still converges and still persists ----

    @Test
    fun mergeConvergesAndSurvivesRecovery() = runTest {
        val storeA = RecordingStore()
        val storeB = RecordingStore()
        val a = exporterFor(replica = replicaA, store = storeA, segmentOps = 8)
        val b = exporterFor(replica = replicaB, store = storeB, segmentOps = 8)

        repeat(20) { a.export(record(it)) }
        repeat(20) { b.export(record(100 + it)) }

        a.merge(b.snapshot())
        val merged = a.snapshot().toList()

        val recovered = exporterFor(replica = replicaA, store = storeA, segmentOps = 8)
        recovered.recover()

        assertAll(
            { assertEquals(40, merged.size, "the union of two disjoint 20-record logs") },
            { assertEquals(merged, recovered.snapshot().toList(), "the merged state must survive a restart") },
        )
    }

    @Test
    fun mergeIsIdempotentAcrossSegments() = runTest {
        val storeA = RecordingStore()
        val a = exporterFor(replica = replicaA, store = storeA, segmentOps = 8)
        val b = exporterFor(replica = replicaB, store = RecordingStore(), segmentOps = 8)
        repeat(12) { b.export(record(100 + it)) }
        val remote = b.snapshot()

        a.merge(remote)
        val once = a.snapshot().toList()
        a.merge(remote)

        val recovered = exporterFor(replica = replicaA, store = storeA, segmentOps = 8)
        recovered.recover()

        assertAll(
            { assertEquals(once, a.snapshot().toList()) },
            { assertEquals(once, recovered.snapshot().toList()) },
        )
    }
}
