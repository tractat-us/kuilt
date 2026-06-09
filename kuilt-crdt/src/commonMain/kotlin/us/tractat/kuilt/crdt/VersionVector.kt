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
 * @property entries the backing map; never holds a `0` or negative value.
 */
@Serializable
public data class VersionVector(public val entries: Map<ReplicaId, Long> = emptyMap()) {

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
