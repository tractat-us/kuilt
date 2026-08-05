package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/** ORSet is the add-wins set lattice (Causal<DotMap<E, DotSet>>) — it obeys every law. */
internal class ORSetConformanceTest : QuiltedConformanceSuite<ORSet<String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    private val base = ORSet.empty<String>()
    private val x = base.piece { it.add(a, "x") }
    private val xRetired = x.piece { it.remove("x") }

    /** C adds "x" back after A removed it, under a fresh tag of its own. */
    private val xReAsserted = xRetired.piece { it.add(c, "x") }

    private val xy = x.piece { it.add(b, "y") }

    override fun samples(): List<ORSet<String>> = listOf(
        base,
        x,
        xRetired,
        xy,
        xy.piece { it.remove("x") },
        xReAsserted,
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): Triple<ORSet<String>, ORSet<String>, ORSet<String>> =
        Triple(x, xRetired, xReAsserted)
}
