package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

// Fixed element pool forces frequent concurrency collisions — exactly the pattern that reveals
// add-wins vs remove-wins ambiguity bugs. The `-roam` ops draw from it; the pinned ops agree on
// ELEMENTS[0] so the critical shape's three steps touch the same element.
private val ELEMENTS = listOf("elem-0", "elem-1", "elem-2", "elem-3")
private val FOCUS = ELEMENTS[0]

internal class ORSetConvergenceTest : CrdtConvergenceSuite<ORSet<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<ORSet<String>> = CrdtConvergenceHarness(
        initial = ORSet.empty(),
        alphabet = listOf(
            LatticeOp("add", OpKind.ASSERT) { state, replicaIndex, _ ->
                state.piece { it.add(ReplicaId("R$replicaIndex"), FOCUS) }
            },
            LatticeOp("add-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                state.piece { it.add(ReplicaId("R$replicaIndex"), ELEMENTS[random.nextInt(ELEMENTS.size)]) }
            },
            LatticeOp("remove", OpKind.RETIRE) { state, _, _ ->
                state.piece { it.remove(FOCUS) }
            },
            LatticeOp("remove-roam", OpKind.RETIRE) { state, _, random ->
                state.piece { it.remove(ELEMENTS[random.nextInt(ELEMENTS.size)]) }
            },
        ),
        // Declared rather than derived. The default would re-assert with `add-roam`, which draws
        // its element from `random` and so need not land back on what `remove` retired. Re-adding
        // the SAME element is the shape that matters here: `add` mints a fresh dot, so a lattice
        // that let the retired tag survive lands somewhere a correct one does not.
        criticalShapes = listOf(listOf("add", "remove", "add")),
        serializer = ORSet.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
