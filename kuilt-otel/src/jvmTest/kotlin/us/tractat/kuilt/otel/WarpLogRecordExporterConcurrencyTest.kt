@file:Suppress("ForbiddenImport") // deliberate real-threading regression test: WarpLogRecordExporter threads mutable derived state (tail id, visible count, dedup map) forward across export() calls under an explicit lock, and a lost update there is only observable on a genuine multi-threaded dispatcher, which virtual-time runTest cannot provide — the production-dispatcher-in-tests ban is exempted here per the module's coroutine-determinism policy.

package us.tractat.kuilt.otel

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `WarpLogRecordExporter` advertises correctness under a real multi-threaded dispatcher, and
 * it now carries mutable derived state across calls — the tail [us.tractat.kuilt.crdt.RgaId],
 * the visible-record count that gates eviction, and the `recordId → RgaId` dedup map. All three
 * live under the same `reentrantLock` as the `Rga` itself, so a concurrent `export()` can never
 * observe them out of step with the log.
 *
 * This stress loop drives many concurrent `export()`s on a fixed real thread pool and asserts
 * the three invariants a lost update would break: the buffer cap holds exactly, no record is
 * dropped when there is room for all of them, and a record exported concurrently from many
 * threads lands exactly once.
 */
class WarpLogRecordExporterConcurrencyTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    // Spread across all 8 bytes, not `i.toByte()` repeated: the window tests below use ids past
    // 255, and a single-byte id would silently alias two records into one dedup slot.
    private fun record(i: Int) = LogRecord(
        recordId = ByteString(ByteArray(8) { b -> (i shr (8 * b)).toByte() }),
        body = "body-$i",
        observedEpochNanos = 1_000L + i,
    )

    /** Yields a variable number of times before committing, so writes finish out of start order. */
    private class VariableLatencyStore(
        seed: Int,
        private val backing: DurableStore = InMemoryDurableStore(),
    ) : DurableStore {
        private val rng = kotlin.random.Random(seed)
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            repeat(rng.nextInt(MAX_YIELDS)) { yield() }
            backing.write(key, bytes)
        }
        override suspend fun delete(key: StoreKey) = backing.delete(key)

        private companion object {
            private const val MAX_YIELDS = 6
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun concurrentExportsKeepDerivedStateInStepWithTheLog() {
        val dispatcher = newFixedThreadPoolContext(THREADS, "otel-log-export-stress")
        try {
            runBlocking {
                repeat(REPEATS) { iter ->
                    // (a) Uncapped: every distinct record must survive — a lost dedup-map or
                    // tail update would drop or misplace one.
                    val uncapped = WarpLogRecordExporter(replicaA, VariableLatencyStore(iter))
                    (0 until CONCURRENT).map { i ->
                        launch(dispatcher) { uncapped.export(record(i)) }
                    }.joinAll()
                    assertEquals(
                        CONCURRENT,
                        uncapped.snapshot().toList().size,
                        "iter $iter: lost update — records dropped with no buffer cap in play",
                    )

                    // (b) Capped: the visible count gates eviction, so a lost decrement or
                    // increment shows up as a buffer that overshoots or undershoots the cap.
                    val capped = WarpLogRecordExporter(
                        replica = replicaA,
                        store = VariableLatencyStore(iter),
                        maxRecords = CAP,
                        bufferPolicy = BufferPolicy.DROP_OLDEST,
                    )
                    (0 until CONCURRENT).map { i ->
                        launch(dispatcher) { capped.export(record(i)) }
                    }.joinAll()
                    assertEquals(
                        CAP,
                        capped.snapshot().toList().size,
                        "iter $iter: buffer cap violated",
                    )

                    // (c) The same record exported from every thread must land exactly once.
                    val deduping = WarpLogRecordExporter(replicaA, VariableLatencyStore(iter))
                    val same = record(0)
                    (0 until CONCURRENT).map {
                        launch(dispatcher) { deduping.export(same) }
                    }.joinAll()
                    assertEquals(
                        1,
                        deduping.snapshot().toList().size,
                        "iter $iter: concurrent re-export of one record was not deduplicated",
                    )
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    // ── The write turn: batches must reach the store in the order they were built ──────────
    //
    // Everything above runs at the default maxRecords = 10_000, which 32 concurrent exports
    // never reach — so no test in this file drove a window pass, let alone the segment
    // retirement that follows one, and the DELETE path was entirely unexercised under real
    // threads. The two tests below run at a cap small enough that passes and retirements
    // happen many times per iteration.
    //
    // Both assert the same two properties, because a batch built under `lock` but applied
    // outside it can break either:
    //
    //  - **no orphan.** Every resident segment key must be named by the on-disk index, as
    //    sealed, as active, or on the retirement ledger. There is no key-enumeration API in
    //    production, so a key the index forgets is unreachable and unsweepable forever. A
    //    stale index write landing after a fresher one drops the numbers the fresher one
    //    had just added.
    //  - **recovery reconstructs exactly what the exporter holds.** `export` returns Success
    //    once the durable write returns, so a record it accepted must survive a restart. A
    //    stale ACTIVE-SEGMENT write landing after a fresher one silently discards whatever
    //    the fresher one added — and, when a window pass had just raised the floor, also the
    //    covering state that a queued Sweep is about to delete a segment on the strength of.
    //
    // Neither is observable through `snapshot()`, which is why the assertions here go through
    // the store: the in-memory log is correct in every interleaving — it is only ever touched
    // under `lock` — and it is the DISK that diverges from it.

    /** The window-pass configuration: a cap and a segment size small enough to retire. */
    private fun windowingExporter(store: DurableStore) = WarpLogRecordExporter(
        replica = replicaA,
        store = store,
        maxRecords = WINDOW_CAP,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = WINDOW_SEGMENT_OPS,
    )

    /** Segment numbers resident in [store] that the on-disk index does not name at all. */
    private suspend fun orphanedSegments(store: RecordingStore): Set<Int> {
        val index = decodeIndexForTest(store.read(INDEX_KEY_FOR_TEST) ?: return emptySet())
        val named = index.sealedSegments.toSet() + index.active + index.retired
        return store.keys()
            .filter { it.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }
            .map { it.substringAfterLast('.').toInt() }
            .toSet() - named
    }

    private fun retirements(store: RecordingStore) = store.operations().count {
        it.kind == StoreOpKind.DELETE && it.key.name.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST)
    }

    /** A foreign replica's op-log of [count] records, distinct per [batch]. */
    private fun foreignLog(batch: Int, count: Int = FOREIGN_RECORDS): Rga<LogRecord> {
        var rga = Rga.empty<LogRecord>()
        var tail = RgaId.HEAD
        repeat(count) { i ->
            val (next, op) = rga.insertAfter(
                replica = replicaB,
                after = tail,
                value = record(FOREIGN_ID_BASE + batch * count + i),
            )
            rga = next
            tail = op.id
        }
        return rga
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun concurrentExportsThroughWindowPassesLeaveTheStoreDescribingTheLog() {
        val dispatcher = newFixedThreadPoolContext(THREADS, "otel-log-window-stress")
        val orphans = mutableListOf<String>()
        val divergences = mutableListOf<String>()
        var deletes = 0
        try {
            runBlocking {
                repeat(WINDOW_REPEATS) { iter ->
                    val recording = RecordingStore()
                    val exporter = windowingExporter(VariableLatencyStore(iter, recording))
                    (0 until CONCURRENT).map { i ->
                        launch(dispatcher) { exporter.export(record(i)) }
                    }.joinAll()

                    val live = exporter.snapshot().toList()
                    deletes += retirements(recording)
                    orphanedSegments(recording).takeIf { it.isNotEmpty() }?.let { orphans += "iter $iter: $it" }
                    val recovered = windowingExporter(recording).also { it.recover() }.snapshot().toList()
                    if (recovered != live) {
                        divergences += "iter $iter: ${live.size} live vs ${recovered.size} recovered"
                    }
                }
            }
        } finally {
            dispatcher.close()
        }

        assertAll(
            {
                assertTrue(
                    deletes > 0,
                    "precondition: nothing was ever retired, so the delete path was never reached at all",
                )
            },
            {
                assertTrue(
                    orphans.isEmpty(),
                    "segment keys survive that the index does not name; unreachable and unsweepable " +
                        "in a format with no key enumeration: $orphans",
                )
            },
            {
                assertTrue(
                    divergences.isEmpty(),
                    "a restart did not reconstruct what the exporter holds; export() returned Success " +
                        "for records the store no longer carries: $divergences",
                )
            },
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun concurrentMergesThroughWindowPassesLeaveTheStoreDescribingTheLog() {
        // `merge` builds and commits its own batch — a second call site with the same hazard,
        // and the only one a gossip-fed replica uses. Each merge adopts the remote log as a
        // NEW sealed segment, so two overlapping merges each write an index naming a segment
        // the other's index does not: whichever lands last decides, and the other's segment
        // key is orphaned.
        val dispatcher = newFixedThreadPoolContext(THREADS, "otel-log-merge-stress")
        val orphans = mutableListOf<String>()
        val divergences = mutableListOf<String>()
        try {
            runBlocking {
                repeat(WINDOW_REPEATS) { iter ->
                    val recording = RecordingStore()
                    val exporter = windowingExporter(VariableLatencyStore(iter, recording))
                    (0 until MERGES).map { i ->
                        launch(dispatcher) { exporter.merge(foreignLog(i)) }
                    }.joinAll()

                    val live = exporter.snapshot().toList()
                    orphanedSegments(recording).takeIf { it.isNotEmpty() }?.let { orphans += "iter $iter: $it" }
                    val recovered = windowingExporter(recording).also { it.recover() }.snapshot().toList()
                    if (recovered != live) {
                        divergences += "iter $iter: ${live.size} live vs ${recovered.size} recovered"
                    }
                }
            }
        } finally {
            dispatcher.close()
        }

        assertAll(
            {
                assertTrue(
                    orphans.isEmpty(),
                    "concurrent merges orphaned segment keys the index does not name: $orphans",
                )
            },
            {
                assertTrue(
                    divergences.isEmpty(),
                    "a restart did not reconstruct what the merged log holds: $divergences",
                )
            },
        )
    }

    private companion object {
        private const val THREADS = 4
        private const val REPEATS = 50
        private const val CONCURRENT = 32
        private const val CAP = 8

        private const val WINDOW_REPEATS = 20
        private const val WINDOW_CAP = 8
        private const val WINDOW_SEGMENT_OPS = 4
        private const val MERGES = 8
        private const val FOREIGN_RECORDS = 4
        private const val FOREIGN_ID_BASE = 1_000
    }
}
