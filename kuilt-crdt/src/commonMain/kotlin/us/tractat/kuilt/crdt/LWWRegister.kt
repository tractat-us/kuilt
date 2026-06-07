package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A last-writer-wins register. Each [set] tags the value with
 * `(timestamp, replicaId)`; [piece] picks the entry with the largest tag,
 * breaking ties on `replicaId` lexicographically so the merge is deterministic
 * regardless of arrival order.
 *
 * *Wall-clock or causal time?* This register treats whichever monotonically-
 * increasing source the caller passes as `timestamp` as the truth — wall-clock
 * is the common case but skew can silently drop a value. NTP-class drift will
 * cause surprising silent drops: a write with a lagging timestamp loses to an
 * older write from a faster clock. For semantics that preserve concurrent writes
 * use [MVRegister]; for correctness under arbitrary clock skew, pair with a
 * Hybrid Logical Clock above this layer.
 */
@Serializable
public class LWWRegister<V> private constructor(
    private val timestamp: Long,
    private val origin: ReplicaId,
    public val value: V?,
) : Quilted<LWWRegister<V>> {

    /**
     * Write [value] tagged with ([timestamp], [replica]).
     *
     * **Precondition — tag uniqueness.** The `(replica, timestamp)` pair MUST
     * uniquely identify this write. Calling `set(r, ts, v1)` and then
     * `set(r, ts, v2)` with the *same* `(replica, timestamp)` violates this
     * contract. Under [piece], the tie-break `else -> this` assumes equal tags
     * mean equal values; a duplicate tag with a different value produces
     * non-deterministic convergence — which replica "wins" depends on merge
     * order, not write order.
     *
     * In practice: use a monotonic source for `timestamp` per replica (e.g., a
     * logical clock that increments on every write) and never reuse a
     * `(replica, timestamp)` pair. This is not enforced at runtime.
     */
    public fun set(replica: ReplicaId, timestamp: Long, value: V): LWWRegister<V> =
        LWWRegister(timestamp, replica, value)

    /** The join: pick the larger `(timestamp, replicaId)` tag. */
    override fun piece(other: LWWRegister<V>): LWWRegister<V> = when {
        other.timestamp > timestamp -> other
        other.timestamp < timestamp -> this
        other.origin.value > origin.value -> other
        other.origin.value < origin.value -> this
        else -> this // same tag — assume same value; pick either
    }

    override fun equals(other: Any?): Boolean =
        other is LWWRegister<*> &&
            timestamp == other.timestamp &&
            origin == other.origin &&
            value == other.value

    override fun hashCode(): Int {
        var h = timestamp.hashCode()
        h = 31 * h + origin.hashCode()
        h = 31 * h + (value?.hashCode() ?: 0)
        return h
    }

    override fun toString(): String = "LWWRegister(value=$value, ts=$timestamp, by=$origin)"

    public companion object {
        private val BOTTOM_REPLICA = ReplicaId("")

        /** An empty register. Any [set] supersedes it. */
        public fun <V> empty(): LWWRegister<V> = LWWRegister(Long.MIN_VALUE, BOTTOM_REPLICA, null)
    }
}
