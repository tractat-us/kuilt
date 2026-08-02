package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.TwoPhaseSet
import us.tractat.kuilt.crdt.piece

// Mixed adds and removes populate both the added and removed sets, so both fields are
// exercised for canonical encoding.
internal class TwoPhaseSetConvergenceTest : CrdtConvergenceSuite<TwoPhaseSet<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<TwoPhaseSet<String>> = CrdtConvergenceHarness(
        initial = TwoPhaseSet.empty(),
        gen = OperationGenerator { state, _, random ->
            val element = "e${random.nextInt(6)}"
            if (random.nextInt(3) == 0) state.piece(state.remove(element))
            else state.piece(state.add(element))
        },
        serializer = TwoPhaseSet.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
