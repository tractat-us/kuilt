package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId

// Fixed key pool forces concurrent put/remove collisions — exercises add-wins semantics
// under every delivery permutation.
internal class ORMapConvergenceTest : CrdtConvergenceSuite<ORMap<String, GCounter>>() {
    override fun newHarness(): CrdtConvergenceHarness<ORMap<String, GCounter>> = CrdtConvergenceHarness(
        initial = ORMap.empty(),
        gen = OperationGenerator { state, replicaIndex, random ->
            val r = ReplicaId("R$replicaIndex")
            val key = "k-${random.nextInt(0, 3)}"
            when (random.nextInt(0, 3)) {
                0 -> state.put(r, key, GCounter.of(r to 1L))
                1 -> state.remove(key)
                else -> state.put(r, key, GCounter.of(r to random.nextLong(1L, 4L)))
            }
        },
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
