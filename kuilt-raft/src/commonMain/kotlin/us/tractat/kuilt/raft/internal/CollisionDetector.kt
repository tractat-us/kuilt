package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.DedupKey

/**
 * Passive detector for two live writers sharing one [ClientId]. The owning engine records every
 * serial it issues, and on boot the highest one a durable id's retained log holds; a committed entry
 * under the engine's own id bearing a serial above that high-water-mark reads as a foreign writer.
 *
 * That is exact within one incarnation, and only as good as the retained log across a restart. A
 * serial an earlier incarnation issued that this one never received looks the same as a foreign
 * writer's, because the mark has no other record of it (tracked by #3060). Confined to the engine's
 * single dispatch loop; no internal locking.
 */
internal class CollisionDetector(private val myClientId: ClientId) {
    private var maxIssued = 0L

    /** Record a serial this engine issued (auto-counter bump or explicit requestId). */
    fun issued(requestId: Long) { if (requestId > maxIssued) maxIssued = requestId }

    /** True iff [key] is under this engine's identity with a serial it never issued. */
    fun isForeign(key: DedupKey?): Boolean =
        key != null && key.clientId == myClientId && key.requestId > maxIssued
}
