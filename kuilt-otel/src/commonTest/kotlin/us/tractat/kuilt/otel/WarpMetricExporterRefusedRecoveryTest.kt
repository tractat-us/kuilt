package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins that a **refused** recovery read of one metric kind never lets a later write of that kind
 * overwrite the durable series the read could not see (#3390, under the fail-closed ruling on #3385).
 *
 * Before the fix a refused read started its kind fresh, and the kind's next write encoded that
 * fresh map over the persisted one: a sum of `5` read back as `1` after one `incrementSum(1)`.
 * The rule now: while a kind's read is owed, every turn of that kind reads again first, and while
 * the read is still refused the turn admits nothing of that kind and writes nothing of it. The read
 * that lands joins the persisted series into memory, and the turn goes on. A *decode* failure still
 * starts fresh.
 *
 * Every arm asserts its rig fired, so a store that never refused cannot pass it by absence.
 */
class WarpMetricExporterRefusedRecoveryTest {

    private val replica = ReplicaId("a")
    private val peer = ReplicaId("b")

    private val sum = MetricKey("sum", MetricKind.SUM)
    private val otherSum = MetricKey("sum.other", MetricKind.SUM)
    private val doubleSum = MetricKey("double", MetricKind.SUM)
    private val otherDoubleSum = MetricKey("double.other", MetricKind.SUM)
    private val gauge = MetricKey("gauge", MetricKind.GAUGE)
    private val otherGauge = MetricKey("gauge.other", MetricKind.GAUGE)
    private val cardinality = MetricKey("cardinality", MetricKind.CARDINALITY)
    private val otherCardinality = MetricKey("cardinality.other", MetricKind.CARDINALITY)
    private val histogram = MetricKey("histogram", MetricKind.EXPONENTIAL_HISTOGRAM)
    private val otherHistogram = MetricKey("histogram.other", MetricKind.EXPONENTIAL_HISTOGRAM)

    private val sumsKey = StoreKey("otel.metrics.sums")
    private val doubleSumsKey = StoreKey("otel.metrics.sums.double")
    private val gaugesKey = StoreKey("otel.metrics.gauges")
    private val cardinalitiesKey = StoreKey("otel.metrics.cardinalities")
    private val histogramsKey = StoreKey("otel.metrics.histograms")

    private val gaugeTimestamp = 10L

    /** What a previous process left durable: one series of every kind. */
    private suspend fun seed(store: DurableStore) {
        WarpMetricExporter(replica, store).apply {
            incrementSum(sum, 5L)
            incrementSumDouble(doubleSum, 2.5)
            setGauge(gauge, 4.0, gaugeTimestamp)
            addCardinality(cardinality, "one")
            addCardinality(cardinality, "two")
            addCardinality(cardinality, "three")
            recordHistogram(histogram, 1.0)
            recordHistogram(histogram, 2.0)
            recordHistogram(histogram, 3.0)
        }
    }

    private suspend fun restarted(store: DurableStore): WarpMetricExporter =
        WarpMetricExporter(replica, store).also { it.recover() }

    /**
     * One metric kind, with what each arm needs of it: a write of **another** series of the kind
     * through [record] and through the kind's `merge*`, and the kind's map out of a [MetricCatalog].
     * Another series rather than the seeded one, so the overwrite is visible whatever the kind's
     * join would make of the seeded value.
     */
    private inner class Kind(
        val storeKey: StoreKey,
        val update: MetricUpdate,
        val merge: suspend WarpMetricExporter.() -> MetricWriteResult,
        val seriesOf: (MetricCatalog) -> Map<MetricKey, Any>,
    )

    /** A peer's snapshot of one series of each kind, to hand to the `merge*` methods. */
    private suspend fun peerCatalog(): MetricCatalog {
        val peerStore = KindReadRefusingStore(RecordingStore())
        return WarpMetricExporter(peer, peerStore).apply {
            incrementSum(otherSum, 3L)
            incrementSumDouble(otherDoubleSum, 1.5)
            setGauge(otherGauge, 9.0, 1L)
            addCardinality(otherCardinality, "peer")
            recordHistogram(otherHistogram, 7.0)
        }.snapshotAll()
    }

    private suspend fun kinds(): List<Kind> {
        val remote = peerCatalog()
        return listOf(
            Kind(
                sumsKey,
                MetricUpdate.IncrementSum(otherSum, 1L),
                { mergeSum(otherSum, remote.sums.getValue(otherSum)) },
            ) { it.sums },
            Kind(
                doubleSumsKey,
                MetricUpdate.IncrementSumDouble(otherDoubleSum, 1.0),
                { mergeSumDouble(otherDoubleSum, remote.doubleSums.getValue(otherDoubleSum)) },
            ) { it.doubleSums },
            Kind(
                gaugesKey,
                MetricUpdate.SetGauge(otherGauge, 1.0, 1L),
                { mergeGauge(otherGauge, remote.gauges.getValue(otherGauge)) },
            ) { it.gauges },
            Kind(
                cardinalitiesKey,
                MetricUpdate.AddCardinality(otherCardinality, "new"),
                { mergeCardinality(otherCardinality, remote.cardinalities.getValue(otherCardinality)) },
            ) { it.cardinalities },
            Kind(
                histogramsKey,
                MetricUpdate.RecordHistogram(otherHistogram, 1.0),
                { mergeHistogram(otherHistogram, remote.histograms.getValue(otherHistogram)) },
            ) { it.histograms },
        )
    }

    private class Overwrite(
        val name: String,
        val update: MetricUpdate,
        val refusals: Int,
        /** A [MetricExportResult] from [WarpMetricExporter.record], or a [MetricWriteResult] from a `merge*`. */
        val result: Any,
        val seeded: Map<MetricKey, Any>,
        val afterRestart: Map<MetricKey, Any>,
        val writesOfTheKind: Int,
        val readsOfTheKind: Int,
        val recoveryFailed: Boolean,
    )

    /**
     * For each kind: refuse its reads, [recover] unless told not to, run [write], and read back what
     * a restart sees. With [refuse] off the reads land, so the arm asks only whether [write] joined
     * the durable series rather than overwriting them.
     *
     * After a refused [recover] the kind's first turn skips its read, inside the backoff window
     * (#3436), so the arm spends that skip with one [write] first: the measured turn then reads,
     * and is refused by the store itself. Both turns count toward [Overwrite.writesOfTheKind].
     */
    private suspend fun overwriteArms(
        recover: Boolean = true,
        refuse: Boolean = true,
        write: suspend (WarpMetricExporter, Kind) -> Any,
    ): List<Overwrite> =
        kinds().map { kind ->
            val backing = RecordingStore()
            seed(backing)
            val seeded = kind.seriesOf(restarted(backing).snapshotAll())
            val store = KindReadRefusingStore(backing)
            if (refuse) store.refuse(kind.storeKey)
            val exporter = WarpMetricExporter(replica, store).also { if (recover) it.recover() }
            val writesBefore = backing.operations().count { it.kind == StoreOpKind.WRITE && it.key == kind.storeKey }
            if (recover && refuse) write(exporter, kind)
            val result = write(exporter, kind)
            val writes = backing.operations().count { it.kind == StoreOpKind.WRITE && it.key == kind.storeKey } - writesBefore
            Overwrite(
                name = kind.storeKey.name,
                update = kind.update,
                refusals = store.refusalsOf(kind.storeKey),
                result = result,
                seeded = seeded,
                afterRestart = kind.seriesOf(restarted(backing).snapshotAll()),
                writesOfTheKind = writes,
                readsOfTheKind = store.readsOf(kind.storeKey),
                recoveryFailed = exporter.health.value.recoveryFailed,
            )
        }

    private fun assertDurableIntact(arms: List<Overwrite>, path: String, refusals: Int = 2) = assertAll(
        *arms.flatMap { o ->
            listOf<() -> Unit>(
                { assertEquals(refusals, o.refusals, "${o.name}: the rig refused ${o.refusals} reads, not $refusals") },
                { assertEquals(1, o.seeded.size, "${o.name}: the fixture did not seed one durable series") },
                { assertNotCounted(o, path) },
                { assertEquals(0, o.writesOfTheKind, "${o.name}: $path wrote the kind while its read was refused") },
                { assertEquals(o.seeded, o.afterRestart, "${o.name}: $path overwrote the durable series it never read") },
            )
        }.toTypedArray(),
    )

    /**
     * A record over the unread kind is [MetricExportResult.Partial] listing exactly the kind's update
     * as uncounted; a merge over it is a [MetricWriteResult.Failure]. Either way the cause names the
     * kind, and neither reports success.
     */
    private fun assertNotCounted(o: Overwrite, path: String) {
        val cause = when (val r = o.result) {
            is MetricExportResult -> {
                val partial = assertIs<MetricExportResult.Partial>(r, "${o.name}: $path over an unread kind reported $r")
                assertEquals(listOf(o.update), partial.uncounted, "${o.name}: $path listed ${partial.uncounted} as uncounted")
                partial.causes.single()
            }
            else -> assertIs<MetricWriteResult.Failure>(r, "${o.name}: $path over an unread kind reported $r").cause
        }
        assertEquals(o.name, (cause as? MetricKindUnreadException)?.storeKey?.name, "${o.name}: $path did not say the kind went unread: $cause")
    }

    // ---- A refused read, then a write of that kind ----

    @Test
    fun aRecordOverAKindWhoseReadWasRefusedLeavesItsDurableSeriesIntact() = runTest {
        assertDurableIntact(overwriteArms { exporter, kind -> exporter.record(listOf(kind.update)) }, "record")
    }

    @Test
    fun aMergeOverAKindWhoseReadWasRefusedLeavesItsDurableSeriesIntact() = runTest {
        assertDurableIntact(overwriteArms { exporter, kind -> kind.merge(exporter) }, "merge")
    }

    // ---- An exporter nobody recovered ----

    /**
     * [WarpMetricExporter.recover] is the documented first call, but nothing enforces it, and an
     * exporter that skipped it held the same fresh map a refused read leaves: its first write of a
     * kind replaced every series a previous process had made durable. So a kind nobody has read is
     * owed a read exactly as a refused one is, and the first turn of it reads first.
     */
    @Test
    fun aFirstWriteOfAKindNobodyRecoveredJoinsTheDurableSeriesRatherThanReplacingThem() = runTest {
        val arms = overwriteArms(recover = false, refuse = false) { exporter, kind -> exporter.record(listOf(kind.update)) } +
            overwriteArms(recover = false, refuse = false) { exporter, kind -> kind.merge(exporter) }
        assertAll(
            *arms.flatMap { o ->
                listOf<() -> Unit>(
                    { assertEquals(1, o.seeded.size, "${o.name}: the fixture did not seed one durable series") },
                    {
                        val success: Any = if (o.result is MetricExportResult) MetricExportResult.Success else MetricWriteResult.Success
                        assertEquals(success, o.result, "${o.name}: the first write reported ${o.result}")
                    },
                    { assertEquals(1, o.writesOfTheKind, "${o.name}: the rig never reached the write") },
                    { assertEquals(1, o.readsOfTheKind, "${o.name}: the first turn read the kind ${o.readsOfTheKind} times, not once") },
                    { assertEquals(o.seeded, o.afterRestart.filterKeys { it in o.seeded }, "${o.name}: the first write replaced the durable series") },
                    { assertEquals(2, o.afterRestart.size, "${o.name}: a restart holds ${o.afterRestart.keys}, not the seeded series and the new one") },
                )
            }.toTypedArray(),
        )
    }

    @Test
    fun aFirstWriteOfAKindNobodyRecoveredOverARefusingStoreWritesNothing() = runTest {
        val arms = overwriteArms(recover = false) { exporter, kind -> exporter.record(listOf(kind.update)) } +
            overwriteArms(recover = false) { exporter, kind -> kind.merge(exporter) }
        assertDurableIntact(arms, "an unrecovered first write", refusals = 1)
        assertAll(
            *arms.map { o ->
                { assertTrue(o.recoveryFailed, "${o.name}: a refused first read left health.recoveryFailed unset") }
            }.toTypedArray(),
        )
    }

    // ---- The read that heals ----

    /**
     * The refused turn admitted nothing, so after the heal the seeded series and the healing turn's
     * update combine exactly: the refused `+10` is not counted, the seeded `5` and the healing `+2`
     * are. A turn that admitted the refused update into memory would leave this replica's slot at
     * `10` beside the durable `5`, and the join, a max per replica, would keep `10`: neither the
     * sum the caller was told was refused nor the one it was told was counted.
     */
    @Test
    fun theReadThatHealsJoinsTheDurableSeriesWithTheHealingTurnsUpdate() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val store = KindReadRefusingStore(backing)
        StoreKind.all.forEach { store.refuse(it) }
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }

        val refusedBatch = listOf(
            MetricUpdate.IncrementSum(sum, 10L),
            MetricUpdate.IncrementSumDouble(doubleSum, 10.0),
            MetricUpdate.SetGauge(gauge, 99.0, gaugeTimestamp + 5),
            MetricUpdate.AddCardinality(cardinality, "refused"),
            MetricUpdate.RecordHistogram(histogram, 50.0),
        )
        val refused = exporter.record(refusedBatch)
        val refusalsBeforeHeal = StoreKind.all.associateWith { store.refusalsOf(it) }
        StoreKind.all.forEach { store.heal(it) }

        val healed = exporter.record(
            listOf(
                MetricUpdate.IncrementSum(sum, 2L),
                MetricUpdate.IncrementSumDouble(doubleSum, 1.0),
                // Older than the durable gauge: a join keeps the durable value, a replace would not.
                MetricUpdate.SetGauge(gauge, 1.0, gaugeTimestamp - 5),
                MetricUpdate.AddCardinality(cardinality, "four"),
                MetricUpdate.RecordHistogram(histogram, 4.0),
            ),
        )
        val afterRestart = restarted(backing)

        assertAll(
            // The refused batch is the window's one skipped turn of each kind, so the healing batch reads.
            { assertEquals(StoreKind.all.associateWith { 1 }, refusalsBeforeHeal, "the rig did not refuse recover's read of every kind") },
            { assertEquals(refusedBatch, assertIs<MetricExportResult.Partial>(refused, "the turn over five unread kinds reported $refused").uncounted) },
            { assertEquals(MetricExportResult.Success, healed) },
            { assertEquals(7L, exporter.sumValue(sum), "sum: seeded 5 + healing 2, the refused 10 uncounted") },
            { assertEquals(7L, afterRestart.sumValue(sum), "sum after restart") },
            { assertEquals(3.5, afterRestart.doubleSumValue(doubleSum), "double sum: seeded 2.5 + healing 1.0") },
            { assertEquals(4.0, afterRestart.gaugeValue(gauge), "gauge: the durable later write wins the join") },
            { assertEquals(4L, afterRestart.cardinalityEstimate(cardinality), "cardinality: three seeded + one healing") },
            { assertEquals(4L, afterRestart.histogramSnapshot(histogram).count, "histogram: three seeded + one healing") },
            { assertEquals(4.0, afterRestart.histogramQuantile(histogram, 1.0) ?: Double.NaN, 0.1, "histogram: the refused 50.0 uncounted") },
        )
    }

    /**
     * The heal's read lands, then the store refuses the write; the next turn writes, a second
     * [WarpMetricExporter.recover] runs over memory that is ahead of the store, and the process
     * restarts. The persisted series must be counted exactly once through all of it: a histogram
     * of three seeded values and two recorded ones holds five, and a sum of a seeded `5` and
     * increments of `2` and `1` holds `8`.
     *
     * Every join here is a per-replica maximum (a `DDSketch`'s bucket cells are `GCounter`s), so
     * joining the persisted series into memory a second time cannot add it twice. What this pins is
     * the other direction: nothing of the kind reached memory before the read, so the maximum the
     * join keeps is the whole history and not one of two rival ones.
     */
    @Test
    fun aHealedReadWhoseWriteIsRefusedCountsThePersistedSeriesOnceThroughRetryRecoverAndRestart() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val reads = KindReadRefusingStore(backing)
        val store = WriteRefusingStore(reads) { it == sumsKey || it == histogramsKey }
        reads.refuse(sumsKey)
        reads.refuse(histogramsKey)
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }
        // The window's one skipped turn of each kind (#3436), so the turn after the heal reads.
        val skipped = exporter.record(listOf(MetricUpdate.IncrementSum(sum, 100L), MetricUpdate.RecordHistogram(histogram, 100.0)))

        reads.heal(sumsKey)
        reads.heal(histogramsKey)
        store.refuseWrites()
        val refusedWrite = exporter.record(listOf(MetricUpdate.IncrementSum(sum, 2L), MetricUpdate.RecordHistogram(histogram, 4.0)))
        store.allowWrites()
        val retried = exporter.record(listOf(MetricUpdate.IncrementSum(sum, 1L), MetricUpdate.RecordHistogram(histogram, 5.0)))
        exporter.recover()
        val afterRestart = restarted(backing)

        assertAll(
            { assertEquals(1, reads.refusalsOf(sumsKey), "the rig did not refuse recover's read of the sums") },
            { assertEquals(1, reads.refusalsOf(histogramsKey), "the rig did not refuse recover's read of the histograms") },
            { assertEquals(2, (skipped as? MetricExportResult.Partial)?.uncounted?.size, "the window's skipped turn counted an update: $skipped") },
            { assertEquals(2, store.refusedWrites(), "the rig refused ${store.refusedWrites()} writes, not the healing turn's two") },
            { assertEquals(emptyList(), assertIs<MetricExportResult.Partial>(refusedWrite, "the turn whose writes were refused reported $refusedWrite").uncounted, "a refused write's updates were counted") },
            { assertEquals(MetricExportResult.Success, retried) },
            { assertEquals(5L, exporter.histogramSnapshot(histogram).count, "histogram in memory: three seeded + two recorded") },
            { assertEquals(5L, afterRestart.histogramSnapshot(histogram).count, "histogram after restart: three seeded + two recorded") },
            { assertEquals(8L, exporter.sumValue(sum), "sum in memory: seeded 5 + 2 + 1") },
            { assertEquals(8L, afterRestart.sumValue(sum), "sum after restart: seeded 5 + 2 + 1") },
        )
    }

    /**
     * Once a read of the kind lands, later turns do not read it again. Two turns race the heal; the
     * first parks inside the read, the second waits. A second read would be wasted work rather than a
     * double count, since the join is idempotent; the counts below pin that each update lands once.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun twoTurnsRacingTheHealReadOnceAndCountBothUpdatesOnce() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val store = KindReadRefusingStore(backing)
        store.refuse(histogramsKey)
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }
        // The window's one skipped turn (#3436), so the racing turns read.
        val skipped = exporter.recordHistogram(histogram, 100.0)
        val readsAfterRecover = store.readsOf(histogramsKey)

        store.heal(histogramsKey)
        val gate = store.gateNextRead(histogramsKey)
        val results = mutableListOf<MetricExportResult>()
        val first = launch { results += exporter.recordHistogram(histogram, 4.0) }
        runCurrent()
        val second = launch { results += exporter.recordHistogram(histogram, 5.0) }
        runCurrent()
        val parkedInTheRead = store.parkedOn(histogramsKey)
        gate.complete(Unit)
        first.join()
        second.join()
        val turnReads = store.readsOf(histogramsKey) - readsAfterRecover
        val afterRestart = restarted(backing)

        assertAll(
            { assertEquals(1, readsAfterRecover, "the rig never reached recover's read of the histograms, or the skipped turn read") },
            { assertIs<MetricExportResult.Partial>(skipped, "the window's skipped turn ran") },
            { assertEquals(1, parkedInTheRead, "the first turn never parked inside the healing read") },
            { assertEquals(1, turnReads, "the turns read the histograms $turnReads times, not once") },
            { assertEquals(listOf<MetricExportResult>(MetricExportResult.Success, MetricExportResult.Success), results) },
            { assertEquals(5L, exporter.histogramSnapshot(histogram).count, "three seeded and two raced, each once") },
            { assertEquals(5L, afterRestart.histogramSnapshot(histogram).count, "three seeded and two raced, each once, durably") },
        )
    }

    @Test
    fun oneKindsRefusedReadDoesNotBlockTheOtherKindsOfABatch() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val store = KindReadRefusingStore(backing)
        store.refuse(sumsKey)
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }

        val result = exporter.record(
            listOf(
                MetricUpdate.IncrementSum(otherSum, 1L),
                MetricUpdate.SetGauge(otherGauge, 2.0, 1L),
                MetricUpdate.RecordHistogram(histogram, 4.0),
            ),
        )
        val afterRestart = restarted(backing)

        assertAll(
            // The turn is the window's one skipped turn of the sums (#3436): no read of its own.
            { assertEquals(1, store.refusalsOf(sumsKey), "the rig did not refuse recover's read of the sums") },
            {
                val partial = assertIs<MetricExportResult.Partial>(result, "a batch with an unread kind reported $result")
                assertEquals(listOf<MetricUpdate>(MetricUpdate.IncrementSum(otherSum, 1L)), partial.uncounted, "the updates the batch did not count")
            },
            { assertEquals(5L, afterRestart.sumValue(sum), "the unread sums were overwritten") },
            { assertEquals(0L, afterRestart.sumValue(otherSum), "the refused kind's update was written anyway") },
            { assertEquals(2.0, afterRestart.gaugeValue(otherGauge), "the gauge write was blocked by the sums' read") },
            { assertEquals(4.0, afterRestart.gaugeValue(gauge), "the seeded gauge did not survive its own kind's write") },
            { assertEquals(4L, afterRestart.histogramSnapshot(histogram).count, "the histogram write was blocked by the sums' read") },
        )
    }

    // ---- Not counted, beside not durable ----

    /**
     * The gauges' read is refused and the sums' write lands, in one batch. The sum was counted and
     * is durable; the gauge was not counted. So the Partial lists the gauge's update alone, and
     * recording exactly that list after the heal counts each update once: the sums are not written
     * again, and the seeded `5` plus the one increment reads back `6`. A Partial that listed the
     * landed sum too would have a retry count it twice.
     */
    @Test
    fun aRecordOverAnUnreadKindListsExactlyItsUpdatesAsUncountedAndRecordingThemAfterTheHealCountsEachOnce() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val store = KindReadRefusingStore(backing)
        store.refuse(gaugesKey)
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }
        val increment = MetricUpdate.IncrementSum(sum, 1L)
        val level = MetricUpdate.SetGauge(otherGauge, 7.0, 1L)

        val result = exporter.record(listOf(increment, level))
        val partial = result as? MetricExportResult.Partial
        store.heal(gaugesKey)
        val writesBefore = backing.operations().filter { it.kind == StoreOpKind.WRITE }.groupingBy { it.key }.eachCount()
        val retried = exporter.record(partial?.uncounted ?: emptyList())
        val writesAfter = backing.operations().filter { it.kind == StoreOpKind.WRITE }.groupingBy { it.key }.eachCount()
        val afterRestart = restarted(backing)

        assertAll(
            // The batch is the window's one skipped turn of the gauges (#3436), so the retry reads.
            { assertEquals(1, store.refusalsOf(gaugesKey), "the rig did not refuse recover's read of the gauges") },
            { assertIs<MetricExportResult.Partial>(result, "the batch reported $result") },
            { assertEquals(listOf<MetricUpdate>(level), partial?.uncounted, "the updates the batch did not count") },
            { assertEquals(gaugesKey, (partial?.causes?.singleOrNull() as? MetricKindUnreadException)?.storeKey, "the causes ${partial?.causes}") },
            { assertEquals(MetricExportResult.Success, retried) },
            { assertEquals(writesBefore[sumsKey] ?: 0, writesAfter[sumsKey] ?: 0, "recording the uncounted updates wrote the sums again") },
            { assertEquals((writesBefore[gaugesKey] ?: 0) + 1, writesAfter[gaugesKey] ?: 0, "recording the uncounted updates did not write the gauges once") },
            { assertEquals(6L, afterRestart.sumValue(sum), "sum: seeded 5 + the one increment, counted once") },
            { assertEquals(7.0, afterRestart.gaugeValue(otherGauge), "the uncounted gauge, counted on the retry") },
            { assertEquals(4.0, afterRestart.gaugeValue(gauge), "the seeded gauge did not survive") },
        )
    }

    /**
     * The histograms' read is refused and the sums' write is refused, in one batch. The sum was
     * counted, and is only not yet durable, so retrying it counts it twice; the histogram was not
     * counted, so retrying it counts it once. The Partial lists the histogram's update alone, and
     * carries both causes.
     */
    @Test
    fun aBatchWithAnUnreadKindAndARefusedWriteListsOnlyTheUnreadKindsUpdatesAsUncounted() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val reads = KindReadRefusingStore(backing)
        val store = WriteRefusingStore(reads) { it == sumsKey }
        reads.refuse(histogramsKey)
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }
        store.refuseWrites()
        val increment = MetricUpdate.IncrementSum(sum, 1L)
        val measurement = MetricUpdate.RecordHistogram(histogram, 4.0)

        val result = exporter.record(listOf(increment, measurement))
        val partial = assertIs<MetricExportResult.Partial>(result, "the batch reported $result")
        val unread = partial.causes.filterIsInstance<MetricKindUnreadException>()

        assertAll(
            // The batch is the window's one skipped turn of the histograms (#3436): no read of its own.
            { assertEquals(1, reads.refusalsOf(histogramsKey), "the rig did not refuse recover's read of the histograms") },
            { assertEquals(1, store.refusedWrites(), "the rig refused ${store.refusedWrites()} writes, not the sums' one") },
            { assertEquals(listOf<MetricUpdate>(measurement), partial.uncounted, "the updates the batch did not count") },
            { assertEquals(listOf(histogramsKey), unread.map { it.storeKey }, "the unread causes ${partial.causes}") },
            { assertEquals("simulated refused read of otel.metrics.histograms", unread.singleOrNull()?.cause?.message) },
            { assertEquals(2, partial.causes.size, "the Partial carries ${partial.causes}, not both kinds' causes") },
            { assertTrue(partial.causes.any { it.message == "store refused otel.metrics.sums" }, "the sums' write refusal is missing from ${partial.causes}") },
            { assertEquals(6L, exporter.sumValue(sum), "the sum was counted: seeded 5 + 1") },
            { assertEquals(0L, exporter.histogramSnapshot(histogram).count, "the histogram was counted into the unread kind") },
        )
    }

    /**
     * A batch refused for an overflow is [MetricExportResult.Refused], never a Partial, even beside
     * an unread kind: it applies nothing and is no turn of any kind, the unread one included. A caller
     * that leaves the saturated series out and records the rest again has made one turn of the
     * unread kind, not two.
     */
    @Test
    fun anOverflowIsRefusedByTypeAndIsNoFailedTurnOfTheKindItCouldNotRead() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val store = KindReadRefusingStore(backing)
        store.refuse(histogramsKey)
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }
        val failedBefore = exporter.health.value.failed
        val measurement = MetricUpdate.RecordHistogram(histogram, 4.0)

        val overflowed = exporter.record(listOf(MetricUpdate.IncrementSum(sum, Long.MAX_VALUE), measurement))
        val failedAfterOverflow = exporter.health.value.failed
        val retried = exporter.record(listOf(measurement))
        val failedAfterRetry = exporter.health.value.failed

        assertAll(
            // The overflowed batch would have read the histograms, so it spends the window's one
            // skip (#3436), and the retry reads.
            { assertEquals(2, store.refusalsOf(histogramsKey), "the rig did not refuse recover's and the retry's reads") },
            { assertIs<MetricSumOverflowException>(assertIs<MetricExportResult.Refused>(overflowed, "the overflow reported $overflowed").overflow) },
            { assertEquals(5L, exporter.sumValue(sum), "the refused batch applied its increment") },
            { assertEquals(failedBefore, failedAfterOverflow, "the overflow-refused batch counted as a failed turn") },
            { assertEquals(listOf<MetricUpdate>(measurement), (retried as? MetricExportResult.Partial)?.uncounted, "the retry reported $retried") },
            { assertEquals(failedBefore + 1, failedAfterRetry, "the retry did not count one failed turn") },
        )
    }

    // ---- Health, and memory ----

    private fun MetricCatalog.maps(): List<Map<MetricKey, Any>> = listOf(sums, doubleSums, gauges, cardinalities, histograms)

    /**
     * A turn refused for an unread kind is a failed turn on `health`, outstanding until a write of
     * the kind lands, as the log exporter counts it; an exporter that refuses every write forever
     * must not read `failed = 0`. And the refusal changes nothing in memory, even where memory
     * already holds the kind: here a healthy [WarpMetricExporter.recover] loaded the sums, and a
     * second one was refused.
     */
    @Test
    fun aTurnRefusedForAnUnreadKindIsAFailedTurnUntilTheHealedWriteAndLeavesMemoryAlone() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val store = KindReadRefusingStore(backing)
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }
        store.refuse(sumsKey)
        exporter.recover()
        val afterRecover = exporter.health.value
        val memoryBefore = exporter.snapshotAll().maps()
        val sumBefore = exporter.sumValue(sum)

        val refused = exporter.record(listOf(MetricUpdate.IncrementSum(sum, 1L), MetricUpdate.IncrementSum(otherSum, 1L)))
        val refusedMerge = exporter.mergeSum(otherSum, exporter.sumSnapshot(sum))
        val afterRefused = exporter.health.value
        val memoryAfter = exporter.snapshotAll().maps()
        store.heal(sumsKey)
        // The record skipped its read and the merge's read was refused, so the sums sit in a window of
        // three turns (#3436). A recover always reads, so the healed write does not wait it out, and a
        // read is no persistence turn: the refused turns stay outstanding until that write lands.
        exporter.recover()
        val afterHealedRead = exporter.health.value
        val healed = exporter.incrementSum(sum, 1L)
        val afterHeal = exporter.health.value
        val durable = restarted(backing).sumValue(sum)

        assertAll(
            { assertEquals(2, store.refusalsOf(sumsKey), "the rig refused ${store.refusalsOf(sumsKey)} reads, not the second recover's and the merge's") },
            { assertEquals(2L, afterHealedRead.outstandingFailures, "the healed read resolved the refused turns") },
            { assertTrue(afterRecover.recoveryFailed, "the refused second recover was not reported") },
            { assertEquals(5L, sumBefore, "the fixture's first recover did not load the sums") },
            { assertIs<MetricExportResult.Partial>(refused, "the refused record reported $refused") },
            { assertIs<MetricWriteResult.Failure>(refusedMerge, "the refused merge reported $refusedMerge") },
            { assertEquals(afterRecover.failed + 2, afterRefused.failed, "two refused turns moved failed by ${afterRefused.failed - afterRecover.failed}") },
            { assertEquals(2L, afterRefused.outstandingFailures, "the refused turns are not outstanding") },
            { assertEquals(memoryBefore, memoryAfter, "a refused turn changed memory") },
            { assertEquals(MetricExportResult.Success, healed) },
            { assertEquals(0L, afterHeal.outstandingFailures, "the healed write did not resolve the refused turns") },
            { assertEquals(6L, durable, "seeded 5 + the healed 1, the refused turns uncounted") },
        )
    }

    // ---- What does not change ----

    @Test
    fun anUndecodableEntryStillStartsItsKindFreshAndWrites() = runTest {
        val backing = RecordingStore()
        seed(backing)
        backing.putRaw(sumsKey, byteArrayOf(0x7f, 0x00, 0x13))
        val exporter = restarted(backing)

        val result = exporter.incrementSum(otherSum, 1L)
        val afterRestart = restarted(backing)

        assertAll(
            { assertTrue(exporter.health.value.recoveryFailed, "the undecodable entry was not reported") },
            { assertEquals(MetricExportResult.Success, result, "an undecodable kind must not refuse writes") },
            { assertEquals(1L, afterRestart.sumValue(otherSum)) },
            { assertEquals(0L, afterRestart.sumValue(sum), "the undecodable sums start fresh") },
        )
    }

    /**
     * A second [WarpMetricExporter.recover] meets memory that is **ahead** of the store: a write the
     * store refused left its increment in memory only. A read that replaced the series would drop
     * that increment; the read joins instead.
     */
    @Test
    fun aSecondRecoverJoinsRatherThanReplacingAnIncrementARefusedWriteLeftInMemory() = runTest {
        val store = WriteRefusingStore(RecordingStore())
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }
        exporter.incrementSum(sum, 5L)
        store.refuseWrites()
        val refusedWrite = exporter.incrementSum(sum, 4L)
        store.allowWrites()

        exporter.recover()
        val healed = exporter.incrementSum(sum, 1L)
        val durable = restarted(store).sumValue(sum)

        assertAll(
            { assertEquals(1, store.refusedWrites(), "the rig did not refuse the write") },
            { assertEquals(emptyList(), assertIs<MetricExportResult.Partial>(refusedWrite).uncounted, "a refused write's update was not counted") },
            { assertEquals(MetricExportResult.Success, healed) },
            { assertEquals(10L, exporter.sumValue(sum), "the second recover dropped the increment a refused write left in memory") },
            { assertEquals(10L, durable) },
        )
    }

    /**
     * The series a heal reads back are held to [WarpMetricExporter]'s `maxMetrics` like any other,
     * and the arm names exactly what survives. The read brings back `sum` and `otherSum` beside the
     * gauge already in memory: three series against a cap of two. `DROP_OLDEST` takes the first sum
     * series, `sum`, and the healing increment lands on the `otherSum` the read brought back, so it
     * holds the stored `1` plus the healing `1`. A heal that skipped the join to stay under the cap
     * would leave `otherSum` at `1`; one that joined without evicting would keep `sum`.
     */
    @Test
    fun theSeriesAHealReadsBackRespectTheBufferCap() = runTest {
        val backing = RecordingStore()
        WarpMetricExporter(replica, backing).apply {
            incrementSum(sum, 1L)
            incrementSum(otherSum, 1L)
        }
        val store = KindReadRefusingStore(backing)
        store.refuse(sumsKey)
        val exporter = WarpMetricExporter(replica, store, maxMetrics = 2).also { it.recover() }
        exporter.setGauge(gauge, 1.0, 1L)
        // The window's one skipped turn of the sums (#3436), so the healing turn reads.
        exporter.incrementSum(otherSum, 100L)
        val beforeHeal = exporter.snapshotAll()
        store.heal(sumsKey)

        val healed = exporter.incrementSum(otherSum, 1L)
        val afterHeal = exporter.snapshotAll()
        val afterRestart = restarted(backing).snapshotAll()

        assertAll(
            { assertEquals(1, store.refusalsOf(sumsKey), "the rig did not refuse recover's read of the sums") },
            { assertEquals(emptySet(), beforeHeal.sums.keys, "no sum series should be in memory before the heal") },
            { assertEquals(setOf(gauge), beforeHeal.gauges.keys, "only the gauge should be in memory before the heal") },
            { assertEquals(MetricExportResult.Success, healed) },
            { assertEquals(setOf(otherSum), afterHeal.sums.keys, "the sum series the capped heal kept") },
            { assertEquals(setOf(gauge), afterHeal.gauges.keys, "the gauge did not survive the capped heal") },
            { assertEquals(2L, exporter.sumValue(otherSum), "otherSum: the stored 1 + the healing 1") },
            { assertEquals(mapOf(otherSum to 2L), afterRestart.sums.mapValues { it.value.value }, "the sums on disk") },
        )
    }

    /**
     * A cap below one is refused. Below zero the heal's eviction loop had nothing left to evict and
     * an exporter still over the cap; at zero the heal and a write disagreed, the heal evicting to
     * nothing and a write of a new series keeping it, since there was nothing to evict for it. At a
     * cap of one both hold one series. Recover joins the seeded series kind by kind and evicts as it
     * goes, so `DROP_OLDEST` leaves the last kind read, the histogram, with its three seeded values.
     */
    @Test
    fun aBufferCapBelowOneIsRefusedAndACapOfOneHoldsOneSeriesOnHealAndWrite() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val one = WarpMetricExporter(replica, backing, maxMetrics = 1).also { it.recover() }
        val healed = one.snapshotAll()
        val written = one.incrementSum(otherSum, 1L)

        assertAll(
            { assertFailsWith<IllegalArgumentException>("a cap of -1 was accepted") { WarpMetricExporter(replica, backing, maxMetrics = -1) } },
            { assertFailsWith<IllegalArgumentException>("a cap of 0 was accepted") { WarpMetricExporter(replica, backing, maxMetrics = 0) } },
            { assertEquals(setOf(histogram), healed.histograms.keys, "the series a capped recover kept") },
            { assertEquals(3L, healed.histograms[histogram]?.count, "recover did not join the seeded histogram") },
            { assertEquals(emptySet(), healed.sums.keys + healed.doubleSums.keys + healed.gauges.keys + healed.cardinalities.keys) },
            { assertEquals(MetricExportResult.Success, written) },
            { assertEquals(1, one.metricCount(), "a write of a new series at a cap of one kept ${one.metricCount()} series") },
            { assertEquals(1L, one.sumValue(otherSum), "the new series did not take the place of the healed one") },
        )
    }

    /**
     * A clear whose last delete is refused, on an exporter nobody recovered. The histograms key it
     * could not delete still holds the series the caller asked to drop. Memory is empty, so what is
     * on disk is unwanted rather than unseen: the next histogram write must not read it back in, and
     * writing over it finishes the clear, as it did before kinds started owed a read.
     */
    @Test
    fun aClearWhoseDeleteWasRefusedNeverReadsBackTheSeriesItDropped() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val store = KeyDeleteRefusingStore(backing, histogramsKey)
        val exporter = WarpMetricExporter(replica, store)

        val cleared = exporter.clear()
        val survived = backing.read(histogramsKey) != null
        val readsBefore = backing.operations().count { it.kind == StoreOpKind.READ && it.key == histogramsKey }
        val written = exporter.recordHistogram(otherHistogram, 1.0)
        val turnReads = backing.operations().count { it.kind == StoreOpKind.READ && it.key == histogramsKey } - readsBefore
        val afterRestart = restarted(backing).snapshotAll()

        assertAll(
            { assertEquals(1, store.refusedDeletes(), "the rig refused ${store.refusedDeletes()} deletes, not the histograms' one") },
            { assertIs<MetricWriteResult.Failure>(cleared, "the clear with a refused delete reported $cleared") },
            { assertTrue(survived, "the rig: the refused delete did not leave the histograms on disk") },
            { assertEquals(MetricExportResult.Success, written) },
            { assertEquals(0, turnReads, "the write after the clear read the histograms $turnReads times") },
            { assertEquals(setOf(otherHistogram), afterRestart.histograms.keys, "the cleared histogram series came back") },
            { assertEquals(emptySet(), afterRestart.sums.keys, "the sums' delete did not land") },
        )
    }

    /** A clear deletes every key, so nothing is left unread: the next write runs without a read. */
    @Test
    fun aClearSettlesAKindWhoseReadWasRefused() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val store = KindReadRefusingStore(backing)
        store.refuse(sumsKey)
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }

        val cleared = exporter.clear()
        val written = exporter.incrementSum(otherSum, 1L)
        val afterRestart = restarted(backing)

        assertAll(
            { assertEquals(1, store.refusalsOf(sumsKey), "the rig refused ${store.refusalsOf(sumsKey)} reads, not recover's alone") },
            { assertEquals(MetricWriteResult.Success, cleared) },
            { assertEquals(MetricExportResult.Success, written, "a kind whose key clear deleted still refused its writes") },
            { assertEquals(1L, afterRestart.sumValue(otherSum)) },
            { assertEquals(0L, afterRestart.sumValue(sum), "the cleared sums came back") },
        )
    }

    // ---- Cancellation ----

    @Test
    fun aCancellationTheStoreMintsOnATurnsReadRefusesThatTurn() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val store = CancellationScriptedStore(backing)
        store.arm(StoreCall.READ, StoreAct.MINT) { it == sumsKey }
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }
        // The window's one skipped turn of the sums (#3436), so the next turn reads.
        exporter.incrementSum(otherSum, 100L)

        store.arm(StoreCall.READ, StoreAct.MINT) { it == sumsKey }
        val minted = escapeOf { exporter.incrementSum(otherSum, 1L) }
        val durable = restarted(backing).sumValue(sum)

        assertAll(
            { assertEquals(2, store.minted, "the rig minted ${store.minted} cancellations, not recover's and the turn's") },
            { assertNull(minted.escaped, "a cancellation the store minted on a turn's read escaped: ${minted.escaped}") },
            { assertEquals(listOf<MetricUpdate>(MetricUpdate.IncrementSum(otherSum, 1L)), (minted.returned as? MetricExportResult.Partial)?.uncounted, "the minted turn read reported ${minted.returned}") },
            { assertEquals(5L, durable, "the turn wrote over sums it never read") },
        )
    }

    @Test
    fun theCallersOwnCancellationInATurnsReadStillPropagates() = runTest {
        val backing = RecordingStore()
        seed(backing)
        val store = CancellationScriptedStore(backing)
        store.arm(StoreCall.READ, StoreAct.MINT) { it == sumsKey }
        val exporter = WarpMetricExporter(replica, store).also { it.recover() }
        // The window's one skipped turn of the sums (#3436), so the next turn reads.
        exporter.incrementSum(otherSum, 100L)

        store.arm(StoreCall.READ, StoreAct.PARK) { it == sumsKey }
        val returned = cancelInside(store) { exporter.incrementSum(otherSum, 1L) }
        val durable = restarted(backing).sumValue(sum)

        assertAll(
            { assertEquals(1, store.minted, "the rig never refused recover's read") },
            { assertEquals(1, store.parked, "the rig never parked the turn's read") },
            { assertEquals(1, store.cancelled, "the cancellation never landed inside the turn's read") },
            { assertNull(returned, "the cancelled turn returned instead of being cancelled: $returned") },
            { assertEquals(5L, durable, "the cancelled turn wrote over sums it never read") },
        )
    }

    private object StoreKind {
        val all = listOf(
            StoreKey("otel.metrics.sums"),
            StoreKey("otel.metrics.sums.double"),
            StoreKey("otel.metrics.gauges"),
            StoreKey("otel.metrics.cardinalities"),
            StoreKey("otel.metrics.histograms"),
        )
    }
}

