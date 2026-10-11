package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class WarpOtlpBridgeTest {
    private val replica = ReplicaId("test")
    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    private fun tId(b: Byte) = ByteString(ByteArray(16) { b })
    private fun sId(b: Byte) = ByteString(ByteArray(8) { b })
    private fun recId(b: Byte) = ByteString(ByteArray(8) { b })
    private fun span(b: Byte, parent: ByteString? = null) = SpanRecord(
        traceId = tId(b), spanId = sId(b), parentSpanId = parent,
        name = "op", kind = SpanKind.INTERNAL, startEpochNanos = 1_000L, endEpochNanos = 2_000L,
    )

    private fun telemetry() = WarpTelemetry(replica, InMemoryDurableStore())
    private fun bridge(t: WarpTelemetry) = WarpOtlpBridge(t, clock)

    // ---- all-signal fake ----

    private open class FakeEdge : OtlpEdge {
        private val lock = reentrantLock()
        val knownSpans = mutableSetOf<ByteString>()
        val knownLogs = mutableSetOf<ByteString>()
        val knownMetrics = mutableMapOf<MetricKey, Long>()
        val sentSpans = mutableListOf<SpanRecord>()
        val sentLinks = mutableListOf<SpanLink>()
        val sentLogs = mutableListOf<LogRecord>()
        val sentMetrics = mutableListOf<MetricPoint>()

        override suspend fun digest(): SpanDigest = SpanDigest(lock.withLock { knownSpans.toSet() })
        override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>): Unit = lock.withLock {
            sentSpans += spans; sentLinks += links; knownSpans += spans.map { it.spanId }
        }
        override suspend fun logDigest(): LogDigest = LogDigest(lock.withLock { knownLogs.toSet() })
        override suspend fun sendLogs(logs: Set<LogRecord>): Unit = lock.withLock {
            sentLogs += logs; knownLogs += logs.map { it.recordId }
        }
        override suspend fun metricDigest(): MetricDigest = MetricDigest(lock.withLock { knownMetrics.toMap() })
        override suspend fun sendMetrics(points: Set<MetricPoint>): Unit = lock.withLock {
            sentMetrics += points; points.forEach { knownMetrics[it.key] = it.valueHash() }
        }
    }

    @Test
    fun drainDeliversAllThreeSignals() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        t.logs.export(LogRecord(recordId = recId(1), body = "hi"))
        t.metrics.incrementSum(MetricKey("req", MetricKind.SUM), by = 2L)
        val edge = FakeEdge()

        val result = bridge(t).drain(edge)

        assertIs<DrainResult.Success>(result)
        assertEquals(1, edge.sentSpans.size)
        assertEquals(1, edge.sentLogs.size)
        assertEquals(1, edge.sentMetrics.size)
        assertEquals(1, result.spansSent)
        assertEquals(1, result.logsSent)
        assertEquals(1, result.metricPointsSent)
    }

    @Test
    fun reDrainSendsNothingNewForAllSignals() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        t.logs.export(LogRecord(recordId = recId(1), body = "hi"))
        t.metrics.incrementSum(MetricKey("req", MetricKind.SUM), by = 2L)
        val edge = FakeEdge()
        val b = bridge(t)

        b.drain(edge)
        val before = Triple(edge.sentSpans.size, edge.sentLogs.size, edge.sentMetrics.size)
        b.drain(edge) // idempotent

        assertEquals(before, Triple(edge.sentSpans.size, edge.sentLogs.size, edge.sentMetrics.size))
    }

    @Test
    fun advancedMetricReSendsExactlyOnce() = runTest {
        val t = telemetry()
        val key = MetricKey("req", MetricKind.SUM)
        t.metrics.incrementSum(key, by = 1L)
        val edge = FakeEdge()
        val b = bridge(t)

        b.drain(edge)                        // sends value=1
        b.drain(edge)                        // unchanged → nothing
        t.metrics.incrementSum(key, by = 1L) // value=2
        b.drain(edge)                        // sends value=2

        assertEquals(2, edge.sentMetrics.size)
        assertEquals(listOf(1L, 2L), edge.sentMetrics.filterIsInstance<MetricPoint.Sum>().map { it.value })
    }

    @Test
    fun linksAreInferredAndThreadedToTheEdge() = runTest {
        // Two replicas so a cross-boundary (non-parent) link is inferred.
        val tA = WarpTelemetry(ReplicaId("a"), InMemoryDurableStore())
        tA.spans.export(span(1))
        val tB = WarpTelemetry(ReplicaId("b"), InMemoryDurableStore())
        tB.spans.merge(tA.spans.snapshot())      // B observes A's frontier
        tB.spans.export(span(2))                 // successor, parent=null ≠ A's span ⇒ cross-boundary link
        val edge = FakeEdge()

        bridge(tB).drain(edge)

        assertTrue(edge.sentLinks.any { it.fromSpanId == sId(2) && it.linkedSpanId == sId(1) })
    }

    // ---- per-signal outcome: Success / Partial / Failure (#2524) ----

    private suspend fun telemetryWithAllThreeSignals() = telemetry().apply {
        spans.export(span(1))
        logs.export(LogRecord(recordId = recId(1), body = "hi"))
        metrics.incrementSum(MetricKey("req", MetricKind.SUM), by = 2L)
    }

    @Test
    fun partialFailureIsolatesSignals() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        t.logs.export(LogRecord(recordId = recId(1), body = "hi"))
        val logsDown = RuntimeException("logs down")
        val edge = object : FakeEdge() {
            override suspend fun sendLogs(logs: Set<LogRecord>): Unit = throw logsDown
        }

        val result = bridge(t).drain(edge)

        val partial = assertIs<DrainResult.Partial>(result)
        assertAll(
            { assertEquals(1, edge.sentSpans.size, "spans are delivered despite logs failing") },
            { assertEquals(1, partial.spansSent, "a partial result reports what got through") },
            { assertEquals(0, partial.logsSent) },
            { assertEquals(listOf(OtlpSignal.LOGS), partial.failures.map { it.signal }) },
            { assertSame(logsDown, partial.failures.single().cause, "the edge's own exception, unwrapped") },
        )
    }

    @Test
    fun mixedFailureAttributesEachCauseToItsSignal() = runTest {
        val spanDigestDown = RuntimeException("span digest down")
        val metricsDown = IllegalStateException("metrics down")
        val edge = object : FakeEdge() {
            override suspend fun digest(): SpanDigest = throw spanDigestDown
            override suspend fun sendMetrics(points: Set<MetricPoint>): Unit = throw metricsDown
        }

        val result = bridge(telemetryWithAllThreeSignals()).drain(edge)

        val partial = assertIs<DrainResult.Partial>(result)
        assertAll(
            { assertEquals(0, partial.spansSent) },
            { assertEquals(1, partial.logsSent, "the surviving signal is delivered and counted") },
            { assertEquals(0, partial.metricPointsSent) },
            { assertEquals(listOf(OtlpSignal.SPANS, OtlpSignal.METRICS), partial.failures.map { it.signal }) },
            // Throwable equality is identity, so this compares the edge's own instances.
            { assertEquals(listOf<Throwable>(spanDigestDown, metricsDown), partial.failures.map { it.cause }) },
        )
    }

    @Test
    fun allSignalsFailingKeepsEveryCause() = runTest {
        val spansDown = RuntimeException("spans down")
        val logsDown = RuntimeException("logs down")
        val metricsDown = RuntimeException("metrics down")
        val edge = object : FakeEdge() {
            override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>): Unit = throw spansDown
            override suspend fun logDigest(): LogDigest = throw logsDown
            override suspend fun metricDigest(): MetricDigest = throw metricsDown
        }

        val result = bridge(telemetryWithAllThreeSignals()).drain(edge)

        val failure = assertIs<DrainResult.Failure>(result)
        assertAll(
            {
                assertEquals(
                    listOf(OtlpSignal.SPANS, OtlpSignal.LOGS, OtlpSignal.METRICS),
                    failure.failures.map { it.signal },
                )
            },
            // Throwable equality is identity, so this compares the edge's own instances.
            { assertEquals(listOf<Throwable>(spansDown, logsDown, metricsDown), failure.failures.map { it.cause }) },
        )
    }

    @Test
    fun aLoneFailingSignalIsAFailureNamingIt() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        val digestDown = RuntimeException("digest down")
        val edge = object : FakeEdge() {
            override suspend fun digest(): SpanDigest = throw digestDown
        }

        // Must not throw; unbuffered logs and metrics are not attempted, so they cannot count as succeeding.
        val result = bridge(t).drain(edge)

        assertEquals(DrainResult.Failure(listOf(SignalFailure(OtlpSignal.SPANS, digestDown))), result)
    }

    @Test
    fun upToDateSignalPlusAFailureIsPartialNotFailure() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        t.logs.export(LogRecord(recordId = recId(1), body = "hi"))
        val logsDown = RuntimeException("logs down")
        val edge = object : FakeEdge() {
            override suspend fun sendLogs(logs: Set<LogRecord>): Unit = throw logsDown
        }
        edge.knownSpans += sId(1) // the edge already holds the span, so the span leg sends nothing

        val result = bridge(t).drain(edge)

        // Zero records sent is not a failure: the span leg succeeded, it was simply up to date.
        assertAll(
            { assertEquals(0, edge.sentSpans.size) },
            {
                assertEquals(
                    DrainResult.Partial(
                        spansSent = 0,
                        logsSent = 0,
                        metricPointsSent = 0,
                        failures = listOf(SignalFailure(OtlpSignal.LOGS, logsDown)),
                    ),
                    result,
                )
            },
        )
    }

    @Test
    fun drainResultSuccessWhenNothingBuffered() = runTest {
        var edgeCalls = 0
        val edge = object : FakeEdge() {
            override suspend fun digest(): SpanDigest = super.digest().also { edgeCalls++ }
            override suspend fun logDigest(): LogDigest = super.logDigest().also { edgeCalls++ }
            override suspend fun metricDigest(): MetricDigest = super.metricDigest().also { edgeCalls++ }
        }

        val result = bridge(telemetry()).drain(edge)

        assertAll(
            { assertEquals(DrainResult.Success(spansSent = 0, logsSent = 0, metricPointsSent = 0), result) },
            { assertEquals(0, edgeCalls, "an empty buffer attempts no signal") },
        )
    }

    @Test
    fun aFailureResultMustNameAFailedSignalOnce() {
        val boom = RuntimeException("boom")
        assertAll(
            { assertFailsWith<IllegalArgumentException> { DrainResult.Failure(emptyList()) } },
            { assertFailsWith<IllegalArgumentException> { DrainResult.Partial(1, 0, 0, emptyList()) } },
            {
                assertFailsWith<IllegalArgumentException> {
                    DrainResult.Failure(listOf(SignalFailure(OtlpSignal.LOGS, boom), SignalFailure(OtlpSignal.LOGS, boom)))
                }
            },
        )
    }

    @Test
    fun drainDeliversEachMetricKind() = runTest {
        val t = telemetry()
        t.metrics.incrementSum(MetricKey("s", MetricKind.SUM), by = 1L)
        t.metrics.incrementSumDouble(MetricKey("ds", MetricKind.SUM), by = 1.5)
        t.metrics.setGauge(MetricKey("g", MetricKind.GAUGE), value = 0.5, timestamp = 1L)
        t.metrics.addCardinality(MetricKey("c", MetricKind.CARDINALITY), element = "u1")
        t.metrics.recordHistogram(MetricKey("h", MetricKind.EXPONENTIAL_HISTOGRAM), value = 12.5)
        val edge = FakeEdge()

        bridge(t).drain(edge)

        assertEquals(1, edge.sentMetrics.filterIsInstance<MetricPoint.Sum>().size)
        assertEquals(1, edge.sentMetrics.filterIsInstance<MetricPoint.DoubleSum>().size)
        assertEquals(1, edge.sentMetrics.filterIsInstance<MetricPoint.Gauge>().size)
        assertEquals(1, edge.sentMetrics.filterIsInstance<MetricPoint.Cardinality>().size)
        assertEquals(1, edge.sentMetrics.filterIsInstance<MetricPoint.ExponentialHistogram>().size)
    }

    @Test
    fun advancedHistogramReSendsExactlyOnce() = runTest {
        val t = telemetry()
        val key = MetricKey("lat", MetricKind.EXPONENTIAL_HISTOGRAM)
        t.metrics.recordHistogram(key, 10.0)
        val edge = FakeEdge()
        val b = bridge(t)

        b.drain(edge)                       // sends count=1
        b.drain(edge)                       // unchanged sketch → nothing
        t.metrics.recordHistogram(key, 20.0)
        b.drain(edge)                       // sends count=2

        val sent = edge.sentMetrics.filterIsInstance<MetricPoint.ExponentialHistogram>()
        assertEquals(listOf(1L, 2L), sent.map { it.count })
    }
}
