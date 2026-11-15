package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the counted backoff on [WarpSpanExporter]'s refused read of its stored spans (#3436).
 *
 * Under the fail-closed rule (#3390) a write turn ([WarpSpanExporter.export],
 * [WarpSpanExporter.merge], [WarpSpanExporter.clear]) reads the stored spans first until a read
 * lands. Retried on **every** turn, a long outage made each turn read the whole span blob and pay the
 * store's full timeout. Now, after `k` refused reads in a row, the next `2^k − 1` turns refuse
 * without touching the store, `k` capped at 6, the window the log (#3404) and metric exporters use.
 * A read that lands makes the exporter recovered, which nothing undoes, so it ends the window for
 * good. [WarpSpanExporter.recover] always reads.
 *
 * [SpanReadsStore] counts every read it is asked for, the refused ones included, so "did this turn
 * read the spans?" is one counter. Every arm asserts its rig refused at all.
 */
class WarpSpanExporterRefusedReadBackoffTest {

    private val replica = ReplicaId("a")
    private val spansKey = StoreKey("otel.spans")
    private val clockKey = WarpCausalClock.STORE_KEY

    private fun span(id: Int) = SpanRecord(
        ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() }), null,
        "span-$id", SpanKind.INTERNAL, id.toLong(), id.toLong() + 1,
    )

    private fun ids(vararg id: Int): Set<ByteString> = id.map { span(it).spanId }.toSet()

    /** A store a previous process left spans 1 and 2 in, behind a [SpanReadsStore] refusing nothing yet. */
    private suspend fun seeded(): Pair<InMemoryDurableStore, SpanReadsStore> {
        val backing = InMemoryDurableStore()
        val previous = WarpSpanExporter(replica, backing).also { it.recover() }
        listOf(1, 2).forEach { assertEquals(ExportResult.Success, previous.export(span(it)), "the rig: span $it was not saved") }
        return backing to SpanReadsStore(backing)
    }

    private suspend fun idsAfterRestart(store: DurableStore): Set<ByteString> =
        WarpSpanExporter(replica, store).also { it.recover() }.snapshot().elements.map { it.spanId }.toSet()

    private fun remote(id: Int): ORSet<SpanRecord> = ORSet.empty<SpanRecord>().piece { it.add(ReplicaId("b"), span(id)) }

    /**
     * Whether each of [turns] turns reads the spans, after one refused read and while the store stays
     * down: a window of 1, then 3, 7, 15, 31, then 63 for good. Built from the recurrence
     * `w' = 2w + 1` rather than from `2^k − 1`, so it is not the production formula read back.
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
    fun aLongOutageReadsTheSpansOnAWindowThatDoublesUpToACap() = runTest {
        val (backing, store) = seeded()
        store.refuse(spansKey)
        val exporter = WarpSpanExporter(replica, store).also { it.recover() }
        val refusedInRecover = store.refusalsOf(spansKey)
        val writesBefore = store.writesOf(spansKey)

        val turns = 300
        val read = mutableListOf<Boolean>()
        val results = (0 until turns).map { turn ->
            val before = store.readsOf(spansKey)
            exporter.export(span(10 + turn)).also { read += store.readsOf(spansKey) > before }
        }
        val health = exporter.health.value

        val durable = idsAfterRestart(backing)
        assertAll(
            { assertEquals(1, refusedInRecover, "precondition: the rig did not refuse recover's read") },
            { assertEquals(expectedReads(turns), read, "the span turns read on the wrong schedule") },
            { assertEquals(1 + expectedReads(turns).count { it }, store.refusalsOf(spansKey), "the rig refused a different number of reads than were attempted") },
            { assertTrue(results.all { it is ExportResult.Failure }, "a turn over the unread spans succeeded") },
            { assertEquals(0, store.writesOf(spansKey) - writesBefore, "a turn wrote over spans it never read") },
            { assertEquals(emptySet(), exporter.snapshot().elements, "a refused or skipped export added its span to memory") },
            { assertEquals(turns.toLong(), health.failed, "a refused or skipped turn was not counted as failed") },
            { assertEquals(turns.toLong(), health.outstandingFailures, "a refused or skipped turn is not outstanding") },
            { assertEquals(ids(1, 2), durable, "the durable spans were lost to the outage") },
        )
    }

    /**
     * One window across the three write turns: an export, a merge and a clear each spend a turn of it
     * or make the read, on the same schedule as exports alone. Inside the window a merge keeps its
     * remote set in memory, as a refused one does, and a clear drops nothing.
     */
    @Test
    fun aMergeAndAClearInsideTheWindowBehaveLikeAnExport() = runTest {
        val (backing, store) = seeded()
        store.refuse(spansKey)
        val exporter = WarpSpanExporter(replica, store).also { it.recover() }
        val writesBefore = store.writesOf(spansKey)

        val turns = 150
        val read = mutableListOf<Boolean>()
        val results = (0 until turns).map { turn ->
            val before = store.readsOf(spansKey)
            when (turn % 3) {
                0 -> exporter.export(span(10 + turn))
                1 -> exporter.merge(remote(100 + turn))
                else -> exporter.clear()
            }.also { read += store.readsOf(spansKey) > before }
        }
        val merged = (0 until turns).filter { it % 3 == 1 }.map { 100 + it }.toIntArray()

        val durable = idsAfterRestart(backing)
        assertAll(
            { assertEquals(1, store.refusalsOf(spansKey) - expectedReads(turns).count { it }, "precondition: the rig did not refuse recover's read") },
            { assertEquals(expectedReads(turns), read, "the export, merge and clear turns read on the wrong schedule") },
            { assertTrue(results.all { it is ExportResult.Failure }, "a turn over the unread spans succeeded: ${results.toSet()}") },
            { assertEquals(0, store.writesOf(spansKey) - writesBefore, "a turn wrote over spans it never read") },
            { assertEquals(ids(*merged), exporter.snapshot().elements.map { it.spanId }.toSet(), "a clear dropped a merged span, or an export added one") },
            { assertEquals(turns.toLong(), exporter.health.value.failed, "a refused or skipped turn was not counted as failed") },
            { assertEquals(ids(1, 2), durable, "the durable spans were lost to the outage") },
        )
    }

    @Test
    fun aHealInsideTheWindowLandsOnTheNextAttemptedReadAndEndsTheWindowForGood() = runTest {
        val (backing, store) = seeded()
        store.refuse(spansKey)
        val exporter = WarpSpanExporter(replica, store).also { it.recover() } // refused 1: a window of 1
        val read = mutableListOf<Boolean>()
        suspend fun turn(id: Int): ExportResult {
            val before = store.readsOf(spansKey)
            return exporter.export(span(id)).also { read += store.readsOf(spansKey) > before }
        }
        val beforeHeal = listOf(turn(10), turn(11), turn(12)) // skip, refused 2 (a window of 3), skip
        store.heal(spansKey)
        val inWindow = listOf(turn(13), turn(14)) // the last two turns of that window
        val healed = turn(15) // the next attempted read lands
        val readsToHeal = read.toList()

        // Recovered is never left: a store that refuses again is never read by a turn, and no turn skips.
        store.refuse(spansKey)
        val refusalsBefore = store.refusalsOf(spansKey)
        exporter.recover()
        val afterRefusedRecover = (16..25).map { turn(it) }

        val durable = idsAfterRestart(backing)
        assertAll(
            { assertEquals(listOf(false, true, false, false, false, true), readsToHeal, "the heal did not land on the next attempted read") },
            { assertTrue((beforeHeal + inWindow).all { it is ExportResult.Failure }, "a turn ran inside the window: ${beforeHeal + inWindow}") },
            { assertEquals(ExportResult.Success, healed, "the read that landed did not run its turn") },
            { assertEquals(1, store.refusalsOf(spansKey) - refusalsBefore, "precondition: the second outage's rig did not refuse recover") },
            { assertEquals(List(10) { false }, read.drop(readsToHeal.size), "a recovered exporter read the spans again") },
            { assertTrue(afterRefusedRecover.all { it == ExportResult.Success }, "a recovered exporter skipped a turn: $afterRefusedRecover") },
            { assertEquals(ids(1, 2, 15) + (16..25).flatMap { ids(it) }, durable, "the healed writes lost a span") },
        )
    }

    /**
     * A skipped turn and a refused read must be indistinguishable to the caller and on `health`: the
     * same failure, carrying the refusal the store last made, the same failed turn, outstanding until
     * a write lands, and nothing written or added.
     */
    @Test
    fun aSkippedTurnRefusesExactlyAsARefusedReadDoes() = runTest {
        val (backing, store) = seeded()
        store.refuse(spansKey)
        val exporter = WarpSpanExporter(replica, store).also { it.recover() } // refused 1: a window of 1
        val recoverRefusal = store.lastRefusal()

        val readsBeforeSkip = store.readsOf(spansKey)
        val failedBeforeSkip = exporter.health.value.failed
        val skipped = exporter.export(span(10))
        val readsInSkip = store.readsOf(spansKey) - readsBeforeSkip
        val failedInSkip = exporter.health.value.failed - failedBeforeSkip
        val healthAfterSkip = exporter.health.value

        val readsBeforeRead = store.readsOf(spansKey)
        val failedBeforeRead = exporter.health.value.failed
        val refusedRead = exporter.export(span(11)) // refused 2: a window of 3
        val readsInRead = store.readsOf(spansKey) - readsBeforeRead
        val failedInRead = exporter.health.value.failed - failedBeforeRead
        val readRefusal = store.lastRefusal()
        val writesWhileRefused = store.writesOf(spansKey)

        store.heal(spansKey)
        val inWindow = (12..14).map { exporter.export(span(it)) }
        val healed = exporter.export(span(15))
        val afterHeal = exporter.health.value

        val durable = idsAfterRestart(backing)
        assertAll(
            { assertEquals(1, readsInRead, "precondition: the second turn did not attempt its read") },
            { assertEquals(0, readsInSkip, "a skipped turn touched the store") },
            { assertSame(recoverRefusal, assertIs<ExportResult.Failure>(skipped).cause, "a skipped turn's cause is not the refusal the store last made") },
            { assertSame(readRefusal, assertIs<ExportResult.Failure>(refusedRead).cause, "precondition: a refused read's cause is the store's") },
            { assertEquals(failedInRead, failedInSkip, "a skipped turn counted differently from a refused read") },
            { assertEquals(1L, failedInSkip, "a skipped turn was not one failed turn") },
            { assertSame(recoverRefusal, healthAfterSkip.lastFailure, "a skipped turn did not report its cause") },
            { assertEquals(1L, healthAfterSkip.outstandingFailures, "a skipped turn is not outstanding") },
            { assertEquals(0, writesWhileRefused, "a skipped or refused turn wrote the spans") },
            { assertTrue(inWindow.all { it is ExportResult.Failure }, "a turn inside the window ran: $inWindow") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(0L, afterHeal.outstandingFailures, "the healed write did not resolve the skipped turns") },
            { assertEquals(ids(1, 2, 15), durable, "a skipped export left its span behind, or the healed one lost its own") },
        )
    }

    @Test
    fun recoverReadsThroughTheWindow() = runTest {
        // An explicit request to read does not wait out the window, refused or landing.
        val (backing, store) = seeded()
        store.refuse(spansKey)
        val exporter = WarpSpanExporter(replica, store).also { it.recover() } // refused 1
        exporter.export(span(10)) // skipped
        exporter.export(span(11)) // refused 2: a window of 3

        val refusedBefore = store.refusalsOf(spansKey)
        exporter.recover() // inside the window, still refused: refused 3, a window of 7
        val refusedInRecover = store.refusalsOf(spansKey) - refusedBefore
        val skips = (0 until 3).map { turn ->
            val before = store.readsOf(spansKey)
            exporter.export(span(20 + turn))
            store.readsOf(spansKey) > before
        }

        store.heal(spansKey)
        val readsBefore = store.readsOf(spansKey)
        exporter.recover() // four turns still left in the window, and the read lands
        val readsInRecover = store.readsOf(spansKey) - readsBefore
        val exported = exporter.export(span(30))

        val durable = idsAfterRestart(backing)
        assertAll(
            { assertEquals(1, refusedInRecover, "recover waited out the window instead of reading") },
            { assertEquals(List(3) { false }, skips, "the turns after recover's refusal did not skip") },
            { assertEquals(1, readsInRecover, "recover did not read the spans inside the window") },
            { assertEquals(ExportResult.Success, exported, "the window outlived the read recover landed") },
            { assertEquals(ids(1, 2, 30), durable) },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theCallersOwnCancellationSpendsNoTurnOfTheWindow() = runTest {
        // A read turn under a cancelled caller ends in that cancellation. A skipped turn makes no
        // store call, so it must ask, or it would return a Failure into a cancelled coroutine. Neither
        // cancelled turn may spend the window.
        val (_, store) = seeded()
        store.refuse(spansKey)
        store.refuse(clockKey)
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)
        val clockRecovery = clock.recover(store)
        exporter.recover() // refused 1: a window of 1

        var skipReturned: ExportResult? = null
        val spanReadsBeforeSkip = store.readsOf(spansKey)
        launch {
            coroutineContext.job.cancel()
            skipReturned = exporter.merge(remote(9))
        }.join()
        val spanReadsInCancelledSkip = store.readsOf(spansKey) - spanReadsBeforeSkip

        // The export reads the clock before it reaches the spans. Parked in that read and cancelled
        // there, it must not spend the spans' skip, which comes after.
        var exportReturned: ExportResult? = null
        store.parkNextRead(clockKey)
        val export = launch { exportReturned = exporter.export(span(10)) }
        runCurrent()
        val parkedInTheClockRead = store.parkedOn(clockKey)
        export.cancel()
        export.join()

        val stillSkips = store.readsOf(spansKey).let { before ->
            exporter.export(span(11))
            store.readsOf(spansKey) == before
        }
        val thenReads = store.readsOf(spansKey).let { before ->
            exporter.export(span(12))
            store.readsOf(spansKey) > before
        }

        assertAll(
            { assertIs<ClockRecovery.Refused>(clockRecovery, "precondition: the clock's read was not refused") },
            { assertNull(skipReturned, "a skipped turn returned into a cancelled caller: $skipReturned") },
            { assertEquals(0, spanReadsInCancelledSkip, "a cancelled skipped turn touched the store") },
            { assertEquals(1, parkedInTheClockRead, "precondition: the export did not park in the clock's read") },
            { assertNull(exportReturned, "a cancelled export returned into its caller: $exportReturned") },
            { assertTrue(stillSkips, "a cancelled turn used up the window") },
            { assertTrue(thenReads, "the window outlasted its one turn") },
            { assertEquals(2L, exporter.health.value.failed, "failed is not the two turns that ran uncancelled") },
        )
    }
}

