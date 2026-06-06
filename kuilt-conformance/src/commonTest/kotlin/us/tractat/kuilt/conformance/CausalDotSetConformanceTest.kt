package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.DotSet
import us.tractat.kuilt.crdt.ReplicaId

/** Causal<DotSet> is the optimized add-wins set lattice — it obeys every law. */
internal class CausalDotSetConformanceTest : QuiltedConformanceSuite<Causal<DotSet>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<Causal<DotSet>> = listOf(
        Causal(DotSet(), DotContext.EMPTY),
        Causal(DotSet(setOf(Dot(a, 1L))), DotContext.of(Dot(a, 1L))),
        Causal(DotSet(emptySet()), DotContext.of(Dot(a, 1L))), // saw (A,1) and removed it
        Causal(DotSet(setOf(Dot(a, 1L), Dot(b, 1L))), DotContext.of(Dot(a, 1L), Dot(b, 1L))),
        Causal(DotSet(setOf(Dot(b, 1L))), DotContext.of(Dot(a, 1L), Dot(b, 1L))),
    )
}
