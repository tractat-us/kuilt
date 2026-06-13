package us.tractat.kuilt.conformance.convergence

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
        gen = OperationGenerator { state, replicaIndex, random ->
            val replica = replicaIds[replicaIndex]
            // Sparse clock space (0–4) ensures frequent equal-clock collisions
            // between put and leave ops from the same replica across replica histories.
            val clock = random.nextLong(0L, 5L)
            if (random.nextBoolean()) {
                state.put(replica, "v${random.nextInt(4)}", clock)
            } else {
                state.leave(replica, clock)
            }
        },
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
