package us.tractat.kuilt.crdt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * A unique, totally-ordered identity for a single Fugue element.
 *
 * Carries two orthogonal counters:
 * - [lamport] — the total-order tiebreak used by the Fugue tree. Monotonic per
 *   author but **not dense** (the clock jumps to `max(seen) + 1`).
 * - [seq] — a **dense, contiguous per-author delivery counter** (1, 2, 3, …). This
 *   is the key into the causal-stability version vectors used by [Fugue.compact]
 *   (same pattern as [RgaId.seq] for [Rga]). [seq] never participates in ordering.
 *
 * Total order ([compareTo]): higher [lamport] wins; [replicaId] breaks ties
 * deterministically. [seq] is deliberately excluded — it tracks delivery, not order.
 *
 * The special sentinel [HEAD] is the virtual root of the Fugue tree;
 * it sorts before every real id. Its [seq] is `0` (never an author dot).
 *
 * **Wire-format note:** Adding [seq] is a breaking change relative to the pre-#714
 * format. This is intentional and cheap pre-1.0 per the design note
 * (`docs/op-log-crdt-compaction.md`).
 */
@Serializable
public data class FugueId(
    public val lamport: Long,
    public val replicaId: ReplicaId,
    /** Dense per-author delivery counter. Used by causal-stability GC; excluded from ordering. */
    public val seq: Long,
) : Comparable<FugueId> {
    override fun compareTo(other: FugueId): Int {
        val byLamport = lamport.compareTo(other.lamport)
        return if (byLamport != 0) byLamport else replicaId.value.compareTo(other.replicaId.value)
    }

    /** This id's causal [Dot] — `(replicaId, seq)`. The key into causal-stability VVs. */
    public val dot: Dot get() = Dot(replicaId, seq)

    public companion object {
        /**
         * Virtual root of the Fugue tree. Sorts before every real [FugueId].
         * Its [seq] is `0` — it is never an author dot.
         */
        public val HEAD: FugueId = FugueId(lamport = Long.MIN_VALUE, replicaId = ReplicaId(""), seq = 0L)
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

    /**
     * Records that the [positions] entries have been garbage-collected from the op-log.
     *
     * The map carries each compacted id's tree-parent at GC time (`id → Insert.parent`).
     * [buildTree] uses this to re-root surviving children of a dropped node to the
     * nearest surviving ancestor, preserving the traversal order.
     *
     * The ids purged are [positions].keys. Merging two [Compact] ops via [Fugue.piece]
     * unions their [positions] maps — sound because a given id's [Insert.parent] is
     * fixed at insert time, so two replicas always agree on the value.
     *
     * Applying a [Compact] removes every [Insert] and [Remove] op whose id is in
     * [positions].keys. Receiving the same [Compact] twice is idempotent.
     *
     * [id] is the sentinel [FugueId.HEAD] — a [Compact] carries no element id of its own
     * but must satisfy the [FugueOp] contract.
     */
    @Serializable
    public data class Compact(
        public val positions: Map<FugueId, FugueId>,
    ) : FugueOp<Nothing> {
        override val id: FugueId get() = FugueId.HEAD
    }
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
 * **Causal-stability GC.** [causalDots] exposes the delivered [Dot]s so the
 * [us.tractat.kuilt.quilter.Quilter] causal-stability machinery can drive
 * compaction. [compact] garbage-collects causally-stable tombstones whose id
 * is no longer a live tree parent. This bounds op-log growth under long-running
 * replication. See `docs/op-log-crdt-compaction.md` for the safety argument.
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

    private val compactedIds: Set<FugueId> by lazy {
        cache?.compactedIds ?: computeCompactedIds()
    }

    private val compactPositions: Map<FugueId, FugueId> by lazy {
        cache?.compactPositions ?: computeCompactPositions()
    }

    /**
     * Ceiling of the [FugueId.seq] seen per [ReplicaId], maintained incrementally.
     * Used by [nextSeqFor] to avoid rescanning the op-log.
     */
    private val maxSeqByReplica: Map<ReplicaId, Long> by lazy {
        cache?.maxSeqByReplica ?: computeMaxSeqByReplica()
    }

    /**
     * The materialized sequence of all [FugueId]s in Fugue tree-traversal order,
     * including tombstoned elements.
     */
    private val sequence: List<FugueId> by lazy { computeSequence() }

    /** Canonical sorted op list, cached across encodes. Used by [FugueSerializer]. */
    internal val sortedOps: List<FugueOp<V>> by lazy { ops.sortedWith(compareBy { it.id }) }

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
        val seq = nextSeqFor(replica)
        val id = FugueId(lamport = newLamport, replicaId = replica, seq = seq)
        val op = buildInsertOp(id, value, visible, index, tree)
        return applyInsert(op, newLamport) to op
    }

    private fun nextSeqFor(replica: ReplicaId): Long = (maxSeqByReplica[replica] ?: 0L) + 1L

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
            compactedIds = compactedIds,
            compactPositions = compactPositions,
            maxSeqByReplica = maxSeqByReplica,
        )
        return Fugue(ops + op, lamport, newCache) to op
    }

    /**
     * Garbage-collect tombstoned elements that are **causally stable** under the
     * eviction-safe causal-stability barrier (same design as [Rga.compact],
     * `docs/op-log-crdt-compaction.md`).
     *
     * A tombstoned element with dot `(r, sᵢ)` is purged iff **all** hold:
     * 1. **Tombstoned** — implied (only [tombstones] are candidates).
     * 2. **Causally stable** — `sᵢ ≤ stableCut[r]`: every live peer has delivered it.
     * 3. **Frontier-complete** — `∀x: delivered[x] ≥ frontierMax[x]`: this replica has
     *    delivered every op below every known frontier, so any concurrent
     *    `Insert(J, parent=id)` that exists anywhere has been delivered locally.
     * 4. **No surviving tree anchor** — no live [FugueOp.Insert] has `parent == id`
     *    OR `rightOrigin == id`. Dropping a node while a child still refers to it as
     *    its tree parent would detach that child's subtree; dropping a node that is
     *    still a `rightOrigin` of a surviving insert would change the right-sibling
     *    ordering and break non-interleaving. The compacted positions map handles
     *    re-rooting surviving children after GC on the next compaction pass.
     *
     * Returns the compacted [Fugue] and a [FugueOp.Compact] delta to broadcast to
     * peers, or `null` if no element qualifies (or condition 3 is not yet met).
     *
     * Peers that receive the [FugueOp.Compact] apply it via [apply] or absorb it
     * through [piece] — both paths strip the referenced ops from the log.
     *
     * @param stableCut `S` — elementwise **min** over all live peers' delivered VVs.
     * @param frontierMax `F` — elementwise **max** of the live frontier and any
     *   retained (evicted-peer) frontier; the set of dots known to *exist*.
     * @param delivered this replica's own contiguous delivered VV.
     */
    public fun compact(
        stableCut: VersionVector,
        frontierMax: VersionVector,
        delivered: VersionVector,
    ): Pair<Fugue<V>, FugueOp.Compact>? {
        if (!delivered.dominates(frontierMax)) return null  // condition 3 — frontier-complete
        // Condition 4: id must not be a live anchor — guard both parent AND rightOrigin.
        // A compacted id that is still a rightOrigin of a surviving insert would break
        // buildTree's nearestAncestor chain, causing wrong sibling ordering.
        val liveAnchors = insertsById.values.flatMapTo(mutableSetOf()) {
            listOfNotNull(it.parent, it.rightOrigin)
        }
        val gcIds = tombstones
            .filter { id -> stableCut.contains(id.dot) && id !in liveAnchors }  // (2) + (4)
            .toSet()
        if (gcIds.isEmpty()) return null
        val positions = gcIds.associateWith { id -> insertsById.getValue(id).parent }
        val compactOp = FugueOp.Compact(positions)
        val newOps = purgeAndRecord(ops, gcIds, compactOp)
        return withCompactCaches(newOps, gcIds, compactOp) to compactOp
    }

    /**
     * Apply an op received from a remote replica, advancing the Lamport clock.
     *
     * Duplicate delivery is safe — set-union is idempotent.
     */
    public fun apply(op: FugueOp<V>): Fugue<V> = when (op) {
        is FugueOp.Insert -> applyInsert(op, maxOf(lamport, op.id.lamport))
        is FugueOp.Remove -> applyRemove(op)
        is FugueOp.Compact -> applyCompact(op)
    }

    /**
     * The causal [Dot]s this op-log has delivered — one per [FugueOp.Insert] plus
     * one per id in every [FugueOp.Compact].
     *
     * [FugueOp.Remove] mints no dot (it reuses the target insert's id). Including it
     * would over-claim when a Remove arrives before its Insert, prematurely advancing
     * the stable cut (the #275-class hazard, per-Rga reasoning).
     *
     * [FugueOp.Compact] ids re-emit the compacted inserts' dots so the contiguous
     * frontier does not develop holes after GC.
     */
    override fun causalDots(): Set<Dot> =
        ops.asSequence()
            .flatMap { op ->
                when (op) {
                    is FugueOp.Insert -> sequenceOf(op.id.dot)
                    is FugueOp.Compact -> op.positions.keys.asSequence().map { it.dot }
                    is FugueOp.Remove -> emptySequence()
                }
            }
            .toSet()

    /**
     * Merge two replicas' op-logs. The result is idempotent set-union — both
     * replicas converge to the same [toList] after [piece].
     *
     * Satisfies the [Quilted] lattice laws:
     * - **Idempotent**: `a.piece(a)` converges to `a`
     * - **Commutative**: `a.piece(b)` == `b.piece(a)`
     * - **Associative**: `a.piece(b).piece(c)` == `a.piece(b.piece(c))`
     *
     * Any [FugueOp.Compact] ops in the union are applied eagerly so that Insert/Remove
     * ops already GC'd on one peer do not re-inflate the op-log on merge.
     */
    override fun piece(other: Fugue<V>): Fugue<V> {
        val mergedCompactedIds = compactedIds + other.compactedIds
        val mergedCompactPositions = compactPositions + other.compactPositions
        val rawInsertsById = insertsById + other.insertsById
        val mergedInsertsById = if (mergedCompactedIds.isEmpty()) rawInsertsById
            else rawInsertsById.filterKeys { it !in mergedCompactedIds }
        val rawTombstones = tombstones + other.tombstones
        val mergedTombstones = if (mergedCompactedIds.isEmpty()) rawTombstones
            else rawTombstones.filterTo(mutableSetOf()) { it !in mergedCompactedIds }
        val mergedMaxSeq = mergeMaxSeq(maxSeqByReplica, other.maxSeqByReplica)
        val mergedLamport = maxOf(lamport, other.lamport)
        val rawUnion = ops + other.ops
        val mergedOps = if (mergedCompactedIds.isEmpty()) rawUnion else purge(rawUnion, mergedCompactedIds)
        val newCache = FugueCache(
            insertsById = mergedInsertsById,
            tombstones = mergedTombstones,
            compactedIds = mergedCompactedIds,
            compactPositions = mergedCompactPositions,
            maxSeqByReplica = mergedMaxSeq,
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
        if (op.id in insertsById || op.id in compactedIds) return this
        val newSeq = maxSeqByReplica[op.id.replicaId].let { cur ->
            if (cur == null || op.id.seq > cur) maxSeqByReplica + (op.id.replicaId to op.id.seq)
            else maxSeqByReplica
        }
        val newCache = FugueCache(
            insertsById = insertsById + (op.id to op),
            tombstones = tombstones,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
            maxSeqByReplica = newSeq,
        )
        return Fugue(ops + op, newLamport, newCache)
    }

    private fun applyRemove(op: FugueOp.Remove<V>): Fugue<V> {
        if (op.id in tombstones || op.id in compactedIds) return this
        val newCache = FugueCache(
            insertsById = insertsById,
            tombstones = tombstones + op.id,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
            maxSeqByReplica = maxSeqByReplica,
        )
        return Fugue(ops + op, lamport, newCache)
    }

    private fun applyCompact(op: FugueOp.Compact): Fugue<V> {
        val newOps = purgeAndRecord(ops, op.positions.keys, op)
        return withCompactCaches(newOps, op.positions.keys, op)
    }

    private fun withCompactCaches(
        newOps: Set<FugueOp<V>>,
        gcIds: Set<FugueId>,
        compactOp: FugueOp.Compact,
    ): Fugue<V> {
        val newCache = FugueCache(
            insertsById = insertsById - gcIds,
            tombstones = tombstones - gcIds,
            compactedIds = compactedIds + gcIds,
            compactPositions = compactPositions + compactOp.positions,
            maxSeqByReplica = maxSeqByReplica,
        )
        return Fugue(newOps, lamport, newCache)
    }

    private fun computeInsertsById(): Map<FugueId, FugueOp.Insert<V>> =
        ops.filterIsInstance<FugueOp.Insert<V>>().associateBy { it.id }

    private fun computeTombstones(): Set<FugueId> =
        ops.filterIsInstance<FugueOp.Remove<V>>()
            .mapTo(mutableSetOf()) { it.id }
            .apply { removeAll(compactedIds) }

    private fun computeCompactedIds(): Set<FugueId> =
        ops.filterIsInstance<FugueOp.Compact>()
            .flatMapTo(mutableSetOf()) { it.positions.keys }

    private fun computeCompactPositions(): Map<FugueId, FugueId> =
        ops.filterIsInstance<FugueOp.Compact>()
            .flatMap { it.positions.entries }
            .associate { (k, v) -> k to v }

    /**
     * Compute the maxSeqByReplica map from the op-log (fallback when no cache is provided).
     *
     * Folds in **compacted ids** from [FugueOp.Compact.positions] keys as well as live
     * [FugueOp.Insert]s. A self-compaction purges the Insert from the log, so scanning
     * only surviving inserts would regress the per-author high-water and let [nextSeqFor]
     * reuse a seq (same as [Rga] #639 fix).
     */
    private fun computeMaxSeqByReplica(): Map<ReplicaId, Long> {
        val result = mutableMapOf<ReplicaId, Long>()
        fun consider(id: FugueId) {
            val current = result[id.replicaId]
            if (current == null || id.seq > current) result[id.replicaId] = id.seq
        }
        for (op in ops) {
            when (op) {
                is FugueOp.Insert -> consider(op.id)
                is FugueOp.Compact -> op.positions.keys.forEach(::consider)
                is FugueOp.Remove -> {}
            }
        }
        return result
    }

    /**
     * Build the in-memory Fugue tree from the current op-log.
     *
     * Returns a map from [FugueId] to [FugueNode]. HEAD is NOT included; use
     * [buildHeadNode] to obtain the virtual root.
     *
     * After construction, each node's [FugueNode.leftChildren] and
     * [FugueNode.rightChildren] are sorted according to the Fugue ordering:
     * - Left children: ascending [FugueId].
     * - Right children: reverse rightOrigin sequence order, breaking ties by
     *   sender-id descending.
     *
     * **Positional re-root (#714).** An [FugueOp.Insert] whose [FugueOp.Insert.parent]
     * has been compacted away does not drop from the tree. Instead, `nearestAncestor`
     * chain-walks [compactPositions] until it reaches a present (non-compacted) id or
     * [FugueId.HEAD]. This preserves the traversal order of surviving nodes: a child of
     * a GC'd node stays below the GC'd node's own surviving ancestor rather than
     * floating to HEAD arbitrarily.
     */
    private fun buildTree(): Map<FugueId, FugueNode> {
        val present = insertsById.keys
        val positions = compactPositions

        fun nearestAncestor(start: FugueId): FugueId {
            var cur = start
            while (cur != FugueId.HEAD && cur !in present) cur = positions[cur] ?: FugueId.HEAD
            return cur
        }

        val nodes = mutableMapOf<FugueId, FugueNode>()
        val headNode = FugueNode(id = FugueId.HEAD, parent = null, side = FugueSide.Right, rightOrigin = null)
        nodes[FugueId.HEAD] = headNode

        // Sort ascending by id — parents always have lower lamport than children,
        // so ascending order guarantees topological order (parent before child).
        val sortedInserts = insertsById.values.sortedWith(compareBy({ it.id.lamport }, { it.id.replicaId.value }))
        for (insert in sortedInserts) {
            val effectiveParentId = if (insert.parent == FugueId.HEAD || insert.parent in present) {
                insert.parent
            } else {
                nearestAncestor(insert.parent)
            }
            val parentNode = nodes[effectiveParentId]
                ?: error("Fugue tree: parent $effectiveParentId not found for ${insert.id}.")
            val rightOriginNode = insert.rightOrigin?.let { ro ->
                val effectiveRo = if (ro == FugueId.HEAD || ro in present) ro else nearestAncestor(ro)
                nodes[effectiveRo]
            }
            val node = FugueNode(id = insert.id, parent = parentNode, side = insert.side, rightOrigin = rightOriginNode)
            nodes[insert.id] = node
            when (insert.side) {
                FugueSide.Left -> parentNode.leftChildren.add(node)
                FugueSide.Right -> parentNode.rightChildren.add(node)
            }
        }

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

        /**
         * Strip Insert and Remove ops for all [gcIds] from [ops], merging the
         * [compactOp] in. The Compact op itself is retained.
         */
        internal fun <V> purgeAndRecord(
            ops: Set<FugueOp<V>>,
            gcIds: Set<FugueId>,
            compactOp: FugueOp.Compact,
        ): Set<FugueOp<V>> = purge(ops, gcIds) + compactOp

        /**
         * Remove all [FugueOp.Insert] and [FugueOp.Remove] ops whose id is in [gcIds].
         * [FugueOp.Compact] ops are left intact.
         */
        internal fun <V> purge(ops: Set<FugueOp<V>>, gcIds: Set<FugueId>): Set<FugueOp<V>> =
            ops.filterTo(mutableSetOf()) { op ->
                when (op) {
                    is FugueOp.Insert -> op.id !in gcIds
                    is FugueOp.Remove -> op.id !in gcIds
                    is FugueOp.Compact -> true
                }
            }

        private fun mergeMaxSeq(a: Map<ReplicaId, Long>, b: Map<ReplicaId, Long>): Map<ReplicaId, Long> {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val result = a.toMutableMap()
            for ((replica, seq) in b) {
                val current = result[replica]
                if (current == null || seq > current) result[replica] = seq
            }
            return result
        }
    }
}

/**
 * Incrementally-maintained derived state for [Fugue], threaded forward across
 * mutations to avoid rescanning the op-log on every operation.
 */
internal data class FugueCache<V>(
    val insertsById: Map<FugueId, FugueOp.Insert<V>>,
    val tombstones: Set<FugueId>,
    val compactedIds: Set<FugueId>,
    val compactPositions: Map<FugueId, FugueId>,
    val maxSeqByReplica: Map<ReplicaId, Long>,
) {
    companion object {
        fun <V> empty(): FugueCache<V> = FugueCache(
            insertsById = emptyMap(),
            tombstones = emptySet(),
            compactedIds = emptySet(),
            compactPositions = emptyMap(),
            maxSeqByReplica = emptyMap(),
        )
    }
}
