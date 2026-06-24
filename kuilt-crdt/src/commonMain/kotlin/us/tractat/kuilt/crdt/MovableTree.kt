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
 * Move-log GC is performed by [compact], which removes causally-stable ops whose
 * effect has been superseded by a later stable op on the same node. Causal
 * stability is determined by the `Quilter` replicator, which gossips delivered
 * version vectors and exposes them to `MovableTreeGcCoordinator`. [causalDots]
 * exposes each op's `(replica, seq)` dot so the `Quilter` can compute the
 * contiguous delivered VV. The compaction result ([MoveTreeCompact]) is
 * broadcast to peers so they can trim their own logs.
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
    /**
     * Dense per-replica sequence counter: `replica → highest seq assigned to that replica`.
     * Bumped at op-creation time (addNode / move); merged (max) on piece().
     * Monotonically non-decreasing — never resets after compaction.
     */
    private val seqByReplica: Map<ReplicaId, Long>,
    /**
     * Dots of ops that have been garbage-collected by a [MoveTreeCompact].
     * Re-emitted in [causalDots] to keep the Quilter's contiguous delivered frontier gap-free
     * after GC removes raw ops from the log.
     */
    private val compactedDots: Set<Dot>,
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

    /** Number of ops in the move-log (grows with every [move] or [addNode] call; shrinks on compaction). */
    public val moveLogSize: Int get() = log.size

    // ── Mutation API ─────────────────────────────────────────────────────────

    /**
     * Add a fresh node under [parent], tagging the op with ([replica], [ts]).
     *
     * The caller is responsible for choosing a [ts] that is monotonically
     * increasing per replica and unique per `(replica, ts)` pair. A practical
     * choice is a Lamport clock incremented on every local operation.
     *
     * @return an [AddNodeResult] carrying the updated tree, the newly minted node id
     *   (`"<value>:<replica>:<ts>"`), and a [Patch] suitable for delta-propagation to
     *   peers via [Quilted.piece]. Mirrors [move]'s `Patch`-returning shape so
     *   `addNode` composes with `Quilter.apply()` without further bookkeeping.
     */
    public fun addNode(replica: ReplicaId, ts: Long, parent: String, value: V): AddNodeResult<V> {
        val nodeId = "$value:${replica.value}:$ts"
        val seq = nextSeqFor(replica)
        val op = MoveOp(ts = ts, replica = replica, node = nodeId, newParent = parent, value = value, seq = seq)
        val delta = MovableTree<V>(listOf(op), mapOf(replica to seq), emptySet())
        return AddNodeResult(tree = applyOp(op, seq), nodeId = nodeId, patch = Patch(delta))
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
        val seq = nextSeqFor(replica)
        val op = MoveOp<V>(ts = ts, replica = replica, node = node, newParent = newParent, value = null, seq = seq)
        val updated = applyOp(op, seq)
        val delta = MovableTree<V>(listOf(op), mapOf(replica to seq), emptySet())
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
        val mergedSeq = mergeSeqs(seqByReplica, other.seqByReplica)
        val mergedCompacted = compactedDots + other.compactedDots
        // Apply any compacted dots from the other side — remove ops that were GC'd there.
        val prunedLog = if (mergedCompacted.isEmpty()) merged
            else merged.filter { op -> op.dot !in mergedCompacted }
        return MovableTree(prunedLog, mergedSeq, mergedCompacted)
    }

    // ── Causal GC ────────────────────────────────────────────────────────────

    /**
     * The causal [Dot]s this op-log has delivered: one dot per [MoveOp] in the log
     * (its `(replica, seq)` pair) **plus** the dots of any previously compacted ops
     * (so the Quilter's contiguous delivered frontier stays gap-free after GC).
     */
    override fun causalDots(): Set<Dot> {
        val liveDots = log.mapTo(mutableSetOf()) { op -> op.dot }
        return liveDots + compactedDots
    }

    /**
     * Garbage-collect causally-stable ops whose effect has been superseded.
     *
     * A [MoveOp] is eligible for GC when ALL hold:
     * 1. **Causally stable** — `op.seq ≤ stableCut[op.replica]`: every live peer delivered it.
     * 2. **Superseded** — a later causally-stable op exists on the same node whose effect is what
     *    the replay actually keeps; this op is not the winning placement for its node.
     * 3. **Not a creation op that is still referenced** — creation ops (`value != null`) that
     *    any live op still references as `node` or `newParent` must be retained.
     * 4. **Frontier-complete** — `delivered.dominates(frontierMax)`: self has delivered every
     *    op known to exist (guards against a concurrent move that would make an op non-superseded).
     *
     * Returns the compacted tree and a [MoveTreeCompact] to broadcast to peers, or `null` if
     * no op is eligible.
     *
     * @param stableCut `S` — elementwise min over live peers' delivered VVs.
     * @param frontierMax `F` — elementwise max of live and retained-evicted peer frontiers.
     * @param delivered this replica's own contiguous delivered VV.
     */
    public fun compact(
        stableCut: VersionVector,
        frontierMax: VersionVector,
        delivered: VersionVector,
    ): Pair<MovableTree<V>, MoveTreeCompact>? {
        if (!delivered.dominates(frontierMax)) return null // condition 4 — frontier-complete

        val winningOps = winningOpPerNode()
        val referencedNodes = referencedNodeIds()
        val droppable = log.filter { op -> isDroppable(op, stableCut, winningOps, referencedNodes) }
        if (droppable.isEmpty()) return null

        val droppedDots = droppable.mapTo(mutableSetOf()) { op -> op.dot }
        val newLog = log.filter { op -> op.dot !in droppedDots }
        val newCompacted = compactedDots + droppedDots
        val compactOp = MoveTreeCompact(droppedDots)
        return MovableTree(newLog, seqByReplica, newCompacted) to compactOp
    }

    /**
     * Apply a [MoveTreeCompact] received from a peer, trimming the local log to match.
     * Idempotent — applying the same compact twice is safe.
     */
    public fun applyCompact(compact: MoveTreeCompact): MovableTree<V> {
        val newLog = log.filter { op -> op.dot !in compact.droppedDots }
        val newCompacted = compactedDots + compact.droppedDots
        return MovableTree(newLog, seqByReplica, newCompacted)
    }

    override fun equals(other: Any?): Boolean =
        other is MovableTree<*> && log == other.log &&
            seqByReplica == other.seqByReplica && compactedDots == other.compactedDots

    override fun hashCode(): Int = 31 * (31 * log.hashCode() + seqByReplica.hashCode()) + compactedDots.hashCode()

    override fun toString(): String = "MovableTree(ops=${log.size}, nodes=${effectiveParents.size + 1})"

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun nextSeqFor(replica: ReplicaId): Long = (seqByReplica[replica] ?: 0L) + 1L

    private fun applyOp(op: MoveOp<V>, seq: Long): MovableTree<V> {
        val newSeq = seqByReplica + (op.replica to maxOf(seqByReplica[op.replica] ?: 0L, seq))
        return MovableTree(insertSorted(log, op), newSeq, compactedDots)
    }

    /**
     * Compute the winning op (last stable, highest-priority applied op) for each node.
     * The replay builds the effective parent map by applying ops in order; a later op
     * on a node that is not skipped (for cycle prevention) supersedes earlier ones.
     * The winning op for node `n` is the last op in replay order whose application
     * actually updated `effectiveParents[n]`.
     */
    private fun winningOpPerNode(): Map<String, MoveOp<V>> {
        val winners = mutableMapOf<String, MoveOp<V>>()
        val parents = mutableMapOf<String, String>()
        for (op in log) {
            if (op.node == ROOT_ID) continue
            if (wouldCycle(op.node, op.newParent, parents)) continue
            parents[op.node] = op.newParent
            winners[op.node] = op
        }
        return winners
    }

    /**
     * The set of all node ids referenced by any op in the log — either as the moved node
     * or as the target parent. Used to protect creation ops from being GC'd while any
     * live op still references the node.
     */
    private fun referencedNodeIds(): Set<String> =
        log.flatMapTo(mutableSetOf()) { op -> listOf(op.node, op.newParent) }

    /**
     * True if [op] is eligible for GC under the compaction safety conditions.
     */
    private fun isDroppable(
        op: MoveOp<V>,
        stableCut: VersionVector,
        winningOps: Map<String, MoveOp<V>>,
        referencedNodes: Set<String>,
    ): Boolean {
        if (!stableCut.contains(op.dot)) return false // (1) not causally stable
        if (op.value != null && op.node in referencedNodes) return false // (3) creation op still referenced
        val winner = winningOps[op.node] ?: return false
        return winner.dot != op.dot // (2) op is not the winning placement for this node
    }

    public companion object {
        /** The reserved id of the tree's root node. The root has no parent. */
        public const val ROOT_ID: String = "__root__"

        /** An empty tree containing only [ROOT_ID]. */
        public fun <V> empty(): MovableTree<V> = MovableTree(emptyList(), emptyMap(), emptySet())
    }
}

