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
        public val HEAD: RgaId = RgaId(lamport = Long.MIN_VALUE, replicaId = ReplicaId.Bottom, seq = 0L)
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
        /**
         * Serialized in canonical key order (#1978): the map is built from a merge-ordered
         * tombstone set, so two replicas at the same logical state hold `equal` [Compact] ops
         * whose plain-map encodings differ. [RgaOpSerializer] — the wire path, via
         * [Rga.wireSerializer] — applies the same [CanonicalMapSerializer]; the annotation covers
         * the compiler-generated serializer a consumer reaches through `Compact.serializer()`.
         */
        @Serializable(with = CanonicalMapSerializer::class)
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
     * Per-author high-water of dots this replica has **compacted away** — every dot
     * `(r, s)` with `s <= compactedBelow[r]` is permanently suppressed.
     *
     * This is the bounded form of [RgaOp.Compact]: a `Compact` retains one
     * `(RgaId -> RgaId)` pair per dropped element forever, so it is Θ(elements ever);
     * a floor is O(authors). It can only describe a **downward-closed** compacted set,
     * which [dropWindow] guarantees by advancing it across a contiguous own-dot run only.
     *
     * Merged by [VersionVector.ceilWith] under [piece]: the product of (op-set under
     * union) and (floor under elementwise max) is a join-semilattice, so the [Quilted]
     * laws hold by construction. Part of [equals] — it is state, not a cache.
     *
     * **Accepted constraint: a floor's positional reroot degrades to [RgaId.HEAD].** An
     * [RgaOp.Compact] records each dropped element's predecessor, so a survivor whose
     * predecessor was GC'd re-attaches to that predecessor's own surviving ancestor
     * (`computeSequence`'s #293 reroot). A floor records nothing — recording it would be the
     * per-element map, and therefore the Θ(elements ever) cost, this field exists to remove.
     * So a survivor whose predecessor was floored away re-roots to [RgaId.HEAD] instead, and
     * HEAD's child list is sorted by id **descending**: a high-lamport survivor can land ahead
     * of older HEAD-anchored records. This is **not** confined to cross-author logs. One author
     * that mints `A` after HEAD, `B` after HEAD, then `C` after `A` reads `B, A, C` (HEAD's two
     * children sort descending, so the later `B` leads); flooring `A` re-roots `C` to HEAD, where
     * its still-higher lamport puts it ahead of `B` — `C, B`. [insertAt] at index `0` produces
     * that after-HEAD shape routinely, so a single-author log reorders too.
     *
     * This is a **reordering, not a divergence.** The sequence stays a deterministic function
     * of `(ops, compactedBelow)`, and [piece] merges the floor on both sides, so every replica
     * that has absorbed the same ops and the same floor computes the identical order. What is
     * given up is the *stability* of a survivor's position across its predecessor being
     * dropped — the price of the bound, paid deliberately.
     */
    public val compactedBelow: VersionVector = VersionVector.EMPTY,
    /**
     * Pre-computed derived state. When non-null (all mutation paths), the fields are
     * used directly instead of scanning [ops]. When null (deserialization via
     * [fromOps]), each field is computed from [ops] on first access.
     * Excluded from the wire format by [@Transient].
     */
    @Transient private val cache: RgaCache<V>? = null,
) : Quilted<Rga<V>> {

    /**
     * All ids that have been garbage-collected by any [RgaOp.Compact] in this op-log — the
     * *unbounded* half of the compaction record, as against the O(authors) [compactedBelow] floor.
     *
     * Public because a consumer that partitions the op-log across storage segments has to decide
     * whether a segment is fully superseded before it may drop it, and suppression comes from the
     * two together: a dot is suppressed iff [compactedBelow] contains it **or** its id is in this
     * set. Reading only the floor would judge a *foreign* author's windowed-away element
     * unsuppressed — [dropWindow] can never fold a foreign dot into the floor — and so keep its
     * segment on disk forever.
     */
    public val compactedIds: Set<RgaId> by lazy {
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
     * How many operations this log holds — Inserts, Removes and retained `Compact`s alike.
     *
     * A consumer that partitions the op-log across storage segments budgets in ops, and **in that
     * budget** a `Compact` occupies one slot exactly as an `Insert` does. It is invisible to both
     * [sequence] and [tombstones], so a `sequence.size + tombstones.size` estimate silently
     * undercounts a segment that carries one.
     *
     * The equality is one of *count*, not of cost: a `Compact` carries a `(RgaId -> RgaId)` pair
     * per position it suppresses and no value at all, so on the wire or on disk it can be much
     * smaller — or, having absorbed a whole window, much larger — than an `Insert` carrying one
     * element. A consumer budgeting **bytes** must measure them; this count will mislead it.
     */
    public val opCount: Int get() = ops.size

    /**
     * How many [RgaOp.Compact] ops this log retains.
     *
     * Public for the same storage-partitioning reason as [compactedIds], but answering the
     * converse question. A `Compact` is the only carrier of this class's "once compacted, always
     * compacted" guarantee for the ids it names, and **nothing ever prunes one** — [purgeBelow]
     * keeps it unconditionally and [piece] unions the positions it carries — so a consumer that
     * drops the storage holding one silently revokes that guarantee, and a peer that never
     * received the compaction can re-admit the purged element on the next [piece]. A segment
     * holding a `Compact` therefore may never be dropped, however superseded its other ops are.
     *
     * Deliberately not `compactedIds.isNotEmpty()`: that projection is blind to a `Compact`
     * carrying an empty positions map, and "may I delete this?" must not be decided by a
     * predicate with a false-negative case.
     */
    public val compactOpCount: Int get() = ops.count { it is RgaOp.Compact }

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
        return Rga(newOps, newLamport, compactedBelow, newCache) to op
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
        return Rga(newOps, lamport, compactedBelow, newCache) to op
    }

    /**
     * Append [values] as a chain starting immediately after [after], minting one
     * [RgaOp.Insert] per element on behalf of [replica].
     *
     * The bulk sibling of [insertAfter], and **indistinguishable from calling it in a
     * loop**: the same ids, the same `after` links, the same Lamport clock, the same
     * op-set. What differs is the cost. [insertAfter] rebuilds `ops` and `insertsById`
     * on every call, so appending `k` elements to an `N`-op log is `k` copies of `N` —
     * Θ(k·N). This pays **one** `ops + newOps` and **one** cache build for the whole
     * run, so it is Θ(N + k).
     *
     * That is the amortisation `WarpLogRecordExporter` needs to stop paying a Θ(N)
     * append per log record (#2194), and it needs no persistent data structure and no
     * new dependency on this deliberately dependency-free module. It does **not**
     * remove the Θ(N) term — one copy per run remains, which is #2193.
     *
     * An empty [values] returns `this` — the same instance, not a copy.
     *
     * @return the new state, and the ops to broadcast **in append order**.
     */
    public fun insertAllAfter(
        replica: ReplicaId,
        after: RgaId,
        values: List<V>,
    ): Pair<Rga<V>, List<RgaOp.Insert<V>>> {
        if (values.isEmpty()) return this to emptyList()
        var newLamport = lamport
        var seq = nextSeqFor(replica) - 1L
        var predecessor = after
        val minted = ArrayList<RgaOp.Insert<V>>(values.size)
        values.forEach { value ->
            newLamport += 1L
            seq += 1L
            val id = RgaId(lamport = newLamport, replicaId = replica, seq = seq)
            minted += RgaOp.Insert(id = id, value = value, after = predecessor)
            predecessor = id
        }
        val newCache = RgaCache(
            insertsById = insertsById + minted.associateBy { it.id },
            maxSeqByReplica = maxSeqByReplica + (replica to seq),
            tombstones = tombstones,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
        )
        return Rga(ops + minted, newLamport, compactedBelow, newCache) to minted
    }

    /**
     * Tombstone the first [count] **visible** elements, minting one [RgaOp.Remove] each.
     *
     * The bulk sibling of `removeAt(0)` repeated, and indistinguishable from it: the
     * same ids tombstoned, in the same order. The cost differs the same way
     * [insertAllAfter]'s does — one `ops + removes` copy and one cache build for the
     * whole run, and **one** [sequence] materialisation rather than one per removal.
     * That second saving is the larger one in practice: every `removeAt` returns a new
     * instance whose `sequence` lazy is cold, so a loop of `k` removals recomputes the
     * full RGA order `k` times.
     *
     * Existing tombstones are skipped rather than counted, exactly as `removeAt(0)`
     * skips them — [count] is a number of *visible* elements.
     *
     * A [count] of zero or less returns `this` (the same instance, not a copy).
     *
     * @throws IllegalArgumentException if [count] exceeds [size].
     * @return the new state, and the ops to broadcast in removal order.
     */
    public fun removeFirst(count: Int): Pair<Rga<V>, List<RgaOp.Remove<V>>> {
        if (count <= 0) return this to emptyList()
        val visible = visibleSequence()
        require(count <= visible.size) {
            "removeFirst($count) exceeds the visible size of ${visible.size}"
        }
        val removed = visible.subList(0, count)
        val minted = removed.map { id -> RgaOp.Remove<V>(id = id) }
        val newCache = RgaCache(
            insertsById = insertsById,
            maxSeqByReplica = maxSeqByReplica,
            tombstones = tombstones + removed,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
        )
        return Rga(ops + minted, lamport, compactedBelow, newCache) to minted
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
     * This state with its compaction floor raised to `compactedBelow ceilWith [floor]`,
     * purging every op the raised floor now covers.
     *
     * Low-level: it will happily raise **another** author's entry, which is unsound as a
     * local decision — a foreign dot that has not been minted yet would be annihilated
     * rather than resurfacing at the window boundary. Prefer [dropWindow], which raises
     * only this replica's own entry. This entry point exists for absorbing a floor that
     * arrived from its own author over the wire, and for tests.
     *
     * `internal`, not `public`, for exactly that reason: the sound public paths are [dropWindow]
     * (raises only this replica's own entry) and [piece]/[fromOps] (absorb a floor its author
     * already decided on).
     */
    internal fun withCompactedBelow(floor: VersionVector): Rga<V> {
        val merged = compactedBelow.ceilWith(floor)
        if (merged == compactedBelow) return this
        return Rga(purgeBelow(ops, merged), lamport, merged, cacheAfterFloor(merged))
    }

    /**
     * Drop [dropped] from this log — the **un-gated history-windowing** path (#254), not the
     * causal-stability barrier of [compact].
     *
     * Windowing deliberately forgets position, so unlike [compact] it may drop a live element
     * and needs no stability gate: reroot-to-HEAD keeps the retained window reachable, and a
     * concurrent `Insert(J, after = dropped-I)` resurfaces at the window boundary rather than
     * being orphaned.
     *
     * The drop is recorded in the cheapest sound form. This replica's **own** dots that form a
     * contiguous run up from `compactedBelow[self] + 1` fold into the floor — O(authors), and
     * the reason a windowed log stops growing. Everything else (a foreign author's dots; own
     * dots above the first retained one) keeps an explicit [RgaOp.Compact] entry, which costs
     * one `(RgaId -> RgaId)` pair each.
     *
     * Only [self]'s floor entry is ever raised. Raising a foreign author's would annihilate a
     * dot that author may not have minted yet; this replica, by contrast, can never hold an
     * undelivered dot of its own. (The reason is *not* that a single-author log is somehow
     * safe — see [compactedBelow], where the reorder is shown to bite within one author too.)
     *
     * **The floor's positional reroot degrades to [RgaId.HEAD].** A floor writes no
     * [RgaOp.Compact.positions], so a survivor whose predecessor this call floored away
     * re-roots to HEAD rather than to that predecessor's surviving ancestor, and can overtake
     * older HEAD-anchored records. That is a stated part of this contract, not a defect: see
     * [compactedBelow] for why it is accepted. Refusing to floor past a dot that still has a
     * surviving successor — the barrier [compact] uses — would reclaim nothing at all under a
     * drop-oldest window, which is the whole reason this entry point exists.
     *
     * **The floor is reported through [causalFloor], not [causalDots].** A raised floor purges its
     * ops and — unlike [RgaOp.Compact] — records no id set, so those dots leave [causalDots]
     * entirely. A consumer folding a delivered frontier must therefore read the two together (a
     * dot is delivered if it is in [causalDots] **or** at-or-below [causalFloor]); reading only
     * the dots would stop the walk at the first swallowed seq, and since the floor is
     * downward-closed that seq is `1`, collapsing the author's frontier to `0` — a regression
     * that, once gossiped, would leave [compact]'s condition 3 (`delivered.dominates(frontierMax)`)
     * permanently unsatisfiable for that author. `Quilter` reads both (#2127); a consumer that
     * folds its own frontier must do the same.
     *
     * @return `(newState, delta)` — the delta is a minimal [Rga], wrapped as a [Patch] so it
     *   cannot be swapped with the state at a destructuring site, that any peer absorbs through
     *   [piece] to perform the same drop — or `null` if [dropped] is empty.
     *
     * @sample us.tractat.kuilt.crdt.sampleRgaDropWindow
     */
    public fun dropWindow(self: ReplicaId, dropped: Set<RgaId>): Pair<Rga<V>, Patch<Rga<V>>>? {
        if (dropped.isEmpty()) return null
        val ownSeqs = dropped.mapNotNullTo(mutableSetOf()) { if (it.replicaId == self) it.seq else null }
        // Own dots ALREADY dropped explicitly — recorded in a retained Compact, and therefore
        // never *reappear* in insertsById; a caller may still re-pass one, which the residue
        // filter below tolerates. Without these the walk cannot step over them and the floor
        // wedges below the first one FOREVER (see the wedge test).
        val ownCompacted = compactedIds.mapNotNullTo(mutableSetOf()) { if (it.replicaId == self) it.seq else null }
        var floorSeq = compactedBelow[self]
        while ((floorSeq + 1L) in ownSeqs || (floorSeq + 1L) in ownCompacted) floorSeq++
        val newFloor = compactedBelow.ceilWith(VersionVector.of(mapOf(self to floorSeq)))

        // `it in insertsById` is load-bearing: positionsFor calls getValue and throws on an
        // unknown id, and a caller may legitimately re-pass an id an earlier drop already took.
        val residue = dropped.filterTo(mutableSetOf()) { !newFloor.contains(it.dot) && it in insertsById }
        val compactOp = if (residue.isEmpty()) null else RgaOp.Compact(positionsFor(residue))

        val state = withCompactedBelow(newFloor).let { if (compactOp == null) it else it.apply(compactOp) }
        return state to Patch(deltaOf<V>(newFloor, compactOp))
    }

    /** Rebuild the derived caches after the floor rose to [merged]. */
    private fun cacheAfterFloor(merged: VersionVector): RgaCache<V> = RgaCache(
        insertsById = insertsById.filterKeys { !merged.contains(it.dot) },
        // Sole pin: RgaCompactedFloorTest.aFloorRaisedPastTheHeldOpsStillHoldsTheSeqHighWaterUp.
        // maxSeqByReplica must NOT drop: the floor is itself evidence those seqs were minted.
        maxSeqByReplica = maxSeqByReplica.mergeMax(merged.entries),
        tombstones = tombstones.filterTo(mutableSetOf()) { !merged.contains(it.dot) },
        compactedIds = compactedIds,
        compactPositions = compactPositions,
    )

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
        if (op.id in compactedIds || compactedBelow.contains(op.id.dot)) return this
        val newOps = ops + op
        val newLamport = maxOf(lamport, op.id.lamport)
        val newCache = RgaCache(
            insertsById = insertsById + (op.id to op),
            maxSeqByReplica = updateMaxSeq(maxSeqByReplica, op.id),
            tombstones = tombstones,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
        )
        return Rga(newOps, newLamport, compactedBelow, newCache)
    }

    private fun applyRemove(op: RgaOp.Remove<V>): Rga<V> {
        if (op.id in compactedIds || compactedBelow.contains(op.id.dot)) return this
        val newOps = ops + op
        val newCache = RgaCache(
            insertsById = insertsById,
            maxSeqByReplica = maxSeqByReplica,
            tombstones = tombstones + op.id,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
        )
        return Rga(newOps, lamport, compactedBelow, newCache)
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
        return Rga(newOps, lamport, compactedBelow, newCache)
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
    override fun causalDots(): Set<Dot> = engine<V>().causalDots(ops)

    /**
     * The delivered dots this replica compacted away **without** keeping their ids — exactly
     * [compactedBelow].
     *
     * The bounded half of the delivered surface: *raising* it purges an own dot's op without
     * recording a [RgaOp.Compact] for it — no per-dot id set, which is what keeps this O(authors)
     * instead of O(elements). That does not mean every dot beneath the floor is absent from
     * [causalDots], though: [dropWindow]'s contiguity walk steps over an own dot a still-retained
     * `Compact` already recorded (an inherited or previously-explicit one), so that dot can end up
     * beneath the floor while [causalDots] keeps re-emitting it. Read the two as a union, as
     * [Quilted.causalFloor] describes — the overlap is harmless.
     */
    override fun causalFloor(): VersionVector = compactedBelow

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
     * [compactedBelow] merges by [VersionVector.ceilWith] and suppresses alongside
     * [compactedIds]: a peer that still holds the raw ops under the other side's floor
     * must not resurrect them. The state is the product of (op-set under union) and
     * (floor under elementwise max), so both components are join-semilattices and the
     * laws above still hold.
     *
     * Derived caches are merged incrementally — no full O(ops) rescan on the merged result.
     */
    override fun piece(other: Rga<V>): Rga<V> {
        val mergedFloor = compactedBelow.ceilWith(other.compactedBelow)
        val rawUnion = ops + other.ops
        val mergedLamport = maxOf(lamport, other.lamport)
        val mergedCompactedIds = compactedIds + other.compactedIds
        val mergedCompactPositions = compactPositions + other.compactPositions
        // Fast path only when NOTHING is suppressed — an empty id-set with a non-empty
        // floor still has to filter, and vice versa.
        val suppresses = mergedCompactedIds.isNotEmpty() || mergedFloor.entries.isNotEmpty()
        val survives = { id: RgaId -> id !in mergedCompactedIds && !mergedFloor.contains(id.dot) }
        val rawInsertsById = insertsById + other.insertsById
        val mergedInsertsById = if (!suppresses) rawInsertsById else rawInsertsById.filterKeys(survives)
        val rawTombstones = tombstones + other.tombstones
        val mergedTombstones = if (!suppresses) rawTombstones
            else rawTombstones.filterTo(mutableSetOf(), survives)
        // No fold of `mergedFloor` here: every construction site maintains
        // `maxSeqByReplica[r] >= compactedBelow[r]` (the cacheless base cases resolve through
        // computeMaxSeqByReplica, which folds the floor), so `a.maxSeq ⊔ b.maxSeq` already
        // dominates `F_a ⊔ F_b`. Adding one back would be dead code, not defence in depth.
        val mergedMaxSeq = maxSeqByReplica.mergeMax(other.maxSeqByReplica)
        val mergedOps = if (!suppresses) rawUnion
            else purgeBelow(purge(rawUnion, mergedCompactedIds), mergedFloor)
        val newCache = RgaCache(
            insertsById = mergedInsertsById,
            maxSeqByReplica = mergedMaxSeq,
            tombstones = mergedTombstones,
            compactedIds = mergedCompactedIds,
            compactPositions = mergedCompactPositions,
        )
        return Rga(mergedOps, mergedLamport, mergedFloor, newCache)
    }

    /**
     * Two [Rga] instances are equal when their op-sets **and** their [compactedBelow]
     * floors are equal — i.e. they represent the same CRDT state.
     *
     * The floor is part of the value, not a cache of the op-set. Two replicas can hold
     * identical surviving ops and still disagree about what may be re-admitted: one that
     * has floored `(r, 1..3)` will silently drop a late `Insert` with dot `(r, 2)`, while
     * one that has not will absorb it and grow a record the other can never show. They
     * are different states and must not compare equal — otherwise `piece` could return a
     * state `equal` to an input whose future behaviour differs, and the delta-fingerprint
     * that [us.tractat.kuilt.quilter.Quilter] derives from equality would elide a real
     * change. It also keeps `a.piece(b) == b.piece(a)` honest: the floor merges by
     * elementwise max, so both sides carry it and both sides must see it.
     *
     * The [lamport] high-water mark stays out: it is a clock convenience, and two
     * converged replicas may differ in it if one advanced its clock by merging with a
     * peer that had a higher clock, so including it would break `a.piece(a) == a`.
     *
     * [Fugue.equals] is still ops-only — it has no floor.
     */
    override fun equals(other: Any?): Boolean =
        other is Rga<*> && ops == other.ops && compactedBelow == other.compactedBelow

    override fun hashCode(): Int = 31 * ops.hashCode() + compactedBelow.hashCode()

    override fun toString(): String = "Rga(${toList()})"

    // ---- Private helpers ----

    private fun visibleSequence(): List<RgaId> = sequence.filter { it !in tombstones }

    /** Compute the insertsById map from the op-log (fallback when no cache is provided). */
    private fun computeInsertsById(): Map<RgaId, RgaOp.Insert<V>> =
        ops.filterIsInstance<RgaOp.Insert<V>>().associateBy { it.id }

    /**
     * Compute the maxSeqByReplica map from the op-log (fallback when no cache is provided).
     * Delegates to the shared [OpLogEngine], which folds in **compacted ids** as well as
     * live [RgaOp.Insert]s so a self-compaction can't regress the per-author high-water
     * and let [nextSeqFor] reuse a seq it already minted (#639).
     *
     * [compactedBelow] is folded in for the same reason and is **not** optional: a floor
     * purges the ops beneath it, so the op-log holds no evidence at all that those seqs were
     * minted, and a window that drained entirely would leave this map empty and let
     * [nextSeqFor] hand back `1` — a dot the floor itself suppresses, so the next record would
     * be silently annihilated by the very next [piece]. This is the no-cache path, which the wire
     * decode reaches through [fromOps] (#2127); [cacheAfterFloor] folds the floor in on the path
     * that raises one. [piece] needs no fold of its own — folding here and there makes
     * `maxSeqByReplica[r] >= compactedBelow[r]` hold at every construction site, so the merged
     * high-water already dominates the merged floor.
     */
    private fun computeMaxSeqByReplica(): Map<ReplicaId, Long> =
        engine<V>().maxSeqByReplica(ops).mergeMax(compactedBelow.entries)

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
     * **Positional reroot (#293) — [RgaOp.Compact] only.** An [RgaOp.Insert] whose `after` was
     * removed *by a `Compact`* does not simply jump to [RgaId.HEAD]. Instead,
     * [nearestPresentAncestor] chain-walks [compactPositions] — the union of all
     * [RgaOp.Compact] `positions` maps — until it reaches either a present (non-compacted) id
     * or [RgaId.HEAD]. This preserves the relative order of surviving elements: a successor of
     * a GC'd element stays below the GC'd element's own surviving predecessor rather than
     * floating to the top.
     *
     * The chain-walk is bounded: compaction only removes causally-stable tombstones (barrier
     * condition 4 ensures no surviving successor exists for the element being GC'd when it
     * is GC'd by [compact]), so the positions map is acyclic and terminates at HEAD or a
     * live element within at most O(compacted depth) steps.
     *
     * **A [compactedBelow] floor does not get this.** A floor records no positions — that map
     * is the Θ(elements ever) cost the floor exists to eliminate — so [compactPositions] has no
     * entry for a floored predecessor and the walk falls through to [RgaId.HEAD]. A survivor
     * whose predecessor was floored away therefore **re-roots to HEAD**, not to the floored
     * element's surviving ancestor. See [compactedBelow] for why that is accepted rather than
     * fixed.
     */
    private fun computeSequence(): List<RgaId> {
        // Group each insert op by its effective predecessor: HEAD if `after` is HEAD or present,
        // else chain-walk compactPositions to the nearest surviving ancestor (positional
        // reroot, #293) — preserves relative order when GC removes an intermediate element.
        val present = insertsById.keys
        val positions = compactPositions
        val childrenOf = insertsById.values
            .groupBy(
                keySelector = { ins ->
                    val a = ins.after
                    if (a == RgaId.HEAD || a in present) a else nearestPresentAncestor(a, RgaId.HEAD, present, positions)
                },
                valueTransform = { it.id },
            )
            .mapValues { (_, ids) -> ids.sortedDescending() }
        val result = mutableListOf<RgaId>()
        appendChildren(RgaId.HEAD, childrenOf, result)
        return result
    }

    /**
     * Depth-first pre-order traversal of the RGA tree, appending children in
     * descending-id order at each node (the concurrent-insert tiebreak).
     *
     * Iterative (explicit LIFO stack) rather than recursive so a degenerate
     * single-spine tree — a long append-only chain — cannot overflow the native
     * stack (#1206). Children are pushed in reverse so the first child pops first,
     * reproducing "each child immediately followed by its full subtree, siblings
     * in listed order."
     */
    private fun appendChildren(
        parent: RgaId,
        childrenOf: Map<RgaId, List<RgaId>>,
        result: MutableList<RgaId>,
    ) {
        val stack = ArrayDeque<RgaId>()
        val roots = childrenOf[parent].orEmpty()
        for (i in roots.indices.reversed()) stack.addLast(roots[i])
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            result.add(node)
            val children = childrenOf[node].orEmpty()
            for (i in children.indices.reversed()) stack.addLast(children[i])
        }
    }

    // ── Test-only differential oracle (#1206) ───────────────────────────────
    //
    // Not used by computeSequence/appendChildren above (production). Preserves
    // the pre-#1206 *recursive* traversal exactly, so a differential test can
    // assert the iterative rewrite produces identical output to this oracle
    // across many randomized trees. Recursion depth here is only ever bounded
    // by the depth of the bushy (not deep-chain) trees such a test generates —
    // never call this against a real, potentially-deep op-log.

    /**
     * Test-only oracle for [computeSequence]: recomputes the same childrenOf
     * grouping, then walks it with the pre-#1206 recursive
     * [appendChildrenRecursiveOracle] instead of the production iterative
     * [appendChildren]. `internal` purely so a dual-track ordering test can
     * differentially verify the fix.
     */
    internal fun computeSequenceViaRecursiveOracle(): List<V> {
        val present = insertsById.keys
        val positions = compactPositions
        val childrenOf = insertsById.values
            .groupBy(
                keySelector = { ins ->
                    val a = ins.after
                    if (a == RgaId.HEAD || a in present) a else nearestPresentAncestor(a, RgaId.HEAD, present, positions)
                },
                valueTransform = { it.id },
            )
            .mapValues { (_, ids) -> ids.sortedDescending() }
        val result = mutableListOf<RgaId>()
        appendChildrenRecursiveOracle(RgaId.HEAD, childrenOf, result)
        return result.map { insertsById.getValue(it).value }
    }

    private fun appendChildrenRecursiveOracle(
        parent: RgaId,
        childrenOf: Map<RgaId, List<RgaId>>,
        result: MutableList<RgaId>,
    ) {
        for (child in childrenOf[parent].orEmpty()) {
            result.add(child)
            appendChildrenRecursiveOracle(child, childrenOf, result)
        }
    }

    public companion object {
        /** The empty sequence with no ops. */
        public fun <V> empty(): Rga<V> = Rga(
            ops = emptySet(),
            lamport = 0L,
            compactedBelow = VersionVector.EMPTY,
            cache = RgaCache.empty(),
        )

        /**
         * Package-internal factory for deserialization via [RgaSerializer].
         * Uses the private constructor with no cache; derived state is computed
         * from the op-log lazily on first access.
         *
         * The ops are purged against [compactedBelow] on construction, so a decoded
         * blob whose op-set contradicts its own floor cannot present a resurrected
         * element — the floor wins, exactly as it does on every other path.
         *
         * **The floor is canonicalised through [VersionVector.of].** Its primary constructor
         * keeps a non-positive entry and kotlinx-serialization decodes through the constructor,
         * so a blob carrying `{r: 0}` would otherwise produce a floor that suppresses nothing yet
         * is `!equals` the canonical [VersionVector.EMPTY] — and since the floor is part of
         * [equals], `decode(encode(x)) != x` for a value whose ops are identical.
         */
        internal fun <V> fromOps(
            ops: Set<RgaOp<V>>,
            lamport: Long,
            compactedBelow: VersionVector = VersionVector.EMPTY,
        ): Rga<V> {
            val floor = VersionVector.of(compactedBelow.entries)
            return Rga(purgeBelow(ops, floor), lamport, floor)
        }

        /**
         * A minimal state carrying only a compaction record, for propagation through [piece].
         * The lamport is `0` deliberately: [piece] takes the elementwise max, so a delta can
         * never drag a peer's clock backwards.
         */
        private fun <V> deltaOf(floor: VersionVector, compactOp: RgaOp.Compact?): Rga<V> {
            val ops: Set<RgaOp<V>> = if (compactOp == null) emptySet() else setOf(compactOp)
            return Rga(ops, 0L, floor, null)
        }

        /**
         * Drop every [RgaOp.Insert]/[RgaOp.Remove] op whose dot is at-or-below [floor].
         * [RgaOp.Compact] ops carry no dot of their own and are kept.
         */
        internal fun <V> purgeBelow(ops: Set<RgaOp<V>>, floor: VersionVector): Set<RgaOp<V>> {
            if (floor.entries.isEmpty()) return ops
            return ops.filterTo(mutableSetOf()) { op ->
                when (op) {
                    is RgaOp.Insert -> !floor.contains(op.id.dot)
                    is RgaOp.Remove -> !floor.contains(op.id.dot)
                    is RgaOp.Compact -> true
                }
            }
        }

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
         * The shared op-log core (op classification + causal-dot projection) for [Rga].
         * See [OpLogEngine].
         */
        private fun <V> engine(): OpLogEngine<RgaId, RgaOp<V>> = OpLogEngine(
            view = { op ->
                when (op) {
                    is RgaOp.Insert -> LogOp.Insert(op.id)
                    is RgaOp.Remove -> LogOp.Remove(op.id)
                    is RgaOp.Compact -> LogOp.Compact(op.positions.keys)
                }
            },
            dotOf = { it.dot },
        )

        /**
         * Strip Insert and Remove ops for all [gcIds] from [ops], merging the
         * [compactOp] in. The Compact op itself is retained.
         */
        internal fun <V> purgeAndRecord(
            ops: Set<RgaOp<V>>,
            gcIds: Set<RgaId>,
            compactOp: RgaOp.Compact,
        ): Set<RgaOp<V>> = engine<V>().purgeAndRecord(ops, gcIds, compactOp)

        /**
         * Remove all [RgaOp.Insert] and [RgaOp.Remove] ops whose id is in [gcIds].
         * [RgaOp.Compact] ops are left intact.
         */
        internal fun <V> purge(ops: Set<RgaOp<V>>, gcIds: Set<RgaId>): Set<RgaOp<V>> =
            engine<V>().purge(ops, gcIds)

        /** Update [map] with a single new [id], returning a new map only if the seq is higher. */
        private fun updateMaxSeq(map: Map<ReplicaId, Long>, id: RgaId): Map<ReplicaId, Long> {
            val current = map[id.replicaId]
            return if (current != null && id.seq <= current) map else map + (id.replicaId to id.seq)
        }
    }
}
