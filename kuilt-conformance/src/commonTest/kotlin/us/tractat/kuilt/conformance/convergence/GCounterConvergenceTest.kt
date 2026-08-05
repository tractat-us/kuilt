package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

internal class GCounterConvergenceTest : CrdtConvergenceSuite<GCounter>() {
    override fun newHarness(): CrdtConvergenceHarness<GCounter> = CrdtConvergenceHarness(
        initial = GCounter.ZERO,
        // Grow-only: no RETIRE op exists, so `defaultCriticalShapes` yields none. That is the
        // honest reading — there is nothing to retire and re-assert.
        alphabet = listOf(
            LatticeOp("inc", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = ReplicaId("R$replicaIndex")
                state.piece(state.inc(replica, by = random.nextLong(1L, 6L)))
            },
        ),
        serializer = GCounter.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
