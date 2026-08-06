package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Fugue's state is its op-log; piece is idempotent set-union of uniquely-identified
 * ops — it obeys every lattice law. Lamport high-water is excluded from equality.
 */
internal class FugueConformanceTest : QuiltedConformanceSuite<Fugue<String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private val base = Fugue.empty<String>()
    private val x = base.insertAt(a, 0, "x").first
    private val xy = x.insertAt(b, 1, "y").first

    /**
     * `removeAt` tombstones the element carrying "x", so the sequence stops showing it and puts
     * nothing in its place — retirement under [us.tractat.kuilt.conformance.lattice.OpKind], and the
     * same reading `FugueConvergenceTest`'s `remove-head` op takes.
     */
    private val yOnly = xy.removeAt(0)!!.first

    /**
     * "x" comes back under a **fresh** [us.tractat.kuilt.crdt.FugueId] — the tombstone is on the old
     * id, so re-inserting the value is a new element rather than an un-removal, exactly as
     * re-adding to an `ORSet` mints a new tag.
     */
    private val xReAsserted = yOnly.insertAt(a, 0, "x").first

    // Concurrent sibling branch relative to xy: A extends x without seeing B's op.
    private val xz = x.insertAt(a, 1, "z").first

    override fun samples(): List<Fugue<String>> = listOf(base, x, xy, yOnly, xz, xReAsserted)

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<Fugue<String>> =
        RetirementReAssertion(
            subject = """the value "x" in the sequence""",
            asserted = x,
            retired = yOnly,
            reAsserted = xReAsserted,
            shows = { "x" in it.toList() },
        )
}
