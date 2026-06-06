package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId

/** GCounter is a join-semilattice (elementwise max) — it obeys every law. */
internal class GCounterConformanceTest : QuiltedConformanceSuite<GCounter>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<GCounter> = listOf(
        GCounter.ZERO,
        GCounter.of(a to 1L),
        GCounter.of(a to 2L),
        GCounter.of(a to 1L, b to 1L),
        GCounter.of(b to 5L),
    )
}
