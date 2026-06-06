package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.DotMap
import us.tractat.kuilt.crdt.DotSet
import us.tractat.kuilt.crdt.ReplicaId

/** Causal<DotMap<String, DotSet>> is the OR-Set lattice — it obeys every law. */
internal class CausalDotMapConformanceTest : QuiltedConformanceSuite<Causal<DotMap<String, DotSet>>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private fun map(vararg entries: Pair<String, Set<Dot>>): DotMap<String, DotSet> =
        DotMap(entries.associate { (k, v) -> k to DotSet(v) })

    override fun samples(): List<Causal<DotMap<String, DotSet>>> = listOf(
        Causal(DotMap(), DotContext.EMPTY),
        Causal(map("x" to setOf(Dot(a, 1L))), DotContext.of(Dot(a, 1L))),
        Causal(DotMap<String, DotSet>(), DotContext.of(Dot(a, 1L))), // x removed
        Causal(
            map("x" to setOf(Dot(a, 1L)), "y" to setOf(Dot(b, 1L))),
            DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        ),
        Causal(map("y" to setOf(Dot(b, 1L))), DotContext.of(Dot(a, 1L), Dot(b, 1L))),
    )
}
