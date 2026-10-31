package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class HealthStore : DurableStore {
    private val backing = InMemoryDurableStore()
    val cause = IllegalStateException("persistence refused")
    var refusedWrites: Set<StoreKey> = emptySet()
    var refusedReads: Set<StoreKey> = emptySet()
    var corruptReads: Set<StoreKey> = emptySet()
    var refusedDelete: StoreKey? = null
    var writes = 0
    var writeFailures = 0
    var readFailures = 0
    var deleteFailures = 0
    var deletes = 0

    override suspend fun read(key: StoreKey): ByteArray? {
        if (key in refusedReads) {
            readFailures++
            throw cause
        }
        return if (key in corruptReads) byteArrayOf(0xff.toByte()) else backing.read(key)
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        writes++
        if (key in refusedWrites) {
            writeFailures++
            throw cause
        }
        backing.write(key, bytes)
    }

    override suspend fun delete(key: StoreKey) {
        deletes++
        if (key == refusedDelete) {
            deleteFailures++
            throw cause
        }
        backing.delete(key)
    }
}

internal fun healthSpan(): SpanRecord = SpanRecord(
    traceId = ByteString(ByteArray(16) { 1 }),
    spanId = ByteString(ByteArray(8) { 2 }),
    parentSpanId = null,
    name = "health",
    kind = SpanKind.INTERNAL,
    startEpochNanos = 1L,
    endEpochNanos = 2L,
)

class PersistenceHealthTest {
    private val replica = ReplicaId("health")
    private val spanKey = StoreKey("otel.spans")
    private val metricKeys = listOf(
        "otel.metrics.sums", "otel.metrics.sums.double", "otel.metrics.gauges",
        "otel.metrics.cardinalities", "otel.metrics.histograms",
    ).map(::StoreKey)

    @Test
    fun spanExportMergeAndClearAccountWholeTurns() = runTest {
        val store = HealthStore()
        val exporter = WarpSpanExporter(replica, store)
        val turns: List<suspend () -> ExportResult> = listOf(
            { exporter.export(healthSpan()) },
            { exporter.merge(ORSet.empty()) },
            { exporter.clear() },
        )
        turns.forEachIndexed { index, turn ->
            store.refusedWrites = setOf(spanKey)
            assertIs<ExportResult.Failure>(turn())
            val failed = exporter.health.value
            assertAll(
                { assertEquals((index + 1).toLong(), failed.failed) },
                { assertEquals(1L, failed.outstandingFailures) },
                { assertEquals(index.toLong(), failed.acknowledged) },
                { assertSame(store.cause, failed.lastFailure) },
            )
            store.refusedWrites = emptySet()
            assertEquals(ExportResult.Success, turn())
            assertAll(
                { assertEquals((index + 1).toLong(), exporter.health.value.acknowledged) },
                { assertEquals(0L, exporter.health.value.outstandingFailures) },
                { assertSame(store.cause, exporter.health.value.lastFailure) },
            )
        }
        assertAll(
            { assertEquals(turns.size, store.writeFailures, "each refusal rig fired") },
            { assertEquals(turns.size * 2, store.writes) },
        )
    }

    @Test
    fun aPartialClockAndSpanTurnIsNeverAcknowledged() = runTest {
        // Refuse each stage in turn: the clock itself, then spans after the clock landed.
        for (refused in listOf(WarpCausalClock.STORE_KEY, spanKey)) {
            val store = HealthStore()
            val telemetry = WarpTelemetry(replica, store)
            store.refusedWrites = setOf(refused)
            assertIs<ExportResult.Failure>(telemetry.spans.export(healthSpan()))
            val afterExport = telemetry.health.spans
            assertIs<ExportResult.Failure>(telemetry.spans.clear())
            assertAll(
                { assertEquals(0L, afterExport.acknowledged) },
                { assertEquals(1L, afterExport.failed) },
                { assertEquals(2L, telemetry.health.spans.outstandingFailures) },
                { assertEquals(2, store.writeFailures, "both turns reached the refused stage") },
            )
            store.refusedWrites = emptySet()
            telemetry.spans.merge(ORSet.empty())
            assertEquals(2L, telemetry.health.spans.outstandingFailures,
                "a span-only write cannot acknowledge an incomplete clock-and-span turn")
            telemetry.spans.clear()
            assertAll(
                { assertEquals(2L, telemetry.health.spans.acknowledged, "one per turn, not per write") },
                { assertEquals(0L, telemetry.health.spans.outstandingFailures) },
            )
        }
    }

