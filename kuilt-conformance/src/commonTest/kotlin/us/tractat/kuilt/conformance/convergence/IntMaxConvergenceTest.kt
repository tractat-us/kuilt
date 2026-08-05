package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.Quilted

@Serializable
internal data class IntMax(val value: Int) : Quilted<IntMax> {
    override fun piece(other: IntMax): IntMax = IntMax(maxOf(value, other.value))
}

internal class IntMaxConvergenceTest : CrdtConvergenceSuite<IntMax>() {
    override fun newHarness(): CrdtConvergenceHarness<IntMax> = CrdtConvergenceHarness(
        initial = IntMax(0),
        // A max-lattice over a total order: every join is trivial, nothing is ever retired, and the
        // laws are free. That is the point of binding it — it is the reference for what a free pass
        // looks like, so a real type accidentally reading like this is recognisable.
        alphabet = listOf(
            LatticeOp("raise", OpKind.ASSERT) { state, _, random ->
                IntMax(state.value + random.nextInt(1, 100))
            },
        ),
        serializer = IntMax.serializer(),
        replicaCount = 3,
        opsPerReplica = 5,
    )
}
