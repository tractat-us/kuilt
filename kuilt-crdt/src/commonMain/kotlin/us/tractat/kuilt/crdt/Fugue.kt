package us.tractat.kuilt.crdt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * A unique, totally-ordered identity for a single Fugue element.
 *
 * Carries a [lamport] timestamp (for total ordering) and a [replicaId]
 * (for deterministic tiebreaking). Two real ids from the same author
 * share neither a lamport value nor a replica id, so the order is total.
 *
 * The special sentinel [HEAD] is the virtual root of the Fugue tree;
 * it sorts before every real id.
 */
@Serializable
public data class FugueId(
    public val lamport: Long,
    public val replicaId: ReplicaId,
) : Comparable<FugueId> {
    override fun compareTo(other: FugueId): Int {
        val byLamport = lamport.compareTo(other.lamport)
        return if (byLamport != 0) byLamport else replicaId.value.compareTo(other.replicaId.value)
    }

    public companion object {
        /**
         * Virtual root of the Fugue tree. Sorts before every real [FugueId].
         */
        public val HEAD: FugueId = FugueId(lamport = Long.MIN_VALUE, replicaId = ReplicaId(""))
    }
}

/**
 * The side a node occupies relative to its [FugueNode.parent] in the Fugue tree.
 *
 * - [Left] — left child: the node was inserted just after a descendant of [FugueNode.parent].
 *   Left children are traversed before the parent.
 * - [Right] — right child: the node claims the space immediately after [FugueNode.parent].
 *   Right children are traversed after the parent. Only right-side nodes carry a
 *   [FugueNode.rightOrigin].
 */
@Serializable
public enum class FugueSide { Left, Right }

/**
 * An operation on a [Fugue] sequence.
 *
 * Every operation carries an [id] — the [FugueId] of the element it creates or
 * tombstones. This is the stable sort key used for canonical serialization.
 */
@Serializable
public sealed interface FugueOp<out V> {
    /** The [FugueId] of the element this operation creates or tombstones. */
    public val id: FugueId

    /**
     * Insert [value] with identity [id].
     *
     * The Fugue tree placement is specified by [parent] and [side]:
     * - [parent] is the tree node this element is a child of (not the sequence
     *   left-neighbour — the tree parent is computed from the sequence context
     *   at insert time).
     * - [side] = [FugueSide.Left]: left child of [parent] (inserted after a
     *   descendant of [parent]).
     * - [side] = [FugueSide.Right]: right child of [parent] (inserted into
     *   [parent]'s open right slot). [rightOrigin] records the next element in
     *   the traversal at insert time, used to sort right siblings.
     */
    @Serializable
    public data class Insert<V>(
        override val id: FugueId,
        public val value: V,
        public val parent: FugueId,
        public val side: FugueSide,
        /** Non-null only when [side] == [FugueSide.Right]. */
        public val rightOrigin: FugueId?,
    ) : FugueOp<V>

    /**
     * Tombstone the element with [id].
     */
    @Serializable
    public data class Remove<V>(
        override val id: FugueId,
    ) : FugueOp<V>
}

/**
 * In-memory tree node, used only during sequence materialisation.
 * Not serialized — rebuilt from the op-log on each [computeSequence] call.
 */
private class FugueNode(
    val id: FugueId,
    val parent: FugueNode?,
    val side: FugueSide,
    val rightOrigin: FugueNode?,   // non-null only for Right-side nodes
) {
    val leftChildren: MutableList<FugueNode> = mutableListOf()
    val rightChildren: MutableList<FugueNode> = mutableListOf()
}

