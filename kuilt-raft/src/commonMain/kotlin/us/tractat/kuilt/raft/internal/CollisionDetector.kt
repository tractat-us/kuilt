package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.DedupKey

/**
 * Passive detector for two live writers sharing one [ClientId]. The owning engine raises a
 * high-water mark with every serial it issues, and on boot with the highest one a durable id's
 * retained log holds; a committed entry under the engine's own id bearing a serial above that mark
 * reads as a foreign writer.
 *
 * It sees only what is above the mark. A foreign serial at or below it passes, within one incarnation
 * as well as across a restart, and a retained foreign serial raises the mark like any other. A serial
 * an earlier incarnation issued that this one never received looks the same as a foreign writer's,
 * because the mark has no other record of it (tracked by #3060). Confined to the engine's single
 * dispatch loop; no internal locking.
 */
internal class CollisionDetector(private val myClientId: ClientId) {
    private var maxIssued = 0L

    /**
     * Raise the mark to [requestId] if it is higher: an auto-counter bump, an explicit requestId, or
     * on boot the highest serial retained under this id, which this incarnation never issued.
     */
    fun issued(requestId: Long) { if (requestId > maxIssued) maxIssued = requestId }

    /** True iff [key] is under this engine's identity with a serial above the mark. */
    fun isForeign(key: DedupKey?): Boolean =
        key != null && key.clientId == myClientId && key.requestId > maxIssued
}
