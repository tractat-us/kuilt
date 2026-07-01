package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarpSpanExporterAutoStampTest {
    private fun tId(b: Byte) = ByteString(ByteArray(16) { b })
    private fun sId(b: Byte) = ByteString(ByteArray(8) { b })
    private fun span(b: Byte, stamp: CausalStamp? = null) = SpanRecord(
        traceId = tId(b), spanId = sId(b), parentSpanId = null,
        name = "op", kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L, endEpochNanos = 2_000L, causalStamp = stamp,
    )

    @Test
    fun exportAutoStampsWhenClockPresent() = runTest {
        val clock = WarpCausalClock(ReplicaId("a"))
        val exp = WarpSpanExporter(ReplicaId("a"), InMemoryDurableStore(), causalClock = clock)
        exp.export(span(1))
        val stored = exp.snapshot().elements.single()
        assertNotNull(stored.causalStamp)
    }

    @Test
    fun explicitStampWins() = runTest {
        val clock = WarpCausalClock(ReplicaId("a"))
        val exp = WarpSpanExporter(ReplicaId("a"), InMemoryDurableStore(), causalClock = clock)
        val explicit = clock.tick()
        exp.export(span(1, stamp = explicit))
        assertEquals(explicit, exp.snapshot().elements.single().causalStamp)
    }

    @Test
    fun nullClockLeavesUnstamped() = runTest {
        val exp = WarpSpanExporter(ReplicaId("a"), InMemoryDurableStore())
        exp.export(span(1))
        assertNull(exp.snapshot().elements.single().causalStamp)
    }

    @Test
    fun crossReplicaMergeObservesRemoteFrontier() = runTest {
        // Replica A exports s1; B merges A's set, then exports s2. s2's predecessors
        // must include A's dot — the cross-boundary causal path.
        val clockA = WarpCausalClock(ReplicaId("a"))
        val expA = WarpSpanExporter(ReplicaId("a"), InMemoryDurableStore(), causalClock = clockA)
        expA.export(span(1))
        val aStamp = expA.snapshot().elements.single().causalStamp
        assertNotNull(aStamp)

        val clockB = WarpCausalClock(ReplicaId("b"))
        val expB = WarpSpanExporter(ReplicaId("b"), InMemoryDurableStore(), causalClock = clockB)
        expB.merge(expA.snapshot())          // B observes A's frontier
        expB.export(span(2))
        val s2 = expB.snapshot().elements.single { it.spanId == sId(2) }
        assertNotNull(s2.causalStamp)
        assertTrue(s2.causalStamp.predecessors.contains(aStamp.dot))
    }

    @Test
    fun clockPersistsAcrossRestart() = runTest {
        val store = InMemoryDurableStore()
        val clock1 = WarpCausalClock(ReplicaId("a"))
        val exp1 = WarpSpanExporter(ReplicaId("a"), store, causalClock = clock1)
        exp1.export(span(1))
        val firstDot = exp1.snapshot().elements.single().causalStamp?.dot
        assertNotNull(firstDot)

        // Fresh clock + exporter over the same store, recover, export again.
        val clock2 = WarpCausalClock(ReplicaId("a"))
        clock2.recover(store)
        val exp2 = WarpSpanExporter(ReplicaId("a"), store, causalClock = clock2)
        exp2.recover()
        exp2.export(span(2))
        val secondDot = exp2.snapshot().elements.single { it.spanId == sId(2) }.causalStamp?.dot
        assertNotNull(secondDot)
        // seq advanced — no re-minted dot.
        assertTrue(secondDot.seq > firstDot.seq)
    }
}
