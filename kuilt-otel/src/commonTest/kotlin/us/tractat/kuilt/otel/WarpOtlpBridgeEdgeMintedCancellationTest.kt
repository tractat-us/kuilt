package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Pins that a `CancellationException` an [OtlpEdge] **mints** fails only that signal's leg of a
 * [WarpOtlpBridge.drain], while the caller's **own** cancellation still ends the drain (#3392).
 *
 * `runCatchingCancellable` rethrows every `CancellationException`, so an edge running its own
 * `withTimeout` threw its `TimeoutCancellationException` out of the drain: the legs after it were
 * never attempted, and the caller was cancelled where it should have got a [DrainResult]. Each mint
 * arm has a control in which the caller really is cancelled inside the same edge call.
 */
class WarpOtlpBridgeEdgeMintedCancellationTest {

    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    private suspend fun telemetryWithAllThreeSignals() = WarpTelemetry(ReplicaId("a"), InMemoryDurableStore()).apply {
        spans.export(
            SpanRecord(
                ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { 1 }), null,
                "op", SpanKind.INTERNAL, 1_000L, 2_000L,
            ),
        )
        logs.export(LogRecord(recordId = ByteString(ByteArray(8) { 1 }), body = "hi"))
        metrics.incrementSum(MetricKey("req", MetricKind.SUM), by = 2L)
    }

    // ---- The first leg: what follows it must still run ----

    @Test
    fun aCancellationTheEdgeMintsOnTheSpanSendFailsOnlyTheSpanLeg() = runTest {
        val edge = CancellationScriptedEdge()
        edge.arm(EdgeCall.SEND_SPANS, StoreAct.MINT)

        val outcome = escapeOf { WarpOtlpBridge(telemetryWithAllThreeSignals(), clock).drain(edge) }
        val partial = outcome.returned as? DrainResult.Partial

        assertAll(
            { assertEquals(1, edge.minted, "the rig minted ${edge.minted} cancellations on the span send, not one") },
            { assertNull(outcome.escaped, "a cancellation the edge minted escaped the drain: ${outcome.escaped}") },
            { assertIs<DrainResult.Partial>(outcome.returned, "the drain reported ${outcome.returned}") },
            { assertEquals(listOf(OtlpSignal.SPANS), partial?.failures?.map { it.signal }) },
            { assertIs<TimeoutCancellationException>(partial?.failures?.single()?.cause, "the leg's cause is not the minted one") },
            { assertEquals(1, edge.sentLogs, "the log leg after the minted span leg was not drained") },
            { assertEquals(1, edge.sentMetrics, "the metric leg after the minted span leg was not drained") },
            { assertEquals(1, partial?.logsSent) },
            { assertEquals(1, partial?.metricPointsSent) },
        )
    }

    @Test
    fun theCallersOwnCancellationInTheSpanSendEndsTheDrain() = runTest {
        val edge = CancellationScriptedEdge()
        edge.arm(EdgeCall.SEND_SPANS, StoreAct.PARK)
        val bridge = WarpOtlpBridge(telemetryWithAllThreeSignals(), clock)

        val returned = cancelInsideEdge(edge) { bridge.drain(edge) }

        assertAll(
            { assertEquals(1, edge.parked, "the rig never parked the span send") },
            { assertEquals(1, edge.cancelled, "the cancellation never landed inside the span send") },
            { assertNull(returned, "the cancelled drain returned instead of being cancelled: $returned") },
            { assertEquals(0, edge.logDigests, "the cancelled drain went on to the log leg") },
            { assertEquals(0, edge.metricDigests, "the cancelled drain went on to the metric leg") },
        )
    }

    // ---- The middle leg, on a digest rather than a send ----

    @Test
    fun aCancellationTheEdgeMintsOnTheLogDigestFailsOnlyTheLogLeg() = runTest {
        val edge = CancellationScriptedEdge()
        edge.arm(EdgeCall.LOG_DIGEST, StoreAct.MINT)

        val outcome = escapeOf { WarpOtlpBridge(telemetryWithAllThreeSignals(), clock).drain(edge) }
        val partial = outcome.returned as? DrainResult.Partial

        assertAll(
            { assertEquals(1, edge.minted, "the rig never minted on the log digest") },
            { assertNull(outcome.escaped, "a cancellation the edge minted escaped the drain: ${outcome.escaped}") },
            { assertEquals(listOf(OtlpSignal.LOGS), partial?.failures?.map { it.signal }, "the drain reported ${outcome.returned}") },
            { assertEquals(1, edge.sentSpans, "the span leg before the minted log leg was not drained") },
            { assertEquals(1, edge.sentMetrics, "the metric leg after the minted log leg was not drained") },
        )
    }

    // ---- The last leg: nothing follows, but the caller is still owed a result ----

    @Test
    fun aCancellationTheEdgeMintsOnTheMetricRetainFailsOnlyTheMetricLeg() = runTest {
        val edge = CancellationScriptedEdge()
        edge.arm(EdgeCall.RETAIN_METRICS, StoreAct.MINT)

        val outcome = escapeOf { WarpOtlpBridge(telemetryWithAllThreeSignals(), clock).drain(edge) }
        val partial = outcome.returned as? DrainResult.Partial

        assertAll(
            { assertEquals(1, edge.minted, "the rig never minted on the metric retain") },
            { assertNull(outcome.escaped, "a cancellation the edge minted escaped the drain: ${outcome.escaped}") },
            { assertEquals(listOf(OtlpSignal.METRICS), partial?.failures?.map { it.signal }, "the drain reported ${outcome.returned}") },
            { assertEquals(1, partial?.spansSent) },
            { assertEquals(1, partial?.logsSent) },
        )
    }

    @Test
    fun theCallersOwnCancellationInTheMetricRetainEndsTheDrain() = runTest {
        val edge = CancellationScriptedEdge()
        edge.arm(EdgeCall.RETAIN_METRICS, StoreAct.PARK)
        val bridge = WarpOtlpBridge(telemetryWithAllThreeSignals(), clock)

        val returned = cancelInsideEdge(edge) { bridge.drain(edge) }

        assertAll(
            { assertEquals(1, edge.parked, "the rig never parked the metric retain") },
            { assertEquals(1, edge.cancelled, "the cancellation never landed inside the metric retain") },
            { assertNull(returned, "the cancelled drain returned instead of being cancelled: $returned") },
        )
    }
}

