package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.LatticeDotFun
import us.tractat.kuilt.crdt.ReplicaId

/**
 * `Causal<LatticeDotFun<GCounter>>` obeys every law **even when one dot carries different payloads
 * on different samples** — the case `CausalDotFunConformanceTest` has to rule out by hand ("each Dot
 * maps to a FIXED value across all samples"), because `DotFun`'s join keeps the left payload.
 *
 * `bumpedByB` and `bumpedByC` hold the same dot `(A,1)` with payloads each replica grew on its own.
 * Bound to `DotFun` instead, those two samples alone red [pieceIsCommutative].
 */
internal class CausalLatticeDotFunConformanceTest : QuiltedConformanceSuite<Causal<LatticeDotFun<GCounter>>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")
    private val d1 = Dot(a, 1L)
    private val d2 = Dot(b, 1L)

    private val asserted = Causal(LatticeDotFun(mapOf(d1 to GCounter.of(a to 1L))), DotContext.of(d1))
    private val bumpedByB = Causal(LatticeDotFun(mapOf(d1 to GCounter.of(a to 1L, b to 2L))), DotContext.of(d1))
    private val bumpedByC = Causal(LatticeDotFun(mapOf(d1 to GCounter.of(a to 1L, c to 5L))), DotContext.of(d1))

    /** `(A,1)` removed after it was seen: witnessed, absent from the store. */
    private val retired = Causal(LatticeDotFun<GCounter>(), DotContext.of(d1))

    /** A fresh dot under a different author, so the retired payload cannot dominate it. */
    private val reAsserted = Causal(LatticeDotFun(mapOf(d2 to GCounter.of(b to 1L))), DotContext.of(d1, d2))

    override fun samples(): List<Causal<LatticeDotFun<GCounter>>> = listOf(
        Causal(LatticeDotFun(), DotContext.EMPTY),
        asserted,
        bumpedByB,
        bumpedByC,
        retired,
        reAsserted,
        Causal(LatticeDotFun(mapOf(d2 to GCounter.of(b to 3L))), DotContext.of(d2)),
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<Causal<LatticeDotFun<GCounter>>> =
        RetirementReAssertion(
            subject = "a live dot",
            asserted = asserted,
            retired = retired,
            reAsserted = reAsserted,
            shows = { it.store.values.isNotEmpty() },
        )
}
