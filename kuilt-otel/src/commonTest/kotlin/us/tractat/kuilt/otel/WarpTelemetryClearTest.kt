package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins [WarpTelemetry.clear] (#2208) — the call a consumer's "Clear store" affordance makes. */
class WarpTelemetryClearTest {

    private val replicaA = ReplicaId("A")

    // Little-endian over a Long: Int.shr masks its operand to 5 bits, so `id shr 32` is `id shr 0`
    // and an Int-based version would silently mirror bytes 0-3 into 4-7 rather than encode 64 bits.
    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id.toLong() shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_000L + id,
    )

    // traceId is validated at 16 bytes and spanId at 8; parentSpanId and kind have no defaults.
    private fun span(id: Int) = SpanRecord(
        traceId = ByteString(ByteArray(16) { id.toByte() }),
        spanId = ByteString(ByteArray(8) { id.toByte() }),
        parentSpanId = null,
        name = "span-$id",
        kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L + id,
        endEpochNanos = 2_000L + id,
    )

    @Test
    fun clearEmptiesEverySignalAndAFreshTelemetryRecoversEmpty() = runTest {
        val store = InMemoryDurableStore()
        val telemetry = WarpTelemetry(replica = replicaA, store = store)
        repeat(5) { i -> telemetry.logs.export(record(i)) }
        repeat(5) { i -> telemetry.spans.export(span(i)) }
        telemetry.metrics.incrementSum(MetricKey("requests", MetricKind.SUM), by = 3L)

        assertEquals(ExportResult.Success, telemetry.clear())

        val recovered = WarpTelemetry(replica = replicaA, store = store)
        recovered.recover()
        assertAll(
            { assertEquals(emptyList(), telemetry.logs.snapshot().toList()) },
            { assertEquals(emptySet(), telemetry.spans.snapshot().elements) },
            { assertEquals(0, telemetry.metrics.metricCount()) },
            { assertEquals(emptyList(), recovered.logs.snapshot().toList()) },
            { assertEquals(emptySet(), recovered.spans.snapshot().elements) },
            { assertEquals(0, recovered.metrics.metricCount()) },
        )
    }

    @Test
    fun theSameInstanceKeepsExportingAfterAClear() = runTest {
        val store = InMemoryDurableStore()
        val telemetry = WarpTelemetry(replica = replicaA, store = store)
        repeat(5) { i -> telemetry.logs.export(record(i)) }
        telemetry.clear()

        assertEquals(ExportResult.Success, telemetry.logs.export(record(99)))

        val recovered = WarpTelemetry(replica = replicaA, store = store)
        recovered.recover()
        assertEquals(
            listOf(record(99).recordId),
            recovered.logs.snapshot().toList().map { it.recordId },
            "no restart is required for the store to accept new records",
        )
    }

    @Test
    fun aClearEmptiesTheCausalFrontierWithoutRegressingTheClock() = runTest {
        val store = InMemoryDurableStore()
        val telemetry = WarpTelemetry(replica = replicaA, store = store)
        // Unstamped spans are auto-stamped, so each export ticks the clock AND persists it.
        telemetry.spans.export(span(1))
        telemetry.spans.export(span(2))

        telemetry.clear()

        // WarpTelemetry's clock is private, so read the persisted one — which is the state
        // that actually has to survive, and the state a restart would see.
        val persisted = WarpCausalClock(replicaA).also { it.recover(store) }
        assertAll(
            { assertEquals(emptySet(), persisted.frontier(), "the frontier is forgotten") },
            {
                assertTrue(
                    persisted.tick().dot.seq > 2L,
                    "seq must not regress: the two spans above already consumed 1 and 2, " +
                        "so a recovered clock ticks to 3 — a reset one would tick to 1 and " +
                        "re-mint a dot an earlier span already carries",
                )
            },
        )
    }
}
