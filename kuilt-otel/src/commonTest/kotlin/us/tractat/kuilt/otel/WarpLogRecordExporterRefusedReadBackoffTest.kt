package us.tractat.kuilt.otel

import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the counted backoff on [WarpLogRecordExporter]'s refused persisted read (#3404).
 *
 * Under the fail-closed rule (#3385) a refused read is retried before a write turn runs. Retried on
 * **every** turn, a long outage made each drained log batch pay the store's full timeout. Now, after
 * `k` refused reads in a row, the next `min(2^k − 1, 63)` turns refuse without touching the store,
 * and the read that lands resets `k`. The window is counted in turns: the exporter owns no clock.
 *
 * [ReadRefusingStore] refuses the index read, which is the first call recovery makes, so an
 * attempted read is exactly one call on the store while it is down. Every arm counts the store
 * calls a turn made, and the precondition that the rig refused at all.
 */
class WarpLogRecordExporterRefusedReadBackoffTest {

    private val replica = ReplicaId("A")

    private fun recordId(id: Int): ByteString = ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporterFor(store: DurableStore) = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = 100,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = 8,
    )

    private suspend fun bodiesAfterRestart(store: DurableStore): List<String?> =
        exporterFor(store).also { it.recover() }.snapshot().toList().map { it.body }

    /** A store holding ten records, the index read refused from the start, and those records' bodies. */
    private suspend fun outage(): Triple<RecordingStore, ReadRefusingStore, List<String?>> {
        val backing = RecordingStore()
        val writer = exporterFor(backing)
        repeat(10) { writer.export(record(it)) }
        return Triple(backing, ReadRefusingStore(backing, INDEX_KEY_FOR_TEST), writer.snapshot().toList().map { it.body })
    }

    /** Every call the store took, the refused ones included. */
    private fun calls(backing: RecordingStore, store: ReadRefusingStore): Int = backing.operations().size + store.refusals

    /** Every mutation the backing store took. */
    private fun mutations(backing: RecordingStore): Int = backing.operations().count { it.kind != StoreOpKind.READ }

    /**
     * Whether each of [turns] turns after a refused [WarpLogRecordExporter.recover] reads, while the
     * store stays down: a window of 1, then 3, 7, 15, 31, then 63 for good. Built from the window
     * recurrence `w' = 2w + 1` rather than from `2^k − 1`, so it is not the production formula read
     * back.
     */
    private fun expectedReads(turns: Int): List<Boolean> {
        val expected = mutableListOf<Boolean>()
        var window = 1
        while (expected.size < turns) {
            repeat(window) { expected += false }
            expected += true
            window = minOf(2 * window + 1, 63)
        }
        return expected.take(turns)
    }

    @Test
    fun aLongOutageReadsTheStoreOnAWindowThatDoublesUpToACap() = runTest {
        val (backing, store, persisted) = outage()
        val exporter = exporterFor(store)

        val callsBeforeRecover = calls(backing, store)
        exporter.recover()
        val callsInRecover = calls(backing, store) - callsBeforeRecover
        val mutationsBefore = mutations(backing)
        val turns = 300
        val read = mutableListOf<Boolean>()
        val results = (0 until turns).map { turn ->
            val before = calls(backing, store)
            exporter.export(record(100 + turn)).also { read += calls(backing, store) > before }
        }
        val expected = expectedReads(turns)
        val mutationsInTurns = mutations(backing) - mutationsBefore

        val afterRestart = bodiesAfterRestart(backing)
        assertAll(
            { assertEquals(10, persisted.size, "precondition: the fixture did not persist 10 records") },
            { assertEquals(1, callsInRecover, "precondition: recover did not stop at the refused index read") },
            { assertEquals(1 + expected.count { it }, store.refusals, "the rig refused a different number of reads than were attempted") },
            { assertEquals(expected, read, "the turns read on the wrong schedule") },
            { assertTrue(results.all { it is ExportResult.Failure }, "a turn ran over an unread index") },
            { assertEquals(0, mutationsInTurns, "a turn wrote or deleted over an unread index") },
            { assertEquals(turns.toLong(), exporter.health.value.failed, "a refused or skipped turn was not counted as failed") },
            { assertEquals(persisted, afterRestart, "records were lost to the outage") },
        )
    }

    @Test
    fun aHealInsideTheWindowLandsOnTheNextAttemptedReadAndResetsTheWindow() = runTest {
        val (backing, store, persisted) = outage()
        val exporter = exporterFor(store)

        exporter.recover() // refused 1: a window of 1
        val read = mutableListOf<Boolean>()
        suspend fun turn(id: Int): ExportResult {
            val before = calls(backing, store)
            return exporter.export(record(id)).also { read += calls(backing, store) > before }
        }
        val beforeHeal = listOf(turn(100), turn(101), turn(102)) // skip, refused 2 (window 3), skip
        store.heal()
        val inWindow = listOf(turn(103), turn(104)) // the last two turns of that window
        val healed = turn(105) // the next attempted read lands
        val readsToHeal = read.toList()

        // A fresh outage must start at a window of 1 again: the read that landed reset the count.
        store.refuse()
        val refusalsBefore = store.refusals
        exporter.recover() // refused again: a window of 1, not of 7
        val afterReset = turn(106)
        store.heal()
        val resumed = turn(107)

        val afterRestart = bodiesAfterRestart(backing)
        assertAll(
            { assertEquals(listOf(false, true, false, false, false, true), readsToHeal, "the heal did not land on the next attempted read") },
            { assertTrue((beforeHeal + inWindow).all { it is ExportResult.Failure }, "a turn ran inside the window: ${beforeHeal + inWindow}") },
            { assertEquals(ExportResult.Success, healed, "the read that landed did not run its turn") },
            { assertEquals(1, store.refusals - refusalsBefore, "precondition: the second outage's rig did not refuse recover") },
            { assertEquals(listOf(false, true), read.drop(readsToHeal.size), "the read that landed did not reset the window to 1") },
            { assertTrue(afterReset is ExportResult.Failure, "a turn ran over the second unread index: $afterReset") },
            { assertEquals(ExportResult.Success, resumed, "the turn after a window of 1 did not read") },
            { assertEquals(persisted + listOf("body-105", "body-107"), afterRestart) },
        )
    }

    @Test
    fun aSkippedTurnRefusesExactlyAsARefusedReadDoes() = runTest {
        val (backing, store, persisted) = outage()
        val exporter = exporterFor(store)
        val remote = WarpLogRecordExporter(replica = ReplicaId("B"), store = InMemoryDurableStore())
            .also { it.export(record(500)) }
            .snapshot()

        exporter.recover() // refused 1: a window of 1
        val skipped = exporter.export(record(100))
        val healthAfterSkip = exporter.health.value
        val read = exporter.export(record(101)) // refused 2: a window of 3
        val healthAfterRead = exporter.health.value
        val callsBefore = calls(backing, store)
        val windowed = listOf(exporter.export(record(102)), exporter.merge(remote), exporter.clear())
        val callsInWindow = calls(backing, store) - callsBefore
        val snapshotInWindow = exporter.snapshot().toList().map { it.body }
        store.heal()
        // Nothing a skipped turn saw was admitted, so the same records land once the read heals.
        val reExported = exporter.export(listOf(record(100), record(101), record(102)))

        val skippedCause = (skipped as? ExportResult.Failure)?.cause
        val readCause = (read as? ExportResult.Failure)?.cause
        val afterRestart = bodiesAfterRestart(backing)
        assertAll(
            { assertTrue(skipped is ExportResult.Failure, "a skipped turn did not fail: $skipped") },
            { assertTrue(read is ExportResult.Failure, "a refused read did not fail: $read") },
            { assertTrue(skippedCause is IllegalStateException, "a skipped turn's cause is not the refusal: $skippedCause") },
            { assertEquals(skippedCause?.message, readCause?.message, "a skipped turn's Failure is shaped differently") },
            { assertEquals(1L, healthAfterSkip.failed, "a skipped turn was not counted as failed") },
            { assertEquals(1, healthAfterSkip.consecutiveFailures, "a skipped turn was not counted in the failure streak") },
            { assertSame(skippedCause, healthAfterSkip.lastFailure, "a skipped turn did not report its cause") },
            { assertEquals(2L, healthAfterRead.failed) },
            { assertEquals(2, healthAfterRead.consecutiveFailures) },
            { assertTrue(windowed.all { it is ExportResult.Failure }, "an export, merge or clear ran in the window: $windowed") },
            { assertEquals(0, callsInWindow, "a skipped turn touched the store") },
            { assertEquals(emptyList(), snapshotInWindow, "a skipped turn mutated the log") },
            { assertEquals(ExportResult.Success, reExported) },
            { assertEquals(persisted + listOf("body-100", "body-101", "body-102"), afterRestart) },
        )
    }

    @Test
    fun recoverAlwaysReadsAndALandedReadClosesTheWindow() = runTest {
        // An explicit request to read does not wait out the window.
        val (backing, store, persisted) = outage()
        val exporter = exporterFor(store)

        exporter.recover() // refused 1
        exporter.export(record(100)) // skipped
        exporter.export(record(101)) // refused 2: a window of 3
        val callsBefore = calls(backing, store)
        store.heal()
        exporter.recover()
        val recoverCalls = calls(backing, store) - callsBefore
        val exported = exporter.export(record(102))

        val afterRestart = bodiesAfterRestart(backing)
        assertAll(
            { assertEquals(2, store.refusals, "precondition: the rig did not refuse recover and one turn") },
            { assertTrue(recoverCalls > 0, "recover waited out the window instead of reading") },
            { assertEquals(ExportResult.Success, exported, "the window outlived the read recover landed") },
            { assertEquals(persisted + "body-102", afterRestart) },
        )
    }

    @Test
    fun theCallersOwnCancellationEndsASkippedTurnAsItEndsARead() = runTest {
        // A read turn under a cancelled caller ends in that cancellation: the store throws, and
        // `ensureActive` lets the caller's own cancel through. A skipped turn makes no store call,
        // so it must ask, or it would return a Failure into a coroutine that is already cancelled.
        // Neither cancelled turn may count: the window is where it was.
        val (backing, store, _) = outage()
        val exporter = exporterFor(store)

        exporter.recover() // refused 1: a window of 1
        var skipReturned: ExportResult? = null
        val callsBeforeSkip = calls(backing, store)
        launch {
            coroutineContext.job.cancel()
            skipReturned = exporter.export(record(100))
        }.join()
        val callsInCancelledSkip = calls(backing, store) - callsBeforeSkip

        val stillSkips = calls(backing, store).let { before -> exporter.export(record(101)); calls(backing, store) == before }

        var readReturned: ExportResult? = null
        val callsBeforeRead = calls(backing, store)
        launch {
            coroutineContext.job.cancel()
            readReturned = exporter.export(record(102))
        }.join()
        val callsInCancelledRead = calls(backing, store) - callsBeforeRead

        val stillReads = calls(backing, store).let { before -> exporter.export(record(103)); calls(backing, store) > before }

        assertAll(
            { assertNull(skipReturned, "a skipped turn returned into a cancelled caller: $skipReturned") },
            { assertEquals(0, callsInCancelledSkip, "a cancelled skipped turn touched the store") },
            { assertTrue(stillSkips, "a cancelled skipped turn used up the window") },
            { assertNull(readReturned, "a read turn returned into a cancelled caller: $readReturned") },
            { assertEquals(1, callsInCancelledRead, "precondition: the cancelled read turn did not attempt its read") },
            { assertTrue(stillReads, "a cancelled read counted as a refusal and opened a window") },
            { assertEquals(2L, exporter.health.value.failed, "a cancelled turn was counted as failed") },
        )
    }
}