/**
 * Delegates to [backing], refusing every read of a key in the refused set until it is healed.
 * Counts every read it is asked for, refused or not, the refusals and the writes, per key, and keeps
 * the last refusal it threw. Can park the next read of one key until its caller is cancelled.
 */
private class SpanReadsStore(private val backing: DurableStore) : DurableStore {
    private val lock = reentrantLock()
    private val refusing = mutableSetOf<StoreKey>()
    private val reads = mutableMapOf<StoreKey, Int>()
    private val refusals = mutableMapOf<StoreKey, Int>()
    private val writes = mutableMapOf<StoreKey, Int>()
    private val parkNext = mutableSetOf<StoreKey>()
    private val parks = mutableMapOf<StoreKey, Int>()
    private var last: Throwable? = null

    fun refuse(key: StoreKey): Unit = lock.withLock { refusing += key }

    fun heal(key: StoreKey): Unit = lock.withLock { refusing -= key }

    fun readsOf(key: StoreKey): Int = lock.withLock { reads[key] ?: 0 }

    fun refusalsOf(key: StoreKey): Int = lock.withLock { refusals[key] ?: 0 }

    fun writesOf(key: StoreKey): Int = lock.withLock { writes[key] ?: 0 }

    fun parkedOn(key: StoreKey): Int = lock.withLock { parks[key] ?: 0 }

    fun lastRefusal(): Throwable? = lock.withLock { last }

    /** Park the next read of [key] until its caller is cancelled; it never completes otherwise. */
    fun parkNextRead(key: StoreKey): Unit = lock.withLock { parkNext += key }

    override suspend fun read(key: StoreKey): ByteArray? {
        val park = lock.withLock {
            reads[key] = (reads[key] ?: 0) + 1
            parkNext.remove(key).also { if (it) parks[key] = (parks[key] ?: 0) + 1 }
        }
        if (park) awaitCancellation()
        val refusal = lock.withLock {
            if (key !in refusing) return@withLock null
            refusals[key] = (refusals[key] ?: 0) + 1
            IllegalStateException("simulated refused read of ${key.name}").also { last = it }
        }
        if (refusal != null) throw refusal
        return backing.read(key)
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        lock.withLock { writes[key] = (writes[key] ?: 0) + 1 }
        backing.write(key, bytes)
    }

    override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
}
