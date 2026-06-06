package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

internal class GCounterConvergenceTest : CrdtConvergenceSuite<GCounter>() {
    override fun newHarness(): CrdtConvergenceHarness<GCounter> = CrdtConvergenceHarness(
        initial = GCounter.ZERO,
        gen = OperationGenerator { state, replicaIndex, random ->
            val replica = ReplicaId("R$replicaIndex")
            val delta = state.inc(replica, by = random.nextLong(1L, 6L))
            state.piece(delta)
        },
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
