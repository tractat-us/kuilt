package us.tractat.kuilt.otel.bench

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.LogDigest
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricDigest
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.OtlpEdge
import us.tractat.kuilt.otel.SpanDigest
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord
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
 *
 * The drain is split in two (#3292), and each part is timed on its own rather than derived by
 * subtraction, so [partsOverWhole] checks the rig instead of restating it:
 *
 * - [bridgeNanosPerDrain] is the same drain against a [StubEdge], which keeps its sent-versions in
 *   one in-memory map: the bridge's snapshot, render, value-hash and delta filter, and none of the
 *   edge's work.
 * - [edgeDigestNanosPerDrain] and [edgeSendNanosPerDrain] are the time spent inside the real edge's
 *   `metricDigest` and `sendMetrics`, read off a [TimingEdge] around it; [edgeOtherNanosPerDrain]
 *   is every other edge call, which with spans and logs idle is the `retainMetrics` prune. Of the edge's time, [edgeStoreNanosPerDrain] is in its store; the
 *   rest is its CBOR, the request encoding and the mock POST.
 */
data class DrainRow(
    val series: Int,
    val changed: Int,
    val wire: OtlpWireFormat,
    val sample: Sample,
    val bridgeNanosPerDrain: Double,
    val edgeDigestNanosPerDrain: Double,
    val edgeSendNanosPerDrain: Double,
    val edgeOtherNanosPerDrain: Double,
    val edgeStoreNanosPerDrain: Double,
    val edgeReadsPerDrain: Double,
    val edgeBytesReadPerDrain: Long,
    val edgeBytesWrittenPerDrain: Long,
    val requestBytesPerDrain: Long,
) {
    /** The bridge alone plus the time inside the edge, over the whole drain: near 1 when the rig is sound. */
    val partsOverWhole: Double
        get() = (bridgeNanosPerDrain + edgeDigestNanosPerDrain + edgeSendNanosPerDrain + edgeOtherNanosPerDrain) /
            sample.nanosPerOp
}

/**
 * Times every call into [delegate], so an arm can tell the edge's share of a drain from the
 * bridge's. The two metric calls are timed separately; every other call lands in [otherNanos].
 */
class TimingEdge(private val delegate: OtlpEdge) : OtlpEdge {
    val digestNanos = AtomicLong()
    val sendNanos = AtomicLong()
    val otherNanos = AtomicLong()
    val sendCalls = AtomicLong()

    private inline fun <T> timed(into: AtomicLong, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            into.addAndGet(System.nanoTime() - t0)
        }
    }

    override suspend fun digest(): SpanDigest = timed(otherNanos) { delegate.digest() }

    override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>): Unit =
        timed(otherNanos) { delegate.send(spans, links) }

    override suspend fun logDigest(): LogDigest = timed(otherNanos) { delegate.logDigest() }

    override suspend fun sendLogs(logs: Set<LogRecord>): Unit = timed(otherNanos) { delegate.sendLogs(logs) }

    override suspend fun metricDigest(): MetricDigest = timed(digestNanos) { delegate.metricDigest() }

    override suspend fun sendMetrics(points: Set<MetricPoint>) {
        sendCalls.incrementAndGet()
        timed(sendNanos) { delegate.sendMetrics(points) }
    }

    override suspend fun retainMetrics(live: Set<MetricKey>): Unit = timed(otherNanos) { delegate.retainMetrics(live) }
}

/**
 * An edge whose sent-versions live in one in-memory map, folded in place: a drain with free
 * bookkeeping. Driving the bridge against it prices the bridge alone.
 */
class StubEdge : OtlpEdge {
    private val versions = HashMap<MetricKey, Long>()

    override suspend fun digest(): SpanDigest = SpanDigest(emptySet())

    override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) {}

    override suspend fun metricDigest(): MetricDigest = MetricDigest(versions)

    override suspend fun sendMetrics(points: Set<MetricPoint>) {
        points.forEach { versions[it.key] = it.valueHash() }
    }
}

