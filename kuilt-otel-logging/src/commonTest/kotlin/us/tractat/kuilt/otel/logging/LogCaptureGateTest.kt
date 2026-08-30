package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.store.InMemoryDurableStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class LogCaptureGateTest {
    private val fixedClock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }
    private fun exporter() = WarpLogRecordExporter(ReplicaId("p"), InMemoryDurableStore())
    private fun event() = NormalizedLogEvent(LogLevel.INFO, "com.app.Service", "hello")
    private fun trace(sampled: Boolean) =
        ActiveTrace(ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { 2 }), sampled)

    private fun capture(exporter: WarpLogRecordExporter, config: CaptureConfig, provider: TraceContextProvider?) =
        LogCapture(exporter, config, fixedClock, Random(0), provider)

    // Mirror the production capture edge: resolve synchronously (as the appender
    // does at log() time) and carry the snapshot on the event into capture()
    // (#1034 trace, #1630 attributes). The edge applies every gate capture() would,
    // so a null from resolveAtEdge IS the drop and the event is never queued (#1745)
    // — which is why this returns null rather than asserting the resolve succeeded.
    private suspend fun LogCapture.captureAtEdge(event: NormalizedLogEvent) =
        resolveAtEdge(event)?.let { capture(it) }

    @Test
    fun nullProviderCapturesWithoutStamp() = runTest(StandardTestDispatcher()) {
        val exp = exporter()
        val result = capture(exp, CaptureConfig(), provider = null).captureAtEdge(event())
        assertNotNull(result)
        val rec = exp.snapshot().toList().single()
        assertNull(rec.traceId)
        assertNull(rec.spanId)
    }

    @Test
    fun sampledTraceCapturesAndStamps() = runTest(StandardTestDispatcher()) {
        val exp = exporter()
        val result = capture(exp, CaptureConfig(), TraceContextProvider { trace(sampled = true) }).captureAtEdge(event())
        assertNotNull(result)
        val rec = exp.snapshot().toList().single()
        assertEquals(ByteString(ByteArray(16) { 1 }), rec.traceId)
        assertEquals(ByteString(ByteArray(8) { 2 }), rec.spanId)
    }

    @Test
    fun unsampledTraceDrops() = runTest(StandardTestDispatcher()) {
        val exp = exporter()
        val core = capture(exp, CaptureConfig(), TraceContextProvider { trace(sampled = false) })
        // The drop is decided at the edge, so the event is never queued (#1745).
        assertNull(core.resolveAtEdge(event()), "an unsampled trace is dropped at the edge")
        assertNull(core.captureAtEdge(event()))
        assertTrue(exp.snapshot().toList().isEmpty())
    }

    @Test
    fun untracedRespectsPolicy() = runTest(StandardTestDispatcher()) {
        val capExp = exporter()
        assertNotNull(capture(capExp, CaptureConfig(untracedPolicy = UntracedPolicy.CAPTURE), TraceContextProvider { null }).captureAtEdge(event()))
        assertEquals(1, capExp.snapshot().toList().size)

        val dropExp = exporter()
        val dropCore = capture(dropExp, CaptureConfig(untracedPolicy = UntracedPolicy.DROP), TraceContextProvider { null })
        assertNull(dropCore.resolveAtEdge(event()), "DROP discards an untraced event at the edge")
        assertNull(dropCore.captureAtEdge(event()))
        assertTrue(dropExp.snapshot().toList().isEmpty())
    }

    /**
     * The gate inside `capture()` is a no-op for an edge-resolved event, but it is
     * not dead code: a caller that drives `capture()` straight from its own log site
     * never goes through [LogCapture.resolveAtEdge], so that call *is* its edge and
     * the gate there is the only one it meets. Both call sites ask the same private
     * predicate, so the two can never come to disagree (#1745).
     */
    @Test
    fun drainSideGateStillAppliesToACallerWithNoEdge() = runTest(StandardTestDispatcher()) {
        val dropExp = exporter()
        val dropped = capture(dropExp, CaptureConfig(untracedPolicy = UntracedPolicy.DROP), TraceContextProvider { null })
            .capture(event())
        assertNull(dropped, "an untraced direct call under DROP is dropped by capture()'s own gate")

        val unsampledExp = exporter()
        val unsampled = capture(unsampledExp, CaptureConfig(), TraceContextProvider { trace(sampled = false) })
            .capture(event().copy(activeTrace = trace(sampled = false)))
        assertNull(unsampled, "an unsampled direct call is dropped by capture()'s own gate")

        assertTrue(dropExp.snapshot().toList().isEmpty())
        assertTrue(unsampledExp.snapshot().toList().isEmpty())
    }
}