    private fun metricTurns(exporter: WarpMetricExporter): List<suspend () -> MetricExportResult> {
        val sum = MetricKey("sum", MetricKind.SUM)
        val gauge = MetricKey("gauge", MetricKind.GAUGE)
        val cardinality = MetricKey("cardinality", MetricKind.CARDINALITY)
        val histogram = MetricKey("histogram", MetricKind.EXPONENTIAL_HISTOGRAM)
        return listOf(
            { exporter.incrementSum(sum) },
            { exporter.incrementSumDouble(sum, 0.5) },
            { exporter.setGauge(gauge, 1.0, 1L) },
            { exporter.addCardinality(cardinality, "user") },
            { exporter.recordHistogram(histogram, 1.0) },
            { exporter.mergeSum(sum, exporter.sumSnapshot(sum)) },
            { exporter.mergeSumDouble(sum, exporter.doubleSumSnapshot(sum)) },
            { exporter.mergeGauge(gauge, exporter.gaugeSnapshot(gauge)) },
            { exporter.mergeCardinality(cardinality, exporter.cardinalitySnapshot(cardinality)) },
            { exporter.mergeHistogram(histogram, exporter.histogramSnapshot(histogram)) },
        )
    }

    @Test
    fun everyMetricWritePathAcknowledgesOnlyItsOwnRecovery() = runTest {
        val store = HealthStore()
        val exporter = WarpMetricExporter(replica, store)
        val turns = metricTurns(exporter)
        store.refusedWrites = metricKeys.toSet()
        turns.forEach { assertIs<MetricExportResult.Failure>(it()) }
        assertAll(
            { assertEquals(turns.size, store.writeFailures, "all write paths reached the rig") },
            { assertEquals(turns.size.toLong(), exporter.health.value.failed) },
            { assertEquals(turns.size.toLong(), exporter.health.value.outstandingFailures) },
            { assertEquals(0L, exporter.health.value.acknowledged) },
        )
        store.refusedWrites = emptySet()
        // Both the mutation and merge failed for each stored kind. In particular, success on
        // integer SUM must not clear DOUBLE_SUM even when both were called with the same MetricKey.
        turns.take(metricKeys.size).forEachIndexed { index, turn ->
            assertEquals(MetricExportResult.Success, turn())
            assertEquals((turns.size - (index + 1) * 2).toLong(), exporter.health.value.outstandingFailures)
        }
        turns.drop(metricKeys.size).forEach { assertEquals(MetricExportResult.Success, it()) }
        assertAll(
            { assertEquals(turns.size.toLong(), exporter.health.value.acknowledged) },
            { assertEquals(turns.size.toLong(), exporter.health.value.failed) },
            { assertSame(store.cause, exporter.health.value.lastFailure) },
        )
    }

    @Test
    fun metricClearIsOneSeparateTurnEvenWhenOnlyItsLastDeleteFails() = runTest {
        val store = HealthStore()
        val exporter = WarpMetricExporter(replica, store)
        val turn = metricTurns(exporter).first()
        store.refusedWrites = setOf(metricKeys.first())
        assertIs<MetricExportResult.Failure>(turn())
        store.refusedDelete = metricKeys.last()
        assertIs<MetricExportResult.Failure>(exporter.clear())
        assertAll(
            { assertEquals(metricKeys.size, store.deletes, "the partial clear reached its last delete") },
            { assertEquals(1, store.deleteFailures) },
            { assertEquals(2L, exporter.health.value.failed) },
            { assertEquals(0L, exporter.health.value.acknowledged) },
        )
        store.refusedDelete = null
        assertEquals(MetricExportResult.Success, exporter.clear())
        assertAll(
            { assertEquals(1L, exporter.health.value.acknowledged) },
            { assertEquals(1L, exporter.health.value.outstandingFailures, "deletes do not prove writes work") },
        )
        store.refusedWrites = emptySet()
        turn()
        assertEquals(0L, exporter.health.value.outstandingFailures)
    }

