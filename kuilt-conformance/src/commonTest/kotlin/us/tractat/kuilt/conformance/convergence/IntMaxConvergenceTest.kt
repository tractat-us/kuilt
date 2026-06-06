package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.Quilted

internal data class IntMax(val value: Int) : Quilted<IntMax> {
    override fun piece(other: IntMax): IntMax = IntMax(maxOf(value, other.value))
}

internal class IntMaxConvergenceTest : CrdtConvergenceSuite<IntMax>() {
    override fun newHarness(): CrdtConvergenceHarness<IntMax> = CrdtConvergenceHarness(
        initial = IntMax(0),
        gen = OperationGenerator { state, _, random ->
            IntMax(state.value + random.nextInt(1, 100))
        },
        replicaCount = 3,
        opsPerReplica = 5,
    )
}
