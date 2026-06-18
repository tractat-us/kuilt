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
 * **Bounding (v2 — supersession prune):** the table is self-bounding without any clock, horizon, or
 * heuristic. A [NodeId] is cluster-unique, so two incarnations of one node are never live at once — the
 * *arrival* of a new auto-incarnation (`auto:$nodeId-…`) is proof that its prior siblings are dead.
 * [shouldApply] therefore evicts every same-family auto sibling in the same apply step, so the table
 * holds at most one entry per live auto family plus the durable ids. The prune is a pure function of the
 * committed stream → every replica prunes identically, and a snapshot is always already-pruned, so the
 * [toBytes]/[fromBytes] format is **unchanged** from v1. Durable/stable ids are not auto-shaped, so they
 * are **never** pruned and keep their cross-crash exactly-once. The only residual is the **at-least-once
 * floor the auto path already documents**: an in-flight straggler of an evicted incarnation may
 * double-apply (never a silent drop) — and, being auto-shaped itself, may in turn supersede the live
 * incarnation, briefly dropping it to at-least-once too. Both stay within the auto path's at-least-once
 * guarantee; durable ids are unaffected. Consumers that observe a clean teardown may also call
 * [closeSession]. Long-lived clients should reuse a stable [ClientId] so their entry updates in place.
 */
public class ClientSessionTable private constructor(
    private val highWaterMarks: MutableMap<ClientId, Long>,
) {
    public constructor() : this(mutableMapOf())

    /**
     * Returns `true` if the entry carrying [key] has not yet been applied (and records it), `false`
     * if it is a duplicate to skip. A `null` key (unkeyed/idempotent entry) always returns `true`.
     *
     * When [key] records a fresh auto-shaped id (`auto:$nodeId-…`), every other entry in the same
     * `$nodeId` family is evicted in this same step (supersession prune — see the class KDoc).
     */
    public fun shouldApply(key: DedupKey?): Boolean {
        if (key == null) return true
        val seen = highWaterMarks[key.clientId] ?: 0L
        if (key.requestId <= seen) return false
        highWaterMarks[key.clientId] = key.requestId
        val family = key.clientId.autoFamily()
        if (family != null) {
            highWaterMarks.keys.removeAll { it != key.clientId && it.autoFamily() == family }
        }
        return true
    }

    /**
     * Drop [clientId]'s high-water-mark. Drive from the apply loop on a committed close op when a
     * consumer knows a logical client is finished. A subsequent request from that client re-opens at
     * mark 0 and at worst re-applies — the same at-least-once floor, never a silent drop.
     */
    public fun closeSession(clientId: ClientId) {
        highWaterMarks.remove(clientId)
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