    @Test
    fun recoveryReadAndDecodeFailuresAreStickyForEveryBufferAndTheClock() = runTest {
        for (key in metricKeys + spanKey + WarpCausalClock.STORE_KEY + StoreKey("otel.logs.idx")) {
            for (corrupt in listOf(false, true)) {
                val store = HealthStore()
                if (corrupt) store.corruptReads = setOf(key) else store.refusedReads = setOf(key)
                val telemetry = WarpTelemetry(replica, store)
                telemetry.recover()
                val health = telemetry.health
                val log = key.name.startsWith("otel.logs")
                val metric = key in metricKeys
                assertAll(
                    { assertEquals(!log && !metric, health.spans.recoveryFailed, "$key corrupt=$corrupt") },
                    { assertEquals(metric, health.metrics.recoveryFailed, "$key corrupt=$corrupt") },
                    { assertEquals(log, health.logs.recoveryFailed, "$key corrupt=$corrupt") },
                    { assertEquals(0L, health.spans.failed + health.metrics.failed + health.logs.failed) },
                    { assertEquals(0L, health.spans.acknowledged + health.metrics.acknowledged + health.logs.accepted) },
                    { if (!corrupt) assertEquals(1, store.readFailures, "read refusal reached") },
                )
                val cause = when {
                    log -> health.logs.lastFailure
                    metric -> health.metrics.lastFailure
                    else -> health.spans.lastFailure
                }
                assertTrue(cause != null)
                if (!corrupt) assertSame(store.cause, cause)
                store.corruptReads = emptySet()
                store.refusedReads = emptySet()
                telemetry.recover()
                telemetry.spans.export(healthSpan())
                metricTurns(telemetry.metrics).forEach { it() }
                telemetry.logs.export(LogRecord(recordId = ByteString(ByteArray(8)), body = "log", severityNumber = 9))
                assertAll(
                    { assertEquals(health.spans.recoveryFailed, telemetry.health.spans.recoveryFailed) },
                    { assertEquals(health.metrics.recoveryFailed, telemetry.health.metrics.recoveryFailed) },
                    { assertEquals(health.logs.recoveryFailed, telemetry.health.logs.recoveryFailed) },
                )
            }
        }
    }

    @Test
    fun readsAndLogNoOpsDoNotMasqueradeAsPersistenceProgress() = runTest {
        val store = HealthStore()
        val telemetry = WarpTelemetry(replica, store, maxLogRecords = 1, bufferPolicy = BufferPolicy.DROP_NEWEST)
        telemetry.recover()
        telemetry.spans.snapshot()
        telemetry.metrics.snapshotAll()
        assertAll(
            { assertEquals(PersistenceHealth(), telemetry.health.spans) },
            { assertEquals(PersistenceHealth(), telemetry.health.metrics) },
            { assertEquals(0, store.writes) },
        )
        val log = LogRecord(recordId = ByteString(ByteArray(8)), body = "log", severityNumber = 9)
        telemetry.logs.export(log)
        val written = store.writes
        telemetry.logs.export(emptyList())
        telemetry.logs.export(log)
        telemetry.logs.export(log.copy(recordId = ByteString(ByteArray(8) { 1 })))
        assertAll(
            { assertEquals(written, store.writes, "empty, duplicate, and refused exports do not write") },
            { assertEquals(1L, telemetry.health.logs.accepted) },
            { assertEquals(1L, telemetry.health.logs.refused) },
        )
        // An idempotent merge still performs store IO: it is a real acknowledged turn.
        telemetry.spans.merge(ORSet.empty())
        telemetry.spans.merge(ORSet.empty())
        assertEquals(2L, telemetry.health.spans.acknowledged)
        val before = telemetry.health.metrics
        assertFailsWith<IllegalArgumentException> {
            telemetry.metrics.recordHistogram(MetricKey("invalid", MetricKind.EXPONENTIAL_HISTOGRAM), Double.NaN)
        }
        assertEquals(before, telemetry.health.metrics, "validation before IO is not a persistence turn")
    }

    @Test
    fun combinedViewReportsLogRefusalAndRecoveryWithoutChangingRecordUnits() = runTest {
        val store = HealthStore()
        val telemetry = WarpTelemetry(replica, store)
        store.refusedWrites = setOf(StoreKey("otel.logs.idx"))
        assertIs<ExportResult.Failure>(telemetry.logs.export(LogRecord(recordId = ByteString(ByteArray(8)), body = "log", severityNumber = 9)))
        assertAll(
            { assertEquals(1, store.writeFailures) },
            { assertEquals(1L, telemetry.health.logs.failed) },
            { assertEquals(0L, telemetry.health.logs.accepted) },
        )
        store.refusedWrites = emptySet()
        assertEquals(ExportResult.Success, telemetry.logs.export(LogRecord(recordId = ByteString(ByteArray(8) { 1 }), body = "log", severityNumber = 9)))
        assertAll(
            { assertEquals(1L, telemetry.health.logs.accepted) },
            { assertEquals(0, telemetry.health.logs.consecutiveFailures) },
            { assertSame(store.cause, telemetry.health.logs.lastFailure) },
            { assertEquals(PersistenceHealth(), telemetry.health.spans) },
            { assertEquals(PersistenceHealth(), telemetry.health.metrics) },
        )
    }
}
