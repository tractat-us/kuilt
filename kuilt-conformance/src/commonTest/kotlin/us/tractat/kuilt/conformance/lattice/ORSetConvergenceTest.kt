package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

// Fixed element pool forces frequent concurrency collisions — exactly the pattern that reveals
// add-wins vs remove-wins ambiguity bugs. The `-roam` ops draw from it; the pinned ops agree on
// ELEMENTS[0] so the critical shape's three steps touch the same element.
private val ELEMENTS = listOf("elem-0", "elem-1", "elem-2", "elem-3")
private val FOCUS = ELEMENTS[0]

internal class ORSetConvergenceTest : LatticeLawSuite<ORSet<String>>() {
    override fun newHarness(): LatticeLawHarness<ORSet<String>> = LatticeLawHarness(
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
            // Roams over the elements the state **actually holds**, not over `ELEMENTS`. Drawing
            // from the pool spent 106 of 130 draws (82%) removing an element that was not there,
            // which absorbs the lattice identity and changes nothing — on its own it was 20% of
            // every step this binding took, and it is most of why the binding sat at 33.8% no-ops
            // against Task 5's 25% ceiling. Roaming is the part worth keeping: a remove that lands
            // on an element another replica is concurrently adding is what exercises add-wins, and
            // pinning this op to `FOCUS` (the cheap way to the same number) would delete that.
            //
            // `sorted()` rather than the set's own order: `elements` is backed by a `HashMap` on
            // JVM and Android and by an insertion-ordered map on Kotlin/Native and wasmJs, so
            // indexing it raw would give the targets different trajectories from one seed.
            //
            // The empty-state fallback keeps the absent-element case reachable rather than erasing
            // it: "removing an element you do not hold is the identity" is real behaviour, and it
            // is still reached whenever a replica has removed everything it holds. It is no longer
            // reached from `initial` — since #2145 every replica takes one leading assert, so no
            // replica starts empty and the bottom-state no-ops this line used to produce (85 of the
            // binding's 139 over seeds 0..63, all of them RETIRE) are gone. `runExhaustiveSmall` is
            // where a retire against the empty set is still walked, exhaustively.
            LatticeOp("remove-roam", OpKind.RETIRE) { state, _, random ->
                val held = state.elements.sorted()
                val element = if (held.isEmpty()) ELEMENTS[random.nextInt(ELEMENTS.size)] else held[random.nextInt(held.size)]
                state.piece { it.remove(element) }
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
