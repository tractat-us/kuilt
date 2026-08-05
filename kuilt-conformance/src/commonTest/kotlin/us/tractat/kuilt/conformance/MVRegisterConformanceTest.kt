package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ReplicaId

/** MVRegister is the multi-value register lattice (Causal<DotFun<V>>) — it obeys every law. */
internal class MVRegisterConformanceTest : QuiltedConformanceSuite<MVRegister<String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private val base = MVRegister.empty<String>()
    private val x = base.set(a, "x")
    private val y = base.set(b, "y")

    /** A's second write retires the dot carrying "x" — the register's only form of removal. */
    private val xRetired = x.set(a, "x2")

    /** …and its third puts "x" back, under a dot the retirement never saw. */
    private val xReAsserted = xRetired.set(a, "x")

    override fun samples(): List<MVRegister<String>> =
        listOf(base, x, y, x.piece(y), xRetired, xReAsserted)

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): Triple<MVRegister<String>, MVRegister<String>, MVRegister<String>> =
        Triple(x, xRetired, xReAsserted)
}