/**
 * A Fugue sequence CRDT: an ordered list with a provable **maximal
 * non-interleaving** guarantee for concurrent insertions.
 *
 * **Vs. `Rga`.** `Rga` tracks only a left-origin per element; concurrent
 * runs inserted at the same position can interleave character-by-character.
 * Fugue builds an explicit tree where each element's [FugueOp.Insert.parent]
 * and [FugueOp.Insert.side] determine its tree placement. Depth-first traversal
 * of this tree produces the canonical sequence. Because consecutive inserts from
 * the same replica each become children of the previous element, they form a
 * contiguous chain that cannot interleave with concurrent inserts from other
 * replicas. This is the only sequence CRDT with a formal maximal-non-interleaving
 * proof (Weidner et al., "The Art of the Fugue", arXiv:2305.00583, 2023).
 *
 * **Tree structure.**
 * - The virtual root is [FugueId.HEAD].
 * - Every element N is either a **left child** or **right child** of its [FugueOp.Insert.parent].
 * - **Left children** are traversed before the parent; ordered by [FugueId] ascending
 *   (sender-id order).
 * - **Right children** are traversed after the parent; ordered in reverse [FugueNode.rightOrigin]
 *   sequence order (the right child whose rightOrigin appears later in the sequence
 *   comes first among right siblings — it claims the space closer to the parent).
 * - Traversal is depth-first: leftChildren..., parent, rightChildren...
 *
 * **Convergence.** The "state" is the set of [FugueOp]s. [piece] is idempotent
 * set-union — it satisfies the three lattice laws. Any two replicas that have
 * absorbed the same set of ops produce the same [toList] regardless of delivery
 * order.
 *
 * **Serialization.** Use [wireSerializer] rather than the compiler-generated
 * serializer to correctly thread the element-type serializer through the op-log.
 *
 * @param V the element type. Must be serializable for wire transport.
 *
 * @sample us.tractat.kuilt.crdt.sampleFugue
 */
