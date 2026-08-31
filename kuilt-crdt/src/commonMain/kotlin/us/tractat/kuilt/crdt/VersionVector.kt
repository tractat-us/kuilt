package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A per-author high-water version vector: `replicaId → highest contiguous seq`.
 *
 * Distinct from [DotContext]: a [VersionVector] is the **dense prefix only** — no
 * cloud, no gap-holding. It models the causal-stability quantities of ADR-003
 * addendum v3 (#262):
 * - the compactor's own contiguous **delivered** VV,
 * - the **stable cut** `S = min over live peers`,
 * - the **frontier** `F = max(F_live, retainedFrontier)`.
 *
 * Absent authors read as `0` ([get]); the vector is sparse — only positive
 * high-waters are stored.
 *
 * @property entries the backing map; never holds a `0` or negative value. Encoded through
 *   [CanonicalMapSerializer] because its iteration order is **not a function of the vector's
 *   value**, and `equals` is order-insensitive, so two peers at the same logical vector would
 *   otherwise emit different bytes (#2010). There are two independent producers, and they fail
 *   for unrelated reasons:
 *   - [combine] builds the map from `entries.keys + other.entries.keys`, a `LinkedHashSet` in
 *     **merge order**.
 *   - `Quilter.contiguousFrontier` groups a **merge-ordered** `Set<Dot>` by replica, then appends
 *     any author known only to the CRDT's compaction floor — and *this* is the producer that
 *     reaches the wire, as `QuiltMessage.Delivered.vector`. [combine] is not on that path at all.
 *
 *   The canonical serializer fixes this **at encode time, regardless of how the map was built**,
 *   which is why neither producer sorts and neither one needs to. Sorting a producer would not
 *   make this annotation redundant: it would canonicalise that producer only, and leave the other
 *   — including the public constructor and [of] — free to hand back a differently-ordered map.
 */
@Serializable
public data class VersionVector(
    @Serializable(with = CanonicalMapSerializer::class)
    public val entries: Map<ReplicaId, Long> = emptyMap(),
) {

    /** The high-water seq for [author], or `0` if this vector has never seen it. */
    public operator fun get(author: ReplicaId): Long = entries[author] ?: 0L

    /** True if every author's high-water in [other] is at or below this vector's. */
    public fun dominates(other: VersionVector): Boolean =
        other.entries.all { (author, seq) -> get(author) >= seq }

    /** True if this vector dominates the single dot `(author, seq)` — `get(author) >= seq`. */
    public fun contains(dot: Dot): Boolean = get(dot.replica) >= dot.seq

    /** Elementwise **min** with [other] — the stable-cut operation (`S = min over peers`). */
    public fun floorWith(other: VersionVector): VersionVector =
        combine(other) { a, b -> minOf(a, b) }

    /** Elementwise **max** with [other] — the frontier/merge operation (`F = max over peers`). */
    public fun ceilWith(other: VersionVector): VersionVector =
        combine(other) { a, b -> maxOf(a, b) }

    private inline fun combine(other: VersionVector, op: (Long, Long) -> Long): VersionVector {
        val authors = entries.keys + other.entries.keys
        return of(authors.associateWith { author -> op(get(author), other[author]) })
    }

    public companion object {
        /** The empty vector — every author reads as `0`. */
        public val EMPTY: VersionVector = VersionVector(emptyMap())

        /** A vector from [raw], dropping non-positive high-waters so equality stays canonical. */
        public fun of(raw: Map<ReplicaId, Long>): VersionVector =
            VersionVector(raw.filterValues { it > 0L })

        /**
         * The **delivered** vector a replica holding [dots] above [floor] can honestly claim: per
         * author, the highest seq reachable from [floor] without a gap.
         *
         * This is the quantity every causal-stability decision is expressed in — the `delivered`
         * argument of `Rga.compact` / `Fugue.compact` / `MovableTree.compact`, and the row a peer
         * gossips so the group can agree a stable cut. It stops at the first gap on purpose: a
         * replica that holds `1, 2, 4` has **not** delivered `4`, because `3` is still in flight and
         * something it has not seen may depend on it. Claiming `4` would authorise the group to drop
         * history `3` still refers to.
         *
         * @param dots the identities the state still carries — [Quilted.causalDots].
         * @param floor the dots it delivered and has since purged *without* retaining their
         *   identities — [Quilted.causalFloor]. The two are read as a **union, not a partition**: a
         *   dot at or below [floor] may also be in [dots], which is harmless because the walk only
         *   ever reads seqs strictly above the floor.
         *
         * [floor] is deliberately **not** defaulted. Passing [EMPTY] where a real floor exists
         * collapses that author's high-water to `0` — a floor is downward-closed, so the walk from
         * `0` stops at the first swallowed seq, which is `1` — and a gossiped regression there pins
         * every downstream compaction below the gap **forever**. A default would let a call site
         * reintroduce that silently, so it has to be written down at each one.
         *
         * `O(dots)` plus `O(n − floor)` per author — never `O(floor)`, so a deep floor is free.
         *
         * @sample us.tractat.kuilt.crdt.sampleVersionVectorContiguous
         */
        public fun contiguous(dots: Set<Dot>, floor: VersionVector): VersionVector {
            val seqsByAuthor: Map<ReplicaId, Set<Long>> = dots
                .groupBy(keySelector = { it.replica }, valueTransform = { it.seq })
                .mapValues { (_, seqs) -> seqs.toSet() }
            val authors = seqsByAuthor.keys + floor.entries.keys
            val highWaters = authors.associateWith { author ->
                contiguousHighWater(seqsByAuthor[author].orEmpty(), from = floor[author])
            }
            return of(highWaters)
        }

        /**
         * The highest `n >= from` such that every seq in `from + 1 .. n` is in [seqs]; [from] itself
         * if `from + 1` is absent.
         */
        private fun contiguousHighWater(seqs: Set<Long>, from: Long): Long {
            var n = from
            while ((n + 1L) in seqs) n++
            return n
        }
    }
}
