package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId

internal class LWWMapConvergenceTest : CrdtConvergenceSuite<LWWMap<String, String>>() {
    override fun newHarness(): CrdtConvergenceHarness<LWWMap<String, String>> = CrdtConvergenceHarness(
        initial = LWWMap.empty(),
        gen = OperationGenerator { state, replicaIndex, random ->
            val r = ReplicaId("R$replicaIndex")
            // Sparse timestamp space forces frequent ties — exercises the replicaId tie-breaker.
            val ts = random.nextLong(0L, 10L)
            val key = "k-${random.nextInt(0, 3)}"
            val value = "v-${random.nextInt(0, 10)}"
            state.set(r, ts, key, value)
        },
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
