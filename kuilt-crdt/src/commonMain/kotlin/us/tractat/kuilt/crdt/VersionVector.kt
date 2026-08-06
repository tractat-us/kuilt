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
    }
}
