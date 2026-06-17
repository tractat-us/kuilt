@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * The authoritative client-serial dedup table for a consumer's state machine (Raft §8).
 *
 * Fold it into your apply loop: call [shouldApply] for each `Committed.Entry.entry.dedupKey`; apply
 * the command only when it returns `true`. Serialize it **into your snapshot** via [toBytes] and
 * restore with [fromBytes] on `Committed.Install`, so a follower that joins mid-stream inherits the
 * high-water-marks and rejects stale retries.
 *
 * Not thread-safe — drive it from the same single apply loop that consumes `RaftNode.committed`.
 *
 * **No GC (v1):** ephemeral clients accumulate dead entries; long-lived clients should reuse a stable
 * [ClientId] so their entry updates in place. See the design spec.
 */
public class ClientSessionTable private constructor(
    private val highWaterMarks: MutableMap<ClientId, Long>,
) {
    public constructor() : this(mutableMapOf())

    /**
     * Returns `true` if the entry carrying [key] has not yet been applied (and records it), `false`
     * if it is a duplicate to skip. A `null` key (unkeyed/idempotent entry) always returns `true`.
     */
    public fun shouldApply(key: DedupKey?): Boolean {
        if (key == null) return true
        val seen = highWaterMarks[key.clientId] ?: 0L
        if (key.requestId <= seen) return false
        highWaterMarks[key.clientId] = key.requestId
        return true
    }

    /** Serialize for inclusion in the consumer's snapshot bytes. */
    public fun toBytes(): ByteArray =
        Cbor.encodeToByteArray(Snapshot.serializer(), Snapshot(highWaterMarks))

    @Serializable
    private data class Snapshot(val marks: Map<ClientId, Long>)

    public companion object {
        /** Restore from bytes produced by [toBytes]. */
        public fun fromBytes(bytes: ByteArray): ClientSessionTable =
            ClientSessionTable(Cbor.decodeFromByteArray(Snapshot.serializer(), bytes).marks.toMutableMap())
    }
}