public class Fugue<V> private constructor(
    internal val ops: Set<FugueOp<V>>,
    /** This replica's current Lamport timestamp (max seen + 1 after any op). */
    public val lamport: Long,
    /** Pre-computed derived caches to avoid rescanning the op-log on every call. */
    private val cache: FugueCache<V>?,
) : Quilted<Fugue<V>> {

    // ── Lazy caches ───────────────────────────────────────────────────────────

    private val insertsById: Map<FugueId, FugueOp.Insert<V>> by lazy {
        cache?.insertsById ?: computeInsertsById()
    }

    private val tombstones: Set<FugueId> by lazy {
        cache?.tombstones ?: computeTombstones()
    }

    /**
     * The materialized sequence of all [FugueId]s in Fugue tree-traversal order,
     * including tombstoned elements.
     */
    private val sequence: List<FugueId> by lazy { computeSequence() }

    /**
     * Ops sorted in canonical [FugueId] ascending order, computed once per instance.
     *
     * [FugueSerializer] uses this to produce byte-stable wire output without re-sorting
     * the op set on every encode call (which would be O(M log M) per anti-entropy send).
     * The op set is immutable per [Fugue] instance, so the sorted order never changes.
     */
    internal val sortedOps: List<FugueOp<V>> by lazy {
        ops.sortedWith(compareBy { it.id })
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** The current visible (non-tombstoned) elements, in sequence order. */
    public fun toList(): List<V> = sequence
        .filter { id -> id !in tombstones }
        .map { id -> insertsById.getValue(id).value }

    /** The number of visible elements. */
    public val size: Int get() = sequence.count { it !in tombstones }

    /**
     * Insert [value] at visible position [index] (0 = prepend before first
     * visible element).
     *
     * Returns the new [Fugue] state and the [FugueOp.Insert] op to broadcast.
     *
     * @throws IllegalArgumentException if [index] is outside `0..size`.
     */
    public fun insertAt(
        replica: ReplicaId,
        index: Int,
        value: V,
    ): Pair<Fugue<V>, FugueOp.Insert<V>> {
        val tree = buildTree()
        val visible = visibleSequenceFrom(tree)
        require(index in 0..visible.size) {
            "insertAt($index) out of range; visible size is ${visible.size}"
        }
        val newLamport = lamport + 1L
        val id = FugueId(lamport = newLamport, replicaId = replica)
        val op = buildInsertOp(id, value, visible, index, tree)
        return applyInsert(op, newLamport) to op
    }

    /**
     * Remove the visible element at [index].
     *
     * Returns the new [Fugue] state and the [FugueOp.Remove] op to broadcast, or
     * `null` if [index] is out of bounds.
     */
    public fun removeAt(index: Int): Pair<Fugue<V>, FugueOp.Remove<V>>? {
        val visible = visibleSequence()
        if (index !in visible.indices) return null
        val id = visible[index]
        val op = FugueOp.Remove<V>(id = id)
        val newCache = FugueCache(
            insertsById = insertsById,
            tombstones = tombstones + id,
        )
        return Fugue(ops + op, lamport, newCache) to op
    }

    /**
     * Apply an op received from a remote replica, advancing the Lamport clock.
     *
     * Duplicate delivery is safe — set-union is idempotent.
     */
    public fun apply(op: FugueOp<V>): Fugue<V> = when (op) {
        is FugueOp.Insert -> applyInsert(op, maxOf(lamport, op.id.lamport))
        is FugueOp.Remove -> applyRemove(op)
    }

    /**
     * Merge two replicas' op-logs. The result is idempotent set-union — both
     * replicas converge to the same [toList] after [piece].
     *
     * Satisfies the [Quilted] lattice laws:
     * - **Idempotent**: `a.piece(a)` converges to `a`
     * - **Commutative**: `a.piece(b)` == `b.piece(a)`
     * - **Associative**: `a.piece(b).piece(c)` == `a.piece(b.piece(c))`
     */
    override fun piece(other: Fugue<V>): Fugue<V> {
        val mergedOps = ops + other.ops
        val mergedLamport = maxOf(lamport, other.lamport)
        val mergedInserts = insertsById + other.insertsById
        val mergedTombstones = tombstones + other.tombstones
        val newCache = FugueCache(
            insertsById = mergedInserts,
            tombstones = mergedTombstones,
        )
        return Fugue(mergedOps, mergedLamport, newCache)
    }

    /**
     * Two [Fugue] instances are equal when their op-sets are equal — i.e. they
     * represent the same CRDT state. The [lamport] high-water mark is a clock
     * convenience, not part of the value: two converged replicas may differ in
     * [lamport] if one advanced its clock by observing a duplicate op, so including
     * it in equality would make `a.piece(a) != a` in that case.
     */
    override fun equals(other: Any?): Boolean =
        other is Fugue<*> && ops == other.ops

    override fun hashCode(): Int = ops.hashCode()

    override fun toString(): String = "Fugue(${toList()})"

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun visibleSequence(): List<FugueId> = sequence.filter { it !in tombstones }

    private fun visibleSequenceFrom(tree: Map<FugueId, FugueNode>): List<FugueId> {
        val all = mutableListOf<FugueId>()
        tree[FugueId.HEAD]?.let { traverseSubtree(it, all, emitSelf = false) }
        return all.filter { it !in tombstones }
    }

    /**
     * Build the [FugueOp.Insert] for a new element at [index] in the visible sequence.
     *
     * Following the Fugue tree construction rule:
     * - Find `leftOrigin` = the node at `visible[index-1]` (or HEAD if index=0).
     * - If `leftOrigin` has **no right children** in the current tree: the new node
     *   is a RIGHT child of `leftOrigin`. Its rightOrigin = the next node after
     *   `leftOrigin` in the full (tombstone-inclusive) traversal.
     * - If `leftOrigin` HAS right children: the new node is a LEFT child of
     *   the leftmost descendant of `leftOrigin`'s first right child.
     */
    private fun buildInsertOp(
        id: FugueId,
        value: V,
        visible: List<FugueId>,
        index: Int,
        tree: Map<FugueId, FugueNode>,
    ): FugueOp.Insert<V> {
        val leftOriginId = if (index == 0) FugueId.HEAD else visible[index - 1]
        val leftOriginNode = tree.getValue(leftOriginId)
        return if (leftOriginNode.rightChildren.isEmpty()) {
            val rightOriginId = nextNonDescendantId(leftOriginNode)
            FugueOp.Insert(id = id, value = value, parent = leftOriginId, side = FugueSide.Right, rightOrigin = rightOriginId)
        } else {
            val rightOriginNode = leftmostDescendant(leftOriginNode.rightChildren.first())
            FugueOp.Insert(id = id, value = value, parent = rightOriginNode.id, side = FugueSide.Left, rightOrigin = null)
        }
    }

    private fun applyInsert(op: FugueOp.Insert<V>, newLamport: Long): Fugue<V> {
        if (op.id in insertsById) return this
        val newCache = FugueCache(
            insertsById = insertsById + (op.id to op),
            tombstones = tombstones,
        )
        return Fugue(ops + op, newLamport, newCache)
    }

    private fun applyRemove(op: FugueOp.Remove<V>): Fugue<V> {
        if (op.id in tombstones) return this
        val newCache = FugueCache(
            insertsById = insertsById,
            tombstones = tombstones + op.id,
        )
        return Fugue(ops + op, lamport, newCache)
    }

    private fun computeInsertsById(): Map<FugueId, FugueOp.Insert<V>> =
        ops.filterIsInstance<FugueOp.Insert<V>>().associateBy { it.id }

    private fun computeTombstones(): Set<FugueId> =
        ops.filterIsInstance<FugueOp.Remove<V>>().mapTo(mutableSetOf()) { it.id }

    /**
     * Build the in-memory Fugue tree from the current op-log.
     *
     * Returns a map from [FugueId] to [FugueNode]. HEAD is NOT included; use
     * [buildHeadNode] to obtain the virtual root.
     *
     * After construction, each node's [FugueNode.leftChildren] and
     * [FugueNode.rightChildren] are sorted according to the Fugue ordering:
     * - Left children: ascending [FugueId] (sender-id order).
     * - Right children: reverse rightOrigin sequence order, breaking ties by
     *   sender-id descending.
     */
    private fun buildTree(): Map<FugueId, FugueNode> {
        // Create all nodes first (no children yet).
        val nodes = mutableMapOf<FugueId, FugueNode>()

        // Build a virtual HEAD node as the root sentinel.
        val headNode = FugueNode(id = FugueId.HEAD, parent = null, side = FugueSide.Right, rightOrigin = null)
        nodes[FugueId.HEAD] = headNode

        // Create nodes in ascending ID order — parents always have lower lamport than children,
        // so ascending order guarantees topological order (parent before child).
        val sortedInserts = insertsById.values.sortedWith(compareBy({ it.id.lamport }, { it.id.replicaId.value }))
        for (insert in sortedInserts) {
            val parentNode = nodes[insert.parent]
                ?: error("Fugue tree: parent ${insert.parent} not found for ${insert.id}. Ops may be out of order.")
            val rightOriginNode = insert.rightOrigin?.let { nodes[it] }
            val node = FugueNode(id = insert.id, parent = parentNode, side = insert.side, rightOrigin = rightOriginNode)
            nodes[insert.id] = node
            when (insert.side) {
                FugueSide.Left -> parentNode.leftChildren.add(node)
                FugueSide.Right -> parentNode.rightChildren.add(node)
            }
        }

        // Sort children after all nodes are added.
        sortChildren(nodes)
        return nodes
    }

    /**
     * Sort each node's children list in-place:
     * - Left children: ascending [FugueId].
     * - Right children: by rightOrigin position in the traversal (ascending =
     *   rightOrigin comes LATER in traversal = reverse order of rightOrigin
     *   sequence position). When rightOriginPositions are equal (e.g., both null/HEAD),
     *   tiebreak by sender-id descending.
     *
     * Right-child ordering is determined by the [isLess] traversal order, mirroring
     * the reference implementation's `insertIntoSiblings` for right children.
     */
    private fun sortChildren(nodes: Map<FugueId, FugueNode>) {
        val headNode = nodes[FugueId.HEAD]!!
        sortChildrenRecursive(headNode)
    }

    private fun sortChildrenRecursive(node: FugueNode) {
        // Left children: ascending FugueId.
        node.leftChildren.sortWith(compareBy { it.id })

        // Right children: reverse rightOrigin order (later rightOrigin comes first),
        // tiebreak by sender-id descending.
        node.rightChildren.sortWith(rightSiblingComparator())

        for (child in node.leftChildren) sortChildrenRecursive(child)
        for (child in node.rightChildren) sortChildrenRecursive(child)
    }

    /**
     * Comparator for right siblings: node whose rightOrigin comes LATER in the
     * sequence is placed FIRST (it claims space closer to the parent, i.e.,
     * it was inserted "more to the left" relative to its rightOrigin).
     *
     * This mirrors the reference implementation:
     * "Siblings are in order: reverse order of their rightOrigins, breaking ties
     * using lexicographic order on id.sender."
     */
    private fun rightSiblingComparator(): Comparator<FugueNode> = Comparator { a, b ->
        val roA = a.rightOrigin
        val roB = b.rightOrigin
        when {
            // a.ro < b.ro (a's rightOrigin comes earlier) → a comes LATER → b < a
            isRightOriginLess(roA, roB) -> 1
            // b.ro < a.ro → b comes LATER → a < b
            isRightOriginLess(roB, roA) -> -1
            // Same rightOrigin: tiebreak by sender-id descending
            else -> b.id.compareTo(a.id)
        }
    }

    /**
     * Returns true if `a`'s rightOrigin comes before `b`'s rightOrigin in
     * the traversal order.
     * - null rightOrigin means "end of list" = comes after everything.
     * - null < null = false.
     * - non-null < null = true.
     * - For two real nodes, use structural tree comparison (isLess).
     */
    private fun isRightOriginLess(a: FugueNode?, b: FugueNode?): Boolean = when {
        a == b -> false
        a == null -> false   // null = end of list, not less than anything
        b == null -> true    // a (non-null) comes before end-of-list
        else -> isNodeLess(a, b)
    }

    /**
     * Returns true if node `a` comes before node `b` in the depth-first traversal.
     * Uses structural tree comparison (depth-equalise, find common ancestor, compare
     * side + sibling index).
     *
     * Mirrors the reference implementation's `isLess` function.
     */
    private fun isNodeLess(a: FugueNode, b: FugueNode): Boolean {
        val aDepth = treeDepth(a)
        val bDepth = treeDepth(b)
        var aAnc = a
        var bAnc = b
        var lastAncSideOfA = a.side
        var lastAncSideOfB = b.side

        if (aDepth > bDepth) {
            var d = aDepth
            while (d > bDepth) {
                lastAncSideOfA = aAnc.side
                aAnc = aAnc.parent ?: return false
                d--
            }
            if (aAnc === b) return lastAncSideOfA == FugueSide.Left
        }
        if (bDepth > aDepth) {
            var d = bDepth
            while (d > aDepth) {
                lastAncSideOfB = bAnc.side
                bAnc = bAnc.parent ?: return false
                d--
            }
            if (bAnc === a) return lastAncSideOfB == FugueSide.Right
        }

        while (aAnc.parent !== bAnc.parent) {
            lastAncSideOfA = aAnc.side
            lastAncSideOfB = bAnc.side
            aAnc = aAnc.parent ?: break
            bAnc = bAnc.parent ?: break
        }
        if (aAnc.side != bAnc.side) return aAnc.side == FugueSide.Left
        val ancParent = aAnc.parent ?: return false
        val siblings = if (aAnc.side == FugueSide.Left) ancParent.leftChildren else ancParent.rightChildren
        return siblings.indexOf(aAnc) < siblings.indexOf(bAnc)
    }

    private fun treeDepth(node: FugueNode): Int {
        var depth = 0
        var current: FugueNode = node
        while (true) {
            current = current.parent ?: break
            depth++
        }
        return depth
    }

    /**
     * Returns the leftmost descendant of [node] by repeatedly following the first
     * left child. Returns [node] itself if it has no left children.
     */
    private fun leftmostDescendant(node: FugueNode): FugueNode {
        var current = node
        while (current.leftChildren.isNotEmpty()) current = current.leftChildren.first()
        return current
    }

    /**
     * Returns the [FugueId] of the next node after [node] in the depth-first
     * traversal that is NOT a descendant of [node], or null (for HEAD sentinel =
     * "end of list").
     */
    private fun nextNonDescendantId(node: FugueNode): FugueId? {
        var current = node
        while (true) {
            val parentNode = current.parent ?: break
            val siblings = if (current.side == FugueSide.Left) parentNode.leftChildren else parentNode.rightChildren
            val idx = siblings.indexOf(current)
            if (idx < siblings.size - 1) {
                // The next sibling's subtree immediately follows.
                return leftmostDescendant(siblings[idx + 1]).id
            }
            // If we just finished the left children, the parent itself is next.
            if (current.side == FugueSide.Left && parentNode.rightChildren.isEmpty()) {
                return parentNode.id
            }
            if (current.side == FugueSide.Left) {
                return leftmostDescendant(parentNode.rightChildren.first()).id
            }
            current = parentNode
        }
        return null // reached the root, no next element
    }

    /**
     * Materialise the sequence by depth-first traversal of the Fugue tree:
     * for each node, emit all left children (recursively), then the node itself
     * (unless it is HEAD), then all right children (recursively).
     */
    private fun computeSequence(): List<FugueId> {
        val tree = buildTree()
        val headNode = tree[FugueId.HEAD] ?: return emptyList()
        val result = mutableListOf<FugueId>()
        traverseSubtree(headNode, result, emitSelf = false)
        return result
    }

    private fun traverseSubtree(node: FugueNode, result: MutableList<FugueId>, emitSelf: Boolean = true) {
        for (child in node.leftChildren) traverseSubtree(child, result)
        if (emitSelf) result.add(node.id)
        for (child in node.rightChildren) traverseSubtree(child, result)
    }

    public companion object {
        /** The empty sequence with no ops. */
        public fun <V> empty(): Fugue<V> = Fugue(
            ops = emptySet(),
            lamport = 0L,
            cache = FugueCache.empty(),
        )

        /**
         * Package-internal factory for deserialization via [FugueSerializer].
         * Derived state is computed lazily from [ops] on first access.
         */
        internal fun <V> fromOps(ops: Set<FugueOp<V>>, lamport: Long): Fugue<V> =
            Fugue(ops, lamport, cache = null)

        /**
         * Returns a [KSerializer] for [Fugue]`<V>` that correctly threads
         * [vSerializer] through the op-log, avoiding CBOR polymorphism limitations.
         *
         * **Use this instead of a generated serializer** when wiring [Fugue]
         * into a [us.tractat.kuilt.quilter.Quilter] or any transport that needs
         * an explicit element-type serializer.
         *
         * @param vSerializer the [KSerializer] for element type [V].
         */
        public fun <V> wireSerializer(vSerializer: KSerializer<V>): KSerializer<Fugue<V>> =
            FugueSerializer(vSerializer)
    }
}

/**
 * Incrementally-maintained derived state for [Fugue], threaded forward across
 * mutations to avoid rescanning the op-log on every operation.
 */
internal data class FugueCache<V>(
    val insertsById: Map<FugueId, FugueOp.Insert<V>>,
    val tombstones: Set<FugueId>,
) {
    companion object {
        fun <V> empty(): FugueCache<V> = FugueCache(
            insertsById = emptyMap(),
            tombstones = emptySet(),
        )
    }
}
