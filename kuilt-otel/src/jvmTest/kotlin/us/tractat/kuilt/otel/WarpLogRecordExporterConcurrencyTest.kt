@file:Suppress("ForbiddenImport") // deliberate real-threading regression test: WarpLogRecordExporter threads mutable derived state (tail id, visible count, dedup map) forward across export() calls under an explicit lock, and a lost update there is only observable on a genuine multi-threaded dispatcher, which virtual-time runTest cannot provide — the production-dispatcher-in-tests ban is exempted here per the module's coroutine-determinism policy.

package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
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
import kotlin.test.fail

/**
 * `WarpLogRecordExporter` advertises correctness under a real multi-threaded dispatcher, and
 * it carries mutable derived state across calls — the tail [us.tractat.kuilt.crdt.RgaId],
 * the visible-record count that gates eviction, and the `recordId → RgaId` dedup map. All three
 * live under the same `reentrantLock` as the `Rga` itself, so a concurrent `export()` can never
 * observe them out of step with the log.
 *
 * Four tests in three groups, all on a fixed real thread pool:
 *
 * - **The in-memory state**, in `concurrentExportsKeepDerivedStateInStepWithTheLog`: a stress
 *   loop of concurrent `export()`s asserting the three invariants a lost update would break —
 *   the buffer cap holds exactly, no record is dropped when there is room for all of them, and
 *   a record exported concurrently from many threads lands exactly once.
 * - **The store**, in `concurrentExportsThroughWindowPassesLeaveTheStoreDescribingTheLog` and
 *   `concurrentMergesThroughWindowPassesLeaveTheStoreDescribingTheLog`: the same shape run at a
 *   cap small enough to drive window passes and segment retirement, asserting *through the
 *   store* that no segment key is orphaned and that a restart reconstructs exactly what the
 *   exporter holds. The in-memory log is correct in every interleaving — it is only ever touched
 *   under `lock` — so it is the disk that diverges.
 * - **The turn boundary**, in `anExportDoesNotBuildItsBatchWhileAnotherExportsCommitIsInFlight`:
 *   one turn is held parked inside its first `store.write` while a second `export()` is started,
 *   and the log must not have grown. That is the one observable separating the shipped
 *   whole-turn mutex from the narrower variant that holds it over `commit()` alone; the stress
 *   loops above are green under both.
 */
class WarpLogRecordExporterConcurrencyTest {

    private val replicaA = ReplicaId("A")
    /** The peer that authored merge batch [batch] — see [foreignLog] for why each batch needs one. */
    private fun foreignReplica(batch: Int) = ReplicaId("B$batch")

