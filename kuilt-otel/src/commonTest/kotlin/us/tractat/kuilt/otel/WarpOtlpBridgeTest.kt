package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun partialFailureIsolatesSignals() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        t.logs.export(LogRecord(recordId = recId(1), body = "hi"))
        val edge = object : FakeEdge() {
            override suspend fun sendLogs(logs: Set<LogRecord>): Unit = throw RuntimeException("logs down")
        }
        // spans still delivered despite logs failing; drain does not throw.
        val result = bridge(t).drain(edge)
        assertEquals(1, edge.sentSpans.size)
        // A partial success still reports what got through.
        assertIs<DrainResult.Success>(result)
        assertEquals(1, result.spansSent)
        assertEquals(0, result.logsSent)
    }

    @Test
    fun drainSurvivesSpanDigestFailure() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        val edge = object : FakeEdge() {
            override suspend fun digest(): SpanDigest = throw RuntimeException("digest down")
        }
        // Must not throw; span leg fails, others still run.
        bridge(t).drain(edge)
    }

    @Test
    fun drainResultSuccessWhenNothingBuffered() = runTest {
        val result = bridge(telemetry()).drain(FakeEdge())
        assertIs<DrainResult.Success>(result)
        assertEquals(0, result.spansSent)
        assertEquals(0, result.logsSent)
        assertEquals(0, result.metricPointsSent)
    }

    @Test
    fun drainDeliversEachMetricKind() = runTest {
        val t = telemetry()
        t.metrics.incrementSum(MetricKey("s", MetricKind.SUM), by = 1L)
        t.metrics.incrementSumDouble(MetricKey("ds", MetricKind.SUM), by = 1.5)
        t.metrics.setGauge(MetricKey("g", MetricKind.GAUGE), value = 0.5, timestamp = 1L)
        t.metrics.addCardinality(MetricKey("c", MetricKind.CARDINALITY), element = "u1")
        val edge = FakeEdge()

        bridge(t).drain(edge)

        assertEquals(1, edge.sentMetrics.filterIsInstance<MetricPoint.Sum>().size)
        assertEquals(1, edge.sentMetrics.filterIsInstance<MetricPoint.DoubleSum>().size)
        assertEquals(1, edge.sentMetrics.filterIsInstance<MetricPoint.Gauge>().size)
        assertEquals(1, edge.sentMetrics.filterIsInstance<MetricPoint.Cardinality>().size)
    }
}
