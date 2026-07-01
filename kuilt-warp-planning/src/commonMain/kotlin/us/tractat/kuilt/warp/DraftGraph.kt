package us.tractat.kuilt.warp

// ── Internal graph helpers ────────────────────────────────────────────────────
//
// These live in :kuilt-warp-planning because they are used only by the graph
// rewrites (DraftRewrite). Core's Draft builders append path nodes inline and do
// not need them.

/**
 * Builds a path-predecessor [DraftNode] list from an ordered stage list.
 *
 * Each stage becomes a node with [NodeId] equal to its list index; each node's sole
 * predecessor is the node at `index - 1`. The source node (index 0) has no predecessors.
 *
 * Used by rewrites that produce reordered stage lists and need to rebuild the node graph.
 */
internal fun List<DraftStage>.toPathNodes(): List<DraftNode> =
    mapIndexed { index, stage ->
        DraftNode(
            id = NodeId(index),
            stage = stage,
            predecessors = if (index == 0) emptySet() else setOf(NodeId(index - 1)),
        )
    }

/**
 * Computes the set of successor [NodeId]s for every node in this list.
 *
 * A node S is a successor of P if P's id appears in S's predecessors.
 * Used by rewrites to navigate forward in the DAG (e.g. to check whether an Embroider
 * node has any Free successors before deferring it).
 */
internal fun List<DraftNode>.successors(): Map<NodeId, Set<NodeId>> {
    val result = mutableMapOf<NodeId, MutableSet<NodeId>>()
    for (node in this) {
        for (predId in node.predecessors) {
            result.getOrPut(predId) { mutableSetOf() }.add(node.id)
        }
    }
    return result
}
