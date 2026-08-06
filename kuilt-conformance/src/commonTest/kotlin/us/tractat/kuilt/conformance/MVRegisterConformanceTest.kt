package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ReplicaId

/** MVRegister is the multi-value register lattice (Causal<DotFun<V>>) — it obeys every law. */
internal class MVRegisterConformanceTest : QuiltedConformanceSuite<MVRegister<String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private val base = MVRegister.empty<String>()
    private val x = base.set(a, "x")
    private val y = base.set(b, "y")

    /**
     * A's second write stops the register showing "x", by superseding the dot that carried it.
     *
     * Under the test in `OpKind` that is **supersession, not retirement** — it puts a value in the
     * place of the one it drops — and `MVRegisterConvergenceTest` accordingly declares no `RETIRE`
     * op for the same `set`. It counts here because this surface *checks* the shape rather than
     * averaging it, which is the carve-out `OpKind` states; the guard below reds if this write
     * does not really stop "x" being shown.
     */
    private val xRetired = x.set(a, "x2")

    /** …and its third puts "x" back, under a dot the retirement never saw. */
    private val xReAsserted = xRetired.set(a, "x")

    override fun samples(): List<MVRegister<String>> =
        listOf(base, x, y, x.piece(y), xRetired, xReAsserted)

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<MVRegister<String>> =
        RetirementReAssertion(
            subject = """the value "x"""",
            asserted = x,
            retired = xRetired,
            reAsserted = xReAsserted,
            shows = { "x" in it.values },
        )
}
