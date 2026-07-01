package us.tractat.kuilt.otel

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DigestTypesTest {
    private fun recId(b: Byte) = ByteString(ByteArray(8) { b })

    @Test
    fun logDigestHoldsRecordIds() {
        val d = LogDigest(setOf(recId(1), recId(2)))
        assertEquals(2, d.recordIds.size)
    }

    @Test
    fun metricDigestHoldsPerKeyVersions() {
        val key = MetricKey("m", MetricKind.SUM)
        val d = MetricDigest(mapOf(key to 42L))
        assertEquals(42L, d.versions[key])
    }

    @Test
    fun sumPointHashChangesWithValue() {
        val key = MetricKey("req", MetricKind.SUM)
        val a = MetricPoint.Sum(key, value = 5L, startEpochNanos = 100L, timeEpochNanos = 200L)
        val b = MetricPoint.Sum(key, value = 6L, startEpochNanos = 100L, timeEpochNanos = 200L)
        assertNotEquals(a.valueHash(), b.valueHash())
    }

    @Test
    fun sumPointHashStableAcrossTime() {
        // The hash is over the OTLP *value*, not the observation time — a re-render at a
        // later timeEpochNanos with the same cumulative total must not force a re-send.
        val key = MetricKey("req", MetricKind.SUM)
        val a = MetricPoint.Sum(key, value = 5L, startEpochNanos = 100L, timeEpochNanos = 200L)
        val b = MetricPoint.Sum(key, value = 5L, startEpochNanos = 100L, timeEpochNanos = 999L)
        assertEquals(a.valueHash(), b.valueHash())
    }

    @Test
    fun doubleSumHashesOnValue() {
        val key = MetricKey("bytes", MetricKind.SUM)
        assertNotEquals(
            MetricPoint.DoubleSum(key, value = 1.5, startEpochNanos = 0L, timeEpochNanos = 1L).valueHash(),
            MetricPoint.DoubleSum(key, value = 2.5, startEpochNanos = 0L, timeEpochNanos = 1L).valueHash(),
        )
    }

    @Test
    fun gaugeAndCardinalityHashOnValue() {
        val gk = MetricKey("cpu", MetricKind.GAUGE)
        assertNotEquals(
            MetricPoint.Gauge(gk, value = 0.5, timeEpochNanos = 1L).valueHash(),
            MetricPoint.Gauge(gk, value = 0.6, timeEpochNanos = 1L).valueHash(),
        )
        val ck = MetricKey("users", MetricKind.CARDINALITY)
        assertNotEquals(
            MetricPoint.Cardinality(ck, estimate = 10L, timeEpochNanos = 1L).valueHash(),
            MetricPoint.Cardinality(ck, estimate = 11L, timeEpochNanos = 1L).valueHash(),
        )
    }
}
