package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A unique, totally-ordered identity for a single RGA element.
 *
 * Total order: higher [lamport] wins; [replicaId] breaks ties deterministically.
 * The special sentinel [HEAD] sorts before every real id and is used as the
 * "insert at front" predecessor.
 */
@Serializable
public data class RgaId(
    public val lamport: Long,
    public val replicaId: ReplicaId,
) : Comparable<RgaId> {
    override fun compareTo(other: RgaId): Int {
        val byLamport = lamport.compareTo(other.lamport)
        return if (byLamport != 0) byLamport else replicaId.value.compareTo(other.replicaId.value)
    }

    public companion object {
        /**
         * Sentinel predecessor meaning "insert at the very beginning of the list".
         * Sorts before every real [RgaId].
         */
        public val HEAD: RgaId = RgaId(lamport = Long.MIN_VALUE, replicaId = ReplicaId(""))
    }
}

/**
 * An operation on an [Rga] sequence.
 *
 * Operations are immutable, serializable, and carry their own [RgaId] so they
 * can be delivered in any order and still produce a deterministic sequence.
 */
@Serializable
public sealed interface RgaOp<out V> {

    /**
     * Insert [value] with identity [id] immediately after the element whose id
     * is [after] ([RgaId.HEAD] means "insert at the front").
     *
     * Concurrent inserts after the same [after] are resolved by [id]: the
     * larger id wins the slot immediately after [after]; the smaller id follows.
     */
    @Serializable
    public data class Insert<V>(
        public val id: RgaId,
        public val value: V,
        public val after: RgaId,
    ) : RgaOp<V>

    /**
     * Tombstone the element with [id]. Tombstones are retained in the op-log so
     * that future causal references to [id] remain resolvable.
     */
    @Serializable
    public data class Remove<V>(
        public val id: RgaId,
    ) : RgaOp<V>
}

/**
 * A Replicated Growable Array (RGA): an op-based sequence CRDT for ordered
 * collections such as chat messages or collaborative text.
 *
 * **How it fits [Quilted].** Unlike the delta-state types in this module, RGA's
 * natural unit is an **operation**, not a state fragment. The "state" here is the
 * full op-log: a set of [RgaOp]s. [piece] is an idempotent union of two op-logs —
 * that union satisfies the lattice laws (idempotent, commutative, associative)
 * because the ops are uniquely identified and set-union has those properties.
 * Any two replicas that have absorbed the same set of ops compute the identical
 * sequence from [toList], regardless of the order in which they absorbed them.
 *
 * **Concurrent-insert tiebreak.** When two `Insert(idA, _, p)` and
 * `Insert(idB, _, p)` share the same predecessor `p`, the larger id wins the
 * immediately-after slot. With `idA > idB` the resulting list is `… p A B …`.
 *
 * **Tombstones.** Removed elements remain in the op-log. This is deliberate:
 * a future `Insert(id, _, removedId)` must still find the predecessor. GC of
 * tombstones requires a distributed garbage-collection protocol (out of scope).
 *
 * **Lamport clock.** Each replica tracks a local [lamport] counter. Minting a
 * new op increments it and records the current maximum observed across all
 * received ops.
 *
 * @param V the element type. Must be serializable for wire transport.
 */
