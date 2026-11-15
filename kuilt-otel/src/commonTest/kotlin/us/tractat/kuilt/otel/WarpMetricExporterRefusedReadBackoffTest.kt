package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the counted backoff on [WarpMetricExporter]'s refused reads, one window per metric kind
 * (#3436).
 *
 * Under the fail-closed rule (#3390) a kind whose read was refused is read again before a turn of
 * that kind runs. Retried on **every** turn, a long outage made each batch pay the store's full
 * timeout once per unread kind. Now, after `k` refused reads of a kind in a row, the next
 * `2^k − 1` turns that touch the kind refuse it without touching the store, `k` capped at 6, and a
 * read of the kind that lands resets its `k`. The window is the log exporter's (#3404), counted in
 * the kind's own turns: a turn that does not touch a kind spends nothing of its window.
 *
 * [PerKeyRefusingStore] counts every read it is asked for, the refused ones included, so "did this
 * turn read the kind?" is one counter per key. Every arm asserts its rig refused at all.
 */
class WarpMetricExporterRefusedReadBackoffTest {

    private val replica = ReplicaId("a")

    private val sum = MetricKey("sum", MetricKind.SUM)
    private val gauge = MetricKey("gauge", MetricKind.GAUGE)
    private val histogram = MetricKey("histogram", MetricKind.EXPONENTIAL_HISTOGRAM)

    private val sumsKey = StoreKey("otel.metrics.sums")
    private val gaugesKey = StoreKey("otel.metrics.gauges")
    private val histogramsKey = StoreKey("otel.metrics.histograms")

    /** What a previous process left durable: a sum of 5, a gauge, and a histogram of three values. */
    private suspend fun seeded(): RecordingStore = RecordingStore().also { backing ->
        WarpMetricExporter(replica, backing).apply {
            incrementSum(sum, 5L)
            setGauge(gauge, 4.0, 10L)
            recordHistogram(histogram, 1.0)
            recordHistogram(histogram, 2.0)
            recordHistogram(histogram, 3.0)
        }
    }

    private suspend fun restarted(store: DurableStore): WarpMetricExporter =
        WarpMetricExporter(replica, store).also { it.recover() }

    private fun writesOf(backing: RecordingStore, key: StoreKey): Int =
        backing.operations().count { it.kind == StoreOpKind.WRITE && it.key == key }

    /**
     * Whether each of [turns] turns of one kind reads it, after one refused read and while the
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

    private fun histogramUpdate(turn: Int) = MetricUpdate.RecordHistogram(histogram, 10.0 + turn)

    @Test
    fun aLongOutageReadsAKindOnAWindowThatDoublesUpToACap() = runTest {
        val backing = seeded()
        val seededCount = restarted(backing).histogramSnapshot(histogram).count
        val store = PerKeyRefusingStore(backing)
        store.refuse(histogramsKey)
        val exporter = WarpMetricExporter(replica, store)
        exporter.recover()
        val refusedInRecover = store.refusalsOf(histogramsKey)
        val histogramsBefore = exporter.histogramSnapshot(histogram)
        val writesBefore = writesOf(backing, histogramsKey)
        val failedBefore = exporter.health.value.failed

        val turns = 300
        val read = mutableListOf<Boolean>()
        val results = (0 until turns).map { turn ->
            val before = store.readsOf(histogramsKey)
            exporter.recordHistogram(histogram, 10.0 + turn).also { read += store.readsOf(histogramsKey) > before }
        }
        val expected = expectedReads(turns)
        val durableCount = restarted(backing).histogramSnapshot(histogram).count

        assertAll(
            { assertEquals(1, refusedInRecover, "precondition: the rig did not refuse recover's read of the histograms") },
            { assertEquals(3L, seededCount, "precondition: the fixture did not persist three values") },
            { assertEquals(1 + expected.count { it }, store.refusalsOf(histogramsKey), "the rig refused a different number of reads than were attempted") },
            { assertEquals(expected, read, "the histogram turns read on the wrong schedule") },
            {
                val uncounted = results.map { (it as? MetricExportResult.Partial)?.uncounted }
                assertEquals((0 until turns).map { listOf(histogramUpdate(it)) }, uncounted, "a turn over the unread histograms counted its update")
            },
            { assertEquals(0, writesOf(backing, histogramsKey) - writesBefore, "a turn wrote the histograms it never read") },
            { assertEquals(histogramsBefore, exporter.histogramSnapshot(histogram), "a refused or skipped turn changed memory") },
            { assertEquals(failedBefore + turns, exporter.health.value.failed, "a refused or skipped turn was not counted as failed") },
            { assertEquals(3L, durableCount, "the durable histogram was lost to the outage") },
        )
    }

    /**
     * Two kinds back off, each on its own window counted in its own turns, while a third kind whose
     * read landed writes on every turn and is never read again. The histograms are touched on every
     * turn and the sums on every third, so a window shared across kinds, or one that a turn spends
     * without touching the kind, reads the sums on another schedule.
     */
    @Test
    fun eachKindBacksOffOnItsOwnWindowAndAKindWhoseReadLandedIsUnaffected() = runTest {
        val backing = seeded()
        val store = PerKeyRefusingStore(backing)
        store.refuse(sumsKey)
        store.refuse(histogramsKey)
        val exporter = WarpMetricExporter(replica, store)
        exporter.recover()
        val gaugeReadsAfterRecover = store.readsOf(gaugesKey)
        val gaugeWritesBefore = writesOf(backing, gaugesKey)
        val sumWritesBefore = writesOf(backing, sumsKey)
        val histogramWritesBefore = writesOf(backing, histogramsKey)

        val turns = 200
        val sumReads = mutableListOf<Boolean>()
        val histogramReads = mutableListOf<Boolean>()
        var sumReadOnATurnWithoutSums = false
        val uncounted = mutableListOf<List<MetricUpdate>?>()
        val expectedUncounted = mutableListOf<List<MetricUpdate>>()
        repeat(turns) { turn ->
            val touchesSums = turn % 3 == 0
            val batch = buildList<MetricUpdate> {
                if (touchesSums) add(MetricUpdate.IncrementSum(sum, 1L))
                add(MetricUpdate.SetGauge(gauge, turn.toDouble(), 100L + turn))
                add(histogramUpdate(turn))
            }
            val sumsBefore = store.readsOf(sumsKey)
            val histogramsBefore = store.readsOf(histogramsKey)
            uncounted += (exporter.record(batch) as? MetricExportResult.Partial)?.uncounted
            expectedUncounted += batch.filter { it !is MetricUpdate.SetGauge }
            val readSums = store.readsOf(sumsKey) > sumsBefore
            if (touchesSums) sumReads += readSums else if (readSums) sumReadOnATurnWithoutSums = true
            histogramReads += store.readsOf(histogramsKey) > histogramsBefore
        }
        val afterRestart = restarted(backing)

        assertAll(
            { assertEquals(0, store.refusalsOf(gaugesKey), "precondition: the gauges must not be refused") },
            { assertEquals(1, gaugeReadsAfterRecover, "precondition: recover did not read the gauges once") },
            { assertEquals(expectedReads(turns), histogramReads, "the histograms read on the wrong schedule") },
            { assertEquals(expectedReads(sumReads.size), sumReads, "the sums read on the wrong schedule for their own turns") },
            { assertEquals(67, sumReads.size, "precondition: the schedule did not touch the sums on every third turn") },
            { assertTrue(!sumReadOnATurnWithoutSums, "a turn that did not touch the sums read them") },
            { assertEquals<List<List<MetricUpdate>?>>(expectedUncounted, uncounted, "a turn's uncounted updates are not exactly its unread kinds'") },
            { assertEquals(1, store.readsOf(gaugesKey), "a kind whose read landed was read again") },
            { assertEquals(turns, writesOf(backing, gaugesKey) - gaugeWritesBefore, "a kind whose read landed did not write on every turn") },
            { assertEquals(0, writesOf(backing, sumsKey) - sumWritesBefore, "the unread sums were written") },
            { assertEquals(0, writesOf(backing, histogramsKey) - histogramWritesBefore, "the unread histograms were written") },
            { assertEquals((turns - 1).toDouble(), afterRestart.gaugeValue(gauge), "the gauge's last write did not land") },
            { assertEquals(5L, afterRestart.sumValue(sum), "the durable sum was overwritten") },
            { assertEquals(3L, afterRestart.histogramSnapshot(histogram).count, "the durable histogram was overwritten") },
        )
    }

    @Test
    fun aHealInsideTheWindowLandsOnTheNextAttemptedReadAndResetsTheWindow() = runTest {
        val backing = seeded()
        val store = PerKeyRefusingStore(backing)
        store.refuse(histogramsKey)
        val exporter = WarpMetricExporter(replica, store)
        exporter.recover() // refused 1: a window of 1
        val read = mutableListOf<Boolean>()
        suspend fun turn(value: Double): MetricExportResult {
            val before = store.readsOf(histogramsKey)
            return exporter.recordHistogram(histogram, value).also { read += store.readsOf(histogramsKey) > before }
        }
        val beforeHeal = listOf(turn(10.0), turn(11.0), turn(12.0)) // skip, refused 2 (window 3), skip
        store.heal(histogramsKey)
        val inWindow = listOf(turn(13.0), turn(14.0)) // the last two turns of that window
        val healed = turn(15.0) // the next attempted read lands
        val readsToHeal = read.toList()

        // A fresh outage must start at a window of 1 again: the read that landed reset the count.
        store.refuse(histogramsKey)
        val refusalsBefore = store.refusalsOf(histogramsKey)
        exporter.recover() // refused again: a window of 1, not of 7
        val afterReset = turn(16.0)
        store.heal(histogramsKey)
        val resumed = turn(17.0)
        val durableCount = restarted(backing).histogramSnapshot(histogram).count

        assertAll(
            { assertEquals(listOf(false, true, false, false, false, true), readsToHeal, "the heal did not land on the next attempted read") },
            { assertTrue((beforeHeal + inWindow).all { it is MetricExportResult.Partial }, "a turn ran inside the window: ${beforeHeal + inWindow}") },
            { assertEquals(MetricExportResult.Success, healed, "the read that landed did not run its turn") },
            { assertEquals(1, store.refusalsOf(histogramsKey) - refusalsBefore, "precondition: the second outage's rig did not refuse recover") },
            { assertEquals(listOf(false, true), read.drop(readsToHeal.size), "the read that landed did not reset the window to 1") },
            { assertIs<MetricExportResult.Partial>(afterReset, "a turn ran over the second unread histograms") },
            { assertEquals(MetricExportResult.Success, resumed, "the turn after a window of 1 did not read") },
            { assertEquals(5L, durableCount, "seeded three, plus the healed 15.0 and 17.0") },
        )
    }

    /**
     * A skipped turn and a refused read must be indistinguishable to the caller and on `health`:
     * the same Partial, naming the same kind and the same cause, the same failed turn, and nothing
     * of the kind admitted. The other kinds of a skipped batch go ahead. A `merge*` in the window is
     * a [MetricWriteResult.Failure] that never reaches the store either.
     */
    @Test
    fun aSkippedTurnRefusesItsKindExactlyAsARefusedReadDoes() = runTest {
        val backing = seeded()
        val store = PerKeyRefusingStore(backing)
        store.refuse(histogramsKey)
        val exporter = WarpMetricExporter(replica, store)
        val remote = WarpMetricExporter(ReplicaId("b"), RecordingStore())
            .apply { recordHistogram(histogram, 70.0) }
            .histogramSnapshot(histogram)
        exporter.recover() // refused 1: a window of 1
        fun batch(turn: Int) = listOf(
            histogramUpdate(turn),
            MetricUpdate.IncrementSum(sum, 1L),
            MetricUpdate.RecordHistogram(histogram, 40.0 + turn),
        )

        val readsBeforeSkip = store.readsOf(histogramsKey)
        val failedBeforeSkip = exporter.health.value.failed
        val skipped = exporter.record(batch(0))
        val readsInSkip = store.readsOf(histogramsKey) - readsBeforeSkip
        val failedInSkip = exporter.health.value.failed - failedBeforeSkip
        val healthAfterSkip = exporter.health.value

        val readsBeforeRead = store.readsOf(histogramsKey)
        val failedBeforeRead = exporter.health.value.failed
        val refusedRead = exporter.record(batch(1)) // refused 2: a window of 3
        val readsInRead = store.readsOf(histogramsKey) - readsBeforeRead
        val failedInRead = exporter.health.value.failed - failedBeforeRead

        val readsBeforeWindow = store.readsOf(histogramsKey)
        val histogramWritesBefore = writesOf(backing, histogramsKey)
        val windowedMerge = exporter.mergeHistogram(histogram, remote)
        val windowedRecord = exporter.recordHistogram(histogram, 99.0)
        val readsInWindow = store.readsOf(histogramsKey) - readsBeforeWindow
        val histogramWritesInWindow = writesOf(backing, histogramsKey) - histogramWritesBefore
        val memoryInWindow = exporter.histogramSnapshot(histogram).count

        store.heal(histogramsKey)
        // The window's last turn skips though the store healed; then re-record exactly what the
        // skipped turn left uncounted.
        val lastOfWindow = exporter.recordHistogram(histogram, 98.0)
        val reRecorded = exporter.record((skipped as? MetricExportResult.Partial)?.uncounted ?: emptyList())

        val skippedPartial = skipped as? MetricExportResult.Partial
        val readPartial = refusedRead as? MetricExportResult.Partial
        val skippedCause = skippedPartial?.causes?.singleOrNull() as? MetricKindUnreadException
        val readCause = readPartial?.causes?.singleOrNull() as? MetricKindUnreadException
        val afterRestart = restarted(backing)
        assertAll(
            { assertEquals(1, readsInRead, "precondition: the second turn did not attempt its read") },
            { assertEquals(0, readsInSkip, "a skipped turn touched the store") },
            { assertEquals(listOf(histogramUpdate(0), MetricUpdate.RecordHistogram(histogram, 40.0)), skippedPartial?.uncounted, "a skipped turn's uncounted: $skipped") },
            { assertEquals(listOf(histogramUpdate(1), MetricUpdate.RecordHistogram(histogram, 41.0)), readPartial?.uncounted, "a refused read's uncounted: $refusedRead") },
            { assertEquals(histogramsKey, skippedCause?.storeKey, "a skipped turn did not name the unread kind: ${skippedPartial?.causes}") },
            { assertEquals(readCause?.cause?.message, skippedCause?.cause?.message, "a skipped turn's cause is not the refusal") },
            { assertTrue(skippedCause?.cause is IllegalStateException, "a skipped turn's cause is not the store's refusal: ${skippedCause?.cause}") },
            { assertEquals(failedInRead, failedInSkip, "a skipped turn counted differently from a refused read") },
            { assertEquals(1L, failedInSkip, "a skipped turn was not one failed turn of the kind") },
            { assertSame(skippedCause?.cause, healthAfterSkip.lastFailure, "a skipped turn did not report its cause") },
            { assertIs<MetricWriteResult.Failure>(windowedMerge, "a merge ran in the window") },
            { assertEquals(histogramsKey, ((windowedMerge as? MetricWriteResult.Failure)?.cause as? MetricKindUnreadException)?.storeKey) },
            { assertIs<MetricExportResult.Partial>(windowedRecord, "a record ran in the window") },
            { assertEquals(0, readsInWindow, "a skipped merge or record touched the store") },
            { assertEquals(0, histogramWritesInWindow, "a skipped turn wrote the histograms") },
            { assertEquals(0L, memoryInWindow, "a skipped turn admitted a histogram value into memory") },
            { assertIs<MetricExportResult.Partial>(lastOfWindow, "the window's last turn read") },
            { assertEquals(MetricExportResult.Success, reRecorded) },
            { assertEquals(7L, afterRestart.sumValue(sum), "the sums of both batches were not counted once each") },
            { assertEquals(5L, afterRestart.histogramSnapshot(histogram).count, "seeded three and the skipped turn's two, re-recorded once") },
        )
    }

    @Test
    fun recoverReadsEveryKindThroughTheWindow() = runTest {
        // An explicit request to read does not wait out the window, refused or landing.
        val backing = seeded()
        val store = PerKeyRefusingStore(backing)
        store.refuse(histogramsKey)
        val exporter = WarpMetricExporter(replica, store)
        exporter.recover() // refused 1
        exporter.recordHistogram(histogram, 10.0) // skipped
        exporter.recordHistogram(histogram, 11.0) // refused 2: a window of 3

        val refusedBefore = store.refusalsOf(histogramsKey)
        exporter.recover() // inside the window, still refused: refused 3, a window of 7
        val refusedInRecover = store.refusalsOf(histogramsKey) - refusedBefore
        val skips = (0 until 3).map { turn ->
            val before = store.readsOf(histogramsKey)
            exporter.recordHistogram(histogram, 20.0 + turn)
            store.readsOf(histogramsKey) > before
        }

        store.heal(histogramsKey)
        val readsBefore = store.readsOf(histogramsKey)
        exporter.recover() // four turns still left in the window, and the read lands
        val readsInRecover = store.readsOf(histogramsKey) - readsBefore
        val recorded = exporter.recordHistogram(histogram, 30.0)
        val durableCount = restarted(backing).histogramSnapshot(histogram).count

        assertAll(
            { assertEquals(1, refusedInRecover, "recover waited out the window instead of reading") },
            { assertEquals(List(3) { false }, skips, "the turns after recover's refusal did not skip") },
            { assertEquals(1, readsInRecover, "recover did not read the histograms inside the window") },
            { assertEquals(MetricExportResult.Success, recorded, "the window outlived the read recover landed") },
            { assertEquals(4L, durableCount, "seeded three plus the 30.0") },
        )
    }

    @Test
    fun theCallersOwnCancellationSpendsNoTurnOfTheWindow() = runTest {
        // A read turn under a cancelled caller ends in that cancellation: the store throws, and
        // `ensureActive` lets the caller's own cancel through. A skipped turn makes no store call,
        // so it must ask, or it would return a Partial into a coroutine that is already cancelled.
        // Neither cancelled turn may count: each kind's window is where it was.
        val backing = seeded()
        val store = PerKeyRefusingStore(backing)
        store.refuse(sumsKey)
        store.refuse(histogramsKey)
        val exporter = WarpMetricExporter(replica, store)
        exporter.recover() // both kinds refused 1: a window of 1 each

        var skipReturned: MetricExportResult? = null
        val readsBeforeSkip = store.readsOf(histogramsKey)
        launch {
            coroutineContext.job.cancel()
            skipReturned = exporter.recordHistogram(histogram, 10.0)
        }.join()
        val readsInCancelledSkip = store.readsOf(histogramsKey) - readsBeforeSkip
        val stillSkips = store.readsOf(histogramsKey).let { before ->
            exporter.recordHistogram(histogram, 11.0)
            store.readsOf(histogramsKey) == before
        }

        // The histograms now owe a read and the sums are still inside their window. A cancelled
        // batch of both reads the histograms, and the cancellation ends it there: the sums' skip,
        // which comes first in kind order, must not be spent by it.
        var mixedReturned: MetricExportResult? = null
        val histogramReadsBeforeMixed = store.readsOf(histogramsKey)
        launch {
            coroutineContext.job.cancel()
            mixedReturned = exporter.record(listOf(MetricUpdate.IncrementSum(sum, 1L), histogramUpdate(0)))
        }.join()
        val histogramReadsInMixed = store.readsOf(histogramsKey) - histogramReadsBeforeMixed
        val sumsStillSkip = store.readsOf(sumsKey).let { before ->
            exporter.incrementSum(sum, 1L)
            store.readsOf(sumsKey) == before
        }
        val histogramsStillRead = store.readsOf(histogramsKey).let { before ->
            exporter.recordHistogram(histogram, 12.0)
            store.readsOf(histogramsKey) > before
        }

        assertAll(
            { assertNull(skipReturned, "a skipped turn returned into a cancelled caller: $skipReturned") },
            { assertEquals(0, readsInCancelledSkip, "a cancelled skipped turn touched the store") },
            { assertTrue(stillSkips, "a cancelled skipped turn used up the window") },
            { assertNull(mixedReturned, "a cancelled batch returned into its caller: $mixedReturned") },
            { assertEquals(1, histogramReadsInMixed, "precondition: the cancelled batch did not attempt the histograms' read") },
            { assertTrue(sumsStillSkip, "a cancelled batch spent the sums' skip") },
            { assertTrue(histogramsStillRead, "a cancelled read counted as a refusal and opened a window") },
            { assertEquals(3L, exporter.health.value.failed, "failed is not the three turns that ran uncancelled") },
        )
    }
}

/**
 * Delegates to [backing], refusing every read of a key in the refused set until it is healed.
 * Counts every read it is asked for, refused or not, and the refusals, per key.
 */
private class PerKeyRefusingStore(private val backing: DurableStore) : DurableStore {
    private val lock = reentrantLock()
    private val refusing = mutableSetOf<StoreKey>()
    private val reads = mutableMapOf<StoreKey, Int>()
    private val refusals = mutableMapOf<StoreKey, Int>()

    fun refuse(key: StoreKey): Unit = lock.withLock { refusing += key }

    fun heal(key: StoreKey): Unit = lock.withLock { refusing -= key }

    fun readsOf(key: StoreKey): Int = lock.withLock { reads[key] ?: 0 }

    fun refusalsOf(key: StoreKey): Int = lock.withLock { refusals[key] ?: 0 }

    override suspend fun read(key: StoreKey): ByteArray? {
        val refuse = lock.withLock {
            reads[key] = (reads[key] ?: 0) + 1
            (key in refusing).also { if (it) refusals[key] = (refusals[key] ?: 0) + 1 }
        }
        if (refuse) throw IllegalStateException("simulated refused read of ${key.name}")
        return backing.read(key)
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)

    override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
}
