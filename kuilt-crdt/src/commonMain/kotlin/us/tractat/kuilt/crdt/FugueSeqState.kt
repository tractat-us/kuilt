package us.tractat.kuilt.crdt

/**
 * The incrementally-maintained Fugue tree + materialized sequence for the
 * **local-editing fast path** (#1211).
 *
 * Before #1211, every [Fugue.insertAt] called `buildTree()` — an O(n log n)
 * full reconstruction (sort all inserts, rebuild every node, re-sort every
 * sibling list) — making an append-heavy sequence of N local inserts
 * O(N² log N) overall. This state object is built once (from `buildTree()`,
 * or seeded empty by [Fugue.empty]) and then **mutated in place** by each
 * local [Fugue.insertAt]/[Fugue.removeAt], which threads it forward into the
 * new [Fugue] instance.
 *
 * **Why a local insert is O(1) tree work.** [Fugue.insertAt] places the new
 * element by the Fugue insertion rule, and in *both* of its cases the new
 * node joins an **empty** sibling list:
 * - left origin has no right children → the new node becomes its **only**
 *   right child;
 * - left origin has right children → the new node becomes the **only** left
 *   child of the left origin's traversal successor (which, being the leftmost
 *   descendant of the first right child, has no left children).
 *
 * So no sibling list ever needs re-sorting, and no existing node's
 * depth/side/sibling-index — the inputs to the right-sibling comparator —
 * changes. The incrementally-maintained tree is therefore structurally
 * identical to what `buildTree()` would reconstruct from the op-log, and in
 * both cases the new element's traversal position is **immediately after the
 * left origin** — a single [FugueNode.next] link splice.
 *
 * **Ownership contract (single-owner steal).** [Fugue] instances are
 * immutable values; this object is mutable. It is only ever reachable through
 * one instance's atomic slot at a time: an operation *steals* it (atomic
 * exchange with `null`), mutates or snapshots it, and either hands it to the
 * new instance (mutations) or returns it to the slot (reads). A concurrent
 * caller that finds the slot empty rebuilds from the op-log instead — never
 * shared, never observed mid-mutation. Remote [Fugue.apply], [Fugue.piece],
 * and compaction do not thread this state; the next local edit (or read)
 * rebuilds it once and resumes incremental maintenance.
 *
 * @property nodes every present id (plus [FugueId.HEAD]) to its tree node.
 * @property visible the non-tombstoned ids in sequence order — the positional
 *   index [Fugue.insertAt]/[Fugue.removeAt] resolve against.
 */
internal class FugueSeqState(
    val nodes: MutableMap<FugueId, FugueNode>,
    val visible: ArrayList<FugueId>,
) {

    /**
     * Splice a locally-minted [op] into the tree and sequence.
     *
     * [leftOrigin] is the visible predecessor node ([FugueId.HEAD]'s node for a
     * prepend) and [index] the visible position the new element takes. The
     * `check`s pin the sole-child invariant argued in the class KDoc — if either
     * ever fired, the incremental tree would have diverged from `buildTree()`.
     */
    fun applyLocalInsert(op: FugueOp.Insert<*>, leftOrigin: FugueNode, index: Int) {
        val parent = when (op.side) {
            FugueSide.Right -> leftOrigin.also {
                check(it.rightChildren.isEmpty()) {
                    "Fugue incremental insert: right-side parent ${it.id} already has right children"
                }
            }
            FugueSide.Left -> checkNotNull(leftOrigin.next) {
                "Fugue incremental insert: left origin ${leftOrigin.id} has right children but no successor"
            }.also {
                check(it.leftChildren.isEmpty()) {
                    "Fugue incremental insert: left-side parent ${it.id} already has left children"
                }
            }
        }
        val node = FugueNode(
            id = op.id,
            parent = parent,
            side = op.side,
            rightOrigin = op.rightOrigin?.let { nodes.getValue(it) },
        )
        when (op.side) {
            FugueSide.Left -> parent.leftChildren.add(node)
            FugueSide.Right -> parent.rightChildren.add(node)
        }
        node.next = leftOrigin.next
        leftOrigin.next = node
        nodes[op.id] = node
        visible.add(index, op.id)
    }

    companion object {

        /** The state of an empty sequence: just the [FugueId.HEAD] sentinel. */
        fun empty(): FugueSeqState = FugueSeqState(
            nodes = mutableMapOf(
                FugueId.HEAD to FugueNode(
                    id = FugueId.HEAD,
                    parent = null,
                    side = FugueSide.Right,
                    rightOrigin = null,
                ),
            ),
            visible = ArrayList(),
        )

        /**
         * Assemble a state from a freshly-built tree: thread the [FugueNode.next]
         * traversal links through [traversal] (starting at HEAD) and project the
         * visible ids.
         */
        fun from(
            nodes: MutableMap<FugueId, FugueNode>,
            traversal: List<FugueId>,
            tombstones: Set<FugueId>,
        ): FugueSeqState {
            var prev = nodes.getValue(FugueId.HEAD)
            val visible = ArrayList<FugueId>(traversal.size)
            for (id in traversal) {
                val node = nodes.getValue(id)
                prev.next = node
                prev = node
                if (id !in tombstones) visible.add(id)
            }
            return FugueSeqState(nodes, visible)
        }
    }
}
