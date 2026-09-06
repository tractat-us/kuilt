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
 *
 * @sample us.tractat.kuilt.crdt.sampleLWWRegister
 */
@Serializable
public class LWWRegister<V> private constructor(
    /** The timestamp half of the winning write's tag; `Long.MIN_VALUE` when the register is empty. */
    public val timestamp: Long,
    private val origin: ReplicaId,
    public val value: V?,
) : Quilted<LWWRegister<V>> {

    /**
     * Write [value] tagged with ([timestamp], [replica]).
     *
     * **The write goes through [piece], so it can only move this register *up*
     * the lattice** (`X <= X.set(…)`, #2087). A write whose tag loses to the one
     * already held is dropped where it is made, rather than showing up locally
     * until the next merge takes it away. Convergence is unchanged either way —
     * the join was always going to discard it — so what this buys is that a
     * mutator never contradicts the join, and every delta-mutator law over this
     * type holds unconditionally instead of on a carved-out domain.
     *
     * A consequence worth knowing: a write at a tag *equal* to the one held
     * keeps the **incumbent**, exactly as that same cell arriving off the wire
     * would, because [piece]'s tie-break is `else -> this`. That case is a
     * violation of the precondition below, where nothing was ever promised; the
     * change is that the outcome no longer depends on whether the second value
     * was written locally or received.
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
        piece(LWWRegister(timestamp, replica, value))

    /**
     * Clear the value tagged with ([timestamp], [replica]) — a last-writer-wins
     * *tombstone*. It competes under [piece] exactly like a [set]: an unset at a
     * later tag hides an earlier value, and a set at a later tag revives the
     * register. After an unset wins, [value] reads `null`.
     *
     * It is a **write**, not a clear: unsetting an *empty* register still
     * records `(timestamp, replica, null)`, which is what beats a concurrent
     * earlier [set] arriving later. Like [set] it goes through [piece] and so is
     * inflationary — it refuses to lose, it does not refuse to write.
     *
     * The tag-uniqueness precondition on [set] applies equally here: never reuse
     * a `(replica, timestamp)` pair across writes.
     */
    public fun unset(replica: ReplicaId, timestamp: Long): LWWRegister<V> =
        piece(LWWRegister(timestamp, replica, null))


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
        private val BOTTOM_REPLICA = ReplicaId.Bottom

        /** An empty register. Any [set] supersedes it. */
        public fun <V> empty(): LWWRegister<V> = LWWRegister(Long.MIN_VALUE, BOTTOM_REPLICA, null)

        /**
         * The bare tagged cell, with **no join** against any prior state — the
         * assigning form [set] and [unset] had before #2087.
         *
         * Deliberately internal, and deliberately kept rather than deleted. Three
         * callers need it:
         *
         * - [LWWMap.setWhole]/[LWWMap.removeWhole] are the *assigning* reference
         *   `LWWMapDeltaMutatorLawTest` and `LWWMapTest` drive to reach states
         *   strictly **below** their own starting point. A joined mutator cannot
         *   produce one, so routing them through [set] would silently retire that
         *   whole search region — see [LWWMap.setWhole]'s own KDoc.
         * - `LWWMap`'s one-cell patches, where spelling the cell outright is what
         *   the comment above them used to have to explain.
         * - [Gauge.observe], which documents itself as returning *just this tagged
         *   observation*, for the caller to absorb with `piece`. That is a
         *   delta-mutator contract rather than [set]'s, and #2087 deliberately did
         *   not widen to it.
         */
        internal fun <V> tagged(replica: ReplicaId, timestamp: Long, value: V?): LWWRegister<V> =
            LWWRegister(timestamp, replica, value)
    }
}
