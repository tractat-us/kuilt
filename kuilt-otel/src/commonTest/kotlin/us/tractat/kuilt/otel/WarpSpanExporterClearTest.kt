package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
