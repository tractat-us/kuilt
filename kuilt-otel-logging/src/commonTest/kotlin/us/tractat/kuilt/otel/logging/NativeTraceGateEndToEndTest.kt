package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The #1029 acceptance bar: with a [CoroutineContextTraceProvider] wired into
 * `installLogCapture`, a line logged synchronously inside [withActiveTrace] is
 * stamped (sampled) or dropped (unsampled) — proven on wasmJs, iOS and macOS via
 * `commonTest`, not only the JVM. A null stamp on [sampledScopeStampsTheRecord]
 * off-JVM is the exact bug this issue exists to prevent.
 */
class NativeTraceGateEndToEndTest {
    private val fixedClock = object : Clock { override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000) }
    private val sampled = ActiveTrace(ByteString(ByteArray(16) { 5 }), ByteString(ByteArray(8) { 6 }), sampled = true)
    private val unsampled = ActiveTrace(ByteString(ByteArray(16) { 5 }), ByteString(ByteArray(8) { 6 }), sampled = false)

    @Test
    fun sampledScopeStampsTheRecord() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val installation = installLogCapture(
            exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope, CoroutineContextTraceProvider(),
        )
        try {
            withActiveTrace(sampled) {
                KotlinLogging.logger("com.example.Native").info { "inside a sampled trace" }
            }
            testScheduler.runCurrent()
            val record = exporter.snapshot().toList().single()
            assertEquals(sampled.traceId, record.traceId)
            assertEquals(sampled.spanId, record.spanId)
        } finally {
            installation.close()
        }
    }

    @Test
    fun unsampledScopeDropsTheRecord() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val installation = installLogCapture(
            exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope, CoroutineContextTraceProvider(),
        )
        try {
            withActiveTrace(unsampled) {
                KotlinLogging.logger("com.example.Native").info { "inside an unsampled trace" }
            }
            testScheduler.runCurrent()
            assertTrue(exporter.snapshot().toList().isEmpty())
        } finally {
            installation.close()
        }
    }

    @Test
    fun untracedCapturesUnstampedUnderDefaultPolicy() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val installation = installLogCapture(
            exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope, CoroutineContextTraceProvider(),
        )
        try {
            KotlinLogging.logger("com.example.Native").info { "no active trace" }
            testScheduler.runCurrent()
            val record = exporter.snapshot().toList().single()
            assertNull(record.traceId)
            assertNull(record.spanId)
        } finally {
            installation.close()
        }
    }

    @Test
    fun untracedDropsUnderDropPolicy() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val installation = installLogCapture(
            exporter, CaptureConfig(untracedPolicy = UntracedPolicy.DROP), fixedClock, Random(0),
            backgroundScope, CoroutineContextTraceProvider(),
        )
        try {
            KotlinLogging.logger("com.example.Native").info { "no active trace, drop policy" }
            testScheduler.runCurrent()
            assertTrue(exporter.snapshot().toList().isEmpty())
        } finally {
            installation.close()
        }
    }
}
