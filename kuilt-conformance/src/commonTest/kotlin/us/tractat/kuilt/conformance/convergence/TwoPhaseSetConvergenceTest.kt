package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.TwoPhaseSet
import us.tractat.kuilt.crdt.piece

// Mixed adds and removes populate both the added and removed sets, so both fields are
// exercised for canonical encoding.
private const val FOCUS = "e0"
private const val OTHER = "e1"

internal class TwoPhaseSetConvergenceTest : CrdtConvergenceSuite<TwoPhaseSet<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<TwoPhaseSet<String>> = CrdtConvergenceHarness(
        initial = TwoPhaseSet.empty(),
        // The derived default — `add · remove · add-other` — is the right word here, and its second
        // assert has to name a DIFFERENT element rather than repeat the first. A tombstone in a
        // two-phase set is permanent, so re-adding what was just removed puts the element into a
        // set it is already in: the state does not move and the shape would assert nothing. The
        // harness would say so rather than let it pass; naming a distinct element is the fix.
        alphabet = listOf(
            LatticeOp("add", OpKind.ASSERT) { state, _, _ -> state.piece(state.add(FOCUS)) },
            LatticeOp("add-other", OpKind.ASSERT) { state, _, _ -> state.piece(state.add(OTHER)) },
            LatticeOp("add-roam", OpKind.ASSERT) { state, _, random ->
                state.piece(state.add("e${random.nextInt(6)}"))
            },
            LatticeOp("remove", OpKind.RETIRE) { state, _, _ -> state.piece(state.remove(FOCUS)) },
            LatticeOp("remove-roam", OpKind.RETIRE) { state, _, random ->
                state.piece(state.remove("e${random.nextInt(6)}"))
            },
        ),
        serializer = TwoPhaseSet.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
