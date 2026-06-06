package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId

/** ORSet is the add-wins set lattice (Causal<DotMap<E, DotSet>>) — it obeys every law. */
internal class ORSetConformanceTest : QuiltedConformanceSuite<ORSet<String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<ORSet<String>> {
        val base = ORSet.empty<String>()
        val x = base.add(a, "x")
        val xy = x.add(b, "y")
        return listOf(
            base,
            x,
            x.remove("x"),
            xy,
            xy.remove("x"),
        )
    }
}
