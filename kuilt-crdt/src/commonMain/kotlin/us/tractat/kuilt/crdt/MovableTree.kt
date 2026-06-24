package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A replicated tree that supports concurrent **move/reparent** operations without
 * ever producing a cycle.
 *
 * ## The algorithm
 *
 * Every [move] (and [addNode], which is sugar for "move a fresh node under a
 * parent") is recorded as a [MoveOp] stamped with a logical `(timestamp,
 * replicaId)` pair. Merging two replicas takes the **union** of their op-logs,
 * then **replays** them in timestamp order, smallest-first, breaking ties on
 * [ReplicaId] lexicographically (larger wins so a higher replicaId move
 * supersedes a lower one at the same instant).
 *
 * Before applying each op, the replay algorithm checks whether the node being
 * moved is an **ancestor** of the target parent. If it is, applying the move
 * would create a cycle, so the op is **skipped**. Otherwise it is applied,
 * updating that node's parent. Because every replica replays the same total
 * order of the same op-log union, every replica converges to the same tree —
 * the three semilattice laws hold.
 *
 * This is the algorithm from:
 * Kleppmann et al., "A highly-available move operation for replicated trees"
 * (IEEE TPDS 2021).
 *
 * ## Cycle prevention
 *
 * A move `move(node, newParent)` is safe if and only if `newParent` is **not** a
 * descendant of `node` (equivalently: `node` is not an ancestor of `newParent`).
 * The replay procedure checks this against the tree built from ops replayed so
 * far (the "effective" state at that point in the replay), so concurrent moves
 * that together would form a cycle are resolved without one: the lower-priority
 * op is skipped, and the higher-priority op alone is applied.
 *
 * ## Move-log GC
 *
 * The move-log is **unbounded** in this implementation. Safe GC requires
 * *causal stability*: an op can be compacted away only once **every** peer has
 * received and applied it (i.e. the op's timestamp is below the minimum of all
 * replicas' delivered vectors). Implementing stability detection requires
 * coordination at the transport layer (e.g. the delivered-vector gossip of
 * `Quilter` / `RgaGcCoordinator`). Until that is wired up, callers should
 * expect log size to grow linearly with the total number of moves ever made.
 *
 * @param N the type of node identifier — must be a stable, globally unique string
 *   (e.g. a UUID). The special [ROOT_ID] is reserved for the root node.
 * @param V the value stored at each node.
 *
 * @sample us.tractat.kuilt.crdt.sampleMovableTree
 */
@Serializable
public class MovableTree<V> private constructor(
    /** Total-order log of every move op ever applied. Sorted by (ts, replicaId) ascending. */
    private val log: List<MoveOp<V>>,
) : Quilted<MovableTree<V>> {

    // The effective parent map is derived by replaying the log. We cache it lazily
    // via a computed property so the class stays a pure value type with no mutable
    // fields that would complicate serialization.
    private val effectiveParents: Map<String, String> by lazy { replayLog(log) }

    // ── Public read API ───────────────────────────────────────────────────────

    /** True if [nodeId] is present in this tree (root is always present). */
    public fun contains(nodeId: String): Boolean =
        nodeId == ROOT_ID || effectiveParents.containsKey(nodeId)

    /**
     * The parent of [nodeId], or `null` if [nodeId] is the root (which has no parent)
     * or is unknown to this replica.
     */
    public fun parentOf(nodeId: String): String? = effectiveParents[nodeId]

    /** All direct children of [nodeId]. */
    public fun childrenOf(nodeId: String): Set<String> =
        effectiveParents.filterValues { it == nodeId }.keys.toSet()

    /**
     * True if [ancestor] is a proper ancestor of [descendant] (i.e. [ancestor]
     * appears on the path from [descendant] to the root, excluding [descendant]
     * itself).
     */
    public fun isAncestor(ancestor: String, descendant: String): Boolean =
        ancestorPath(descendant, effectiveParents).drop(1).contains(ancestor)

    /**
     * True if [nodeId] has an unbroken path to [ROOT_ID] (i.e. it is reachable
     * from the root).
     */
    public fun hasPathToRoot(nodeId: String): Boolean =
        ancestorPath(nodeId, effectiveParents).lastOrNull() == ROOT_ID

    /** Number of ops in the move-log (grows with every [move] or [addNode] call). */
    public val moveLogSize: Int get() = log.size

    // ── Mutation API ─────────────────────────────────────────────────────────

    /**
     * Add a fresh node under [parent], tagging the op with ([replica], [ts]).
     *
     * The caller is responsible for choosing a [ts] that is monotonically
     * increasing per replica and unique per `(replica, ts)` pair. A practical
     * choice is a Lamport clock incremented on every local operation.
     *
     * @return the updated tree and the newly minted node id (`"<value>:<replica>:<ts>"`).
     */
    public fun addNode(replica: ReplicaId, ts: Long, parent: String, value: V): Pair<MovableTree<V>, String> {
        val nodeId = "$value:${replica.value}:$ts"
        val op = MoveOp(ts = ts, replica = replica, node = nodeId, newParent = parent, value = value)
        return applyOp(op) to nodeId
    }

    /**
     * Move [node] to [newParent], tagging the op with ([replica], [ts]).
     *
     * If [node] is [ROOT_ID], this is a no-op (root has no parent and cannot be
     * reparented). If applying the move would create a cycle, the op is silently
     * recorded in the log but skipped during replay — the node keeps its current
     * parent. This is correct: on merge, the op remains in the log union and
     * participates in the deterministic conflict resolution.
     *
     * @return the updated tree and a [Patch] carrying just the new op (for
     *   delta-propagation to peers).
     */
    public fun move(
        replica: ReplicaId,
        ts: Long,
        node: String,
        newParent: String,
    ): Pair<MovableTree<V>, Patch<MovableTree<V>>> {
        val op = MoveOp<V>(ts = ts, replica = replica, node = node, newParent = newParent, value = null)
        val updated = applyOp(op)
        val delta = MovableTree<V>(listOf(op))
        return updated to Patch(delta)
    }

    // ── Quilted / merge ───────────────────────────────────────────────────────

    /**
     * The join: merge the two move-logs and replay the union in timestamp order.
     *
     * This satisfies all three semilattice laws:
     * - **Idempotent** — `piece(a, a)` deduplicates equal ops (same `(ts, replica)`)
     *   before replay, so the result is the same as `a`.
     * - **Commutative** — the total order is deterministic regardless of which
     *   replica's log appears on which side.
     * - **Associative** — follows from the set-union nature of the log merge and the
     *   determinism of the replay.
     */
    override fun piece(other: MovableTree<V>): MovableTree<V> {
        val merged = mergeDistinctLogs(log, other.log)
        return MovableTree(merged)
    }

    override fun equals(other: Any?): Boolean =
        other is MovableTree<*> && log == other.log

    override fun hashCode(): Int = log.hashCode()

    override fun toString(): String = "MovableTree(ops=${log.size}, nodes=${effectiveParents.size + 1})"

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun applyOp(op: MoveOp<V>): MovableTree<V> = MovableTree(insertSorted(log, op))

    public companion object {
        /** The reserved id of the tree's root node. The root has no parent. */
        public const val ROOT_ID: String = "__root__"

        /** An empty tree containing only [ROOT_ID]. */
        public fun <V> empty(): MovableTree<V> = MovableTree(emptyList())
    }
}

// ── Op record ────────────────────────────────────────────────────────────────

/**
 * One move operation: "set the parent of [node] to [newParent]".
 *
 * When [node] is freshly created by [MovableTree.addNode], [value] carries the
 * initial value for the node. For plain reparents (from [MovableTree.move]),
 * [value] is `null` — the node's value was set when it was first created and
 * never changes.
 *
 * The `(ts, replica)` pair is the logical timestamp that determines the
 * operation's position in the total replay order. Two ops with the same pair are
 * treated as identical (idempotency); callers must ensure uniqueness per replica.
 */
@Serializable
public data class MoveOp<V>(
    public val ts: Long,
    public val replica: ReplicaId,
    public val node: String,
    public val newParent: String,
    public val value: V?,
)

// ── Pure replay logic (top-level functions for easy unit inspection) ──────────

/**
 * Merge two sorted, deduplicated op-logs into one sorted, deduplicated list.
 * Deduplication is by `(ts, replica)` identity — two ops with the same timestamp
 * and replicaId are the same op.
 *
 * Both inputs are already sorted and deduplicated (invariant maintained by [insertSorted]),
 * so a standard merge-step suffices: the only "duplicate" that can arise is when both lists
 * contain the same op (equal `ts` + `replica`), handled by the `cmp == 0` branch which
 * advances both pointers and emits the op once.
 */
internal fun <V> mergeDistinctLogs(a: List<MoveOp<V>>, b: List<MoveOp<V>>): List<MoveOp<V>> {
    val merged = mutableListOf<MoveOp<V>>()
    var i = 0; var j = 0
    while (i < a.size && j < b.size) {
        val opA = a[i]; val opB = b[j]
        val cmp = compareOps(opA, opB)
        when {
            cmp < 0 -> { merged += opA; i++ }
            cmp > 0 -> { merged += opB; j++ }
            else    -> { merged += opA; i++; j++ }  // same op in both — emit once
        }
    }
    while (i < a.size) merged += a[i++]
    while (j < b.size) merged += b[j++]
    return merged
}

/** Insert [op] into the sorted [log] in-place (by value, producing a new list). */
internal fun <V> insertSorted(log: List<MoveOp<V>>, op: MoveOp<V>): List<MoveOp<V>> {
    if (log.isEmpty()) return listOf(op)
    // Check for duplicate (idempotent insert).
    if (log.any { it.ts == op.ts && it.replica == op.replica }) return log
    val insertAt = log.indexOfFirst { compareOps(op, it) < 0 }.takeIf { it >= 0 } ?: log.size
    return log.toMutableList().also { it.add(insertAt, op) }
}

/**
 * Replay the log in order to build the effective parent map.
 *
 * For each op (in ascending `(ts, replica)` order):
 * 1. If the op moves the root, skip it.
 * 2. If the proposed [MoveOp.newParent] is a descendant of [MoveOp.node] in the
 *    tree built so far, skip the op (cycle prevention).
 * 3. Otherwise, set `parents[node] = newParent`.
 *
 * Ops that create a new node (have a non-null [MoveOp.value]) also register the
 * node in the parent map; without a prior registration, a later move-only op
 * targeting that node would have nowhere to anchor.
 */
internal fun <V> replayLog(log: List<MoveOp<V>>): Map<String, String> {
    val parents = mutableMapOf<String, String>()
    for (op in log) {
        if (op.node == MovableTree.ROOT_ID) continue
        if (wouldCycle(op.node, op.newParent, parents)) continue
        parents[op.node] = op.newParent
    }
    return parents
}

/**
 * True if moving [node] to [newParent] would introduce a cycle, i.e. if [node]
 * is an ancestor-or-equal of [newParent] in [parents].
 */
internal fun wouldCycle(node: String, newParent: String, parents: Map<String, String>): Boolean {
    if (node == newParent) return true
    return node in ancestorPath(newParent, parents)
}

/**
 * The path from [nodeId] to the root, inclusive.
 * Returns `[ROOT_ID]` if [nodeId] is itself the root (a single-element path).
 * Returns a path ending at [ROOT_ID] if the node is connected, or a partial path
 * (not ending at [ROOT_ID]) if the node is not yet rooted (unknown parent).
 * Uses a visited-set to guard against infinite loops in a corrupt parent map.
 */
internal fun ancestorPath(nodeId: String, parents: Map<String, String>): List<String> {
    val path = mutableListOf<String>()
    val visited = mutableSetOf<String>()
    var current: String? = nodeId
    while (current != null && current != MovableTree.ROOT_ID && visited.add(current)) {
        path += current
        current = parents[current]
    }
    if (current == MovableTree.ROOT_ID) path += MovableTree.ROOT_ID
    return path
}

/** Total order for ops: ascending `ts`, then ascending `replica.value`. */
private fun <V> compareOps(a: MoveOp<V>, b: MoveOp<V>): Int {
    val tsCmp = a.ts.compareTo(b.ts)
    return if (tsCmp != 0) tsCmp else a.replica.value.compareTo(b.replica.value)
}
