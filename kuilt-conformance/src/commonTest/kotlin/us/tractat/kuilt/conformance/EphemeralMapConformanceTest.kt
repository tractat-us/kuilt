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

    override fun samples(): List<EphemeralMap<String>> = listOf(
        // bottom element
        EphemeralMap.empty(),
        // single present entry
        EphemeralMap.empty<String>().put(r1, "alpha", clock = 1L),
        // single departure
        EphemeralMap.empty<String>().leave(r1, clock = 2L),
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
}
