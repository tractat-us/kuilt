package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricCatalogTest {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private fun sk(n: String) = MetricKey(n, MetricKind.SUM)

    @Test
    fun pieceUnionsKeysAndJoinsValues() {
        val left = MetricCatalog(sums = mapOf(sk("x") to GCounter.of(a to 1L)))
        val right = MetricCatalog(sums = mapOf(sk("x") to GCounter.of(b to 2L), sk("y") to GCounter.of(a to 5L)))
        val merged = left.piece(right)
        assertEquals(3L, merged.sums.getValue(sk("x")).value) // a=1,b=2
        assertEquals(5L, merged.sums.getValue(sk("y")).value)
        assertEquals(merged, right.piece(left)) // commutative
        assertEquals(merged, merged.piece(right)) // idempotent
    }

    @Test
    fun pieceMergesDoubleSums() {
        val left = MetricCatalog(doubleSums = mapOf(sk("cpu") to GCounterDouble.of(a to 1.5)))
        val right = MetricCatalog(doubleSums = mapOf(sk("cpu") to GCounterDouble.of(b to 2.5)))
        assertEquals(4.0, left.piece(right).doubleSums.getValue(sk("cpu")).value)
    }

    @Test
    fun snapshotAllReflectsEveryStore() = runTest {
        val exporter = WarpMetricExporter(replica = a, store = InMemoryDurableStore())
        exporter.incrementSum(MetricKey("req", MetricKind.SUM), by = 4L)
        exporter.incrementSumDouble(MetricKey("cpu", MetricKind.SUM), by = 2.5)
        exporter.setGauge(MetricKey("temp", MetricKind.GAUGE), 21.0, timestamp = 1L)
        exporter.addCardinality(MetricKey("users", MetricKind.CARDINALITY), "u1")
        val cat = exporter.snapshotAll()
        assertEquals(4L, cat.sums.getValue(MetricKey("req", MetricKind.SUM)).value)
        assertEquals(2.5, cat.doubleSums.getValue(MetricKey("cpu", MetricKind.SUM)).value)
        assertEquals(21.0, cat.gauges.getValue(MetricKey("temp", MetricKind.GAUGE)).value)
        assertEquals(1L, cat.cardinalities.getValue(MetricKey("users", MetricKind.CARDINALITY)).estimate())
    }
}
