package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pins [WarpSpanExporter.clear] (#2208). */
class WarpSpanExporterClearTest {

    private val replicaA = ReplicaId("A")

    // traceId is validated at 16 bytes and spanId at 8; SpanRecord has no defaults for
    // parentSpanId or kind, so both are explicit.
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
    fun clearEmptiesTheSetAndTheStoreAFreshExporterRecoversFrom() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpSpanExporter(replica = replicaA, store = store)
        repeat(5) { i -> exporter.export(span(i)) }

        assertEquals(ExportResult.Success, exporter.clear())

        val recovered = WarpSpanExporter(replica = replicaA, store = store)
        recovered.recover()
        assertAll(
            { assertEquals(emptySet(), exporter.snapshot().elements) },
            { assertEquals(emptySet(), recovered.snapshot().elements, "a fresh exporter recovers empty") },
        )
    }

    @Test
    fun aPeerHoldingThePreClearAddsCannotPushThemBackThroughMerge() = runTest {
        val exporter = WarpSpanExporter(replica = replicaA, store = InMemoryDurableStore())
        exporter.export(span(1))
        exporter.export(span(2))
        val peerCopy = exporter.snapshot()

        assertEquals(ExportResult.Success, exporter.clear())
        assertEquals(ExportResult.Success, exporter.merge(peerCopy))

        assertEquals(
            emptySet(),
            exporter.snapshot().elements,
            "the retained causal context must dominate the peer's re-merged adds",
        )
    }

    /**
     * The failure half of the contract (#2251), mirroring
     * `WarpLogRecordExporterClearTest.aRefusedClearReportsFailureAndARetryConverges`. The two
     * exporters document the same divergence, so they are pinned the same way.
     */
    @Test
    fun aRefusedClearReportsFailureAndARetryConverges() = runTest {
        val store = WriteRefusingStore(InMemoryDurableStore())
        val exporter = WarpSpanExporter(replica = replicaA, store = store)
        repeat(5) { i -> exporter.export(span(i)) }

        store.refuseWrites()
        // Reaching the next line is the never-throws assertion. `clear` maps a refused write onto
        // a result, so an escaping IllegalStateException fails this test outright — no separate
        // assertion can say it, because there is nothing left to assert on once it escapes.
        val refused = exporter.clear()

        // The documented divergence: the drop precedes the write and is not undone, so the live
        // set reads empty while the store still holds every span. A caller must read a
        // non-Success as "count unknown", not as zero — which is why both sides are read here.
        val snapshotAfterRefusal = exporter.snapshot().elements
        val divergedFromStore = WarpSpanExporter(replica = replicaA, store = store)
        divergedFromStore.recover()

        store.allowWrites()
        val retried = exporter.clear()

        // THE assertion of this test. A retry that returned Success having written nothing would
        // pass every in-memory line above — the live set was already emptied by the failed
        // attempt — while the store still held all five spans and a restart brought them back.
        // Only a freshly recovered exporter tells the two apart.
        val recovered = WarpSpanExporter(replica = replicaA, store = store)
        recovered.recover()

        assertAll(
            { assertTrue(store.refusedWrites() > 0, "the rig must actually have refused a write") },
            { assertTrue(refused is ExportResult.Failure, "a refused durable write fails the clear") },
            { assertEquals(emptySet(), snapshotAfterRefusal, "the in-memory drop is not undone on failure") },
            {
                assertEquals(
                    5,
                    divergedFromStore.snapshot().elements.size,
                    "the store still holds every span, so the live count is unknown rather than zero",
                )
            },
            { assertEquals(ExportResult.Success, retried, "a retry converges") },
            { assertEquals(emptySet(), recovered.snapshot().elements, "the retry actually reached the store") },
        )
    }

    /**
     * The causal frontier diverges on a refused clear for exactly the reason the span set does —
     * [WarpCausalClock.clearFrontier] runs before the write — and the retry re-converges it.
     *
     * Separate from the test above because it needs a configured clock: with none, `clear`'s
     * frontier lines are no-ops and the assertions here are unreachable.
     */
    @Test
    fun aRefusedClearAlsoDivergesTheCausalFrontierAndTheRetryConvergesIt() = runTest {
        val store = WriteRefusingStore(InMemoryDurableStore())
        val clock = WarpCausalClock(replicaA)
        val exporter = WarpSpanExporter(replica = replicaA, store = store, causalClock = clock)
        // Unstamped spans are auto-stamped, so each export ticks the clock and persists it.
        exporter.export(span(1))
        exporter.export(span(2))
        val frontierBefore = clock.frontier()

        store.refuseWrites()
        val refused = exporter.clear()
        val frontierAfterRefusal = clock.frontier()
        val persistedAfterRefusal = WarpCausalClock(replicaA).also { it.recover(store) }.frontier()

        store.allowWrites()
        val retried = exporter.clear()
        val persistedAfterRetry = WarpCausalClock(replicaA).also { it.recover(store) }.frontier()

        assertAll(
            {
                assertNotEquals(
                    emptySet(),
                    frontierBefore,
                    "the fixture must leave a non-empty frontier or nothing below can fail",
                )
            },
            { assertTrue(refused is ExportResult.Failure, "a refused durable write fails the clear") },
            { assertEquals(emptySet(), frontierAfterRefusal, "the in-memory frontier is emptied before the write") },
            {
                assertEquals(
                    frontierBefore,
                    persistedAfterRefusal,
                    "the store still names the pre-clear frontier: the emptying never reached it",
                )
            },
            { assertEquals(ExportResult.Success, retried, "a retry converges") },
            { assertEquals(emptySet(), persistedAfterRetry, "the retry persists the emptied frontier") },
        )
    }
}
