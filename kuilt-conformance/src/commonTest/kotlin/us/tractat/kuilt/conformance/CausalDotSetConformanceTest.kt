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

    private val asserted = Causal(DotSet(setOf(Dot(a, 1L))), DotContext.of(Dot(a, 1L)))
    private val retired = Causal(DotSet(emptySet()), DotContext.of(Dot(a, 1L))) // saw (A,1) and removed it

    /** (A,1) is retired and gone; (A,2) puts the membership back under a fresh dot. */
    private val reAsserted = Causal(DotSet(setOf(Dot(a, 2L))), DotContext.of(Dot(a, 1L), Dot(a, 2L)))

    override fun samples(): List<Causal<DotSet>> = listOf(
        Causal(DotSet(), DotContext.EMPTY),
        asserted,
        retired,
        Causal(DotSet(setOf(Dot(a, 1L), Dot(b, 1L))), DotContext.of(Dot(a, 1L), Dot(b, 1L))),
        Causal(DotSet(setOf(Dot(b, 1L))), DotContext.of(Dot(a, 1L), Dot(b, 1L))),
        reAsserted,
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): Triple<Causal<DotSet>, Causal<DotSet>, Causal<DotSet>> =
        Triple(asserted, retired, reAsserted)
}
