package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
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

    @Test
    fun nullProviderCapturesWithoutStamp() = runTest(StandardTestDispatcher()) {
        val exp = exporter()
        val result = capture(exp, CaptureConfig(), provider = null).capture(event())
        assertNotNull(result)
        val rec = exp.snapshot().toList().single()
        assertNull(rec.traceId)
        assertNull(rec.spanId)
    }

    @Test
    fun sampledTraceCapturesAndStamps() = runTest(StandardTestDispatcher()) {
        val exp = exporter()
        val result = capture(exp, CaptureConfig(), TraceContextProvider { trace(sampled = true) }).capture(event())
        assertNotNull(result)
        val rec = exp.snapshot().toList().single()
        assertEquals(ByteString(ByteArray(16) { 1 }), rec.traceId)
        assertEquals(ByteString(ByteArray(8) { 2 }), rec.spanId)
    }

    @Test
    fun unsampledTraceDrops() = runTest(StandardTestDispatcher()) {
        val exp = exporter()
        val result = capture(exp, CaptureConfig(), TraceContextProvider { trace(sampled = false) }).capture(event())
        assertNull(result)
        assertTrue(exp.snapshot().toList().isEmpty())
    }

    @Test
    fun untracedRespectsPolicy() = runTest(StandardTestDispatcher()) {
        val capExp = exporter()
        assertNotNull(capture(capExp, CaptureConfig(untracedPolicy = UntracedPolicy.CAPTURE), TraceContextProvider { null }).capture(event()))
        assertEquals(1, capExp.snapshot().toList().size)

        val dropExp = exporter()
        assertNull(capture(dropExp, CaptureConfig(untracedPolicy = UntracedPolicy.DROP), TraceContextProvider { null }).capture(event()))
        assertTrue(dropExp.snapshot().toList().isEmpty())
    }
}
