package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLogging
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

/**
 * Regression for #1034: the sampling gate must consult the [TraceContextProvider]
 * at the **synchronous `log()` edge** on the caller, not on the drain coroutine.
 *
 * An ambient provider (e.g. `OtelSdkTraceContextProvider` → `Span.current()`) reads
 * the caller's thread/coroutine-local context, which is gone by drain time. If the
 * gate resolved on the drain it would always see "no active trace" and never stamp.
 *
 * The test proves resolution timing directly: a provider whose `current()` reads a
 * mutable holder is flipped to `null` **after** the synchronous log edge runs but
 * **before** the drain coroutine advances. The record must carry the trace that was
 * active at `log()` time — not the post-flip value the drain would see.
 */
class GateResolvesAtEdgeTest {
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000)
    }

    @Test
    fun gateResolvesTraceAtLogEdgeNotAtDrain() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val atLogEdge = ActiveTrace(ByteString(ByteArray(16) { 7 }), ByteString(ByteArray(8) { 9 }), sampled = true)

        // A provider whose result changes over time: the value active at the
        // synchronous log edge, then flipped away before the drain runs.
        var current: ActiveTrace? = atLogEdge
        val provider = TraceContextProvider { current }

        val installation =
            installLogCapture(exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope, provider)
        try {
            // The appender's log() runs synchronously here, on this caller — it must
            // resolve the trace NOW (current == atLogEdge).
            KotlinLogging.logger("com.example.Edge").info { "traced at the edge" }

            // Flip the ambient context AFTER the edge, BEFORE the drain. A gate that
            // (buggily) resolves on the drain would now see null → untraced → no stamp.
            current = null

            testScheduler.runCurrent()

            val record = exporter.snapshot().toList().single()
            assertEquals(atLogEdge.traceId, record.traceId, "trace must be resolved at the log edge, not on the drain")
            assertEquals(atLogEdge.spanId, record.spanId, "span must be resolved at the log edge, not on the drain")
        } finally {
            installation.close()
        }
    }
}
