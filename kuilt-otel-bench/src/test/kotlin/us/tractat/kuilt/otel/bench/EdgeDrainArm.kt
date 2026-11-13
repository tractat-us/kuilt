package us.tractat.kuilt.otel.bench

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.WarpOtlpBridge
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.otel.otlp.OtlpHttpEdge
import us.tractat.kuilt.otel.otlp.OtlpWireFormat
import us.tractat.kuilt.store.InMemoryDurableStore
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

/**
 * One row of the edge-drain arm: one `WarpOtlpBridge.drain` through an `OtlpHttpEdge` when
 * [changed] of [series] sum series moved since the last drain.
 *
 * The collector is a `MockEngine` that answers 200 at once, so the row prices the bridge's and the
 * edge's own work — rendering every series, reading and rewriting the per-endpoint sent-versions
 * map, encoding the request — and none of the network.
 */
data class DrainRow(
    val series: Int,
    val changed: Int,
    val wire: OtlpWireFormat,
    val sample: Sample,
    val edgeStoreNanosPerDrain: Double,
    val edgeBytesReadPerDrain: Long,
    val edgeBytesWrittenPerDrain: Long,
    val requestBytesPerDrain: Long,
)

/**
 * Seeds [series] sum series and drains them all once, untimed, so the edge's sent-versions map is
 * full. Each timed round then moves [changed] series — a rotating window, written straight into the
 * telemetry store and recovered, all outside the clock — and times one drain.
 *
 * Rig checks: the telemetry recovers exactly [series] series; every drain is a [DrainResult.Success]
 * that sent exactly [changed] metric points (the first, exactly [series]); and the collector saw
 * exactly one request per drain.
 */
suspend fun edgeDrainArm(series: Int, changed: Int, wire: OtlpWireFormat, rounds: Int, warmUpRounds: Int): DrainRow {
    require(changed in 1..series) { "changed must be in 1..$series, was $changed" }
    val telemetryStore = InMemoryDurableStore()
    val values = LongArray(series) { 1L }
    seedSums(telemetryStore, sumsMap(series) { values[it] })
    val telemetry = WarpTelemetry(replica = BENCH_REPLICA, store = telemetryStore, maxMetrics = series)
    telemetry.recover()
    check(telemetry.metrics.metricCount() == series) {
        "seeded $series series, recovered ${telemetry.metrics.metricCount()}"
    }

    val requests = AtomicLong()
    val requestBytes = AtomicLong()
    val client = HttpClient(
        MockEngine { request ->
            requests.incrementAndGet()
            requestBytes.addAndGet(request.body.contentLength ?: error("request body has no length"))
            respond("", HttpStatusCode.OK)
        },
    )
    val edgeStore = TimingStore(InMemoryDurableStore())
    val edge = OtlpHttpEdge(client, "http://collector.invalid:4318", edgeStore, wire = wire)
    val bridge = WarpOtlpBridge(telemetry, Clock.System)

    suspend fun drainExpecting(points: Int) {
        val result = bridge.drain(edge)
        check(result == DrainResult.Success(spansSent = 0, logsSent = 0, metricPointsSent = points)) {
            "drain returned $result, expected $points metric points"
        }
    }

    var cursor = 0
    suspend fun moveWindow() {
        repeat(changed) {
            values[cursor]++
            cursor = (cursor + 1) % series
        }
        seedSums(telemetryStore, sumsMap(series) { values[it] })
        telemetry.metrics.recover()
    }

    drainExpecting(series)
    repeat(warmUpRounds) {
        moveWindow()
        drainExpecting(changed)
    }
    edgeStore.reset()
    requests.set(0L)
    requestBytes.set(0L)
    val sample = measureEach(rounds, setup = { moveWindow() }) { drainExpecting(changed) }
    client.close()

    check(requests.get() == sample.executed) { "collector saw ${requests.get()} requests for ${sample.executed} drains" }
    return DrainRow(
        series = series,
        changed = changed,
        wire = wire,
        sample = sample,
        edgeStoreNanosPerDrain = (edgeStore.nanosInRead + edgeStore.nanosInWrite).toDouble() / sample.executed,
        edgeBytesReadPerDrain = edgeStore.bytesRead / sample.executed,
        edgeBytesWrittenPerDrain = edgeStore.bytesWritten / sample.executed,
        requestBytesPerDrain = requestBytes.get() / sample.executed,
    )
}
