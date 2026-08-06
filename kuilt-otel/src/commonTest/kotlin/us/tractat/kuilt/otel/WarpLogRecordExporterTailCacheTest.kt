package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the derived state [WarpLogRecordExporter] carries across [WarpLogRecordExporter.export]
 * calls — the tail [RgaId] used as the append predecessor, the visible-record count that gates
 * eviction, and the `recordId → RgaId` dedup map.
 *
 * These were recomputed from the [Rga] on every export (a full `computeSequence()` plus two
 * more O(N) passes); they are now threaded forward incrementally. **A stale tail is a silent
 * corruption**: the record is still inserted and still visible, but after the wrong predecessor,
 * so the order is wrong and nothing throws. Every test here is therefore an *invalidation* test —
 * it drives the exporter through an operation that moves the tail (or the count) out from under
 * the cache and asserts the resulting order.
 *
 * The last test is a differential oracle: it runs a scripted sequence of operations through both
 * the exporter and a cache-free transcription of the same algorithm ([ReferenceLogExporter]) and
 * asserts both the resulting order **and the op-log recovered from the store** agree. The op-log — not the byte layout — is what must not move: #1860 replaced the
 * single persisted blob with segments, so the check is now "persist, recover, compare op-logs",
 * which pins the same equivalence across a layout that is free to change again. It is run at a
 * rolling `segmentOps` as well as the default (#2127): at the default the script never fills a
 * segment, so the round-trip recovers from one active segment and never touches the reclaiming
 * path at all.
 */
class WarpLogRecordExporterTailCacheTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    private fun recordId(id: Byte): ByteString = ByteString(ByteArray(8) { id })

    private fun record(id: Byte, body: String = "body-$id") = LogRecord(
        recordId = recordId(id),
        body = body,
        observedEpochNanos = 1_000L + id,
    )

    private fun exporterFor(
        replica: ReplicaId = replicaA,
        store: DurableStore = InMemoryDurableStore(),
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

    private fun bodies(records: List<LogRecord>): List<String?> = records.map { it.body }

    // ---- Tail invalidation: eviction ----

    @Test
    fun appendAfterDropOldestEvictionPreservesOrder() = runTest {
        // DROP_OLDEST removes index 0, which is never the tail — but the count must still
        // move, and the tail must survive the eviction untouched.
        val exporter = exporterFor(maxRecords = 3, bufferPolicy = BufferPolicy.DROP_OLDEST)
        (1..6).forEach { exporter.export(record(it.toByte())) }
        assertEquals(
            listOf("body-4", "body-5", "body-6"),
            bodies(exporter.snapshot().toList()),
        )
    }

    @Test
    fun dropNewestNeverMovesTheTailOutFromUnderTheCache() = runTest {
        // DROP_NEWEST used to remove visible index size-1 — which *is* the tail — and was
        // therefore the one export-path event that invalidated the cached tail. It refuses
        // the arrival now, so that event ceases to exist and the invalidation branch is
        // gone with it (#2127). This test is what the removal rests on: r1,r2,r3 fill the
        // buffer, every later export is refused, and nothing moves.
        val exporter = exporterFor(maxRecords = 3, bufferPolicy = BufferPolicy.DROP_NEWEST)
        (1..6).forEach { exporter.export(record(it.toByte())) }
        assertEquals(
            listOf("body-1", "body-2", "body-3"),
            bodies(exporter.snapshot().toList()),
        )
    }

    @Test
    fun singleElementLogSurvivesEvictThenAppend() = runTest {
        // maxRecords = 1: every export evicts the only element, emptying the log, so the
        // tail must fall back to HEAD rather than stay pinned to the tombstoned id.
        val exporter = exporterFor(maxRecords = 1, bufferPolicy = BufferPolicy.DROP_OLDEST)
        (1..4).forEach { exporter.export(record(it.toByte())) }
        assertEquals(listOf("body-4"), bodies(exporter.snapshot().toList()))
    }

    @Test
    fun evictedRecordIsNoLongerDeduplicated() = runTest {
        // The eviction path removes the evicted record from the dedup map. If the wrong
        // record is removed (or none is), re-exporting the evicted record is silently dropped.
        val exporter = exporterFor(maxRecords = 2, bufferPolicy = BufferPolicy.DROP_OLDEST)
        val r1 = record(1)
        exporter.export(r1)
        exporter.export(record(2))
        exporter.export(record(3)) // evicts r1
        exporter.export(r1) // must be treated as new, not deduplicated
        val list = exporter.snapshot().toList()
        assertAll(
            { assertEquals(2, list.size, "buffer cap must still hold") },
            { assertTrue(r1 in list, "re-exported evicted record must be re-inserted") },
        )
    }

    // ---- Tail invalidation: merge ----

    @Test
    fun appendAfterMergeLandsAfterRemoteTail() = runTest {
        // B has seen A's record and appended after it, so B's insert sorts *after* A's
        // local tail. A stale tail would append after A's own record, producing r1,r3,r2.
        val exporterA = exporterFor(replica = replicaA)
        val exporterB = exporterFor(replica = replicaB)
        val r1 = record(1)
        exporterA.export(r1)
        exporterB.merge(exporterA.snapshot())
        exporterB.export(record(2))

        exporterA.merge(exporterB.snapshot())
        exporterA.export(record(3))

        assertEquals(
            listOf("body-1", "body-2", "body-3"),
            bodies(exporterA.snapshot().toList()),
        )
    }

    @Test
    fun mergedRemoteTombstoneFreesTheDedupSlot() = runTest {
        // The rebuild must *replace* the dedup map, not merely add to it: a merge can carry
        // in a remote Remove for a record that is visible locally, and that record's slot has
        // to be freed or a later re-export is silently swallowed and the record is lost.
        val exporter = exporterFor()
        val r1 = record(1)
        exporter.export(r1)
        exporter.export(record(2))

        // A peer that has seen r1 and removed it. piece() unions the Remove op in.
        val (remoteWithTombstone, _) = assertNotNull(exporter.snapshot().removeAt(0))
        exporter.merge(remoteWithTombstone)
        assertTrue(r1 !in exporter.snapshot().toList(), "precondition: the merge tombstoned r1")

        exporter.export(r1)
        assertTrue(
            r1 in exporter.snapshot().toList(),
            "a record tombstoned by a merge must be re-insertable",
        )
    }

    @Test
    fun mergeRebuildsCountSoEvictionStillFires() = runTest {
        // The eviction gate reads a threaded-forward visible count. merge() replaces the
        // log wholesale, so the count must be rebuilt or the cap silently stops applying.
        val exporterA = exporterFor(replica = replicaA, maxRecords = 3)
        val exporterB = exporterFor(replica = replicaB)
        (1..3).forEach { exporterB.export(record(it.toByte())) }

        exporterA.merge(exporterB.snapshot())
        exporterA.export(record(4))

        assertEquals(3, exporterA.snapshot().toList().size)
    }

    // ---- Tail invalidation: recover ----

    @Test
    fun appendAfterRecoverLandsAfterRecoveredTail() = runTest {
        val store = InMemoryDurableStore()
        val first = exporterFor(store = store)
        first.export(record(1))
        first.export(record(2))

        val second = exporterFor(store = store)
        second.recover()
        second.export(record(3))

        assertEquals(
            listOf("body-1", "body-2", "body-3"),
            bodies(second.snapshot().toList()),
        )
    }

    @Test
    fun recoverRebuildsCountSoEvictionStillFires() = runTest {
        val store = InMemoryDurableStore()
        val first = exporterFor(store = store, maxRecords = 3)
        (1..3).forEach { first.export(record(it.toByte())) }

        val second = exporterFor(store = store, maxRecords = 3)
        second.recover()
        second.export(record(4))

        assertEquals(3, second.snapshot().toList().size)
    }

    @Test
    fun recoverOnEmptyStoreLeavesTailAtHead() = runTest {
        val exporter = exporterFor()
        exporter.recover()
        exporter.export(record(1))
        exporter.export(record(2))
        assertEquals(listOf("body-1", "body-2"), bodies(exporter.snapshot().toList()))
    }

    // ---- Differential oracle vs the pre-optimisation implementation ----

    @Test
    fun scriptedRunMatchesPreOptimisationReference() = runTest {
        // cap 1 is not just a small cap: under DROP_OLDEST every eviction empties the log, so
        // the append predecessor has to fall back to HEAD. Appending after the tombstoned id
        // instead still yields the right visible order — only the op-log, and therefore the
        // bytes, give it away. cap 4 keeps the log non-empty throughout. Under DROP_NEWEST
        // both caps saturate instead and the script's later exports are all refused, which is
        // its own thing worth pinning: a merge can push the log PAST the cap, and the gate
        // has to keep refusing afterwards rather than reading a stale count.
        //
        // `segmentOps` is crossed in too, and the SMALL value is the load-bearing one. At the
        // 256-op default the script's ~20 ops never fill a segment, so the round-trip below
        // recovers from a single active segment and has zero coverage of the reclaiming path:
        // no roll, no sealed segment, no retirement. At 2 the script rolls repeatedly, so the
        // recovered op-log is a `piece`-union of several sealed segments re-purged under the
        // floor the active one carries — which is what recovery actually is now.
        val configurations = listOf(BufferPolicy.DROP_OLDEST, BufferPolicy.DROP_NEWEST)
            .flatMap { policy -> listOf(policy to ROOMY_CAP, policy to SINGLETON_CAP) }
            .flatMap { config -> listOf(config to DEFAULT_LOG_SEGMENT_OPS, config to ROLLING_SEGMENT_OPS) }
        var anyWindowed = false
        for ((config, segmentOps) in configurations) {
            val (policy, cap) = config
            val store = InMemoryDurableStore()
            val exporter = exporterFor(store = store, maxRecords = cap, bufferPolicy = policy, segmentOps = segmentOps)
            val reference = ReferenceLogExporter(replicaA, cap, policy)

            // A remote op-log whose inserts are concurrent with the local ones.
            val remoteSource = exporterFor(replica = replicaB)
            remoteSource.export(record(REMOTE_ID_1))
            remoteSource.export(record(REMOTE_ID_2))
            val remote = remoteSource.snapshot()

            for (step in SCRIPT) {
                when (step) {
                    is Step.Export -> {
                        exporter.export(record(step.id))
                        reference.export(record(step.id))
                    }
                    Step.Merge -> {
                        exporter.merge(remote)
                        reference.merge(remote)
                    }
                }
            }

            // Rga equality is op-set equality, so this asserts the op-log read back through the
            // segmented layout is exactly the one the reference implementation computes — held
            // across a rolling segmentOps too, where recovery has to re-purge several sealed
            // segments under the active one's floor to get there. Equality is kept rather than
            // relaxed to visible records BECAUSE it survives that: a reclaiming round-trip that
            // reproduces the op-set exactly is a stronger statement than one that reproduces
            // only what is visible, and relaxing an assertion that still holds buys nothing.
            val roundTripped = exporterFor(
                store = store,
                maxRecords = cap,
                bufferPolicy = policy,
                segmentOps = segmentOps,
            )
            roundTripped.recover()
            anyWindowed = anyWindowed || roundTripped.snapshot().causalFloor().entries.isNotEmpty()
            val where = "$policy/cap=$cap/segmentOps=$segmentOps"
            assertAll(
                {
                    assertEquals(
                        bodies(reference.log.toList()),
                        bodies(exporter.snapshot().toList()),
                        "$where: visible order diverged from the reference",
                    )
                },
                {
                    assertEquals(
                        reference.log,
                        roundTripped.snapshot(),
                        "$where: the op-log recovered from the store is not the reference's",
                    )
                },
                {
                    assertEquals(
                        bodies(reference.log.toList()),
                        bodies(roundTripped.snapshot().toList()),
                        "$where: recovered visible order diverged from the reference",
                    )
                },
            )
        }

        // Non-vacuity for the whole cross-product: if no configuration ever windowed, every
        // equality above is an equality between two logs that only ever grew, and the oracle
        // says nothing about the reclaiming path it was extended to cover.
        assertTrue(
            anyWindowed,
            "no configuration reached a window pass, so the recovered op-logs were never re-purged " +
                "under a floor — the script no longer exercises reclamation at all",
        )
    }

    private sealed interface Step {
        data class Export(val id: Byte) : Step
        data object Merge : Step
    }

    /**
     * `WarpLogRecordExporter`'s derived-state handling **without** the incremental caches:
     * the tail recomputed by filtering the whole [Rga.sequence], the admission gate reading
     * [Rga.size], the evicted record read via `toList()[0]`, and the dedup map copied on
     * every append. Drives the same [Rga] mutations in the same order, so the resulting
     * op-log — and therefore the encoded bytes — must be identical.
     *
     * It is the **caches** this is an oracle for, so every *policy* here is production's, not
     * history's: [BufferPolicy.DROP_NEWEST] refuses the arrival, and the op-log is windowed in
     * batches through [Rga.dropWindow] (both #2127). Transcribing the superseded evict-the-tail
     * reading — or leaving the windowing out — would make every divergence it produced read as
     * a cache bug. The windowing transcription costs the oracle nothing: production computes
     * the window from [Rga.sequence]/[Rga.tombstones] too, so that part was never cached on
     * either side.
     *
     * That transcription is **verbatim**, deliberately, which means the window walk itself is
     * NOT differentially covered here: a bug *in the walk* is copied to both sides and they
     * agree on the wrong answer. Accepted, for the reason just given — but do not read this
     * oracle as coverage of the walk. What the walk owes is pinned directly, by
     * `WarpLogRecordExporterWindowingTest`.
     */
    private class ReferenceLogExporter(
        private val replica: ReplicaId,
        private val maxRecords: Int,
        private val bufferPolicy: BufferPolicy,
    ) {
        var log: Rga<LogRecord> = Rga.empty()
            private set
        private var seenIds: Map<ByteString, RgaId> = emptyMap()
        private var evictionsSincePass: Int = 0

        fun export(record: LogRecord) {
            if (record.recordId in seenIds) return
            if (!admit()) return
            val (newLog, insertOp) = log.insertAfter(replica = replica, after = tailId(), value = record)
            log = newLog
            seenIds = seenIds + (record.recordId to insertOp.id)
            if (windowPassDue()) windowPass()
        }

        fun merge(remote: Rga<LogRecord>) {
            log = log.piece(remote)
            seenIds = log.entries().associate { (rgaId, record) -> record.recordId to rgaId }
            if (windowPassDue()) windowPass()
        }

        private fun admit(): Boolean {
            if (log.size < maxRecords) return true
            // Exhaustive, like production's: the oracle must not be the thing that
            // silently absorbs a new BufferPolicy constant.
            return when (bufferPolicy) {
                BufferPolicy.DROP_NEWEST -> false
                BufferPolicy.DROP_OLDEST -> {
                    val (newLog, _) = log.removeAt(0) ?: return true
                    val evictedRecord = log.toList()[0]
                    log = newLog
                    seenIds = seenIds - evictedRecord.recordId
                    evictionsSincePass++
                    true
                }
            }
        }

        private fun windowPassDue(): Boolean = evictionsSincePass >= maxRecords || log.size > maxRecords

        private fun windowPass() {
            evictionsSincePass = 0
            val dropped = idsOutsideWindow() ?: return
            val (newLog, _) = log.dropWindow(replica, dropped) ?: return
            log = newLog
            seenIds = log.entries().associate { (rgaId, record) -> record.recordId to rgaId }
        }

        private fun idsOutsideWindow(): Set<RgaId>? {
            val sequence = log.sequence
            val tombstones = log.tombstones
            var visibleSeen = 0
            var cut = sequence.size
            for (i in sequence.indices.reversed()) {
                if (visibleSeen == maxRecords) break
                if (sequence[i] !in tombstones) visibleSeen++
                cut = i
            }
            if (cut == 0) return null
            return sequence.subList(0, cut).toSet()
        }

        private fun tailId(): RgaId {
            val visible = log.sequence.filter { it !in log.tombstones }
            return visible.lastOrNull() ?: RgaId.HEAD
        }
    }

    private companion object {
        private const val ROOMY_CAP = 4
        private const val SINGLETON_CAP = 1

        /**
         * Small enough that the ~20-op script rolls the active segment repeatedly, so the
         * round-trip recovers from several *sealed* segments rather than from one active one.
         */
        private const val ROLLING_SEGMENT_OPS = 2
        private const val REMOTE_ID_1: Byte = 50
        private const val REMOTE_ID_2: Byte = 51

        /**
         * Appends past the cap (forcing eviction under both policies), re-exports records
         * that are still buffered (dedup hit) and records already evicted (dedup miss), and
         * folds in a concurrent remote op-log part-way through.
         */
        private val SCRIPT: List<Step> = listOf(
            Step.Export(1),
            Step.Export(2),
            Step.Export(2), // dedup hit
            Step.Export(3),
            Step.Export(4),
            Step.Export(5), // first eviction
            Step.Export(6),
            Step.Export(1), // dedup miss — already evicted under DROP_OLDEST
            Step.Merge,
            Step.Export(7),
            Step.Export(8),
            Step.Merge, // idempotent re-merge
            Step.Export(9),
            Step.Export(7), // dedup hit or miss depending on policy
        )
    }
}
