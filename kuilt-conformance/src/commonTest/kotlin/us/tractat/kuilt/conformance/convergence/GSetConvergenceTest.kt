package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.GSet

// A small element pool forces overlapping adds across replicas, so the merged sets differ
// only in insertion order — the shape that exposes a non-canonical set encoding.
internal class GSetConvergenceTest : CrdtConvergenceSuite<GSet<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<GSet<String>> = CrdtConvergenceHarness(
        initial = GSet.empty(),
        gen = OperationGenerator { state, _, random ->
            state.piece(GSet.of("e${random.nextInt(6)}"))
        },
        serializer = GSet.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
