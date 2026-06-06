package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId

/** LWWMap is the per-key product of LWWRegister lattices — it obeys every law. */
internal class LWWMapConformanceTest : QuiltedConformanceSuite<LWWMap<String, String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<LWWMap<String, String>> {
        val base = LWWMap.empty<String, String>()
        return listOf(
            base,
            base.set(a, 10L, "k1", "x"),
            base.set(b, 20L, "k1", "y"),
            base.set(a, 10L, "k2", "u"),
            base.set(a, 10L, "k1", "x").set(b, 15L, "k2", "v"),
        )
    }
}