/**
 * Delegates to [backing], refusing every read of a key in the refused set until it is healed, and
 * optionally parking the next read of one key on a gate. Counts reads, refusals and parks per key,
 * so an arm whose rig never fired reds rather than passing by absence.
 */
private class KindReadRefusingStore(private val backing: DurableStore) : DurableStore {
    private val lock = reentrantLock()
    private val refusing = mutableSetOf<StoreKey>()
    private val refusals = mutableMapOf<StoreKey, Int>()
    private val reads = mutableMapOf<StoreKey, Int>()
    private val parks = mutableMapOf<StoreKey, Int>()
    private val gates = mutableMapOf<StoreKey, CompletableDeferred<Unit>>()

    fun refuse(key: StoreKey): Unit = lock.withLock { refusing += key }

    fun heal(key: StoreKey): Unit = lock.withLock { refusing -= key }

    fun refusalsOf(key: StoreKey): Int = lock.withLock { refusals[key] ?: 0 }

    fun readsOf(key: StoreKey): Int = lock.withLock { reads[key] ?: 0 }

    fun parkedOn(key: StoreKey): Int = lock.withLock { parks[key] ?: 0 }

    /** Park the next read of [key] until the returned gate completes. */
    fun gateNextRead(key: StoreKey): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { gate -> lock.withLock { gates[key] = gate } }

    override suspend fun read(key: StoreKey): ByteArray? {
        val gate = lock.withLock {
            reads[key] = (reads[key] ?: 0) + 1
            gates.remove(key)?.also { parks[key] = (parks[key] ?: 0) + 1 }
        }
        gate?.await()
        val refuse = lock.withLock { (key in refusing).also { if (it) refusals[key] = (refusals[key] ?: 0) + 1 } }
        if (refuse) throw IllegalStateException("simulated refused read of ${key.name}")
        return backing.read(key)
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)

    override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
}

/** Delegates to [backing], refusing every delete of [refused] and counting the refusals. */
private class KeyDeleteRefusingStore(private val backing: DurableStore, private val refused: StoreKey) : DurableStore {
    private val lock = reentrantLock()
    private var refusals = 0

    fun refusedDeletes(): Int = lock.withLock { refusals }

    override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

    override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)

    override suspend fun delete(key: StoreKey) {
        if (key == refused) {
            lock.withLock { refusals++ }
            throw IllegalStateException("simulated refused delete of ${key.name}")
        }
        backing.delete(key)
    }
}
