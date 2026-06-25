@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import us.tractat.kuilt.test.assertAll

class CausalLinkInferenceTest {

    private val replica = ReplicaId("A")

    private fun traceId(id: Byte): ByteString = ByteString(ByteArray(16) { id })
    private fun spanId(id: Byte): ByteString = ByteString(ByteArray(8) { id })
    private fun dot(seq: Long): Dot = Dot(replica, seq)

    private fun span(
        trace: Byte,
        id: Byte,
        parent: Byte?,
        stamp: CausalStamp?,
    ) = SpanRecord(
        traceId = traceId(trace),
        spanId = spanId(id),
        parentSpanId = parent?.let { spanId(it) },
        name = "span-$id",
        kind = SpanKind.INTERNAL,
        startEpochNanos = id.toLong(),
        endEpochNanos = id.toLong() + 1,
        causalStamp = stamp,
    )

    // The headline test: A→B (trace1, B.parent=A), C→D (trace2, D.parent=C),
    // causal chain A→B→C→D via stamps. Exactly one cross-boundary link: C→B.
    @Test
    fun infersExactlyTheCrossBoundaryLink() {
        val a = span(1, 1, parent = null, stamp = CausalStamp(dot(1), emptySet()))
        val b = span(1, 2, parent = 1, stamp = CausalStamp(dot(2), setOf(dot(1))))
        val c = span(2, 3, parent = null, stamp = CausalStamp(dot(3), setOf(dot(2))))
        val d = span(2, 4, parent = 3, stamp = CausalStamp(dot(4), setOf(dot(3))))

        val links = inferCausalLinks(listOf(a, b, c, d))

        assertAll(
            { assertEquals(1, links.size, "only C→B is cross-boundary") },
            { assertEquals(spanId(3), links.single().fromSpanId) },
            { assertEquals(spanId(2), links.single().linkedSpanId) },
            { assertEquals(traceId(1), links.single().linkedTraceId) },
            { assertEquals("potential", links.single().attributes["kuilt.causality"]) },
        )
    }

    // Pure parent chain: every predecessor IS the parent ⇒ zero links.
    @Test
    fun suppressesPredecessorsThatAreTheParent() {
        val a = span(1, 1, parent = null, stamp = CausalStamp(dot(1), emptySet()))
        val b = span(1, 2, parent = 1, stamp = CausalStamp(dot(2), setOf(dot(1))))
        val c = span(1, 3, parent = 2, stamp = CausalStamp(dot(3), setOf(dot(2))))

        assertEquals(emptyList(), inferCausalLinks(listOf(a, b, c)))
    }

    // Same-trace, non-parent causal edge ⇒ link emitted (C-rule keeps non-tree edges).
    @Test
    fun emitsSameTraceNonParentEdge() {
        val a = span(1, 1, parent = null, stamp = CausalStamp(dot(1), emptySet()))
        val b = span(1, 2, parent = 1, stamp = CausalStamp(dot(2), setOf(dot(1))))
        // C's parent is A, but its causal predecessor is B (a sibling) — not the parent.
        val c = span(1, 3, parent = 1, stamp = CausalStamp(dot(3), setOf(dot(2))))

        val links = inferCausalLinks(listOf(a, b, c))
        assertAll(
            { assertEquals(1, links.size) },
            { assertEquals(spanId(3), links.single().fromSpanId) },
            { assertEquals(spanId(2), links.single().linkedSpanId) },
        )
    }

    @Test
    fun ignoresUnstampedSpans() {
        val a = span(1, 1, parent = null, stamp = null)
        val b = span(1, 2, parent = 1, stamp = null)
        assertEquals(emptyList(), inferCausalLinks(listOf(a, b)))
    }

    @Test
    fun skipsUnresolvedPredecessorWithoutThrowing() {
        // Predecessor dot(99) has no span in the set (late/partial sync).
        val orphan = span(1, 1, parent = null, stamp = CausalStamp(dot(1), setOf(dot(99))))
        assertEquals(emptyList(), inferCausalLinks(listOf(orphan)))
    }

    @Test
    fun producesDeterministicOutputRegardlessOfInputOrder() {
        val a = span(1, 1, parent = null, stamp = CausalStamp(dot(1), emptySet()))
        val b = span(2, 2, parent = null, stamp = CausalStamp(dot(2), setOf(dot(1))))
        val c = span(3, 3, parent = null, stamp = CausalStamp(dot(3), setOf(dot(1), dot(2))))

        val ordered = inferCausalLinks(listOf(a, b, c))
        val shuffled = inferCausalLinks(listOf(c, a, b))
        assertEquals(ordered, shuffled)
        assertTrue(ordered.size >= 2, "scenario should produce multiple links")
    }

    // When two links share (fromSpanId, linkedSpanId) the sort must still be total:
    // linkedTraceId breaks the tie. Two predecessors with identical spanId bytes but
    // different traceId bytes must land in a deterministic, consistent order.
    @Test
    fun sortIsTotalWhenLinkedSpanIdCollides() {
        val replicaB = ReplicaId("B")
        // Two spans with the same spanId bytes but different traceId bytes.
        val p1 = SpanRecord(
            traceId = traceId(10), spanId = spanId(1), parentSpanId = null,
            name = "p1", kind = SpanKind.INTERNAL, startEpochNanos = 1, endEpochNanos = 2,
            causalStamp = CausalStamp(Dot(replicaB, 1L), emptySet()),
        )
        val p2 = SpanRecord(
            traceId = traceId(20), spanId = spanId(1), parentSpanId = null,
            name = "p2", kind = SpanKind.INTERNAL, startEpochNanos = 1, endEpochNanos = 2,
            causalStamp = CausalStamp(Dot(replicaB, 2L), emptySet()),
        )
        // Successor points at both predecessors.
        val s = SpanRecord(
            traceId = traceId(30), spanId = spanId(2), parentSpanId = null,
            name = "s", kind = SpanKind.INTERNAL, startEpochNanos = 3, endEpochNanos = 4,
            causalStamp = CausalStamp(dot(3), setOf(Dot(replicaB, 1L), Dot(replicaB, 2L))),
        )
        val forward = inferCausalLinks(listOf(p1, p2, s))
        val reversed = inferCausalLinks(listOf(s, p2, p1))
        assertEquals(2, forward.size)
        assertEquals(forward, reversed)
    }

    @Test
    fun stampedSpanSurvivesOrSetCborRoundTrip() {
        val cbor = Cbor { alwaysUseByteString = true }
        val serializer = ORSet.serializer(SpanRecord.serializer())
        val stamped = span(1, 2, parent = 1, stamp = CausalStamp(dot(2), setOf(dot(1))))

        val set = ORSet.empty<SpanRecord>().add(replica, stamped)
        val bytes = cbor.encodeToByteArray(serializer, set)
        val decoded = cbor.decodeFromByteArray(serializer, bytes)

        assertEquals(set.elements, decoded.elements)
        assertEquals(stamped.causalStamp, decoded.elements.single().causalStamp)
    }
}
