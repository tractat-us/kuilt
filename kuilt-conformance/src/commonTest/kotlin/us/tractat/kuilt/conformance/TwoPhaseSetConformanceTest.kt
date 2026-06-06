package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.TwoPhaseSet
import us.tractat.kuilt.crdt.piece

/** TwoPhaseSet is the product of two grow-only sets — it obeys every law. */
internal class TwoPhaseSetConformanceTest : QuiltedConformanceSuite<TwoPhaseSet<String>>() {
    override fun samples(): List<TwoPhaseSet<String>> {
        val base = TwoPhaseSet.empty<String>()
        val a = base.piece(base.add("a"))
        val ab = a.piece(a.add("b"))
        val abMinusA = ab.piece(ab.remove("a"))
        return listOf(base, a, ab, abMinusA, base.piece(base.add("c")))
    }
}
