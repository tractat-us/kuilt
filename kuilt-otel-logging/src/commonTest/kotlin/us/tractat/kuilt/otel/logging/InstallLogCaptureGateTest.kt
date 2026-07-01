package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class InstallLogCaptureGateTest {
    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    @Test
    fun installThreadsTheProviderIntoTheCore() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("p"), InMemoryDurableStore())
        val provider = TraceContextProvider {
            ActiveTrace(ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { 2 }), sampled = true)
        }
        val installation =
            installLogCapture(exporter, CaptureConfig(), clock, Random(0), backgroundScope, provider)
        try {
            // Drive the installed core directly: the threaded provider must stamp.
            installation.capture.capture(NormalizedLogEvent(LogLevel.INFO, "com.app.Service", "hi"))
            val rec = exporter.snapshot().toList().single()
            assertEquals(ByteString(ByteArray(16) { 1 }), rec.traceId)
            assertEquals(ByteString(ByteArray(8) { 2 }), rec.spanId)
        } finally {
            installation.close()
        }
    }
}
