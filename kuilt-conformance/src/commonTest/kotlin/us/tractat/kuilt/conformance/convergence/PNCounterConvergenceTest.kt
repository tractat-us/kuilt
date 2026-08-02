package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

// Mixed increments and decrements populate both backing GCounters, so both are exercised
// for canonical encoding. Mirrors GCounterConvergenceTest.
internal class PNCounterConvergenceTest : CrdtConvergenceSuite<PNCounter>() {
    override fun newHarness(): CrdtConvergenceHarness<PNCounter> = CrdtConvergenceHarness(
        initial = PNCounter.ZERO,
        gen = OperationGenerator { state, replicaIndex, random ->
            val replica = ReplicaId("R$replicaIndex")
            val amount = random.nextLong(1L, 4L)
            if (random.nextBoolean()) {
                state.piece(state.increment(replica, amount))
            } else {
                state.piece(state.decrement(replica, amount))
            }
        },
        serializer = PNCounter.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
