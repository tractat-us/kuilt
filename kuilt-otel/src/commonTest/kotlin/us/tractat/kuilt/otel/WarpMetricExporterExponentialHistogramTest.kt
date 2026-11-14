package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [MetricUpdate.AddExponentialHistogram]: one interval of already-bucketed measurements, in the
 * OTLP shape, added to a [WarpMetricExporter] histogram series (#3291).
 */
class WarpMetricExporterExponentialHistogramTest {

    private val replica = ReplicaId("A")
    private val key = MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM)

    private fun exporter(store: DurableStore = InMemoryDurableStore()) = WarpMetricExporter(replica = replica, store = store)

    /** A value in the middle of OTLP bucket [index] at [scale]: `base^(index + ½)`. */
    private fun midpointOf(index: Int, scale: Int): Double = 2.0.pow((index + 0.5) / 2.0.pow(scale))

    @Test
    fun theDefaultScaleIsTheOneTheSketchesAreKeptAt() {
        assertEquals(DEFAULT_OTLP_HISTOGRAM_SCALE, exporter().histogramScale)
    }

    @Test
    fun aFinerScaleDownscalesExactlyWithAnArithmeticShift() = runTest {
        val bridged = exporter()
        val t = bridged.histogramScale
        // Odd negative indices are where floor (the shift) and truncation (division) disagree.
        bridged.record(
            listOf(
                MetricUpdate.AddExponentialHistogram(
                    key, scale = t + 1, zeroCount = 0L,
                    positiveOffset = -3, positiveCounts = listOf(1L),
                    negativeOffset = -3, negativeCounts = listOf(1L),
                ),
                MetricUpdate.AddExponentialHistogram(
                    key, scale = t + 1, zeroCount = 0L,
                    positiveOffset = 5, positiveCounts = listOf(2L),
                    negativeOffset = 0, negativeCounts = emptyList(),
                ),
                MetricUpdate.AddExponentialHistogram(
                    key, scale = t + 3, zeroCount = 0L,
                    positiveOffset = -5, positiveCounts = listOf(1L),
                    negativeOffset = 0, negativeCounts = emptyList(),
                ),
            ),
        )
        // Reference: the same values recorded one at a time, so the sketch indexes each by its value.
        val reference = exporter()
        listOf(
            midpointOf(-3, t + 1),
            -midpointOf(-3, t + 1),
            midpointOf(5, t + 1),
            midpointOf(5, t + 1),
            midpointOf(-5, t + 3),
        ).forEach { reference.recordHistogram(key, it) }

        val expected = reference.histogramSnapshot(key)
        assertAll(
            { assertEquals(5L, expected.count, "precondition: the reference holds every value") },
            {
                // OTLP -3 at t+1 lies in OTLP -2 at t (sketch index -1); truncation would say -1 (index 0).
                assertTrue(-1 in expected.positiveBuckets && -1 in expected.negativeBuckets, "precondition: reference floors")
            },
            { assertEquals(expected, bridged.histogramSnapshot(key)) },
        )
    }

    @Test
    fun theExportersOwnScaleRoundTripsThroughTheOtlpRender() = runTest {
        val exp = exporter()
        val update = MetricUpdate.AddExponentialHistogram(
            key, scale = exp.histogramScale, zeroCount = 2L,
            positiveOffset = -4, positiveCounts = listOf(1L, 0L, 3L),
            negativeOffset = 7, negativeCounts = listOf(2L),
        )
        val result = exp.record(listOf(update))
        val point = exp.histogramSnapshot(key).toExponentialHistogramPoint(key, startEpochNanos = 0L, timeEpochNanos = 1L)
        assertAll(
            { assertIs<MetricExportResult.Success>(result) },
            { assertEquals(8L, point.count, "precondition: every count landed") },
            { assertEquals(update.scale, point.scale) },
            { assertEquals(update.zeroCount, point.zeroCount) },
            { assertEquals(update.positiveOffset, point.positiveOffset) },
            { assertEquals(update.positiveCounts, point.positiveBucketCounts) },
            { assertEquals(update.negativeOffset, point.negativeOffset) },
            { assertEquals(update.negativeCounts, point.negativeBucketCounts) },
        )
    }

    @Test
    fun aBatchHoldingACoarserScaleIsRefusedWholeWithNothingApplied() = runTest {
        val store = RecordingStore()
        val exp = exporter(store)
        val requests = MetricKey("requests", MetricKind.SUM)
        val valid = listOf(MetricUpdate.IncrementSum(requests, 3L), MetricUpdate.RecordHistogram(key, 12.5))
        val coarse = MetricUpdate.AddExponentialHistogram(
            key, scale = exp.histogramScale - 1, zeroCount = 0L,
            positiveOffset = 0, positiveCounts = listOf(4L),
            negativeOffset = 0, negativeCounts = emptyList(),
        )

        // The coarse update is LAST, so a check made while applying would already have applied the rest.
        assertFailsWith<IllegalArgumentException>("the coarse update refuses the batch") { exp.record(valid + coarse) }
        val metricsAfterRefusal = exp.metricCount()
        val histogramAfterRefusal = exp.histogramSnapshot(key).count
        val opsAfterRefusal = store.operations().size
        val healthAfterRefusal = exp.health.value
        // Control: the valid updates alone apply and write, so the rig can see a change.
        val control = exp.record(valid)

        assertAll(
            { assertEquals(0, metricsAfterRefusal, "no series created") },
            { assertEquals(0L, histogramAfterRefusal, "no histogram value recorded") },
            { assertEquals(0, opsAfterRefusal, "no store write") },
            { assertEquals(PersistenceHealth(), healthAfterRefusal, "no persistence turn") },
            { assertIs<MetricExportResult.Success>(control) },
            { assertEquals(2, exp.metricCount(), "control: the valid updates do apply") },
            { assertTrue(store.operations().isNotEmpty(), "control: and do write") },
        )
    }

    @Test
    fun deliveringTheSameIntervalTwiceCountsItTwice() = runTest {
        val exp = exporter()
        val update = MetricUpdate.AddExponentialHistogram(
            key, scale = exp.histogramScale, zeroCount = 1L,
            positiveOffset = 10, positiveCounts = listOf(2L),
            negativeOffset = 0, negativeCounts = emptyList(),
        )
        exp.record(listOf(update))
        val once = exp.histogramSnapshot(key).count
        exp.record(listOf(update))
        assertAll(
            { assertEquals(3L, once, "precondition: one delivery landed") },
            { assertEquals(6L, exp.histogramSnapshot(key).count, "a delta added again, as IncrementSum is") },
        )
    }

    @Test
    fun aRecoveredSeriesAtAnotherScaleIsDroppedSoBucketsLandAtTheExportersScale() = runTest {
        // A deployment that moved its prototype from scale 4 to scale 5 across a restart.
        val store = InMemoryDurableStore()
        val before = WarpMetricExporter(
            replica = replica,
            store = store,
            histogramPrototype = us.tractat.kuilt.crdt.DDSketch.empty(relativeAccuracy = alphaForOtlpScale(4)),
        )
        before.recordHistogram(key, 3.0)
        val after = exporter(store).also { it.recover() }
        val t = after.histogramScale
        after.record(
            listOf(
                MetricUpdate.AddExponentialHistogram(
                    key, scale = t, zeroCount = 0L,
                    positiveOffset = 32, positiveCounts = listOf(1L),
                    negativeOffset = 0, negativeCounts = emptyList(),
                ),
            ),
        )
        // Reference: the same value recorded at the exporter's own scale.
        val reference = exporter().also { it.recordHistogram(key, midpointOf(32, t)) }

        assertAll(
            { assertEquals(4, otlpScaleFor(before.histogramSnapshot(key).relativeAccuracy), "precondition: written at scale 4") },
            { assertEquals(5, t, "precondition: recovered by a scale-5 exporter") },
            { assertEquals(reference.histogramSnapshot(key), after.histogramSnapshot(key), "OTLP 32 at scale 5 is ~2.0, not ~4.0") },
        )
    }

    @Test
    fun mergingASketchOfAnotherConfigurationIsRefused() = runTest {
        val exp = exporter()
        val scale4 = us.tractat.kuilt.crdt.DDSketch.empty(relativeAccuracy = alphaForOtlpScale(4))
        val otherRange = us.tractat.kuilt.crdt.DDSketch.empty(
            relativeAccuracy = alphaForOtlpScale(exp.histogramScale),
            maxIndexedValue = 1e12,
        )
        assertFailsWith<IllegalArgumentException>("another scale") {
            exp.mergeHistogram(key, scale4.piece(scale4.add(replica, 3.0).delta))
        }
        assertFailsWith<IllegalArgumentException>("another range") {
            exp.mergeHistogram(key, otherRange.piece(otherRange.add(replica, 3.0).delta))
        }
        assertEquals(0, exp.metricCount(), "nothing installed")
    }

    @Test
    fun constructionRejectsWhatNoExporterCouldApply() {
        fun update(scale: Int = 5, zeroCount: Long = 0L, positive: List<Long> = emptyList(), negative: List<Long> = emptyList()) =
            MetricUpdate.AddExponentialHistogram(key, scale, zeroCount, 0, positive, 0, negative)
        assertAll(
            { update(scale = -10) },
            { update(scale = 20) },
            { assertFailsWith<IllegalArgumentException> { update(scale = -11) } },
            { assertFailsWith<IllegalArgumentException> { update(scale = 21) } },
            { assertFailsWith<IllegalArgumentException> { update(zeroCount = -1L) } },
            { assertFailsWith<IllegalArgumentException> { update(positive = listOf(1L, -1L)) } },
            { assertFailsWith<IllegalArgumentException> { update(negative = listOf(-1L)) } },
        )
    }
}
