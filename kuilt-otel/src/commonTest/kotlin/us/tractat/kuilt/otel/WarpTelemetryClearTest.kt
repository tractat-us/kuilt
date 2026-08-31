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

    /**
     * The best-effort fan-out (#2249): every signal is attempted even after an earlier one
     * failed, and the **first** failure is the one returned.
     *
     * Mutation receipt — the obvious refactor this exists to red. Adding
     * `if (logsResult is ExportResult.Failure) return logsResult` after the first line of
     * [WarpTelemetry.clear] passes every other test in this class, and reds exactly the two
     * `recovered` assertions below (spans keep 5 records, metrics keep 1).
     */
    @Test
    fun everySignalIsClearedEvenWhenAnEarlierOneFailed() = runTest {
        // Refuses exactly the logs signal's writes and permits every other one. A store that
        // refused everything would make this test vacuous: all three signals would fail, so "did
        // the fan-out continue?" would have the same answer either way. Logs clear FIRST, so
        // these are the keys that put a failure in front of the other two signals.
        //
        // The prefix, not INDEX_KEY_FOR_TEST — see LOG_KEY_PREFIX_FOR_TEST. An exporter this
        // small never dirties its index, so a clear writes only its active segment and an
        // index-keyed refusal never fires at all.
        val store = WriteRefusingStore(InMemoryDurableStore()) { it.name.startsWith(LOG_KEY_PREFIX_FOR_TEST) }
        val telemetry = WarpTelemetry(replica = replicaA, store = store)
        repeat(5) { i -> telemetry.logs.export(record(i)) }
        repeat(5) { i -> telemetry.spans.export(span(i)) }
        telemetry.metrics.incrementSum(MetricKey("requests", MetricKind.SUM), by = 3L)

        store.refuseWrites()
        val result = telemetry.clear()
        store.allowWrites()

        // Recovered, not in-memory. Every exporter drops its buffer before its write, so reading
        // `telemetry.spans` here is empty whether or not the fan-out reached the spans clear —
        // the store is the only witness that can tell the two apart.
        val recovered = WarpTelemetry(replica = replicaA, store = store)
        recovered.recover()

        val failure = result as? ExportResult.Failure
        assertAll(
            { assertTrue(store.refusedWrites() > 0, "the rig must actually have refused a logs write") },
            { assertTrue(result is ExportResult.Failure, "a refused signal is reported, not swallowed") },
            {
                assertTrue(
                    failure?.cause?.message?.contains(LOG_KEY_PREFIX_FOR_TEST) == true,
                    "the returned failure is the LOGS one — the first, not the last; got ${failure?.cause}",
                )
            },
            {
                assertEquals(
                    emptySet(),
                    recovered.spans.snapshot().elements,
                    "spans were cleared past the logs failure",
                )
            },
            { assertEquals(0, recovered.metrics.metricCount(), "metrics were cleared past the logs failure") },
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
