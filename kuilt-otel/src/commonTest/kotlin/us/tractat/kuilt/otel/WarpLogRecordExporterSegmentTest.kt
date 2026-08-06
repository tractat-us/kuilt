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
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.VersionVector
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

    /**
     * A compacted legacy [blob], plus a [laggingPeer] whose op-log still holds the
     * purged record's `Insert` — the peer that never received the compaction.
     */
    private class CompactedFixture(val blob: ByteArray, val laggingPeer: Rga<LogRecord>)

    /** The pre-#1860 single-blob format: one CBOR-encoded [Rga] under `otel.logs`. */
    private object Legacy {
        val key: StoreKey = StoreKey("otel.logs")
        private val cbor = Cbor { alwaysUseByteString = true }
        private val serializer = Rga.wireSerializer(LogRecord.serializer())

        fun blobOf(records: List<LogRecord>, replica: ReplicaId): ByteArray =
            cbor.encodeToByteArray(serializer, rgaOf(records, replica))

        /**
         * A legacy blob in which [purged] has been garbage-collected under the ADR-003
         * causal-stability barrier, so the op-log carries an [us.tractat.kuilt.crdt.RgaOp.Compact].
         * This is the state a pre-#1860 build reached by merging a peer that had compacted.
         */
        fun compactedBlobOf(purged: LogRecord, rest: List<LogRecord>, replica: ReplicaId): CompactedFixture {
            // `purged` goes LAST: the barrier's condition 4 refuses to GC an element that
            // still has a surviving successor, so only the tail is ever compactable here.
            val full = rgaOf(rest + purged, replica)
            val (tombstoned, _) = requireNotNull(full.removeAt(rest.size)) { "expected a record to tombstone" }
            val delivered = VersionVector.of(tombstoned.causalDots().associate { it.replica to it.seq })
            val (compacted, _) = requireNotNull(
                tombstoned.compact(stableCut = delivered, frontierMax = delivered, delivered = delivered),
            ) { "expected the barrier to admit a compaction" }
            return CompactedFixture(blob = cbor.encodeToByteArray(serializer, compacted), laggingPeer = full)
        }

        private fun rgaOf(records: List<LogRecord>, replica: ReplicaId): Rga<LogRecord> {
            var rga = Rga.empty<LogRecord>()
            var tail = RgaId.HEAD
            for (r in records) {
                val (next, op) = rga.insertAfter(replica = replica, after = tail, value = r)
                rga = next
                tail = op.id
            }
            return rga
        }
    }

    // ---- opCount must see retained Compact ops, not just sequence + tombstones ----

    /** Test-source shim over the production [opCountOf] — forwards, does not duplicate its logic. */
    private fun opCountOfForTest(segment: Rga<LogRecord>): Int = opCountOf(segment)

    @Test
    fun opCountSeesCompactOpsSoTheRollThresholdIsNotUndercounted() = runTest {
        val ids = mutableListOf<RgaId>()
        var rga = Rga.empty<LogRecord>()
        var tail = RgaId.HEAD
        repeat(4) { i ->
            val (next, op) = rga.insertAfter(replicaA, tail, record(i))
            rga = next
            tail = op.id
            ids += op.id
        }
        val compacted = rga.apply(RgaOp.Compact(rga.positionsFor(setOf(ids[0]))))

        // 3 surviving Inserts + 1 retained Compact.
        assertEquals(4, opCountOfForTest(compacted), "a Compact is an op and occupies segment budget")
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

    @Test
    fun bothBufferPoliciesBoundThePerExportWrite() = runTest {
        // Both policies owe a bound — a per-export write sized by the SEGMENT, never
        // by N — and this test measures BOTH because a bound only ever checked against the
        // default policy hid a policy-specific regression once already (#2126's F3, in a
        // reclamation that no longer exists: nothing is reclaimed under either policy now).
        //
        // What they do once the cap bites diverges, and DROP_NEWEST's side is strictly
        // stronger: it refuses the arrival, so a saturated buffer appends no op and writes
        // nothing at all (#2127). That is asserted here rather than left to read as an
        // incidentally empty list.
        for (policy in BufferPolicy.entries) {
            val store = RecordingStore()
            val exporter = exporterFor(store = store, maxRecords = 20, bufferPolicy = policy, segmentOps = 8)

            repeat(100) { exporter.export(record(it)) }
            val firstWindow = store.writes().max()
            store.resetWriteLog()
            repeat(100) { exporter.export(record(100 + it)) }
            val secondWindow = store.writes()

            assertAll(
                { assertEquals(20, exporter.snapshot().toList().size, "$policy: the cap must hold") },
                { assertTrue(firstWindow < 4 * 1024, "$policy: first-window max $firstWindow") },
                {
                    when (policy) {
                        // Saturated long before the second window opens, so all 100 of those
                        // exports are refused at the door and not one byte is written.
                        BufferPolicy.DROP_NEWEST -> assertEquals(
                            emptyList<Int>(),
                            secondWindow,
                            "$policy: a saturated buffer must write nothing",
                        )
                        BufferPolicy.DROP_OLDEST -> assertTrue(
                            secondWindow.max() <= firstWindow,
                            "$policy: per-export write grew with N: $firstWindow -> ${secondWindow.max()}",
                        )
                    }
                },
            )
        }
    }

    @Test
    fun theStoreIsNoLargerThanTheOpLogItHolds() = runTest {
        // Segments are never dropped, so the total is NOT bounded — the honest claim is
        // that partitioning the op-log across keys does not inflate it. Both policies,
        // because F3 was a policy-specific regression that only measuring one hid.
        for (policy in BufferPolicy.entries) {
            val segmented = RecordingStore()
            exporterFor(store = segmented, maxRecords = 20, bufferPolicy = policy, segmentOps = 8)
                .also { e -> repeat(200) { e.export(record(it)) } }

            val singleBlob = RecordingStore()
            exporterFor(store = singleBlob, maxRecords = 20, bufferPolicy = policy, segmentOps = 100_000)
                .also { e -> repeat(200) { e.export(record(it)) } }

            // A 15% allowance for the per-segment CBOR framing and the index.
            val ceiling = singleBlob.residentBytes() * 115 / 100
            assertTrue(
                segmented.residentBytes() <= ceiling,
                "$policy: segmenting inflated the store from ${singleBlob.residentBytes()} " +
                    "to ${segmented.residentBytes()}",
            )
        }
    }

    // ---- Robustness: one bad read must not cost the whole log ----

    @Test
    fun oneUnreadableSegmentCostsOnlyThatSegmentsRecords() = runTest {
        // Recovery now performs N reads where it performed one. If a single transient
        // failure fails the whole recovery, the numbering falls back to its construction
        // defaults and the next export writes an index naming only segment 0 — and
        // overwrites it. Every other segment is then orphaned permanently, in a format
        // with no key-enumeration API to sweep them.
        val store = RecordingStore()
        exporterFor(store = store, segmentOps = 8).also { e -> repeat(60) { e.export(record(it)) } }
        val failing = FailReadOfStore(store, StoreKey("otel.logs.seg.2"))

        val recovered = exporterFor(store = failing, segmentOps = 8)
        recovered.recover()
        val survived = recovered.snapshot().toList().size
        recovered.export(record(999))

        assertAll(
            { assertTrue(survived >= 40, "one bad segment cost $survived of 60 records") },
            {
                assertTrue(
                    store.keys().count { it.startsWith("otel.logs.seg.") } >= 8,
                    "segments were orphaned: ${store.keys()}",
                )
            },
        )
    }

    @Test
    fun aLegacyKeyThatWillNotDeleteDoesNotCostTheLog() = runTest {
        // The legacy sweep runs on EVERY start, forever. A store whose delete throws
        // (IndexedDbDurableStore does) would otherwise fail recovery on every launch.
        val store = RecordingStore()
        exporterFor(store = store, segmentOps = 8).also { e -> repeat(30) { e.export(record(it)) } }
        val expected = exporterFor(store = store, segmentOps = 8).also { it.recover() }.snapshot().toList()
        store.putRaw(Legacy.key, byteArrayOf(1, 2, 3))

        val recovered = exporterFor(store = FailDeleteStore(store), segmentOps = 8)
        recovered.recover()

        assertAll(
            { assertEquals(expected, recovered.snapshot().toList(), "a failed delete of garbage cost the log") },
            { assertEquals(false, recovered.health.value.recoveryFailed) },
        )
    }

    /** Delegates to [backing], but throws on reading [poisoned]. */
    private class FailReadOfStore(private val backing: DurableStore, private val poisoned: StoreKey) : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? {
            if (key == poisoned) throw IllegalStateException("simulated transient read failure on $key")
            return backing.read(key)
        }

        override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)
        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    /** Delegates to [backing], but throws on every delete. */
    private class FailDeleteStore(private val backing: DurableStore) : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)
        override suspend fun delete(key: StoreKey): Unit = throw IllegalStateException("simulated delete failure")
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
    fun aMergeCannotResurrectRecordsThisReplicaAlreadyEvicted() = runTest {
        // Physically dropping ops is a garbage-collection of CRDT state, and the safety
        // precondition for that is causal stability — every replica has seen them. A local
        // "this segment is fully superseded" test cannot establish it. Once Insert(X) AND
        // Remove(X) are both gone, `piece` is a set union with nothing left to tombstone X,
        // so a peer holding the old ops re-admits X as live.
        val store = RecordingStore()
        val exporter = exporterFor(store = store, maxRecords = 10, segmentOps = 8)

        // A peer takes a snapshot while records 0..7 are still live.
        repeat(8) { exporter.export(record(it)) }
        val peer = exporter.snapshot()
        repeat(92) { exporter.export(record(8 + it)) }

        val restarted = exporterFor(store = store, maxRecords = 10, segmentOps = 8)
        restarted.recover()
        restarted.merge(peer)

        assertEquals(
            10,
            restarted.snapshot().toList().size,
            "the merge resurrected records this replica had already evicted",
        )
    }

    @Test
    fun aCompactionInheritedFromTheLegacyBlobIsNeverDropped() = runTest {
        // Rga guarantees "once compacted, always compacted", and the ONLY thing carrying
        // that guarantee forward is the retained Compact op. A pre-#1860 build that ever
        // merged a compacted peer left Compact ops in its single blob, so they arrive in
        // segment 0 on the very first start of the new build — before any local merge, so
        // a "stop reclaiming once we have merged" flag cannot see them.
        //
        // The harm needs a peer that never got the compaction and still holds the purged
        // record's Insert. While our Compact survives, `piece` unions the compacted-id
        // sets and re-purges it. Drop the segment carrying our Compact and that suppression
        // is simply gone, so the merge re-admits the record as live.
        val store = RecordingStore()
        val purged = record(0)
        val fixture = Legacy.compactedBlobOf(purged, (1 until 12).map { record(it) }, replicaA)
        store.putRaw(Legacy.key, fixture.blob)

        val exporter = exporterFor(store = store, maxRecords = 6, segmentOps = 4)
        exporter.recover()
        repeat(60) { exporter.export(record(1_000 + it)) }

        val restarted = exporterFor(store = store, maxRecords = 6, segmentOps = 4)
        restarted.recover()
        restarted.merge(fixture.laggingPeer)

        assertTrue(
            purged !in restarted.snapshot().toList(),
            "a compacted record came back: the Compact op that purged it was dropped",
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
