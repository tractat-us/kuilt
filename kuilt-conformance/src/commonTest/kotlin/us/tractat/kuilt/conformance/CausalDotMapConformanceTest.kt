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

    private val asserted = Causal(map("x" to setOf(Dot(a, 1L))), DotContext.of(Dot(a, 1L)))
    private val retired = Causal(DotMap<String, DotSet>(), DotContext.of(Dot(a, 1L))) // x removed

    /** "x" comes back under a fresh dot, after the dot that first carried it was retired. */
    private val reAsserted =
        Causal(map("x" to setOf(Dot(a, 2L))), DotContext.of(Dot(a, 1L), Dot(a, 2L)))

    override fun samples(): List<Causal<DotMap<String, DotSet>>> = listOf(
        Causal(DotMap(), DotContext.EMPTY),
        asserted,
        retired,
        Causal(
            map("x" to setOf(Dot(a, 1L)), "y" to setOf(Dot(b, 1L))),
            DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        ),
        Causal(map("y" to setOf(Dot(b, 1L))), DotContext.of(Dot(a, 1L), Dot(b, 1L))),
        reAsserted,
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<Causal<DotMap<String, DotSet>>> =
        RetirementReAssertion(
            subject = """key "x"""",
            asserted = asserted,
            retired = retired,
            reAsserted = reAsserted,
            shows = { "x" in it.store.entries },
        )
}
