package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.MovableTree
import us.tractat.kuilt.crdt.ReplicaId

// Random adds and moves (including cycle-attempting moves, which replay skips
// deterministically) — the patterns that reveal move-log total-order and replay bugs.
internal class MovableTreeConvergenceTest : CrdtConvergenceSuite<MovableTree<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<MovableTree<String>> = CrdtConvergenceHarness(
        initial = MovableTree.empty(),
        gen = OperationGenerator { state, replicaIndex, random ->
            val replica = ReplicaId("R$replicaIndex")
            // ts is monotone per replica within one history (the log grows by one per op),
            // and (replica, ts) is globally unique because each harness replica is distinct —
            // the MovableTree.addNode contract.
            val ts = state.moveLogSize + 1L
            val nodes = nodesOf(state)
            val anchors = listOf(MovableTree.ROOT_ID) + nodes
            if (nodes.size >= 2 && random.nextBoolean()) {
                val node = nodes[random.nextInt(nodes.size)]
                val candidates = anchors.filter { it != node }
                val newParent = candidates[random.nextInt(candidates.size)]
                state.move(replica, ts, node, newParent).first
            } else {
                val parent = anchors[random.nextInt(anchors.size)]
                state.addNode(replica, ts, parent, "n$replicaIndex.${random.nextInt(100)}").tree
            }
        },
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
