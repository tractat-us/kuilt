package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ReplicaId

/** MVRegister is the multi-value register lattice (Causal<DotFun<V>>) — it obeys every law. */
internal class MVRegisterConformanceTest : QuiltedConformanceSuite<MVRegister<String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<MVRegister<String>> {
        val base = MVRegister.empty<String>()
        val x = base.set(a, "x")
        val y = base.set(b, "y")
        return listOf(base, x, y, x.piece(y), x.set(a, "x2"))
    }
}