// ── AddNode result ────────────────────────────────────────────────────────────

/**
 * The result of [MovableTree.addNode]: the updated tree, the newly minted node id,
 * and the [Patch] that encodes the insertion delta for delta-propagation to peers.
 *
 * Supports three-element destructuring:
 * ```kotlin
 * val (tree, nodeId, patch) = base.addNode(alice, ts = 1L, parent = ROOT_ID, value = "A")
 * // ship `patch` to peers; they absorb with `peer.piece(patch)`
 * ```
 *
 * Two-element destructuring also compiles when the patch is not needed locally:
 * ```kotlin
 * val (tree, nodeId) = base.addNode(...)
 * ```
 */
public data class AddNodeResult<V>(
    /** The tree after the new node has been applied. */
    public val tree: MovableTree<V>,
    /** The id minted for the new node (`"<value>:<replica>:<ts>"`). */
    public val nodeId: String,
    /** Delta patch carrying just the insertion op — absorb on peers with [MovableTree.piece]. */
    public val patch: Patch<MovableTree<V>>,
)

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
 *
 * [seq] is a dense per-replica delivery counter (1, 2, 3, …) used by the
 * causal-stability GC machinery. It is assigned at op-creation time by the
 * replica named in [replica] and is separate from [ts] (which is a Lamport clock,
 * not dense). The [dot] property (`Dot(replica, seq)`) is the causal identifier
 * consumed by `Quilter`.
 *
 * **Logical identity** is `(ts, replica)` — [seq] is a delivery-tracking field that
 * does not participate in [equals] or [hashCode]. Two ops with the same `(ts, replica)`
 * represent the same logical operation; the one with the higher [seq] is canonical
 * (minted by the true author). `[insertSorted]` deduplicates by `(ts, replica)` and
 * retains the op with the higher seq so the canonical author's seq survives merges.
 *
 * **Wire-format note:** adding [seq] is a breaking change. This is intentional
 * and cheap pre-1.0 (see docs/op-log-crdt-compaction.md, #725).
 */
@Serializable
public class MoveOp<V>(
    public val ts: Long,
    public val replica: ReplicaId,
    public val node: String,
    public val newParent: String,
    public val value: V?,
    /** Dense per-replica delivery sequence number. Used by [MovableTree.causalDots] for GC. */
    public val seq: Long,
) {
    /** This op's causal [Dot] — `(replica, seq)`. The key into causal-stability VVs. */
    public val dot: Dot get() = Dot(replica, seq)

    /** Logical identity is (ts, replica) — seq is a delivery-tracking field. */
    override fun equals(other: Any?): Boolean =
        other is MoveOp<*> && ts == other.ts && replica == other.replica

    override fun hashCode(): Int = 31 * ts.hashCode() + replica.hashCode()

    override fun toString(): String =
        "MoveOp(ts=$ts, replica=${replica.value}, node=$node, newParent=$newParent, seq=$seq)"

    public fun copy(
        ts: Long = this.ts,
        replica: ReplicaId = this.replica,
        node: String = this.node,
        newParent: String = this.newParent,
        value: V? = this.value,
        seq: Long = this.seq,
    ): MoveOp<V> = MoveOp(ts, replica, node, newParent, value, seq)
}

// ── Compact op ───────────────────────────────────────────────────────────────

/**
 * Records that the ops identified by [droppedDots] have been garbage-collected from the move-log.
 *
 * Broadcast to peers so they can apply the same trim via [MovableTree.applyCompact].
 * Receiving the same [MoveTreeCompact] twice is idempotent.
 *
 * The [droppedDots] are re-emitted by [MovableTree.causalDots] in the compacted tree so that the
 * Quilter's contiguous delivered frontier has no holes after GC removes raw ops from the log.
 */
@Serializable
public data class MoveTreeCompact(
    /** The `(replica, seq)` dots of ops that were dropped by this compaction. */
    public val droppedDots: Set<Dot>,
)

// ── Pure replay logic (top-level functions for easy unit inspection) ──────────

/**
 * Merge two sorted, deduplicated op-logs into one sorted, deduplicated list.
 * Deduplication is by `(ts, replica)` identity — two ops with the same timestamp
 * and replicaId are the same logical op. When both lists contain the same op,
 * the version with the higher [MoveOp.seq] is kept (canonical author seq wins).
 *
 * Both inputs are already sorted and deduplicated (invariant maintained by [insertSorted]),
 * so a standard merge-step suffices.
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
            else    -> {
                // Same (ts, replica) — canonical op has the higher seq.
                merged += if (opA.seq >= opB.seq) opA else opB
                i++; j++
            }
        }
    }
    while (i < a.size) merged += a[i++]
    while (j < b.size) merged += b[j++]
    return merged
}

/**
 * Insert [op] into the sorted [log] in-place (by value, producing a new list).
 *
 * Deduplication is by logical identity `(ts, replica)`. When a duplicate is detected,
 * the op with the **higher [seq]** wins — that is the canonical version minted by the
 * true author. This ensures that a locally-assigned temporary seq is replaced by the
 * author's canonical seq when the authoritative op arrives via a patch.
 */
internal fun <V> insertSorted(log: List<MoveOp<V>>, op: MoveOp<V>): List<MoveOp<V>> {
    if (log.isEmpty()) return listOf(op)
    val existingIdx = log.indexOfFirst { it.ts == op.ts && it.replica == op.replica }
    if (existingIdx >= 0) {
        // Duplicate — keep the version with the higher seq (canonical author seq wins).
        val existing = log[existingIdx]
        return if (op.seq > existing.seq) log.toMutableList().also { it[existingIdx] = op } else log
    }
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

/** Merge two seqByReplica maps, taking the max per replica. */
internal fun mergeSeqs(a: Map<ReplicaId, Long>, b: Map<ReplicaId, Long>): Map<ReplicaId, Long> {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val result = a.toMutableMap()
    for ((replica, seq) in b) {
        val current = result[replica]
        if (current == null || seq > current) result[replica] = seq
    }
    return result
}

/** Total order for ops: ascending `ts`, then ascending `replica.value`. */
private fun <V> compareOps(a: MoveOp<V>, b: MoveOp<V>): Int {
    val tsCmp = a.ts.compareTo(b.ts)
    return if (tsCmp != 0) tsCmp else a.replica.value.compareTo(b.replica.value)
}