@Serializable
public class Rga<V> private constructor(
    /** All ops ever seen by this replica. Op-log is the source of truth. */
    private val ops: Set<RgaOp<V>>,
    /** This replica's current Lamport timestamp (max seen + 1 after any op). */
    public val lamport: Long,
) : Quilted<Rga<V>> {

    /**
     * The set of all [RgaId]s that have been tombstoned.
     */
    private val tombstones: Set<RgaId> by lazy {
        ops.filterIsInstance<RgaOp.Remove<V>>().mapTo(mutableSetOf()) { it.id }
    }

    /**
     * Map from each [RgaId] to its insert op, for O(1) lookup by id.
     */
    private val insertsByid: Map<RgaId, RgaOp.Insert<V>> by lazy {
        ops.filterIsInstance<RgaOp.Insert<V>>().associateBy { it.id }
    }

    /**
     * The materialized sequence: all inserted elements in RGA order, including
     * tombstones. Computed lazily and cached.
     */
    private val sequence: List<RgaId> by lazy { computeSequence() }

    // ---- Public API ----

    /**
     * The current visible (non-tombstoned) elements, in sequence order.
     */
    public fun toList(): List<V> = sequence
        .filter { id -> id !in tombstones }
        .map { id -> insertsByid.getValue(id).value }

    /**
     * The number of visible elements.
     */
    public val size: Int get() = toList().size

    /**
     * Insert [value] immediately after the element with [after] id, minting a
     * new [RgaId] on behalf of [replica].
     *
     * Returns the new [Rga] state and the [RgaOp.Insert] op to broadcast.
     */
    public fun insertAfter(
        replica: ReplicaId,
        after: RgaId,
        value: V,
    ): Pair<Rga<V>, RgaOp.Insert<V>> {
        val newLamport = lamport + 1L
        val id = RgaId(lamport = newLamport, replicaId = replica)
        val op = RgaOp.Insert(id = id, value = value, after = after)
        val newOps = ops + op
        return Rga(newOps, newLamport) to op
    }

    /**
     * Insert [value] at visible position [index] (0 = prepend before first
     * visible element). Computes the [after] id from the current visible
     * sequence.
     *
     * @throws IndexOutOfBoundsException if [index] is outside `0..size`.
     */
    public fun insertAt(
        replica: ReplicaId,
        index: Int,
        value: V,
    ): Pair<Rga<V>, RgaOp.Insert<V>> {
        val visible = visibleSequence()
        require(index in 0..visible.size) {
            "insertAt($index) out of range; visible size is ${visible.size}"
        }
        val after = if (index == 0) RgaId.HEAD else visible[index - 1]
        return insertAfter(replica = replica, after = after, value = value)
    }

    /**
     * Remove the visible element at [index].
     *
     * Returns the new [Rga] state and the [RgaOp.Remove] op to broadcast, or
     * `null` if the index is out of range (the list is empty, or [index] is
     * out of bounds).
     */
    public fun removeAt(index: Int): Pair<Rga<V>, RgaOp.Remove<V>>? {
        val visible = visibleSequence()
        if (index !in visible.indices) return null
        val id = visible[index]
        val op = RgaOp.Remove<V>(id = id)
        val newOps = ops + op
        return Rga(newOps, lamport) to op
    }

    /**
     * Apply an [op] received from a remote replica, advancing the Lamport clock.
     *
     * This is the receive path for op-based propagation: each received op is
     * absorbed exactly once. Duplicate delivery is safe — set-union is idempotent.
     */
    public fun apply(op: RgaOp<V>): Rga<V> {
        val newLamport = when (op) {
            is RgaOp.Insert -> maxOf(lamport, op.id.lamport)
            is RgaOp.Remove -> lamport
        }
        return Rga(ops + op, newLamport)
    }

    /**
     * Merge two replicas' op-logs. The result is the idempotent union — both
     * replicas converge to the same [toList] after [piece].
     *
     * This satisfies the [Quilted] lattice laws:
     * - **Idempotent**: `a.piece(a) == a` (set union with itself)
     * - **Commutative**: `a.piece(b) == b.piece(a)` (set union is commutative)
     * - **Associative**: `a.piece(b).piece(c) == a.piece(b.piece(c))` (set union)
     */
    override fun piece(other: Rga<V>): Rga<V> {
        val merged = ops + other.ops
        val mergedLamport = maxOf(lamport, other.lamport)
        return Rga(merged, mergedLamport)
    }

    override fun equals(other: Any?): Boolean =
        other is Rga<*> && ops == other.ops && lamport == other.lamport

    override fun hashCode(): Int = 31 * ops.hashCode() + lamport.hashCode()

    override fun toString(): String = "Rga(${toList()})"

    // ---- Private helpers ----

    private fun visibleSequence(): List<RgaId> = sequence.filter { it !in tombstones }

    /**
     * Materializes the sequence from the op-log using the classic RGA ordering:
     *
     * 1. Start at [RgaId.HEAD].
     * 2. For each position, collect all inserts whose `after` points here.
     * 3. Sort them by [RgaId] descending — the largest id wins the slot
     *    immediately after the predecessor (concurrent-insert tiebreak).
     * 4. Recurse for each child in sorted order.
     *
     * This produces the canonical RGA sequence: deterministic across all replicas
     * that have seen the same op-log, regardless of insertion order.
     */
    private fun computeSequence(): List<RgaId> {
        // Group all insert ops by their `after` predecessor.
        val childrenOf: Map<RgaId, List<RgaId>> = insertsByid.values
            .groupBy(keySelector = { it.after }, valueTransform = { it.id })
            .mapValues { (_, ids) -> ids.sortedDescending() }

        val result = mutableListOf<RgaId>()
        appendChildren(RgaId.HEAD, childrenOf, result)
        return result
    }

    /**
     * Depth-first traversal of the RGA tree, appending children in descending-id
     * order at each node (the concurrent-insert tiebreak).
     */
    private fun appendChildren(
        parent: RgaId,
        childrenOf: Map<RgaId, List<RgaId>>,
        result: MutableList<RgaId>,
    ) {
        for (child in childrenOf[parent].orEmpty()) {
            result.add(child)
            appendChildren(child, childrenOf, result)
        }
    }

    public companion object {
        /** The empty sequence with no ops. */
        public fun <V> empty(): Rga<V> = Rga(ops = emptySet(), lamport = 0L)
    }
}
