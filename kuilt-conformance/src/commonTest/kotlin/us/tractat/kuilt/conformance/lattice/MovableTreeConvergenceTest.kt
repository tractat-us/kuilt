package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.MovableTree
import us.tractat.kuilt.crdt.ReplicaId

// Random adds and moves (including cycle-attempting moves, which replay skips
// deterministically) — the patterns that reveal move-log total-order and replay bugs.
internal class MovableTreeConvergenceTest : LatticeLawSuite<MovableTree<String>>() {
    override fun newHarness(): LatticeLawHarness<MovableTree<String>> = LatticeLawHarness(
        initial = MovableTree.empty(),
        // `move` is the RETIRE op. Nothing is deleted from a `MovableTree` — the move log only
        // grows — but a move *withdraws* an earlier assertion all the same: the node leaves its
        // previous parent's children. Retirement is about the observable value, not the encoding,
        // which is why the free byte-size proxy reads 0.0% here (see `OpKind`).
        //
        // ts is monotone per replica within one history (the log grows by one per op), and
        // (replica, ts) is globally unique because each harness replica is distinct — the
        // MovableTree.addNode contract.
        alphabet = listOf(
            LatticeOp("add-under-root", OpKind.ASSERT) { state, replicaIndex, random ->
                state.addNode(
                    ReplicaId("R$replicaIndex"),
                    state.moveLogSize + 1L,
                    MovableTree.ROOT_ID,
                    "n$replicaIndex.${random.nextInt(100)}",
                ).tree
            },
            LatticeOp("add-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                val anchors = listOf(MovableTree.ROOT_ID) + nodesOf(state)
                state.addNode(
                    ReplicaId("R$replicaIndex"),
                    state.moveLogSize + 1L,
                    anchors[random.nextInt(anchors.size)],
                    "n$replicaIndex.${random.nextInt(100)}",
                ).tree
            },
            LatticeOp("move-last-under-first", OpKind.RETIRE) { state, replicaIndex, _ ->
                val nodes = nodesOf(state)
                if (nodes.size < 2) {
                    state
                } else {
                    state.move(ReplicaId("R$replicaIndex"), state.moveLogSize + 1L, nodes.last(), nodes.first()).first
                }
            },
            LatticeOp("move-roam", OpKind.RETIRE) { state, replicaIndex, random ->
                val nodes = nodesOf(state)
                if (nodes.size < 2) {
                    state
                } else {
                    val node = nodes[random.nextInt(nodes.size)]
                    val candidates = (listOf(MovableTree.ROOT_ID) + nodes).filter { it != node }
                    state.move(
                        ReplicaId("R$replicaIndex"),
                        state.moveLogSize + 1L,
                        node,
                        candidates[random.nextInt(candidates.size)],
                    ).first
                }
            },
        ),
        // Four steps, not the default three: `move` needs two nodes before it can retire anything,
        // so the word opens with two adds. The third step is the retirement (the second node leaves
        // the root for the first), the fourth re-asserts under the root the move just vacated.
        criticalShapes = listOf(
            listOf("add-under-root", "add-under-root", "move-last-under-first", "add-under-root"),
        ),
        // No-op ceiling tightened from the shared 25% default. Measured over seeds `0..15` — the
        // window `generatorIsNotVacuous` runs — this binding reads **9.2%**; the ceiling sits at
        // 12%, a 2.8-point margin. See `VacuityFloors.maxNoOpSteps` for the rule and for why the
        // shared default cannot do this job. What the 12% catches that 25% did not:
        //  - the leading assert removed from the pool builder: 20.0%, reds by 8.0 points.
        //  - retirement dead off replica 0 (#2158's shape): 15.2%, reds by 3.2 points.
        floors = VacuityFloors(maxNoOpSteps = 0.12),
        serializer = MovableTree.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}

/** All non-root node ids reachable from the root, sorted so index picks are deterministic. */
private fun nodesOf(tree: MovableTree<String>): List<String> {
    val result = mutableListOf<String>()
    val queue = ArrayDeque(listOf(MovableTree.ROOT_ID))
    while (queue.isNotEmpty()) {
        val children = tree.childrenOf(queue.removeFirst()).sorted()
        result += children
        queue += children
    }
    return result.sorted()
}
