package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId

/** LWWRegister is a (timestamp, replicaId)-ordered max lattice — it obeys every law. */
internal class LWWRegisterConformanceTest : QuiltedConformanceSuite<LWWRegister<String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private val base = LWWRegister.empty<String>()
    private val x = base.set(a, 10L, "x")

    /**
     * `unset` is a last-writer-wins **tombstone**: it competes under `piece` exactly like a `set`
     * and, once it wins, `value` reads `null`. That takes an observation back without putting
     * another in its place — retirement under the test in
     * [us.tractat.kuilt.conformance.lattice.OpKind], and the same reading
     * `LWWRegisterConvergenceTest`'s `unset-high` op takes.
     */
    private val xRetired = base.unset(a, 40L)

    /** A later write puts "x" back, at a tag the tombstone loses to. */
    private val xReAsserted = base.set(a, 50L, "x")

    override fun samples(): List<LWWRegister<String>> = listOf(
        base,
        x,
        base.set(b, 10L, "y"),  // same ts as x; tie-breaks on replica
        base.set(a, 20L, "x2"),
        base.set(b, 30L, "z"),
        xRetired,
        xReAsserted,
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<LWWRegister<String>> =
        RetirementReAssertion(
            subject = """the value "x"""",
            asserted = x,
            retired = xRetired,
            reAsserted = xReAsserted,
            shows = { it.value == "x" },
        )
}
