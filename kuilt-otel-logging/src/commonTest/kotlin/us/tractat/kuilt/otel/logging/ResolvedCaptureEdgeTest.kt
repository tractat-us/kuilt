package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class ResolvedCaptureEdgeTest {

    @Test
    fun pausedDrainPreservesEmissionContext() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("edge"), InMemoryDurableStore())
        val emittedTrace = ActiveTrace(ByteString(ByteArray(16) { 3 }), ByteString(ByteArray(8) { 4 }), true)
        var trace: ActiveTrace? = emittedTrace
        var instant = Instant.fromEpochSeconds(10)
        var tenant = "emitted"
        var mappings = 0
        val clock = object : Clock { override fun now(): Instant = instant }
        val config = CaptureConfig(attributeMapper = {
            mappings++
            mapOf("tenant" to tenant)
        })
        val installation = installLogCapture(
            exporter, config, clock, Random(0), backgroundScope, TraceContextProvider { trace },
        )
        try {
            withLogContext("scope" to "emitted") {
                KotlinLogging.logger("com.example.Edge").warn { "traced" }
            }
            trace = null
            KotlinLogging.logger("com.example.Edge").warn { "untraced" }
            val mappingsAtEdge = mappings
            assertEquals(0, exporter.snapshot().toList().size, "the drain must still be paused")
            trace = ActiveTrace(ByteString(ByteArray(16) { 5 }), ByteString(ByteArray(8) { 6 }), true)
            instant = Instant.fromEpochSeconds(20)
            tenant = "drained"
            withLogContext("scope" to "drained") { testScheduler.runCurrent() }
            val records = exporter.snapshot().toList()
            assertEquals(2, records.size)
            val traced = records[0]
            val untraced = records[1]
            assertAll(
                { assertEquals(emittedTrace.traceId, traced.traceId) },
                { assertEquals(emittedTrace.spanId, traced.spanId) },
                { assertEquals(null, untraced.traceId, "resolved absence must remain untraced") },
                { assertEquals(null, untraced.spanId) },
                { assertEquals("emitted", traced.attributes["scope"]) },
                { assertEquals(null, untraced.attributes["scope"]) },
                { assertEquals(listOf("emitted", "emitted"), records.map { it.attributes["tenant"] }) },
                { assertEquals(listOf(10_000_000_000L, 10_000_000_000L), records.map { it.timestampEpochNanos }) },
                { assertEquals(listOf(20_000_000_000L, 20_000_000_000L), records.map { it.observedEpochNanos }) },
                { assertEquals(2, mappingsAtEdge, "both events must resolve before the drain runs") },
                { assertEquals(2, mappings, "draining must not call the mapper again") },
            )
        } finally {
            installation.close()
        }
    }

    @Test
    fun resolvedAttributesAreASnapshotOfAMutableMapperResult() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("snapshot"), InMemoryDurableStore())
        val attributes = mutableMapOf("tenant" to "emitted")
        val capture = LogCapture(
            exporter,
            CaptureConfig(attributeMapper = { attributes }),
            object : Clock { override fun now(): Instant = Instant.fromEpochSeconds(10) },
            Random(0),
        )
        val resolved = kotlin.test.assertNotNull(
            capture.resolveAtEdge(NormalizedLogEvent(LogLevel.INFO, "com.example.Edge", "snapshot")),
        )
        attributes["tenant"] = "drained"
        capture.capture(resolved)
        assertEquals("emitted", exporter.snapshot().toList().single().attributes["tenant"])
    }
}
