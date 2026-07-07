package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga

/**
 * Rga's state is its op-log; piece is idempotent set-union of uniquely-identified
 * ops — it obeys every lattice law. Lamport high-water is excluded from equality.
 */
internal class RgaConformanceTest : QuiltedConformanceSuite<Rga<String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<Rga<String>> {
        val base = Rga.empty<String>()
        val x = base.insertAt(a, 0, "x").first
        val xy = x.insertAt(b, 1, "y").first
        val yOnly = xy.removeAt(0)!!.first
        // Concurrent sibling branch relative to xy: A extends x without seeing B's op.
        val xz = x.insertAt(a, 1, "z").first
        return listOf(base, x, xy, yOnly, xz)
    }
}
