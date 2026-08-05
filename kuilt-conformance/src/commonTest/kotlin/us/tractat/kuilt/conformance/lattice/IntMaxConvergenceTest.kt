package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.Quilted

@Serializable
internal data class IntMax(val value: Int) : Quilted<IntMax> {
    override fun piece(other: IntMax): IntMax = IntMax(maxOf(value, other.value))
}

internal class IntMaxConvergenceTest : LatticeLawSuite<IntMax>() {
    override fun newHarness(): LatticeLawHarness<IntMax> = LatticeLawHarness(
        initial = IntMax(0),
        // A max-lattice over a total order: every join is trivial, nothing is ever retired, and the
        // laws are free. That is the point of binding it — it is the reference for what a free pass
        // looks like, so a real type accidentally reading like this is recognisable.
        alphabet = listOf(
            LatticeOp("raise", OpKind.ASSERT) { state, _, random ->
                IntMax(state.value + random.nextInt(1, 100))
            },
        ),
        // Both waivers, which is the other half of being the reference free pass. `raise` only ever
        // moves the value up, so there is nothing to retire; and the reachable states are a chain,
        // so no generator can produce a concurrent pair — measured 48.4% strict-ancestor pairs
        // against a 50% ceiling that only a total order can reach, and 0.0% concurrent.
        //
        // Declaring them is the whole point rather than a formality: the two floors this binding
        // cannot meet are exactly the two a real type reads like when its generator has stopped
        // searching, so the free pass has to be a signed statement instead of a silent property of
        // the data. Anything that finds itself writing these two lines should be sure it is binding
        // a lattice as simple as `max`.
        floors = VacuityFloors(effectiveRetireSteps = 0.0, totalOrder = true),
        serializer = IntMax.serializer(),
        replicaCount = 3,
        opsPerReplica = 5,
    )
}
