@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.bench

import us.tractat.kuilt.otel.MetricExportResult
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.store.DurableStore

/** The two recording entry points a server's SDK bridge calls on every exported point. */
enum class RecordingOp { INCREMENT_SUM, SET_GAUGE }

/** How many operations, in how many rounds, after how many untimed warm-up operations. */
data class Plan(val opsPerRound: Int, val rounds: Int, val warmUpOps: Int)

/**
 * One row of the recording arm: the cost of one [RecordingOp] against an exporter already holding
 * [series] series of that kind, and how that cost splits.
 *
 * - [sample] — the whole call, as a caller sees it.
 * - [storeNanosPerOp] — the part spent inside `DurableStore.write`.
 * - [encodeNanos] — one whole-map CBOR encode of the same map, timed on its own, outside the
 *   exporter, in rounds alternating with the call's. What is left of the call after the store and
 *   the encode is the CRDT mutation, the two locks and the bookkeeping.
 * - [encodeOverCall] — the median per-round ratio of the two, which survives a busy box better
 *   than [encodeNanos] divided by the call's median: each pair of rounds sees the same load.
 * - [bytesPerWrite] — the size of each durable write: the whole encoded map.
 */
data class RecordingRow(
    val op: RecordingOp,
    val store: String,
    val series: Int,
    val sample: Sample,
    val storeNanosPerOp: Double,
    val encodeNanos: Double,
    val encodeOverCall: Double,
    val bytesPerWrite: Long,
)

/**
 * Seeds [series] series of [op]'s kind into a fresh store from [newStore], recovers an exporter
 * over it, then times [op] cycling over the existing series — no new series, so no eviction and no
 * map growth while the clock runs.
 *
 * Fails, rather than measuring, if the rig did not fire: the recovered exporter must hold exactly
 * [series] series, every timed call must return Success, the store must have taken exactly one
 * write per call, and the counter total (or the last gauge timestamp) must account for every call.
 */
suspend fun recordingArm(
    op: RecordingOp,
    series: Int,
    plan: Plan,
    storeName: String,
    newStore: () -> DurableStore,
): RecordingRow {
    val timing = TimingStore(newStore())
    when (op) {
        RecordingOp.INCREMENT_SUM -> seedSums(timing, sumsMap(series))
        RecordingOp.SET_GAUGE -> seedGauges(timing, gaugesMap(series))
    }
    val exporter = WarpMetricExporter(replica = BENCH_REPLICA, store = timing, maxMetrics = maxOf(series, 1))
    exporter.recover()
    check(exporter.metricCount() == series) {
        "seeded $series series but the exporter recovered ${exporter.metricCount()} — the bench's copy of " +
            "WarpMetricExporter's store key or encoding has drifted"
    }

    val keys = when (op) {
        RecordingOp.INCREMENT_SUM -> List(series) { sumKey(it) }
        RecordingOp.SET_GAUGE -> List(series) { gaugeKey(it) }
    }
    var gaugeClock = 1L
    suspend fun call(i: Int) {
        val key = keys[i % series]
        val result = when (op) {
            RecordingOp.INCREMENT_SUM -> exporter.incrementSum(key)
            RecordingOp.SET_GAUGE -> exporter.setGauge(key, value = i.toDouble(), timestamp = ++gaugeClock)
        }
        check(result == MetricExportResult.Success) { "$op returned $result" }
    }

    // The standalone encode prices the same map the exporter holds. Its SIZE does not change while
    // the clock runs (no series is added), so one snapshot serves every round.
    val catalog = exporter.snapshotAll()
    var encodedBytes = 0L
    val encode: () -> ByteArray = when (op) {
        RecordingOp.INCREMENT_SUM -> { -> MetricEncoding.cbor.encodeToByteArray(MetricEncoding.sums, catalog.sums) }
        RecordingOp.SET_GAUGE -> { -> MetricEncoding.cbor.encodeToByteArray(MetricEncoding.gauges, catalog.gauges) }
    }

    warmUp(plan.warmUpOps) { call(it) }
    warmUp(plan.warmUpOps) { encodedBytes += encode().size }
    timing.reset()
    val paired = measurePaired(plan.opsPerRound, plan.rounds, first = { call(it) }, second = { encodedBytes += encode().size })
    val sample = paired.first
    check(encodedBytes > 0L) { "the standalone encode produced no bytes" }

    val totalCalls = plan.warmUpOps.toLong() + sample.executed
    check(timing.writes == sample.executed) { "store took ${timing.writes} writes for ${sample.executed} calls" }
    when (op) {
        RecordingOp.INCREMENT_SUM -> {
            val total = keys.sumOf { exporter.sumValue(it) }
            check(total == series + totalCalls) { "counters sum to $total, expected ${series + totalCalls}" }
        }
        RecordingOp.SET_GAUGE ->
            check(gaugeClock == 1L + totalCalls) { "gauge clock at $gaugeClock, expected ${1L + totalCalls}" }
    }

    return RecordingRow(
        op = op,
        store = storeName,
        series = series,
        sample = sample,
        storeNanosPerOp = timing.nanosInWrite.toDouble() / timing.writes,
        encodeNanos = paired.second.nanosPerOp,
        encodeOverCall = paired.secondOverFirst,
        bytesPerWrite = timing.bytesWritten / timing.writes,
    )
}

