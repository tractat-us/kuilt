package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/** LWWMap is the per-key product of LWWRegister lattices — it obeys every law. */
internal class LWWMapConformanceTest : QuiltedConformanceSuite<LWWMap<String, String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private val base = LWWMap.empty<String, String>()

    // A separate key, written then removed then written again — the shape the other five samples
    // have no removal at all to express. Timestamps strictly increase so each state observes the
    // last, and every (replica, timestamp, key) tag carries exactly one write.
    private val asserted = base.piece { it.set(a, 10L, "k3", "p") }
    private val retired = asserted.piece { it.remove(a, 20L, "k3") }
    private val reAsserted = retired.piece { it.set(a, 30L, "k3", "q") }

    override fun samples(): List<LWWMap<String, String>> = listOf(
        base,
        base.piece { it.set(a, 10L, "k1", "x") },
        base.piece { it.set(b, 20L, "k1", "y") },
        base.piece { it.set(a, 10L, "k2", "u") },
        base.piece { it.set(a, 10L, "k1", "x") }.piece { it.set(b, 15L, "k2", "v") },
        asserted,
        retired,
        reAsserted,
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<LWWMap<String, String>> =
        RetirementReAssertion(
            subject = """key "k3"""",
            asserted = asserted,
            retired = retired,
            reAsserted = reAsserted,
            shows = { "k3" in it.entries },
        )
}
