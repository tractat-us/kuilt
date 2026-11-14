package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pins that a `CancellationException` a [DurableStore] **mints** reads as a failed store call to
 * [WarpLogRecordExporter], while the caller's **own** cancellation still propagates (#3384).
 *
 * A store may run its own `withTimeout`, and when that expires it throws a
 * `TimeoutCancellationException` at a caller whose job is alive. `runCatchingCancellable` rethrows
 * every `CancellationException`, so each store call it guarded let that one escape: [export] threw
 * although it never throws, and [recover] threw although a throw there leaves capture uninstalled
 * for the whole process (#1860). Only `currentCoroutineContext().ensureActive()` tells the two
 * apart, which is why every arm here has a control in which the caller really is cancelled.
 *
 * [ScriptedStore] mints with a real `withTimeout`, so the exception is the one a store would
 * produce, and it counts every firing: an arm whose rig never fired is green by absence.
 */
class WarpLogRecordExporterStoreMintedCancellationTest {

    private val replica = ReplicaId("A")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    /** Small enough that a few hundred exports roll, window and retire segments. */
    private fun exporterFor(store: DurableStore) = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = 10,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = 8,
    )

    private suspend fun recovered(store: DurableStore): WarpLogRecordExporter =
        exporterFor(store).also { it.recover() }

    private suspend fun bodiesAfterRestart(store: DurableStore): List<String?> =
        recovered(store).snapshot().toList().map { it.body }

    private enum class Op { READ, WRITE, DELETE }

    /** What a [ScriptedStore] does to the one call its armed rule matches. */
    private enum class Act {
        /** Throw a `TimeoutCancellationException` from the store's own `withTimeout`. */
        MINT,

        /** Suspend until the caller is cancelled, so the cancellation lands inside the call. */
        PARK,
    }

    /** The one call a [ScriptedStore] intercepts: an [op] on a key that [matches], and what it does. */
    private data class Rule(val op: Op, val matches: (StoreKey) -> Boolean, val act: Act)

    /**
     * Delegates to [backing], except for the **next** call matching the armed rule, which it
     * mints a cancellation on or parks. One-shot, so the turns after it see a healthy store.
     *
     * [minted], [parked], [cancelled] and [firedOn] are precondition guards, never the assertion
     * under test.
     */
    private class ScriptedStore(private val backing: DurableStore) : DurableStore {
        private val lock = reentrantLock()
        private var rule: Rule? = null
        private var firedKey: StoreKey? = null
        private var mintedCount = 0
        private var parkedCount = 0
        private var cancelledCount = 0

        val minted: Int get() = lock.withLock { mintedCount }
        val parked: Int get() = lock.withLock { parkedCount }
        val cancelled: Int get() = lock.withLock { cancelledCount }

        /** The key of the call the rule fired on, or `null` while it has not fired. */
        val firedOn: StoreKey? get() = lock.withLock { firedKey }

        fun arm(rule: Rule): Unit = lock.withLock { this.rule = rule }

        private suspend fun intercept(op: Op, key: StoreKey) {
            val act = lock.withLock {
                val armed = rule ?: return@withLock null
                if (armed.op != op || !armed.matches(key)) return@withLock null
                rule = null
                firedKey = key
                armed.act
            } ?: return
            when (act) {
                Act.MINT -> {
                    lock.withLock { mintedCount++ }
                    // The caller's job stays alive: only this inner scope is cancelled, and
                    // `withTimeout` throws its TimeoutCancellationException at the caller.
                    withTimeout(1.milliseconds) { awaitCancellation() }
                }
                Act.PARK -> {
                    lock.withLock { parkedCount++ }
                    try {
                        awaitCancellation()
                    } finally {
                        // `awaitCancellation` leaves only by cancellation, so this counts exactly
                        // the cancellations that reached the parked call.
                        lock.withLock { cancelledCount++ }
                    }
                }
            }
        }

        override suspend fun read(key: StoreKey): ByteArray? {
            intercept(Op.READ, key)
            return backing.read(key)
        }

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            intercept(Op.WRITE, key)
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey) {
            intercept(Op.DELETE, key)
            backing.delete(key)
        }
    }

    private val isSegment: (StoreKey) -> Boolean = { it.name.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }
    private val isIndex: (StoreKey) -> Boolean = { it == INDEX_KEY_FOR_TEST }
    private val isLegacy: (StoreKey) -> Boolean = { it.name == LOG_KEY_PREFIX_FOR_TEST }

    /** Run [block], turning a cancellation that escapes it while this job is alive into a value. */
    private suspend fun <T> escapeOf(block: suspend () -> T): Pair<T?, CancellationException?> = try {
        block() to null
    } catch (escaped: CancellationException) {
        // Our own job is alive, so this falls through and records what escaped.
        currentCoroutineContext().ensureActive()
        null to escaped
    }

    /** Export records until [store]'s rule has fired, returning every result in order. */
    private suspend fun exportUntilFired(
        exporter: WarpLogRecordExporter,
        store: ScriptedStore,
        from: Int,
    ): Pair<List<ExportResult?>, List<CancellationException>> {
        val results = mutableListOf<ExportResult?>()
        val escapes = mutableListOf<CancellationException>()
        var id = from
        while (store.minted == 0 && id < from + 500) {
            val (result, escaped) = escapeOf { exporter.export(record(id++)) }
            results += result
            if (escaped != null) escapes += escaped
        }
        return results to escapes
    }

    /** A store holding a retirement ledger, as a start after an interrupted sweep finds it. */
    private suspend fun storeWithALedger(): Pair<RecordingStore, List<LogRecord>> {
        val backing = RecordingStore()
        val exporter = exporterFor(FailDeleteStore(backing))
        repeat(300) { i -> exporter.export(record(i)) }
        return backing to exporter.snapshot().toList()
    }

    private fun segmentNumberOf(key: StoreKey): Long = key.name.removePrefix(SEGMENT_KEY_PREFIX_FOR_TEST).toLong()

    private suspend fun ledgerOf(store: RecordingStore): List<Long> =
        decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST))).retired

    // ---- Export: the durable write ----

    @Test
    fun aCancellationTheStoreMintsOnTheSegmentWriteFailsTheExportAndTheNextTurnRepairsIt() = runTest {
        val backing = RecordingStore()
        val store = ScriptedStore(backing)
        val exporter = exporterFor(store)
        val first = exporter.export(record(0))

        store.arm(Rule(Op.WRITE, isSegment, Act.MINT))
        val (minted, escaped) = escapeOf { exporter.export(record(1)) }
        val failedAfter = exporter.health.value.failed
        val bodiesBeforeRepair = bodiesAfterRestart(backing)
        val repaired = exporter.export(record(2))
        val bodiesAfterRepair = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(ExportResult.Success, first) },
            { assertEquals(1, store.minted, "the rig minted ${store.minted} cancellations, not one") },
            { assertNull(escaped, "a cancellation the store minted escaped export: $escaped") },
            { assertTrue(minted is ExportResult.Failure, "the minted write reported $minted") },
            { assertEquals(1L, failedAfter, "the minted write was not counted as a refused write") },
            { assertEquals(listOf("body-0"), bodiesBeforeRepair, "the minted write landed anyway, so nothing was left to repair") },
            { assertEquals(ExportResult.Success, repaired) },
            { assertEquals(listOf("body-0", "body-1", "body-2"), bodiesAfterRepair, "the next turn did not repair the segment") },
        )
    }

    @Test
    fun theCallersOwnCancellationInTheSegmentWriteStillPropagates() = runTest {
        // The control for the arm above: without `ensureActive` the catch would turn this into a
        // Failure, and the export would return instead of being cancelled.
        val backing = RecordingStore()
        val store = ScriptedStore(backing)
        val exporter = exporterFor(store)
        exporter.export(record(0))

        store.arm(Rule(Op.WRITE, isSegment, Act.PARK))
        val returned = cancelInside(store) { exporter.export(record(1)) }
        val retried = exporter.export(record(1))
        val bodies = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(1, store.parked, "the rig never parked the segment write") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the segment write") },
            { assertNull(returned, "the cancelled export returned instead of being cancelled: $returned") },
            { assertEquals(ExportResult.Success, retried) },
            { assertEquals(listOf("body-0", "body-1"), bodies, "the retry after a cancelled write was not durable") },
        )
    }

    // ---- Export: the best-effort sweep of a retired segment ----

    @Test
    fun aCancellationTheStoreMintsOnASweepDoesNotFailTheExport() = runTest {
        // A sweep deletes superseded garbage after the record is durable; its failure must not
        // fail the export, and the number must stay on the ledger for the next start to retry.
        val backing = RecordingStore()
        val store = ScriptedStore(backing)
        val exporter = exporterFor(store)

        store.arm(Rule(Op.DELETE, isSegment, Act.MINT))
        val (results, escapes) = exportUntilFired(exporter, store, from = 0)
        val key = requireNotNull(store.firedOn) { "the rig never fired" }
        val number = segmentNumberOf(key)
        val keptAfterMint = key.name in backing.keys()
        // One more turn, so a turn that rewrites the index has had the chance to drop the number.
        val next = exporter.export(record(results.size))
        val ledgerAfterNextTurn = ledgerOf(backing)
        val live = exporter.snapshot().toList()
        val afterRestart = recovered(backing).snapshot().toList()

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on a sweep in ${results.size} exports") },
            { assertEquals(emptyList(), escapes, "a cancellation the store minted on a sweep escaped export") },
            { assertTrue(results.all { it == ExportResult.Success }, "a minted sweep failed an export: $results") },
            { assertEquals(0L, exporter.health.value.failed, "a minted sweep was reported as a refused write") },
            { assertTrue(keptAfterMint, "precondition: the minted delete of $key landed anyway") },
            { assertEquals(ExportResult.Success, next) },
            { assertTrue(number in ledgerAfterNextTurn, "segment $number left the ledger unswept: $ledgerAfterNextTurn") },
            { assertEquals(live, afterRestart, "a minted sweep cost records") },
            { assertTrue(key.name !in backing.keys(), "segment $number was orphaned: the next start never swept it") },
        )
    }

    @Test
    fun theCallersOwnCancellationInASweepStillPropagates() = runTest {
        val backing = RecordingStore()
        val store = ScriptedStore(backing)
        val exporter = exporterFor(store)

        store.arm(Rule(Op.DELETE, isSegment, Act.PARK))
        var id = 0
        var returned: ExportResult? = null
        while (store.parked == 0 && id < 500) {
            returned = cancelInside(store) { exporter.export(record(id++)) }
        }

        assertAll(
            { assertEquals(1, store.parked, "the rig never parked a sweep in $id exports") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the sweep") },
            { assertNull(returned, "the export cancelled in its sweep returned instead: $returned") },
        )
    }

    // ---- Recovery ----

    @Test
    fun aCancellationTheStoreMintsOnTheIndexReadDoesNotEscapeRecover() = runTest {
        val backing = RecordingStore()
        exporterFor(backing).also { e -> repeat(30) { e.export(record(it)) } }
        val store = ScriptedStore(backing)
        store.arm(Rule(Op.READ, isIndex, Act.MINT))

        val exporter = exporterFor(store)
        val (_, escaped) = escapeOf { exporter.recover() }

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on the index read") },
            { assertNull(escaped, "a cancellation the store minted escaped recover: $escaped") },
            { assertTrue(exporter.health.value.recoveryFailed, "an unread index was not reported as a recovery failure") },
        )
    }

    @Test
    fun aCancellationTheStoreMintsOnOneSegmentReadCostsThatSegmentAlone() = runTest {
        val backing = RecordingStore()
        val expected = exporterFor(backing).also { e -> repeat(30) { e.export(record(it)) } }.snapshot().toList()
        val store = ScriptedStore(backing)
        store.arm(Rule(Op.READ, isSegment, Act.MINT))

        val exporter = exporterFor(store)
        val (_, escaped) = escapeOf { exporter.recover() }
        val held = exporter.snapshot().size
        val afterCleanStart = recovered(backing).snapshot().toList()

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on a segment read") },
            { assertNull(escaped, "a cancellation the store minted escaped recover: $escaped") },
            { assertTrue(held in 1 until expected.size, "one unread segment should cost its records alone; held $held of ${expected.size}") },
            { assertEquals(false, exporter.health.value.recoveryFailed) },
            // The unread segment stays named, so the next clean start finds every record.
            { assertEquals(expected, afterCleanStart, "the unread segment was orphaned") },
        )
    }

    @Test
    fun aCancellationTheStoreMintsOnTheLegacySweepDoesNotCostTheLog() = runTest {
        val backing = RecordingStore()
        val expected = exporterFor(backing).also { e -> repeat(30) { e.export(record(it)) } }.snapshot().toList()
        val store = ScriptedStore(backing)
        store.arm(Rule(Op.DELETE, isLegacy, Act.MINT))

        val exporter = exporterFor(store)
        val (_, escaped) = escapeOf { exporter.recover() }

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on the legacy sweep") },
            { assertNull(escaped, "a cancellation the store minted escaped recover: $escaped") },
            { assertEquals(expected, exporter.snapshot().toList(), "a minted delete of garbage cost the log") },
            { assertEquals(false, exporter.health.value.recoveryFailed) },
        )
    }

    @Test
    fun aCancellationTheStoreMintsOnARecoverySweepKeepsTheNumberOnTheLedger() = runTest {
        val (backing, live) = storeWithALedger()
        val ledger = ledgerOf(backing)
        val store = ScriptedStore(backing)
        store.arm(Rule(Op.DELETE, isSegment, Act.MINT))

        val exporter = exporterFor(store)
        val (_, escaped) = escapeOf { exporter.recover() }
        val recoveredLog = exporter.snapshot().toList()
        val key = requireNotNull(store.firedOn) { "the rig never fired" }
        val number = segmentNumberOf(key)
        val keptAfterMint = key.name in backing.keys()
        // The recovered index is dirty once the other ledger entries are swept, so this turn
        // rewrites it: the number survives only if recovery kept it on the ledger.
        val next = exporter.export(record(10_000))
        val ledgerAfterNextTurn = ledgerOf(backing)
        recovered(backing)

        assertAll(
            { assertTrue(ledger.isNotEmpty(), "precondition: the fixture left no ledger to sweep") },
            { assertEquals(1, store.minted, "the rig never minted on a recovery sweep") },
            { assertNull(escaped, "a cancellation the store minted escaped recover: $escaped") },
            { assertEquals(live, recoveredLog, "a minted sweep cost the recovered log") },
            { assertEquals(false, exporter.health.value.recoveryFailed) },
            { assertTrue(keptAfterMint, "precondition: the minted delete of $key landed anyway") },
            { assertEquals(ExportResult.Success, next) },
            { assertTrue(number in ledgerAfterNextTurn, "segment $number left the ledger unswept: $ledgerAfterNextTurn") },
            { assertTrue(key.name !in backing.keys(), "segment $number was orphaned: the next clean start never swept it") },
        )
    }

    @Test
    fun theCallersOwnCancellationInEachRecoveryCallStillPropagates() = runTest {
        // One control per guarded recovery call: the index read, the legacy sweep, a ledger sweep,
        // and a segment read. Without `ensureActive` each would log, carry on, and return.
        val (backing, _) = storeWithALedger()
        val rules = listOf(
            "index read" to Rule(Op.READ, isIndex, Act.PARK),
            "legacy sweep" to Rule(Op.DELETE, isLegacy, Act.PARK),
            "ledger sweep" to Rule(Op.DELETE, isSegment, Act.PARK),
            "segment read" to Rule(Op.READ, isSegment, Act.PARK),
        )
        val outcomes = rules.map { (name, rule) ->
            val store = ScriptedStore(backing)
            store.arm(rule)
            val returned = cancelInside(store) { exporterFor(store).recover() }
            Triple(name, store, returned)
        }

        assertAll(
            *outcomes.flatMap { (name, store, returned) ->
                listOf(
                    { assertEquals(1, store.parked, "the rig never parked the $name") },
                    { assertEquals(1, store.cancelled, "the cancellation never landed inside the $name") },
                    { assertNull(returned, "recover cancelled in its $name returned instead of being cancelled") },
                )
            }.toTypedArray(),
        )
    }

    /**
     * Run [block] in a child job, cancel that job once [store] has parked a call, and return what
     * [block] returned — `null` when it was cancelled, as it must be.
     */
    private suspend fun <T : Any> TestScope.cancelInside(store: ScriptedStore, block: suspend () -> T): T? {
        val parkedBefore = store.parked
        var returned: T? = null
        val job = launch { returned = block() }
        runCurrent()
        if (store.parked > parkedBefore) job.cancelAndJoin() else job.join()
        return returned
    }
}
