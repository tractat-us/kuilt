package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An UpDownCounter is summable across replicas (#3307): the fleet's in-flight count is the sum of
 * every node's level. [MetricKind.UP_DOWN_SUM] holds, per series, one level per replica at a version
 * ([us.tractat.kuilt.crdt.LWWMap] from replica to level), each written only by its owner; a merge
 * keeps each entry's higher version, and the series reads the sum of the levels. How the version is
 * minted, so a write supersedes the old entry everywhere, is `WarpMetricExporterUpDownVersionTest`'s.
 *
 * Every arm asserts the state it starts from before it asserts the outcome, so a rig that never
 * reached that state cannot pass.
 */
class WarpMetricExporterUpDownSumTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val inFlight = MetricKey("http.in_flight", MetricKind.UP_DOWN_SUM)
    private val other = MetricKey("other", MetricKind.UP_DOWN_SUM)

    private suspend fun exporter(
        replica: ReplicaId,
        store: DurableStore = RecordingStore(),
        maxMetrics: Int = DEFAULT_MAX_METRICS,
    ) = WarpMetricExporter(replica = replica, store = store, maxMetrics = maxMetrics).also { it.recover() }

    /** Writes of the two up-down maps, leaving out the version claim, which a lease writes now and then. */
    private fun RecordingStore.writeCount(): Int =
        operations().count { it.kind == StoreOpKind.WRITE && it.key.name != "otel.metrics.updown.version" }

    private suspend fun WarpMetricExporter.set(key: MetricKey, level: Long) =
        assertEquals(MetricExportResult.Success, setUpDownSum(key, level), "set $key to $level")

    // ── Summing across replicas ─────────────────────────────────────────────────────────────

    /** Note test 1: A sets 3, B sets 4, merged either way reads 7. The control is the gauge's 3-or-4. */
    @Test
    fun twoReplicasLevelsSumWhicheverWayTheyMerge() = runTest {
        val onA = exporter(a).also { it.set(inFlight, 3L) }
        val onB = exporter(b).also { it.set(inFlight, 4L) }
        val precondition = onA.upDownSumValue(inFlight) to onB.upDownSumValue(inFlight)
        assertEquals(MetricWriteResult.Success, onA.mergeUpDownSum(inFlight, onB.upDownSumSnapshot(inFlight)))
        assertEquals(MetricWriteResult.Success, onB.mergeUpDownSum(inFlight, onA.upDownSumSnapshot(inFlight)))

        val gauge = MetricKey("http.in_flight", MetricKind.GAUGE)
        val gaugeA = exporter(a).also { it.setGauge(gauge, 3.0, 1L) }
        val gaugeB = exporter(b).also { it.setGauge(gauge, 4.0, 2L) }
        gaugeA.mergeGauge(gauge, gaugeB.gaugeSnapshot(gauge))

        assertAll(
            { assertEquals(3L to 4L, precondition, "each replica holds its own level first") },
            { assertEquals(7L, onA.upDownSumValue(inFlight), "A after B merged in") },
            { assertEquals(7L, onB.upDownSumValue(inFlight), "B after A merged in") },
            { assertEquals(4.0, gaugeA.gaugeValue(gauge), "the control: a gauge keeps one replica's level") },
        )
    }

    /** Note test 5: with B's 4 merged in, A sets 2. A's share moves; B's slot does not. */
    @Test
    fun aReplicaSettingItsLevelMovesOnlyItsOwnShare() = runTest {
        val onA = exporter(a).also { it.set(inFlight, 5L) }
        val onB = exporter(b).also { it.set(inFlight, 4L) }
        onA.mergeUpDownSum(inFlight, onB.upDownSumSnapshot(inFlight))
        val before = onA.upDownSumValue(inFlight)
        onA.set(inFlight, 2L)
        val snapshot = onA.upDownSumSnapshot(inFlight)
        assertAll(
            { assertEquals(9L, before, "the rig: 5 + 4 before A moves") },
            { assertEquals(6L, onA.upDownSumValue(inFlight)) },
            { assertEquals(2L, snapshot[a]) },
            { assertEquals(4L, snapshot[b], "B's level is B's to move") },
        )
    }

    // ── Setting a level is idempotent ───────────────────────────────────────────────────────

    /** Note test 2: a cumulative series is re-sent every collection, and an unchanged level writes nothing. */
    @Test
    fun theSameLevelSetTwiceIsOneWrite() = runTest {
        val store = RecordingStore()
        val onA = exporter(a, store)
        val before = store.writeCount()
        onA.set(inFlight, 5L)
        val afterFirst = store.writeCount()
        onA.set(inFlight, 5L)
        assertAll(
            { assertEquals(before + 1, afterFirst, "the first set wrote") },
            { assertEquals(afterFirst, store.writeCount(), "the second set wrote nothing") },
            { assertEquals(5L, onA.upDownSumValue(inFlight)) },
        )
    }

    /**
     * A refused write leaves this replica's new share in memory only. The same level reported again
     * finds nothing to move, yet must write that share again rather than call it settled: otherwise
     * it never reaches disk, a restart reads the older share back, and the refused turn stays
     * outstanding while every later set returns a success. The old gauge mapping rewrote every
     * collection and so healed by itself (#3307).
     */
    @Test
    fun aSteadyLevelWritesAgainTheShareARefusedWriteLeftInMemory() = runTest {
        val backing = RecordingStore()
        val store = WriteRefusingStore(backing) { it.name == "otel.metrics.updown" }
        val onA = exporter(a, store)
        onA.set(inFlight, 3L)
        store.refuseWrites()
        val refused = onA.setUpDownSum(inFlight, 5L)
        store.allowWrites()
        val writesBefore = backing.writeCount()
        val retried = onA.setUpDownSum(inFlight, 5L)
        val writesAfter = backing.writeCount()
        val again = onA.setUpDownSum(inFlight, 5L)
        val restarted = exporter(a, backing)
        assertAll(
            { assertIs<MetricExportResult.Partial>(refused, "the rig: the store refused the set to 5") },
            { assertEquals(1, store.refusedWrites(), "the rig: exactly one write was refused") },
            { assertEquals(MetricExportResult.Success, retried) },
            { assertEquals(writesBefore + 1, writesAfter, "the steady level wrote the share again") },
            { assertEquals(MetricExportResult.Success, again) },
            { assertEquals(writesAfter, backing.writeCount(), "once on disk, the steady level writes nothing") },
            { assertEquals(0L, onA.health.value.outstandingFailures, "the refused turn is resolved") },
            { assertEquals(5L, restarted.upDownSumValue(inFlight), "the share is durable") },
        )
    }

    /** The fractional twin of [aSteadyLevelWritesAgainTheShareARefusedWriteLeftInMemory]. */
    @Test
    fun aSteadyDoubleLevelWritesAgainTheShareARefusedWriteLeftInMemory() = runTest {
        val backing = RecordingStore()
        val store = WriteRefusingStore(backing) { it.name == "otel.metrics.updown.double" }
        val onA = exporter(a, store)
        assertEquals(MetricExportResult.Success, onA.setUpDownSumDouble(inFlight, 0.5))
        store.refuseWrites()
        val refused = onA.setUpDownSumDouble(inFlight, 1.5)
        store.allowWrites()
        val writesBefore = backing.writeCount()
        val retried = onA.setUpDownSumDouble(inFlight, 1.5)
        val writesAfter = backing.writeCount()
        val again = onA.setUpDownSumDouble(inFlight, 1.5)
        val restarted = exporter(a, backing)
        assertAll(
            { assertIs<MetricExportResult.Partial>(refused, "the rig: the store refused the set to 1.5") },
            { assertEquals(1, store.refusedWrites(), "the rig: exactly one write was refused") },
            { assertEquals(MetricExportResult.Success, retried) },
            { assertEquals(writesBefore + 1, writesAfter, "the steady level wrote the share again") },
            { assertEquals(MetricExportResult.Success, again) },
            { assertEquals(writesAfter, backing.writeCount(), "once on disk, the steady level writes nothing") },
            { assertEquals(0L, onA.health.value.outstandingFailures, "the refused turn is resolved") },
            { assertEquals(1.5, restarted.upDownSumDoubleValue(inFlight), "the share is durable") },
        )
    }

    /**
     * Note test 3: a restart recovers this replica's level, and the same level reported again does
     * not count twice: the series still reads 7, where a delta-temporality design would read 14. The
     * first set of the run writes the level once more, at a fresh version, so a peer that saw a
     * newer level the old run never landed on disk is overruled (`WarpMetricExporterUpDownVersionTest`);
     * the next writes nothing.
     */
    @Test
    fun aRestartRecoversTheLevelAndTheSameLevelDoesNotCountTwice() = runTest {
        val store = RecordingStore()
        exporter(a, store).set(inFlight, 7L)
        val restarted = exporter(a, store)
        val recovered = restarted.upDownSumSnapshot(inFlight)
        val writesBefore = store.writeCount()
        restarted.set(inFlight, 7L)
        val afterFirst = store.writeCount()
        restarted.set(inFlight, 7L)
        assertAll(
            { assertEquals(7L, recovered[a], "the rig: the level came back from the store") },
            { assertEquals(7L, restarted.upDownSumValue(inFlight)) },
            { assertEquals(writesBefore + 1, afterFirst, "the run's first set wrote the level again") },
            { assertEquals(afterFirst, store.writeCount(), "the second set wrote nothing") },
            {
                val before = assertNotNull(recovered.tagOf(a))
                assertTrue(assertNotNull(restarted.upDownSumSnapshot(inFlight).tagOf(a)) > before, "at a fresh version")
            },
        )
    }

    /** The same, on an exporter that never called recover: its first turn reads the kind first. */
    @Test
    fun aRestartWithoutRecoverStillReadsTheShareBeforeMovingIt() = runTest {
        val store = RecordingStore()
        exporter(a, store).set(inFlight, 7L)
        val restarted = WarpMetricExporter(replica = a, store = store)
        val before = restarted.upDownSumValue(inFlight)
        restarted.set(inFlight, 7L)
        assertAll(
            { assertEquals(0L, before, "the rig: nothing read yet") },
            { assertEquals(7L, restarted.upDownSumValue(inFlight)) },
        )
    }

    /**
     * A refused read of an up-down kind admits no set of it (#3390, #3453, for the new kinds). A set
     * applied to the fresh map a refused read leaves would start this replica's slot again from
     * nothing beside the durable one, and its write would replace the durable share: a share of 7
     * read back as 3. One arm per map; each asserts its rig refused the read and that the durable
     * share was there to lose.
     */
    @Test
    fun aRefusedReadOfEitherUpDownKindAdmitsNoSetAndLeavesTheDurableShare() = runTest {
        class Arm(val storeKey: String, val seed: suspend WarpMetricExporter.() -> Unit, val update: MetricUpdate, val read: (WarpMetricExporter) -> Double)
        val arms = listOf(
            Arm("otel.metrics.updown", { set(inFlight, 7L) }, MetricUpdate.SetUpDownSum(inFlight, 3L)) {
                it.upDownSumValue(inFlight).toDouble()
            },
            Arm("otel.metrics.updown.double", { setUpDownSumDouble(inFlight, 7.0) }, MetricUpdate.SetUpDownSumDouble(inFlight, 3.0)) {
                it.upDownSumDoubleValue(inFlight)
            },
        )
        val checks = arms.flatMap { arm ->
            val backing = RecordingStore()
            exporter(a, backing).apply { arm.seed(this) }
            val store = ReadRefusingStore(backing, StoreKey(arm.storeKey))
            // No recover: the kind's first turn reads it, and the store refuses that read.
            val onA = WarpMetricExporter(replica = a, store = store)
            val writesBefore = backing.operations().count { it.kind == StoreOpKind.WRITE && it.key.name == arm.storeKey }
            val result = onA.record(listOf(arm.update))
            val writes = backing.operations().count { it.kind == StoreOpKind.WRITE && it.key.name == arm.storeKey } - writesBefore
            val inMemory = arm.read(onA)
            val restarted = arm.read(exporter(a, backing))
            listOf<() -> Unit>(
                { assertEquals(1, store.refusals, "${arm.storeKey}: the rig refused ${store.refusals} reads, not 1") },
                {
                    val partial = assertIs<MetricExportResult.Partial>(result, "${arm.storeKey}: a set over an unread kind reported $result")
                    assertEquals(listOf(arm.update), partial.uncounted, "${arm.storeKey}: the set was counted")
                },
                { assertEquals(0.0, inMemory, "${arm.storeKey}: the set was applied to the map the refused read left") },
                { assertEquals(0, writes, "${arm.storeKey}: the kind was written while its read was refused") },
                { assertEquals(7.0, restarted, "${arm.storeKey}: the durable share was replaced") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    /** Note test 4: an evicted series comes back at the level, not at the change. */
    @Test
    fun anEvictedSeriesComesBackAtTheLevel() = runTest {
        val onA = exporter(a, maxMetrics = 1)
        onA.set(inFlight, 5L)
        onA.set(other, 1L)
        val evicted = inFlight !in onA.snapshotAll().upDownSums
        onA.set(inFlight, 5L)
        assertAll(
            { assertTrue(evicted, "the rig: the cap evicted the series") },
            { assertEquals(5L, onA.upDownSumValue(inFlight)) },
        )
    }

    @Test
    fun addMovesTheShareByTheChange() = runTest {
        val onA = exporter(a)
        assertEquals(MetricExportResult.Success, onA.addUpDownSum(inFlight, 3L))
        assertEquals(MetricExportResult.Success, onA.addUpDownSum(inFlight, -5L))
        assertEquals(MetricExportResult.Success, onA.record(listOf(MetricUpdate.SetUpDownSum(inFlight, 4L))))
        assertAll(
            { assertEquals(4L, onA.upDownSumValue(inFlight)) },
            { assertEquals(4L, onA.upDownSumSnapshot(inFlight)[a]) },
        )
    }

    // ── Doubles ─────────────────────────────────────────────────────────────────────────────

    /** Note test 6: `+1.5 −0.25` reads 1.25, and an unchanged double level writes nothing. */
    @Test
    fun aDoubleLevelMovesBothWaysAndAnUnchangedOneWritesNothing() = runTest {
        val store = RecordingStore()
        val onA = exporter(a, store)
        assertEquals(MetricExportResult.Success, onA.addUpDownSumDouble(inFlight, 1.5))
        assertEquals(MetricExportResult.Success, onA.addUpDownSumDouble(inFlight, -0.25))
        val afterAdds = onA.upDownSumDoubleValue(inFlight)
        assertEquals(MetricExportResult.Success, onA.setUpDownSumDouble(inFlight, 0.3))
        val afterFirstSet = store.writeCount()
        repeat(3) { assertEquals(MetricExportResult.Success, onA.setUpDownSumDouble(inFlight, 0.3)) }
        assertAll(
            { assertEquals(1.25, afterAdds) },
            { assertEquals(0.3, onA.upDownSumDoubleValue(inFlight), "the level is stored exactly") },
            { assertEquals(afterFirstSet, store.writeCount(), "re-setting 0.3 wrote nothing after the first") },
        )
    }

    /** A level is stored as given, so a steady one matches exactly, and `-0.0` is the same level as `0.0`. */
    @Test
    fun aSteadyDoubleLevelIsAnExactMatchAndNegativeZeroIsZero() = runTest {
        val store = RecordingStore()
        val onA = exporter(a, store)
        val levels = listOf(0.1, -7.3, 1e300, Double.MIN_VALUE, 0.0)
        val start = store.writeCount()
        val unsettled = levels.filter { level ->
            onA.setUpDownSumDouble(inFlight, level)
            val written = store.writeCount()
            onA.setUpDownSumDouble(inFlight, level)
            store.writeCount() != written
        }
        val beforeNegativeZero = store.writeCount()
        onA.setUpDownSumDouble(inFlight, -0.0)
        assertAll(
            { assertEquals(levels.size, beforeNegativeZero - start, "the rig: each new level wrote once") },
            { assertEquals(emptyList<Double>(), unsettled, "levels that wrote again when set again") },
            { assertEquals(beforeNegativeZero, store.writeCount(), "-0.0 after 0.0 wrote") },
            { assertEquals(0.0.toRawBits(), onA.upDownSumDoubleSnapshot(inFlight)[a]?.toRawBits(), "stored as 0.0") },
        )
    }

    // ── What each update refuses at construction (note test 7) ──────────────────────────────

    @Test
    fun updatesRefuseWhatTheirCounterCannotHold() {
        val arms: List<() -> Unit> = listOf<() -> Unit>(
            { assertFailsWith<IllegalArgumentException> { MetricUpdate.AddUpDownSum(inFlight, 0L) }; Unit },
            { assertFailsWith<IllegalArgumentException> { MetricUpdate.AddUpDownSum(inFlight, Long.MIN_VALUE) }; Unit },
        ) + listOf(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).map { by ->
            { assertFailsWith<IllegalArgumentException>("add $by") { MetricUpdate.AddUpDownSumDouble(inFlight, by) }; Unit }
        } + listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).map { level ->
            { assertFailsWith<IllegalArgumentException>("set $level") { MetricUpdate.SetUpDownSumDouble(inFlight, level) }; Unit }
        }
        assertAll(*arms.toTypedArray())
    }

    // ── Overflow ────────────────────────────────────────────────────────────────────────────

    /** An add that would carry this replica's level past [Long.MAX_VALUE] is refused whole. */
    @Test
    fun anAddThatWouldOverflowTheLevelIsRefusedWhole() = runTest {
        val store = RecordingStore()
        val onA = exporter(a, store)
        onA.set(inFlight, Long.MAX_VALUE)
        val writesBefore = store.writeCount()
        val refused = onA.record(listOf(MetricUpdate.SetUpDownSum(other, 1L), MetricUpdate.AddUpDownSum(inFlight, 1L)))
        assertAll(
            { assertEquals(Long.MAX_VALUE, onA.upDownSumValue(inFlight), "the rig: the level is at Long.MAX_VALUE") },
            { assertEquals(inFlight, assertIs<MetricUpDownSumOverflowException>(assertIs<MetricExportResult.Refused>(refused).overflow).key) },
            { assertNull(onA.snapshotAll().upDownSums[other], "nothing of the batch was applied") },
            { assertEquals(writesBefore, store.writeCount(), "nothing was written") },
        )
    }

    /**
     * Two replicas' levels that each fit can add up past [Long.MAX_VALUE], which the bridge cannot
     * export. A set that would leave the series there is refused; one that brings it back is not.
     */
    @Test
    fun aSetThatWouldLeaveTheTotalPastLongIsRefusedAndOneThatBringsItBackLands() = runTest {
        val onA = exporter(a)
        val onB = exporter(b).also { it.set(inFlight, Long.MAX_VALUE) }
        onA.mergeUpDownSum(inFlight, onB.upDownSumSnapshot(inFlight))
        val refused = onA.setUpDownSum(inFlight, 1L)
        val landed = onA.setUpDownSum(inFlight, -1L)
        assertAll(
            { assertIs<MetricUpDownSumOverflowException>(assertIs<MetricExportResult.Refused>(refused).overflow) },
            { assertEquals(MetricExportResult.Success, landed) },
            { assertEquals(Long.MAX_VALUE - 1L, onA.upDownSumValue(inFlight)) },
        )
    }

    /** A merge is a join and is never refused; a total past Long is skipped by the bridge instead. */
    @Test
    fun aMergedTotalPastLongIsKeptAndTheBridgeSkipsTheSeries() = runTest {
        val onA = exporter(a).also { it.set(inFlight, Long.MAX_VALUE) }
        val onB = exporter(b).also { it.set(inFlight, 1L) }
        val merged = onA.mergeUpDownSum(inFlight, onB.upDownSumSnapshot(inFlight))
        val points = renderMetricPoints(onA.snapshotAll(), nowEpochNanos = 1L, FirstSightings(), FirstSightings(), FirstSightings())
        assertAll(
            { assertEquals(MetricWriteResult.Success, merged) },
            { assertNull(onA.snapshotAll().upDownSums.getValue(inFlight).upDownTotalOrNull(), "the rig: the total passes Long") },
            { assertTrue(points.none { it.key == inFlight }, "skipped, not rendered: $points") },
        )
    }

    // ── Forget ──────────────────────────────────────────────────────────────────────────────

    /** Forget drops exactly the series it names, locally, and forgetting nothing writes nothing. */
    @Test
    fun forgetDropsExactlyItsSeriesAndForgettingNothingWritesNothing() = runTest {
        val store = RecordingStore()
        val onA = exporter(a, store)
        val oldTwin = MetricKey("http.in_flight", MetricKind.GAUGE)
        val realGauge = MetricKey("temperature", MetricKind.GAUGE)
        onA.setGauge(oldTwin, 3.0, 1L)
        onA.setGauge(realGauge, 21.0, 1L)
        val held = onA.snapshotAll().gauges.keys
        assertEquals(MetricExportResult.Success, onA.record(listOf(MetricUpdate.Forget(oldTwin))))
        val afterForget = store.writeCount()
        assertEquals(MetricExportResult.Success, onA.record(listOf(MetricUpdate.Forget(oldTwin))))
        val restarted = exporter(a, store)
        assertAll(
            { assertEquals(setOf(oldTwin, realGauge), held, "the rig: both gauges held") },
            { assertEquals(setOf(realGauge), onA.snapshotAll().gauges.keys) },
            { assertEquals(afterForget, store.writeCount(), "a second forget found nothing and wrote nothing") },
            { assertEquals(setOf(realGauge), restarted.snapshotAll().gauges.keys, "the forget is durable") },
        )
    }

    // ── Persistence (note test 9) ───────────────────────────────────────────────────────────

    @Test
    fun recoverReadsAndClearDeletesBothUpDownKeys() = runTest {
        val store = RecordingStore()
        val onA = exporter(a, store)
        onA.set(inFlight, 2L)
        onA.setUpDownSumDouble(inFlight, 0.5)
        val acknowledged = onA.health.value.acknowledged
        val reads = store.operations().filter { it.kind == StoreOpKind.READ }.map { it.key.name }
        assertEquals(MetricWriteResult.Success, onA.clear())
        val deletes = store.operations().filter { it.kind == StoreOpKind.DELETE }.map { it.key.name }
        assertAll(
            { assertTrue("otel.metrics.updown" in reads && "otel.metrics.updown.double" in reads, "$reads") },
            { assertTrue("otel.metrics.updown" in deletes && "otel.metrics.updown.double" in deletes, "$deletes") },
            { assertEquals(3L, acknowledged, "one health turn per key: the version claim and the two maps") },
            { assertEquals(setOf("otel.metrics.updown.version"), store.keys(), "the clear keeps only the version claim") },
        )
    }
}