/** The [OtlpEdge] calls a [CancellationScriptedEdge] can be armed on. */
private enum class EdgeCall { SEND_SPANS, LOG_DIGEST, RETAIN_METRICS }

/**
 * A recording [OtlpEdge] that mints a cancellation on, or parks, the **next** call matching its
 * armed rule: the edge-side twin of [CancellationScriptedStore]. One-shot, so every call after it
 * sees a healthy edge.
 *
 * It mints with a real `withTimeout`, so the exception is the one an edge would throw. [minted],
 * [parked] and [cancelled] count every firing, as precondition guards rather than the assertion
 * under test. The sent counts are records per signal; each fixture buffers one of each.
 */
private class CancellationScriptedEdge : OtlpEdge {
    private val lock = reentrantLock()
    private var rule: Pair<EdgeCall, StoreAct>? = null
    private var mintedCount = 0
    private var parkedCount = 0
    private var cancelledCount = 0
    private var logDigestCount = 0
    private var metricDigestCount = 0
    private var spansSent = 0
    private var logsSent = 0
    private var metricsSent = 0

    val minted: Int get() = lock.withLock { mintedCount }
    val parked: Int get() = lock.withLock { parkedCount }
    val cancelled: Int get() = lock.withLock { cancelledCount }
    val logDigests: Int get() = lock.withLock { logDigestCount }
    val metricDigests: Int get() = lock.withLock { metricDigestCount }
    val sentSpans: Int get() = lock.withLock { spansSent }
    val sentLogs: Int get() = lock.withLock { logsSent }
    val sentMetrics: Int get() = lock.withLock { metricsSent }

    fun arm(call: EdgeCall, act: StoreAct): Unit = lock.withLock { rule = call to act }

    private suspend fun intercept(call: EdgeCall) {
        val act = lock.withLock {
            val armed = rule ?: return@withLock null
            if (armed.first != call) return@withLock null
            rule = null
            armed.second
        } ?: return
        when (act) {
            StoreAct.MINT -> {
                lock.withLock { mintedCount++ }
                // The caller's job stays alive: only this inner scope is cancelled, and
                // `withTimeout` throws its TimeoutCancellationException at the caller.
                withTimeout(1.milliseconds) { awaitCancellation() }
            }
            StoreAct.PARK -> {
                lock.withLock { parkedCount++ }
                try {
                    awaitCancellation()
                } finally {
                    // `awaitCancellation` leaves only by cancellation, so this counts exactly the
                    // cancellations that reached the parked call.
                    lock.withLock { cancelledCount++ }
                }
            }
        }
    }

    override suspend fun digest(): SpanDigest = SpanDigest(emptySet())

    override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) {
        intercept(EdgeCall.SEND_SPANS)
        lock.withLock { spansSent += spans.size }
    }

    override suspend fun logDigest(): LogDigest {
        lock.withLock { logDigestCount++ }
        intercept(EdgeCall.LOG_DIGEST)
        return LogDigest(emptySet())
    }

    override suspend fun sendLogs(logs: Set<LogRecord>) {
        lock.withLock { logsSent += logs.size }
    }

    override suspend fun metricDigest(): MetricDigest {
        lock.withLock { metricDigestCount++ }
        return MetricDigest(emptyMap())
    }

    override suspend fun sendMetrics(points: Set<MetricPoint>) {
        lock.withLock { metricsSent += points.size }
    }

    override suspend fun retainMetrics(live: Set<MetricKey>) {
        intercept(EdgeCall.RETAIN_METRICS)
    }
}

/**
 * Run [block] in a child job, cancel that job once [edge] has parked a call, and return what
 * [block] returned: `null` when it was cancelled, as it must be.
 */
private suspend fun <T : Any> TestScope.cancelInsideEdge(edge: CancellationScriptedEdge, block: suspend () -> T): T? {
    val parkedBefore = edge.parked
    var returned: T? = null
    val job: Job = launch { returned = block() }
    runCurrent()
    if (edge.parked > parkedBefore) job.cancelAndJoin() else job.join()
    return returned
}
