package us.tractat.kuilt.crdt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
 * Incrementally-maintained derived state, threaded forward across mutations to avoid
 * O(ops) rescans on every [Rga.insertAfter], [Rga.apply], and [Rga.piece] call.
 *
 * Passed via the `@Transient` [Rga.cache] parameter so the kotlinx-serialization
 * plugin does not include it in the wire format. Deserialization always reconstructs
 * these from the op-log via [Rga.fromOps].
 */
internal data class RgaCache<V>(
    val insertsById: Map<RgaId, RgaOp.Insert<V>>,
    val maxSeqByReplica: Map<ReplicaId, Long>,
    val tombstones: Set<RgaId>,
    val compactedIds: Set<RgaId>,
    val compactPositions: Map<RgaId, RgaId>,
) {
    companion object {
        fun <V> empty(): RgaCache<V> = RgaCache(
            insertsById = emptyMap(),
            maxSeqByReplica = emptyMap(),
            tombstones = emptySet(),
            compactedIds = emptySet(),
            compactPositions = emptyMap(),
        )
    }
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
 *
 * @sample us.tractat.kuilt.crdt.sampleRga
 */
@Serializable
public class Rga<V> private constructor(
    /** All ops ever seen by this replica. Op-log is the source of truth. */
    internal val ops: Set<RgaOp<V>>,
    /** This replica's current Lamport timestamp (max seen + 1 after any op). */
    public val lamport: Long,
    /**
     * Pre-computed derived state. When non-null (all mutation paths), the fields are
     * used directly instead of scanning [ops]. When null (deserialization via
     * [fromOps]), each field is computed from [ops] on first access.
     * Excluded from the wire format by [@Transient].
     */
    @Transient private val cache: RgaCache<V>? = null,
) : Quilted<Rga<V>> {

    /**
     * All ids that have been garbage-collected by any [RgaOp.Compact] in this op-log.
     */
    private val compactedIds: Set<RgaId> by lazy {
        cache?.compactedIds ?: computeCompactedIds()
    }

    /**
     * The set of all [RgaId]s that have been tombstoned (and not yet compacted).
     *
     * Exposed for `WindowPolicy` (in :kuilt-quilter) implementations that need to
     * inspect the current tombstone set (e.g. `WindowPolicy.byCount`).
     */
    public val tombstones: Set<RgaId> by lazy {
        cache?.tombstones ?: computeTombstones()
    }

    /**
     * Map from each [RgaId] to its insert op, for O(1) lookup by id.
     * Excludes compacted ids — their Insert ops have been removed from the log.
     * Threaded forward by mutations to avoid O(ops) rescans.
     * Exposed as `internal` for test verification; consumers should use [toList]/[sequence].
     */
    internal val insertsById: Map<RgaId, RgaOp.Insert<V>> by lazy {
        cache?.insertsById ?: computeInsertsById()
    }

    /**
     * Ceiling of the [RgaId.seq] seen per [ReplicaId], incremented O(1) on each
     * insert and merged on [piece]. Powers [nextSeqFor] without scanning the op-log.
     * Exposed as `internal` for test verification.
     */
    internal val maxSeqByReplica: Map<ReplicaId, Long> by lazy {
        cache?.maxSeqByReplica ?: computeMaxSeqByReplica()
    }

    /**
     * Union of all [RgaOp.Compact] ops' [RgaOp.Compact.positions] maps in this log.
     * Maps each compacted id to its [RgaOp.Insert.after] at GC time.
     * Used by [computeSequence] to resolve orphaned elements to their nearest surviving ancestor.
     * Collisions are impossible: a given id's [RgaOp.Insert.after] is fixed at insert time,
     * so two [RgaOp.Compact] ops can carry the same key only with the same value.
     */
    private val compactPositions: Map<RgaId, RgaId> by lazy {
        cache?.compactPositions ?: computeCompactPositions()
    }

    /**
     * The materialized sequence of all [RgaId]s in RGA order, including tombstones.
     * Computed lazily and cached.
     *
     * Exposed for `WindowPolicy` (in :kuilt-quilter) implementations that need to
     * inspect the full ordered sequence (e.g. `WindowPolicy.byCount`).
     */
    public val sequence: List<RgaId> by lazy { computeSequence() }

    // ---- Public API ----

    /**
     * The current visible (non-tombstoned) elements, in sequence order.
     */
    public fun toList(): List<V> = sequence
        .filter { id -> id !in tombstones }
        .map { id -> insertsById.getValue(id).value }

    /**
     * The visible elements paired with their [RgaId]s, in sequence order — the
     * id-carrying form of [toList] (`toList() == entries().map { it.second }`).
     *
     * Each pair is `(id, value)`. The [RgaId] is the element's total-order key:
     * [RgaId.compareTo] is `(lamport, replicaId)`, so a consumer holding entries
     * from several replicas can interleave them into one deterministic order, and
     * [RgaId.dot] gives the causal `(replicaId, seq)` handle. Use this instead of
     * hand-zipping [sequence] against [toList] when you need each element's origin
     * and ordering position, not just its value.
     */
    public fun entries(): List<Pair<RgaId, V>> = sequence
        .filter { id -> id !in tombstones }
        .map { id -> id to insertsById.getValue(id).value }

    /**
     * The number of visible elements.
     */
    public val size: Int get() = sequence.count { it !in tombstones }

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
        val newCache = RgaCache(
            insertsById = insertsById + (id to op),
            maxSeqByReplica = maxSeqByReplica + (replica to seq),
            tombstones = tombstones,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
        )
        return Rga(newOps, newLamport, newCache) to op
    }

    /**
     * The next dense per-author [RgaId.seq] for [replica], derived from the
     * incrementally-maintained [maxSeqByReplica] map: O(1) lookup instead of
     * scanning the entire op-log.
     */
    private fun nextSeqFor(replica: ReplicaId): Long = (maxSeqByReplica[replica] ?: 0L) + 1L

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
        val newCache = RgaCache(
            insertsById = insertsById,
            maxSeqByReplica = maxSeqByReplica,
            tombstones = tombstones + id,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
        )
        return Rga(newOps, lamport, newCache) to op
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
        val predecessors = insertsById.values.mapTo(mutableSetOf()) { it.after }
        val gcIds = tombstones
            .filter { id -> stableCut.contains(id.dot) && id !in predecessors } // (2) + (4)
            .toSet()
        if (gcIds.isEmpty()) return null
        val positions = gcIds.associateWith { id -> insertsById.getValue(id).after }
        val compactOp = RgaOp.Compact(positions)
        val newOps = purgeAndRecord(ops, gcIds, compactOp)
        return withCompactCaches(newOps, gcIds, compactOp) to compactOp
    }

    /**
     * Returns a positions map for [ids]: each id mapped to its [RgaOp.Insert.after].
     * All ids must be present in [insertsById] (non-compacted — live or tombstoned).
     * Used by [us.tractat.kuilt.quilter.RgaGcCoordinator] to build positions
     * for window-dropped live elements when constructing a combined [RgaOp.Compact].
     */
    public fun positionsFor(ids: Set<RgaId>): Map<RgaId, RgaId> =
        ids.associateWith { id -> insertsById.getValue(id).after }

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
        is RgaOp.Insert -> applyInsert(op)
        is RgaOp.Remove -> applyRemove(op)
        is RgaOp.Compact -> applyCompact(op)
    }

    private fun applyInsert(op: RgaOp.Insert<V>): Rga<V> {
        if (op.id in compactedIds) return this
        val newOps = ops + op
        val newLamport = maxOf(lamport, op.id.lamport)
        val newCache = RgaCache(
            insertsById = insertsById + (op.id to op),
            maxSeqByReplica = updateMaxSeq(maxSeqByReplica, op.id),
            tombstones = tombstones,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
        )
        return Rga(newOps, newLamport, newCache)
    }

    private fun applyRemove(op: RgaOp.Remove<V>): Rga<V> {
        if (op.id in compactedIds) return this
        val newOps = ops + op
        val newCache = RgaCache(
            insertsById = insertsById,
            maxSeqByReplica = maxSeqByReplica,
            tombstones = tombstones + op.id,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
        )
        return Rga(newOps, lamport, newCache)
    }

    private fun applyCompact(op: RgaOp.Compact): Rga<V> {
        val newOps = purgeAndRecord(ops, op.positions.keys, op)
        return withCompactCaches(newOps, op.positions.keys, op)
    }

    /**
     * Build a new [Rga] whose caches reflect a compact operation that purges [gcIds].
     * Shared by [compact] (self-initiated) and [applyCompact] (remote-received).
     */
    private fun withCompactCaches(
        newOps: Set<RgaOp<V>>,
        gcIds: Set<RgaId>,
        compactOp: RgaOp.Compact,
    ): Rga<V> {
        val newCache = RgaCache(
            insertsById = insertsById - gcIds,
            maxSeqByReplica = maxSeqByReplica,
            tombstones = tombstones - gcIds,
            compactedIds = compactedIds + gcIds,
            compactPositions = compactPositions + compactOp.positions,
        )
        return Rga(newOps, lamport, newCache)
    }

    /**
     * The causal [Dot]s this op-log has delivered: every `Insert`'s own dot
     * (`id.dot = (replicaId, seq)`) **plus** every dot recorded in a `Compact` op.
     *
     * This is the [Quilted] capability the causal-stability GC barrier consumes
     * (ADR-003 addendum v3, #262); the [Quilter] folds it into a **contiguous**
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
     *
     * Derived caches are merged incrementally — no full O(ops) rescan on the merged result.
     */
    override fun piece(other: Rga<V>): Rga<V> {
        val rawUnion = ops + other.ops
        val mergedLamport = maxOf(lamport, other.lamport)
        val mergedCompactedIds = compactedIds + other.compactedIds
        val mergedCompactPositions = compactPositions + other.compactPositions
        val rawInsertsById = insertsById + other.insertsById
        val mergedInsertsById = if (mergedCompactedIds.isEmpty()) rawInsertsById
            else rawInsertsById.filterKeys { it !in mergedCompactedIds }
        val rawTombstones = tombstones + other.tombstones
        val mergedTombstones = if (mergedCompactedIds.isEmpty()) rawTombstones
            else rawTombstones.filterTo(mutableSetOf()) { it !in mergedCompactedIds }
        val mergedMaxSeq = mergeMaxSeq(maxSeqByReplica, other.maxSeqByReplica)
        val mergedOps = if (mergedCompactedIds.isEmpty()) rawUnion else purge(rawUnion, mergedCompactedIds)
        val newCache = RgaCache(
            insertsById = mergedInsertsById,
            maxSeqByReplica = mergedMaxSeq,
            tombstones = mergedTombstones,
            compactedIds = mergedCompactedIds,
            compactPositions = mergedCompactPositions,
        )
        return Rga(mergedOps, mergedLamport, newCache)
    }

    /**
     * Two [Rga] instances are equal when their op-sets are equal — i.e. they
     * represent the same CRDT state. The [lamport] high-water mark is a clock
     * convenience, not part of the value: two converged replicas may differ in
     * [lamport] if one advanced its clock by merging with a peer that had a higher
     * clock, so including it in equality would break `a.piece(a) == a` in that case.
     *
     * This matches [Fugue.equals], which is also ops-only.
     */
    override fun equals(other: Any?): Boolean =
        other is Rga<*> && ops == other.ops

    override fun hashCode(): Int = ops.hashCode()

    override fun toString(): String = "Rga(${toList()})"

    // ---- Private helpers ----

    private fun visibleSequence(): List<RgaId> = sequence.filter { it !in tombstones }

    /** Compute the insertsById map from the op-log (fallback when no cache is provided). */
    private fun computeInsertsById(): Map<RgaId, RgaOp.Insert<V>> =
        ops.filterIsInstance<RgaOp.Insert<V>>().associateBy { it.id }

    /**
     * Compute the maxSeqByReplica map from the op-log (fallback when no cache is provided).
     *
     * Folds in **compacted ids** as well as live [RgaOp.Insert]s. A self-compaction purges a
     * replica's own [RgaOp.Insert] from the log, so scanning only surviving inserts would
     * regress the per-author high-water and let [nextSeqFor] reuse a seq it already minted
     * (#639). The purged ids survive in the retained [RgaOp.Compact.positions] keys — each
     * carries its full [RgaId] (hence its seq) — so the true high-water is recoverable from
     * exactly the data that is already persisted. (Like the rest of the CRDT zoo, which mints
     * from the monotonic version vector that compaction only raises, the seq counter must
     * never go backwards.)
     */
    private fun computeMaxSeqByReplica(): Map<ReplicaId, Long> {
        val result = mutableMapOf<ReplicaId, Long>()
        fun consider(id: RgaId) {
            val current = result[id.replicaId]
            if (current == null || id.seq > current) result[id.replicaId] = id.seq
        }
        for (op in ops) {
            when (op) {
                is RgaOp.Insert -> consider(op.id)
                is RgaOp.Compact -> op.positions.keys.forEach(::consider)
                is RgaOp.Remove<*> -> {} // a Remove mints no seq; its insert (or its compacted id) is counted
            }
        }
        return result
    }

    /** Compute compactedIds from the op-log (fallback when no cache is provided). */
    private fun computeCompactedIds(): Set<RgaId> =
        ops.filterIsInstance<RgaOp.Compact>()
            .flatMapTo(mutableSetOf()) { it.positions.keys }

    /** Compute tombstones from the op-log (fallback when no cache is provided). */
    private fun computeTombstones(): Set<RgaId> =
        ops.filterIsInstance<RgaOp.Remove<V>>()
            .mapTo(mutableSetOf()) { it.id }
            .apply { removeAll(compactedIds) }

    /** Compute compactPositions from the op-log (fallback when no cache is provided). */
    private fun computeCompactPositions(): Map<RgaId, RgaId> =
        ops.filterIsInstance<RgaOp.Compact>()
            .flatMap { it.positions.entries }
            .associate { (k, v) -> k to v }

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
     * **Positional reroot (#293).** An [RgaOp.Insert] whose `after` has been compacted away
     * does not simply jump to [RgaId.HEAD]. Instead, `nearestAncestor` chain-walks
     * [compactPositions] — the union of all [RgaOp.Compact] `positions` maps — until it
     * reaches either a present (non-compacted) id or [RgaId.HEAD]. This preserves the
     * relative order of surviving elements: a successor of a GC'd element stays below
     * the GC'd element's own surviving predecessor rather than floating to the top.
     *
     * The chain-walk is bounded: compaction only removes causally-stable tombstones (barrier
     * condition 4 ensures no surviving successor exists for the element being GC'd when it
     * is GC'd by [compact]), so the positions map is acyclic and terminates at HEAD or a
     * live element within at most O(compacted depth) steps.
     */
    private fun computeSequence(): List<RgaId> {
        // Group each insert op by its effective predecessor: HEAD if `after` is HEAD or present,
        // else chain-walk compactPositions to the nearest surviving ancestor (positional
        // reroot, #293) — preserves relative order when GC removes an intermediate element.
        val present = insertsById.keys
        val positions = compactPositions
        fun nearestAncestor(start: RgaId): RgaId {
            var cur = start
            while (cur != RgaId.HEAD && cur !in present) cur = positions[cur] ?: RgaId.HEAD
            return cur
        }
        val childrenOf = insertsById.values
            .groupBy(
                keySelector = { ins ->
                    val a = ins.after
                    if (a == RgaId.HEAD || a in present) a else nearestAncestor(a)
                },
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
        public fun <V> empty(): Rga<V> = Rga(
            ops = emptySet(),
            lamport = 0L,
            cache = RgaCache.empty(),
        )

        /**
         * Package-internal factory for deserialization via [RgaSerializer].
         * Uses the private constructor with no cache; derived state is computed
         * from the op-log lazily on first access.
         */
        internal fun <V> fromOps(ops: Set<RgaOp<V>>, lamport: Long): Rga<V> =
            Rga(ops, lamport)

        /**
         * Returns a [kotlinx.serialization.KSerializer] for [Rga]`<V>` that correctly threads
         * [vSerializer] through the op-log serialization, avoiding the CBOR polymorphism
         * limitation of the compiler-generated `Rga$$serializer`.
         *
         * **Use this instead of `Rga.serializer(...)` when wiring [Rga] into a
         * [us.tractat.kuilt.quilter.Quilter]** — the generated serializer fails
         * for CBOR transport because it defaults to `PolymorphicSerializer(Any::class)` for the
         * element type [V] in [RgaOp.Insert.value].
         *
         * Usage:
         * ```kotlin
         * val msgSer = QuiltMessage.serializer(Rga.wireSerializer(serializer<String>()))
         * val replicator = Quilter(..., messageSerializer = msgSer)
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

        /** Update [map] with a single new [id], returning a new map only if the seq is higher. */
        private fun updateMaxSeq(map: Map<ReplicaId, Long>, id: RgaId): Map<ReplicaId, Long> {
            val current = map[id.replicaId]
            return if (current != null && id.seq <= current) map else map + (id.replicaId to id.seq)
        }

        /** Merge two maxSeqByReplica maps, taking the max per replica. */
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
