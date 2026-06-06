package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId

/** LWWRegister is a (timestamp, replicaId)-ordered max lattice — it obeys every law. */
internal class LWWRegisterConformanceTest : QuiltedConformanceSuite<LWWRegister<String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<LWWRegister<String>> {
        val base = LWWRegister.empty<String>()
        return listOf(
            base,
            base.set(a, 10L, "x"),
            base.set(b, 10L, "y"),  // same ts as above; tie-breaks on replica
            base.set(a, 20L, "x2"),
            base.set(b, 30L, "z"),
        )
    }
}
