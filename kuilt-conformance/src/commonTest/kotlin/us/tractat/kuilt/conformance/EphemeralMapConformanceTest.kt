package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Lattice-law conformance for [EphemeralMap].
 *
 * Samples are chosen to exercise the full decision tree in [EphemeralMap.piece]:
 * - distinct replicas (no conflict)
 * - same replica, strictly higher clock wins
 * - same replica, equal clock — present beats null (the tie-break)
 * - multi-replica overlapping states
 *
 * Each sample uses a distinct [ReplicaId] slot where possible; conflict cases
 * deliberately share a slot at the same clock to probe the tie-break.
 */
internal class EphemeralMapConformanceTest : QuiltedConformanceSuite<EphemeralMap<String>>() {

    private val r1 = ReplicaId("R1")
    private val r2 = ReplicaId("R2")
    private val r3 = ReplicaId("R3")

    private val r1Present = EphemeralMap.empty<String>().put(r1, "alpha", clock = 1L)

    /**
     * `leave` publishes a null-value entry that outranks every entry the replica published before
     * it, so R1 stops being shown at all — an observation withdrawn with nothing put in its place,
     * which is retirement under [us.tractat.kuilt.conformance.lattice.OpKind] and the reading
     * `EphemeralMapConvergenceTest`'s `leave` op already takes.
     */
    private val r1Departed = EphemeralMap.empty<String>().leave(r1, clock = 2L)

    /**
     * A departed replica returns only by publishing at a clock **strictly above** its own
     * departure clock — the rejoin the tombstone's permanence is specified against.
     *
     * Clock 4, not 3: the multi-replica sample below already publishes R1 at clock 3, and
     * `dominates` leaves two *present* entries at one clock un-ordered — the single-writer contract
     * precludes them, so a shared tag across the sample list would red `pieceIsCommutative` rather
     * than say anything about retirement.
     */
    private val r1ReJoined = EphemeralMap.empty<String>().put(r1, "alpha", clock = 4L)

    override fun samples(): List<EphemeralMap<String>> = listOf(
        // bottom element
        EphemeralMap.empty(),
        // single present entry
        r1Present,
        // single departure
        r1Departed,
        // rejoin above the departure clock
        r1ReJoined,
        // two independent replicas
        EphemeralMap.empty<String>()
            .put(r1, "alpha", clock = 1L)
            .put(r2, "beta", clock = 1L),
        // equal-clock present entry (tie-break participant: present side)
        EphemeralMap.empty<String>().put(r3, "live", clock = 5L),
        // equal-clock departure (tie-break participant: null side — same clock as above)
        EphemeralMap.empty<String>().leave(r3, clock = 5L),
        // multi-replica mix: r1 present, r2 departed, r3 present at higher clock
        EphemeralMap.empty<String>()
            .put(r1, "x", clock = 3L)
            .leave(r2, clock = 2L)
            .put(r3, "z", clock = 7L),
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<EphemeralMap<String>> =
        RetirementReAssertion(
            subject = "R1's presence",
            asserted = r1Present,
            retired = r1Departed,
            reAsserted = r1ReJoined,
            shows = { it.entries[r1]?.value != null },
        )
}
