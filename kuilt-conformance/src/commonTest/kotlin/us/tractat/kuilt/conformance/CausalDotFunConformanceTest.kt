package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.DotFun
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Causal<DotFun<String>> is the multi-value register lattice — it obeys every
 * law. Note: each Dot maps to a FIXED value across all samples, honouring the
 * "a dot is minted once" invariant.
 */
internal class CausalDotFunConformanceTest : QuiltedConformanceSuite<Causal<DotFun<String>>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private val asserted = Causal(DotFun(mapOf(Dot(a, 1L) to "x")), DotContext.of(Dot(a, 1L)))
    private val retired = Causal(DotFun<String>(emptyMap()), DotContext.of(Dot(a, 1L))) // written then removed

    /** A writes again after its first write was retired — a fresh dot, and a value of its own. */
    private val reAsserted =
        Causal(DotFun(mapOf(Dot(a, 2L) to "z")), DotContext.of(Dot(a, 1L), Dot(a, 2L)))

    override fun samples(): List<Causal<DotFun<String>>> = listOf(
        Causal(DotFun(), DotContext.EMPTY),
        asserted,
        retired,
        Causal(
            DotFun(mapOf(Dot(a, 1L) to "x", Dot(b, 1L) to "y")),
            DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        ),
        Causal(DotFun(mapOf(Dot(b, 1L) to "y")), DotContext.of(Dot(a, 1L), Dot(b, 1L))),
        reAsserted,
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<Causal<DotFun<String>>> =
        RetirementReAssertion(
            subject = "the register's value",
            asserted = asserted,
            retired = retired,
            reAsserted = reAsserted,
            shows = { it.store.values.isNotEmpty() },
        )
}
