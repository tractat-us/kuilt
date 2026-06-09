package us.tractat.kuilt.crdt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * A unique, totally-ordered identity for a single RGA element.
 *
 * Carries two orthogonal counters:
 * - [lamport] — the total-order tiebreak used by `computeSequence`. Monotonic per
 *   author but **not dense** (the clock jumps to `max(seen) + 1`).
 * - [seq] — a **dense, contiguous per-author delivery counter** (1, 2, 3, …). This
 *   is the quantity Lamports cannot provide: it certifies *contiguous* delivery, so
 *   it is the key into the causal-stability version vectors used by [Rga.compact]
 *   (ADR-003 addendum v3, #262). [seq] never participates in ordering.
 *
 * Total order ([compareTo]): higher [lamport] wins; [replicaId] breaks ties
 * deterministically. [seq] is deliberately excluded — it tracks delivery, not order.
 * Two real ids from the same author can never share a [lamport] (an author's clock
 * is strictly monotonic), so the order is still total.
 *
 * The special sentinel [HEAD] sorts before every real id and is used as the
 * "insert at front" predecessor; its [seq] is `0` (it is never an author dot).
 */
@Serializable
public data class RgaId(
    public val lamport: Long,
    public val replicaId: ReplicaId,
    public val seq: Long,
) : Comparable<RgaId> {
    override fun compareTo(other: RgaId): Int {
        val byLamport = lamport.compareTo(other.lamport)
        return if (byLamport != 0) byLamport else replicaId.value.compareTo(other.replicaId.value)
    }

    /** This id's causal [Dot] — `(replicaId, seq)`. The key into causal-stability VVs. */
    public val dot: Dot get() = Dot(replicaId, seq)

    public companion object {
        /**
         * Sentinel predecessor meaning "insert at the very beginning of the list".
         * Sorts before every real [RgaId]; its [seq] is `0` (never an author dot).
         */
        public val HEAD: RgaId = RgaId(lamport = Long.MIN_VALUE, replicaId = ReplicaId(""), seq = 0L)
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

    /**
     * Records that the [positions] entries have been garbage-collected from the op-log.
     *
     * The map carries each compacted id's predecessor at GC time (`id → Insert.after`).
     * [computeSequence] uses this to reattach surviving successors to the nearest surviving
     * ancestor (positional reroot, #293) rather than to [RgaId.HEAD].
     *
     * The ids that are purged are [positions].keys. Merging two [Compact] ops via [Rga.piece]
     * unions their [positions] maps — sound because a given id's `after` is fixed when its
     * [Insert] was created, so two replicas always agree on the value.
     *
     * Applying a [Compact] removes every [Insert] and [Remove] op whose id is in [positions].keys.
     * Receiving the same [Compact] twice is idempotent.
     */
    @Serializable
    public data class Compact(
        public val positions: Map<RgaId, RgaId>,
    ) : RgaOp<Nothing>
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
 * tombstones is performed by [compact].
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
    internal val ops: Set<RgaOp<V>>,
    /** This replica's current Lamport timestamp (max seen + 1 after any op). */
    public val lamport: Long,
) : Quilted<Rga<V>> {

    /**
     * All ids that have been garbage-collected by any [RgaOp.Compact] in this op-log.
     */
    private val compactedIds: Set<RgaId> by lazy {
        ops.filterIsInstance<RgaOp.Compact>()
            .flatMapTo(mutableSetOf()) { it.positions.keys }
    }

    /**
     * The set of all [RgaId]s that have been tombstoned (and not yet compacted).
     *
     * Exposed for [us.tractat.kuilt.crdt.replicator.WindowPolicy] implementations that need to
     * inspect the current tombstone set (e.g. `WindowPolicy.byCount`).
     */
    public val tombstones: Set<RgaId> by lazy {
        ops.filterIsInstance<RgaOp.Remove<V>>()
            .mapTo(mutableSetOf()) { it.id }
            .apply { removeAll(compactedIds) }
    }

    /**
     * Map from each [RgaId] to its insert op, for O(1) lookup by id.
     * Excludes compacted ids — their Insert ops have been removed from the log.
     */
    private val insertsByid: Map<RgaId, RgaOp.Insert<V>> by lazy {
        ops.filterIsInstance<RgaOp.Insert<V>>().associateBy { it.id }
    }

    /**
     * The materialized sequence of all [RgaId]s in RGA order, including tombstones.
     * Computed lazily and cached.
     *
     * Exposed for [us.tractat.kuilt.crdt.replicator.WindowPolicy] implementations that need to
     * inspect the full ordered sequence (e.g. `WindowPolicy.byCount`).
     */
    public val sequence: List<RgaId> by lazy { computeSequence() }

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
        val seq = nextSeqFor(replica)
        val id = RgaId(lamport = newLamport, replicaId = replica, seq = seq)
        val op = RgaOp.Insert(id = id, value = value, after = after)
        val newOps = ops + op
        return Rga(newOps, newLamport) to op
    }

    /**
     * The next dense per-author [RgaId.seq] for [replica], derived from the op-log:
     * one past the highest [RgaId.seq] this replica has already authored. No external
     * counter — the op-log is the source of truth, so a replica reconstructed from its
     * ops mints the correct next seq.
     */
    private fun nextSeqFor(replica: ReplicaId): Long {
        val highest = ops.asSequence()
            .filterIsInstance<RgaOp.Insert<V>>()
            .map { it.id }
            .filter { it.replicaId == replica }
            .maxOfOrNull { it.seq } ?: 0L
        return highest + 1L
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
     * Garbage-collect tombstoned elements that are **causally stable** under the
     * eviction-safe causal-stability barrier (ADR-003 addendum v3, #262).
     *
     * A tombstoned element with dot `(r, sᵢ)` is purged iff **all** hold:
     * 1. **Tombstoned** — implied (only [tombstones] are candidates).
     * 2. **Causally stable** — `sᵢ ≤ stableCut[r]`: every live peer has delivered it.
     * 3. **Frontier-complete** — `∀x: delivered[x] ≥ frontierMax[x]`: this replica
     *    has delivered every op below every known frontier, so condition 4 below is
     *    *complete* (any concurrent `Insert(_, after=id)` that exists anywhere has
     *    been delivered locally and is therefore visible to condition 4).
     * 4. **No surviving local successor** — no surviving [RgaOp.Insert] has
     *    `after == id`, preserving the structural-predecessor invariant.
     *
     * Conditions 2 and 3 are the author-independent barrier the prior scalar
     * watermark silently assumed: a concurrent `Insert(J, after=I)` minted by a
     * *different* author cannot coexist with a [frontierMax] this replica has fully
     * delivered. See the ADR for the by-construction safety argument against the
     * #272 (author-independence) and #275 (eviction) probes.
     *
     * @param stableCut `S` — elementwise **min** over all live peers' delivered VVs.
     * @param frontierMax `F` — elementwise **max** of the live frontier and the
     *   retained (evicted-peer) frontier; the set of dots known to *exist*.
     * @param delivered this replica's own contiguous delivered VV.
     *
     * Returns the compacted [Rga] and a [RgaOp.Compact] delta to broadcast to
     * peers, or `null` if no element qualifies (or condition 3 is not yet met).
     *
     * Peers that receive the [RgaOp.Compact] delta apply it via [apply] or absorb
     * it through [piece] — both paths strip the referenced ops from the log.
     */
    public fun compact(
        stableCut: VersionVector,
        frontierMax: VersionVector,
        delivered: VersionVector,
    ): Pair<Rga<V>, RgaOp.Compact>? {
        if (!delivered.dominates(frontierMax)) return null // condition 3 — frontier-complete
        val predecessors = insertsByid.values.mapTo(mutableSetOf()) { it.after }
        val gcIds = tombstones
            .filter { id -> stableCut.contains(id.dot) && id !in predecessors } // (2) + (4)
            .toSet()
        if (gcIds.isEmpty()) return null
        val positions = gcIds.associateWith { id -> insertsByid.getValue(id).after }
        val compactOp = RgaOp.Compact(positions)
        val newOps = purgeAndRecord(ops, gcIds, compactOp)
        return Rga(newOps, lamport) to compactOp
    }

    /**
     * Returns a positions map for [ids]: each id mapped to its [RgaOp.Insert.after].
     * All ids must be present in [insertsByid] (live, non-compacted elements).
     * Used by [us.tractat.kuilt.crdt.replicator.RgaGcCoordinator] to build positions
     * for window-dropped live elements when constructing a combined [RgaOp.Compact].
     */
    internal fun positionsFor(ids: Set<RgaId>): Map<RgaId, RgaId> =
        ids.associateWith { id -> insertsByid.getValue(id).after }

    /**
     * Apply an [op] received from a remote replica, advancing the Lamport clock.
     *
     * This is the receive path for op-based propagation: each received op is
     * absorbed exactly once. Duplicate delivery is safe — set-union is idempotent.
     *
     * Applying a [RgaOp.Compact] strips the referenced [RgaOp.Insert] and
     * [RgaOp.Remove] ops from the log. The [RgaOp.Compact] op itself is retained
     * so that a later [piece] with a peer that hasn't compacted yet re-applies GC.
     *
     * An [RgaOp.Insert] or [RgaOp.Remove] whose id is already compacted is **not**
     * re-added — a late raw apply of a purged op must not resurrect it. This makes
     * [apply] agree with [piece] (and [tombstones]): once compacted, always
     * compacted (ADR-003 addendum v3, #262).
     */
    public fun apply(op: RgaOp<V>): Rga<V> = when (op) {
        is RgaOp.Insert -> if (op.id in compactedIds) this else Rga(ops + op, maxOf(lamport, op.id.lamport))
        is RgaOp.Remove -> if (op.id in compactedIds) this else Rga(ops + op, lamport)
        is RgaOp.Compact -> Rga(purgeAndRecord(ops, op.positions.keys, op), lamport)
    }

    /**
     * Merge two replicas' op-logs. The result is the idempotent union — both
     * replicas converge to the same [toList] after [piece].
     *
     * This satisfies the [Quilted] lattice laws:
     * - **Idempotent**: `a.piece(a) == a` (set union with itself)
     * - **Commutative**: `a.piece(b) == b.piece(a)` (set union is commutative)
     * - **Associative**: `a.piece(b).piece(c) == a.piece(b.piece(c))` (set union)
     *
     * Any [RgaOp.Compact] ops in the union are applied eagerly so that Insert/Remove
     * ops already GC'd on one peer do not re-inflate the op-log on merge.
     */
    /**
     * The causal [Dot]s this op-log has delivered: every `Insert`'s own dot
     * (`id.dot = (replicaId, seq)`) **plus** every dot recorded in a `Compact` op.
     *
     * This is the [Quilted] capability the causal-stability GC barrier consumes
     * (ADR-003 addendum v3, #262); the [SeamReplicator] folds it into a **contiguous**
     * delivered version vector. The per-author seq space is dense and defined by
     * `Insert`s, so the set must stay gap-free across GC:
     *
     * - **`Insert` → its dot.**
     * - **`Compact` → its `ids`' dots.** A compaction *removes* the GC'd `Insert`s from
     *   [ops], but those dots **were delivered** (GC only fires once a dot is causally
     *   stable — at-or-below the min over all peers — so every replica delivered it).
     *   If they were dropped here, GC'ing a non-tail dot would punch a permanent hole in
     *   the contiguous frontier, pinning that author's delivered high-water below the gap
     *   forever and stalling all further GC for that author. Re-emitting the `Compact`'d
     *   dots keeps the frontier monotonic across compaction. Including them cannot
     *   over-claim: a `Compact` only exists for universally-delivered dots, and a late
     *   joiner receives them inside FullState's already-compacted state.
     * - **`Remove` → nothing.** A `Remove` reuses its *target Insert's* id (`removeAt`:
     *   `val id = visible[index]`); it mints no dot of its own. Counting it would
     *   over-claim when a `Remove(x)` is delivered out-of-order before `Insert(x)` —
     *   reporting `x` delivered while holding only the tombstone, prematurely advancing
     *   the stable cut (the #275-class hazard).
     */
    override fun causalDots(): Set<Dot> =
        ops.asSequence()
            .flatMap { op ->
                when (op) {
                    is RgaOp.Insert -> sequenceOf(op.id.dot)
                    is RgaOp.Compact -> op.positions.keys.asSequence().map { it.dot }
                    is RgaOp.Remove -> emptySequence()
                }
            }
            .toSet()

    override fun piece(other: Rga<V>): Rga<V> {
        val rawUnion = ops + other.ops
        val mergedLamport = maxOf(lamport, other.lamport)
        val allCompactedIds = rawUnion.filterIsInstance<RgaOp.Compact>()
            .flatMapTo(mutableSetOf()) { it.positions.keys }
        val mergedOps = if (allCompactedIds.isEmpty()) rawUnion else purge(rawUnion, allCompactedIds)
        return Rga(mergedOps, mergedLamport)
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
     *
     * **Reroot-to-HEAD (#254).** An [RgaOp.Insert] whose `after` is **neither [RgaId.HEAD]
     * nor a present (non-compacted) Insert id** has had its structural predecessor purged
     * (history windowing compacts a leading prefix of live inserts). Rather than letting the
     * survivor become unreachable from [RgaId.HEAD] — collapsing the whole retained window to
     * `[]` — it materializes as a child of [RgaId.HEAD], deterministically ordered with HEAD's
     * other children by the same descending-id tiebreak. This is essential for windowing and
     * benign for tombstone GC: a causally-stable GC'd element has no surviving local successor
     * (barrier condition 4), so nothing ever reroots on the GC path.
     */
    private fun computeSequence(): List<RgaId> {
        // Group all insert ops by their effective predecessor: HEAD if `after` is HEAD or has
        // been compacted away (reroot-to-HEAD), else the present `after` id.
        val present = insertsByid.keys
        val childrenOf: Map<RgaId, List<RgaId>> = insertsByid.values
            .groupBy(
                keySelector = { if (it.after in present) it.after else RgaId.HEAD },
                valueTransform = { it.id },
            )
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

        /**
         * Package-internal factory for deserialization via [RgaSerializer].
         * Uses the private constructor directly.
         */
        internal fun <V> fromOps(ops: Set<RgaOp<V>>, lamport: Long): Rga<V> = Rga(ops, lamport)

        /**
         * Returns a [kotlinx.serialization.KSerializer] for [Rga]`<V>` that correctly threads
         * [vSerializer] through the op-log serialization, avoiding the CBOR polymorphism
         * limitation of the compiler-generated `Rga$$serializer`.
         *
         * **Use this instead of `Rga.serializer(...)` when wiring [Rga] into a
         * [us.tractat.kuilt.crdt.replicator.SeamReplicator]** — the generated serializer fails
         * for CBOR transport because it defaults to `PolymorphicSerializer(Any::class)` for the
         * element type [V] in [RgaOp.Insert.value].
         *
         * Usage:
         * ```kotlin
         * val msgSer = ReplicatorMessage.serializer(Rga.wireSerializer(serializer<String>()))
         * val replicator = SeamReplicator(..., messageSerializer = msgSer)
         * ```
         *
         * @param vSerializer the [kotlinx.serialization.KSerializer] for element type [V].
         */
        public fun <V> wireSerializer(vSerializer: KSerializer<V>): KSerializer<Rga<V>> =
            RgaSerializer(vSerializer)

        /**
         * Strip Insert and Remove ops for all [gcIds] from [ops], merging the
         * [compactOp] in (replacing any prior Compact op with the same or smaller
         * id set — or adding if absent). The Compact op itself is retained.
         */
        internal fun <V> purgeAndRecord(
            ops: Set<RgaOp<V>>,
            gcIds: Set<RgaId>,
            compactOp: RgaOp.Compact,
        ): Set<RgaOp<V>> {
            val purged = purge(ops, gcIds)
            return purged + compactOp
        }

        /**
         * Remove all [RgaOp.Insert] and [RgaOp.Remove] ops whose id is in [gcIds].
         * [RgaOp.Compact] ops are left intact.
         */
        internal fun <V> purge(ops: Set<RgaOp<V>>, gcIds: Set<RgaId>): Set<RgaOp<V>> =
            ops.filterTo(mutableSetOf()) { op ->
                when (op) {
                    is RgaOp.Insert -> op.id !in gcIds
                    is RgaOp.Remove -> op.id !in gcIds
                    is RgaOp.Compact -> true
                }
            }

    }
}
