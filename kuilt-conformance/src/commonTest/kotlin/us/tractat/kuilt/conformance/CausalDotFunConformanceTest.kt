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

    override fun samples(): List<Causal<DotFun<String>>> = listOf(
        Causal(DotFun(), DotContext.EMPTY),
        Causal(DotFun(mapOf(Dot(a, 1L) to "x")), DotContext.of(Dot(a, 1L))),
        Causal(DotFun<String>(emptyMap()), DotContext.of(Dot(a, 1L))), // written then removed
        Causal(
            DotFun(mapOf(Dot(a, 1L) to "x", Dot(b, 1L) to "y")),
            DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        ),
        Causal(DotFun(mapOf(Dot(b, 1L) to "y")), DotContext.of(Dot(a, 1L), Dot(b, 1L))),
    )
}
