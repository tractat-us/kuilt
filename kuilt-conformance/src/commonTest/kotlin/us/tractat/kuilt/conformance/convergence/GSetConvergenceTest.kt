package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.GSet

// A small element pool forces overlapping adds across replicas, so the merged sets differ
// only in insertion order — the shape that exposes a non-canonical set encoding.
internal class GSetConvergenceTest : CrdtConvergenceSuite<GSet<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<GSet<String>> = CrdtConvergenceHarness(
        initial = GSet.empty(),
        // Grow-only by construction — `GSet` has no removal, so there is no RETIRE op to declare
        // and no critical shape to derive.
        alphabet = listOf(
            LatticeOp("add", OpKind.ASSERT) { state, _, random ->
                state.piece(GSet.of("e${random.nextInt(6)}"))
            },
        ),
        // `GSet` only grows — `add` is its whole vocabulary and nothing takes an element back.
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = GSet.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
