package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

// Mixed increments and decrements populate both backing GCounters, so both are exercised
// for canonical encoding. Mirrors GCounterConvergenceTest.
internal class PNCounterConvergenceTest : CrdtConvergenceSuite<PNCounter>() {
    override fun newHarness(): CrdtConvergenceHarness<PNCounter> = CrdtConvergenceHarness(
        initial = PNCounter.ZERO,
        // `decrement` is NOT a RETIRE. It withdraws nothing: a PNCounter is two grow-only counters
        // and a decrement adds to the second one, so the observable value falls while every prior
        // contribution stays exactly where it was. Retirement is about what a later op takes back,
        // not about which direction a number moves.
        alphabet = listOf(
            LatticeOp("inc", OpKind.ASSERT) { state, replicaIndex, random ->
                state.piece(state.increment(ReplicaId("R$replicaIndex"), random.nextLong(1L, 4L)))
            },
            LatticeOp("dec", OpKind.ASSERT) { state, replicaIndex, random ->
                state.piece(state.decrement(ReplicaId("R$replicaIndex"), random.nextLong(1L, 4L)))
            },
        ),
        serializer = PNCounter.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