/**
 * Seeds [series] sum series and drains them all once, untimed, into the real edge and into the
 * stub, so both sent-versions maps are full. Each timed round then moves [changed] series — a
 * rotating window, written straight into the telemetry store and recovered, all outside the clock —
 * and times one drain into the stub, then one into the real edge, so a change in machine load lands
 * on both parts of the same round.
 *
 * Rig checks: the telemetry recovers exactly [series] series; every drain, into either edge, is a
 * [DrainResult.Success] that sent exactly [changed] metric points (the first, exactly [series]); the
 * collector saw exactly one request per timed drain; and the real edge's `sendMetrics` ran once per
 * timed drain.
 */
suspend fun edgeDrainArm(series: Int, changed: Int, wire: OtlpWireFormat, rounds: Int, warmUpRounds: Int): DrainRow {
    require(changed in 1..series) { "changed must be in 1..$series, was $changed" }
    require(rounds > 0) { "rounds must be positive" }
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
    val edge = TimingEdge(OtlpHttpEdge(client, "http://collector.invalid:4318", edgeStore, wire = wire))
    val stub = StubEdge()
    val bridge = WarpOtlpBridge(telemetry, Clock.System)

    suspend fun drainExpecting(into: OtlpEdge, points: Int) {
        val result = bridge.drain(into)
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

    drainExpecting(edge, series)
    drainExpecting(stub, series)
    repeat(warmUpRounds) {
        moveWindow()
        drainExpecting(stub, changed)
        drainExpecting(edge, changed)
    }
    edgeStore.reset()
    requests.set(0L)
    requestBytes.set(0L)
    val sendCallsBefore = edge.sendCalls.get()

    val whole = DoubleArray(rounds)
    val wholeAlloc = DoubleArray(rounds)
    val bridgeAlone = DoubleArray(rounds)
    val inDigest = DoubleArray(rounds)
    val inSend = DoubleArray(rounds)
    val inOther = DoubleArray(rounds)
    var executed = 0L
    for (round in 0 until rounds) {
        moveWindow()
        val s0 = System.nanoTime()
        drainExpecting(stub, changed)
        bridgeAlone[round] = (System.nanoTime() - s0).toDouble()

        val d0 = edge.digestNanos.get()
        val e0 = edge.sendNanos.get()
        val o0 = edge.otherNanos.get()
        val a0 = allocatedBytes()
        val t0 = System.nanoTime()
        drainExpecting(edge, changed)
        whole[round] = (System.nanoTime() - t0).toDouble()
        wholeAlloc[round] = (allocatedBytes() - a0).toDouble()
        inDigest[round] = (edge.digestNanos.get() - d0).toDouble()
        inSend[round] = (edge.sendNanos.get() - e0).toDouble()
        inOther[round] = (edge.otherNanos.get() - o0).toDouble()
        executed++
    }
    client.close()

    val sendCalls = edge.sendCalls.get() - sendCallsBefore
    check(executed == rounds.toLong()) { "ran $executed drains, expected $rounds" }
    check(requests.get() == executed) { "collector saw ${requests.get()} requests for $executed drains" }
    check(sendCalls == executed) { "edge sendMetrics ran $sendCalls times for $executed drains" }
    return DrainRow(
        series = series,
        changed = changed,
        wire = wire,
        sample = Sample(
            opsPerRound = 1, rounds = rounds,executed = executed,
            nanosPerOp = whole.median(), allocatedBytesPerOp = wholeAlloc.median(),
        ),
        bridgeNanosPerDrain = bridgeAlone.median(),
        edgeDigestNanosPerDrain = inDigest.median(),
        edgeSendNanosPerDrain = inSend.median(),
        edgeOtherNanosPerDrain = inOther.median(),
        edgeStoreNanosPerDrain = (edgeStore.nanosInRead + edgeStore.nanosInWrite).toDouble() / executed,
        edgeReadsPerDrain = edgeStore.reads.toDouble() / executed,
        edgeBytesReadPerDrain = edgeStore.bytesRead / executed,
        edgeBytesWrittenPerDrain = edgeStore.bytesWritten / executed,
        requestBytesPerDrain = requestBytes.get() / executed,
    )
}
