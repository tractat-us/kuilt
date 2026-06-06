package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.GSet

/** GSet is a join-semilattice (set union) — it obeys every law. */
internal class GSetConformanceTest : QuiltedConformanceSuite<GSet<String>>() {
    override fun samples(): List<GSet<String>> = listOf(
        GSet.empty(),
        GSet.of("a"),
        GSet.of("a", "b"),
        GSet.of("b", "c"),
        GSet.of("x"),
    )
}
