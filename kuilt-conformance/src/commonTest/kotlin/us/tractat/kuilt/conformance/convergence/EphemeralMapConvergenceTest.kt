package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Convergence stress-test for [EphemeralMap].
 *
 * Ops randomly mix put and leave with a sparse clock pool (0–4) so that
 * equal-clock present-vs-null collisions occur frequently across replicas.
 * Each replica writes only to its own slot (single-writer contract), but
 * the merge combines all replicas' views and must converge identically
 * under every delivery permutation.
 */
internal class EphemeralMapConvergenceTest : CrdtConvergenceSuite<EphemeralMap<String>>() {

    private val replicaIds = List(3) { ReplicaId("R$it") }

    override fun newHarness(): CrdtConvergenceHarness<EphemeralMap<String>> = CrdtConvergenceHarness(
        initial = EphemeralMap.empty(),
        alphabet = listOf(
            LatticeOp("put", OpKind.ASSERT) { state, replicaIndex, random ->
                // Sparse clock space (0–4) ensures frequent equal-clock collisions
                // between put and leave ops from the same replica across replica histories.
                state.put(replicaIds[replicaIndex], "v${random.nextInt(4)}", random.nextLong(0L, 5L))
            },
            LatticeOp("leave", OpKind.RETIRE) { state, replicaIndex, random ->
                state.leave(replicaIds[replicaIndex], random.nextLong(0L, 5L))
            },
        ),
        // **Deliberately no critical shape, and this is a declaration rather than an oversight.**
        // `put` and `leave` both no-op unless their clock strictly exceeds the replica's current
        // one, and both draw that clock from the sparse pool above — so a fixed word like
        // `put · leave · put` reaches its shape only on the seeds where the draws happen to
        // ascend. The harness would fail such a shape rather than let it read as coverage, which
        // is the right outcome and not one to route around by weakening the assertion. Giving
        // these ops a monotone, state-derived clock is what makes the shape reachable, and that is
        // #2101's Task 4, which owns this file next; the default shape applies the moment it lands.
        criticalShapes = emptyList(),
        serializer = EphemeralMap.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
