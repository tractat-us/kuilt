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
 * is the common case but skew can silently drop a value. For semantics that
 * preserve concurrent writes use [MVRegister]; for causal correctness over
 * arbitrary fabrics, pair with a Hybrid Logical Clock above this layer.
 */
@Serializable
public class LWWRegister<V> private constructor(
    private val timestamp: Long,
    private val origin: ReplicaId,
    public val value: V?,
) : Quilted<LWWRegister<V>> {

    /** Write [value] tagged with [timestamp] by [replica]. */
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
