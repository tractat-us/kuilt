package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Per-member display names tracked with last-writer-wins semantics, backed by
 * [LWWMap].
 *
 * Each member can set their own display name. Concurrent writes from different
 * replicas resolve by (timestamp, replicaId) — the higher-timestamp write wins;
 * when timestamps tie, the higher [ReplicaId] value (lexicographic) wins, giving
 * a deterministic but arbitrary outcome.
 *
 * ## Immutable value type
 *
 * Every mutating operation returns a new [MemberMetadata] — the receiver is not
 * modified. Wire the return value back into your state holder.
 *
 * ## Clock-skew warning
 *
 * This type inherits [LWWMap]'s clock-skew caveat: wall-clock timestamps work
 * only when clocks are well-synchronized across all replicas. Prefer a Hybrid
 * Logical Clock above this layer when clock drift is a concern.
 */
public class MemberMetadata private constructor(
    private val map: LWWMap<PeerId, String>,
) {
    /** All currently-set display names, keyed by [PeerId]. */
    public val names: Map<PeerId, String> get() = map.entries

    /**
     * Set a display name for [peer] tagged with ([timestamp], [replica]).
     *
     * [replica] is used only for tie-breaking when two replicas write the same
     * key at the same [timestamp]. Use the writing peer's stable [ReplicaId]
     * (e.g. derived from its [PeerId]) so tie-breaking is deterministic and
     * consistent across all replicas.
     *
     * The caller must ensure [timestamp] is strictly increasing per
     * ([peer], [replica]) pair — reusing a tag with a different value produces
     * non-deterministic convergence ([LWWMap] contract).
     *
     * Returns a new [MemberMetadata] containing the updated entry; the receiver
     * is unchanged.
     */
    public fun set(peer: PeerId, displayName: String, timestamp: Long, replica: ReplicaId): MemberMetadata =
        MemberMetadata(map.set(replica, timestamp, peer, displayName))

    /**
     * Merge another replica's metadata into this one.
     *
     * Idempotent and commutative. Per-key, the write with the higher
     * (timestamp, replicaId) wins.
     *
     * Returns a new [MemberMetadata]; neither receiver nor [other] is modified.
     */
    public fun merge(other: MemberMetadata): MemberMetadata =
        MemberMetadata(map.piece(other.map))

    override fun equals(other: Any?): Boolean = other is MemberMetadata && map == other.map
    override fun hashCode(): Int = map.hashCode()
    override fun toString(): String = "MemberMetadata($names)"

    public companion object {
        /** The empty metadata store — no display names set. */
        public fun empty(): MemberMetadata = MemberMetadata(LWWMap.empty())
    }
}
