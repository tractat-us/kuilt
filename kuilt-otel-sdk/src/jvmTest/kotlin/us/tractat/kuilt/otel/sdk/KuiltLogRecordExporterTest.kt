package us.tractat.kuilt.otel.sdk

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.testing.logs.TestLogRecordData
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.DurableStore
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.StoreKey
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KuiltLogRecordExporterTest {
    private fun data(): LogRecordData = TestLogRecordData.builder()
        .setResource(Resource.getDefault())
        .setInstrumentationScopeInfo(InstrumentationScopeInfo.create("test"))
        .setTimestamp(1_700_000_000_000_000_000L, java.util.concurrent.TimeUnit.NANOSECONDS)
        .setObservedTimestamp(1_700_000_001_000_000_000L, java.util.concurrent.TimeUnit.NANOSECONDS)
        .setSeverity(Severity.INFO)
        .setSeverityText("INFO")
        .setBody("hello")
        .setAttributes(Attributes.builder().put("k", "v").build())
        .setSpanContext(
            SpanContext.create(
                "0102030405060708090a0b0c0d0e0f10", "1112131415161718",
                TraceFlags.getSampled(), TraceState.getDefault(),
            ),
        )
        .build()

    @Test
    fun exportMapsAndDrainsWithSuccess() = runTest {
        val warp = WarpLogRecordExporter(ReplicaId("p"), InMemoryDurableStore())
        val bridge = KuiltLogRecordExporter(warp, Random(0), backgroundScope)
        val code = bridge.export(listOf(data()))
        testScheduler.runCurrent()
        assertTrue(code.isSuccess)
        val rec = warp.snapshot().toList().single()
        assertEquals("hello", rec.body)
        assertEquals(9, rec.severityNumber) // INFO
        assertEquals("v", rec.attributes["k"])
        assertEquals(16, assertNotNull(rec.traceId).size)
        assertEquals(8, assertNotNull(rec.spanId).size)
        assertEquals(8, rec.recordId.size)
    }

    @Test
    fun flushCompletesAfterQueueDrains() = runTest {
        val warp = WarpLogRecordExporter(ReplicaId("p"), InMemoryDurableStore())
        val bridge = KuiltLogRecordExporter(warp, Random(0), backgroundScope)
        bridge.export(listOf(data()))
        val flush = bridge.flush()
        testScheduler.runCurrent()
        assertTrue(flush.isSuccess)
        assertEquals(1, warp.snapshot().toList().size)
    }

    @Test
    fun exportReportsFailureWhenDurableWriteFails() = runTest {
        val failing = object : DurableStore {
            override suspend fun read(key: StoreKey): ByteArray? = null
            override suspend fun write(key: StoreKey, bytes: ByteArray): Unit =
                throw java.io.IOException("disk full")
            override suspend fun delete(key: StoreKey) = Unit
        }
        val warp = WarpLogRecordExporter(ReplicaId("p"), failing)
        val bridge = KuiltLogRecordExporter(warp, Random(0), backgroundScope)
        val code = bridge.export(listOf(data()))
        testScheduler.runCurrent()
        assertFalse(code.isSuccess)
    }

    @Test
    fun shutdownDrainsThenCompletes() = runTest {
        val warp = WarpLogRecordExporter(ReplicaId("p"), InMemoryDurableStore())
        val bridge = KuiltLogRecordExporter(warp, Random(0), backgroundScope)
        bridge.export(listOf(data()))
        val down = bridge.shutdown()
        testScheduler.runCurrent()
        assertTrue(down.isSuccess)
        assertEquals(1, warp.snapshot().toList().size)
    }
}
