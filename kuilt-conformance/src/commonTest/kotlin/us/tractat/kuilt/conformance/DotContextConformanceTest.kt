package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.ReplicaId

/** DotContext is a join-semilattice (union of causal histories) — it obeys every law. */
internal class DotContextConformanceTest : QuiltedConformanceSuite<DotContext>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<DotContext> = listOf(
        DotContext.EMPTY,
        DotContext.of(Dot(a, 1L)),
        DotContext.of(Dot(a, 1L), Dot(a, 2L)),
        DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        DotContext.of(Dot(b, 3L)),
    )
}
