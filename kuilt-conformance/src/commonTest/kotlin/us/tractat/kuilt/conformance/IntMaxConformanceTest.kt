package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Quilted

/** Smallest possible lattice — max-wins integer — used to validate the suite. */
internal data class IntMax(val value: Int) : Quilted<IntMax> {
    override fun piece(other: IntMax): IntMax = IntMax(maxOf(value, other.value))
}

/** If the suite is correct, IntMax (a genuine join-semilattice) passes every law. */
internal class IntMaxConformanceTest : QuiltedConformanceSuite<IntMax>() {
    override fun samples(): List<IntMax> =
        listOf(IntMax(0), IntMax(3), IntMax(7), IntMax(-2))
}