    // `i` as four little-endian bytes, then those four bytes again: Kotlin's `Int.shr` masks the
    // shift count to five bits, so `b >= 4` wraps back to `b - 4` and the second half of the id
    // copies the first. Harmless — 2^32 distinct ids is far more than these tests use — but it is
    // not the eight independent bytes it reads as. What it must not be is `i.toByte()` repeated:
    // the window tests below use ids past 255, and a single-byte id would silently alias two
    // records into one dedup slot.
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
        // A missing index is a broken fixture, not a clean result. Every exporter here writes one
        // on its very first batch, so its absence means no batch ever landed — and returning
        // "no orphans" for that would green this assertion on precisely the store state it exists
        // to catch.
        val bytes = store.read(INDEX_KEY_FOR_TEST)
            ?: fail("no index was ever written — the fixture never landed a batch, so nothing was checked")
        val index = decodeIndexForTest(bytes)
        val named = index.sealedSegments.toSet() + index.active + index.retired
        return store.keys()
            .filter { it.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }
            .map { it.substringAfterLast('.').toInt() }
            .toSet() - named
    }

    private fun retirements(store: RecordingStore) = store.operations().count {
        it.kind == StoreOpKind.DELETE && it.key.name.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST)
    }

    /**
     * Writes to the **active** segment's key — the merge path's evidence that a window pass ran.
     *
     * `merge` writes an adopted segment under a freshly allocated number and never rolls the
     * active one, so the active segment's key is written on that path only by the
     * `activeSegmentWrite()` a pass adds. [ACTIVE_SEGMENT] is the number a fresh exporter opens
     * on, and nothing on the merge path advances it.
     */
    private fun windowPassWrites(store: RecordingStore) = store.operations().count {
        it.kind == StoreOpKind.WRITE && it.key.name == segmentKeyForTest(ACTIVE_SEGMENT)
    }

    /**
     * A foreign replica's op-log of [count] records, distinct per [batch].
     *
     * **Each batch is a different peer**, and that is load-bearing rather than colour. Every
     * batch builds from a fresh `Rga.empty()`, so its ops are minted at seqs 1..[count]; giving
     * them all one [ReplicaId] would make batch 1's `RgaId` *identical* to batch 0's, and
     * `Rga.piece` is set union over ids — eight merges would collapse to [count] visible records
     * no matter how distinct their `recordId`s were. The buffer would then never pass
     * [WINDOW_CAP], no window pass would ever come due, and nothing would ever be retired. That
     * is not hypothetical: it is what this fixture did before the preconditions below were added.
     */
    private fun foreignLog(batch: Int, count: Int = FOREIGN_RECORDS): Rga<LogRecord> {
        var rga = Rga.empty<LogRecord>()
        var tail = RgaId.HEAD
        repeat(count) { i ->
            val (next, op) = rga.insertAfter(
                replica = foreignReplica(batch),
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
        var windowPassWrites = 0
        var deletes = 0
        var emptyLogs = 0
        try {
            runBlocking {
                repeat(WINDOW_REPEATS) { iter ->
                    val recording = RecordingStore()
                    val exporter = windowingExporter(VariableLatencyStore(iter, recording))
                    (0 until MERGES).map { i ->
                        launch(dispatcher) { exporter.merge(foreignLog(i)) }
                    }.joinAll()

                    val live = exporter.snapshot().toList()
                    if (live.isEmpty()) emptyLogs++
                    // Read every counter BEFORE the recovery exporter below touches the store:
                    // `recover()` sweeps the on-disk retirement ledger, and those deletes are
                    // recovery's, not the merge path's.
                    windowPassWrites += windowPassWrites(recording)
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
            // Three preconditions, because this test's name claims all three and none of them is
            // implied by the two assertions below: both are satisfied vacuously by a run that
            // merged nothing, windowed nothing and retired nothing. A future edit to MERGES,
            // FOREIGN_RECORDS or WINDOW_CAP that stopped reaching the path would otherwise stay
            // green while testing nothing.
            {
                assertEquals(
                    0,
                    emptyLogs,
                    "precondition: the merged log was empty, so live-vs-recovered compared nothing",
                )
            },
            {
                assertTrue(
                    windowPassWrites > 0,
                    "precondition: no window pass ever ran — only a pass makes merge() write the " +
                        "active segment, so the windowing path was never reached",
                )
            },
            {
                assertTrue(
                    deletes > 0,
                    "precondition: nothing was ever retired, so the delete path was never reached at all",
                )
            },
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

    // ── Turn exclusion: a turn must not BUILD while another turn's commit is in flight ─────
    //
    // The narrower variant of the fix — hold the mutex over `commit()` alone rather than over
    // the whole turn — greens both stress tests above. It is still wrong, and this is where the
    // difference shows: under it a second export takes `lock`, inserts into the log and builds
    // its whole batch while the first turn's writes are still landing. The store cannot see that
    // (every step of a double-stage is idempotent, so it leaves no trace to assert on) but the
    // in-memory log can, straight through `snapshot()`.

    /**
     * A store whose **first** [write] parks: it completes [entered], then awaits [release].
     *
     * That holds exactly one turn open inside its commit, on a real thread, so a test can start a
     * second turn and look at what it has done by the time it settles.
     */
    private class ParkFirstWriteStore(
        private val backing: DurableStore = InMemoryDurableStore(),
    ) : DurableStore {
        private val lock = reentrantLock()
        private var parkTaken = false

        /** Completed from inside the first [write], once it is committed to parking. */
        val entered: CompletableDeferred<Unit> = CompletableDeferred()

        /** Complete this to let the parked write proceed. */
        val release: CompletableDeferred<Unit> = CompletableDeferred()

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            val park = lock.withLock { if (parkTaken) false else true.also { parkTaken = true } }
            if (park) {
                entered.complete(Unit)
                release.await()
            }
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun anExportDoesNotBuildItsBatchWhileAnotherExportsCommitIsInFlight() {
        val dispatcher = newFixedThreadPoolContext(THREADS, "otel-log-turn-exclusion")
        try {
            runBlocking {
                val store = ParkFirstWriteStore()
                val exporter = WarpLogRecordExporter(replicaA, store)

                val first = launch(dispatcher) { exporter.export(record(0)) }
                store.entered.await() // `first` is now parked inside its first store.write
                val second = launch(dispatcher) { exporter.export(record(1)) }
                // Generous on purpose. The negative direction is what needs the window: under the
                // narrow variant `second` inserts within microseconds of being scheduled, so any
                // wait long enough to see it is long enough. Under the shipped fix `second` is
                // parked on the mutex and no wait changes that.
                delay(SETTLE_MILLIS)
                val stagedWhileParked = exporter.snapshot().toList().size

                store.release.complete(Unit)
                joinAll(first, second)
                val afterRelease = exporter.snapshot().toList().size

                assertAll(
                    {
                        assertEquals(
                            1,
                            stagedWhileParked,
                            "a second export built its batch while the first turn's commit was in " +
                                "flight: it took `lock`, inserted into the log and encoded a batch " +
                                "over state the in-flight turn is still writing. Build order is no " +
                                "longer apply order, and the retirement staging window is open again",
                        )
                    },
                    {
                        // Without this the assertion above is satisfied by an export that never
                        // ran at all — it is what proves the second turn had a record to insert.
                        assertEquals(
                            2,
                            afterRelease,
                            "the second export did not land once the first turn's commit completed",
                        )
                    },
                )
            }
        } finally {
            dispatcher.close()
        }
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

        /** The segment number a fresh exporter opens on; the merge path never advances it. */
        private const val ACTIVE_SEGMENT = 0

        /** How long the parked-turn test lets a second export run before looking at the log. */
        private const val SETTLE_MILLIS = 500L
    }
}
